package com.example.webtts;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V1.7: Engine giong HoaiMy Edge (online) + preload 35 cau.
 *
 * Port tu web_tts Python:
 * - generate_raw_tts(): goi Edge WS -> mp3 raw, cache theo sha1(voice|index|text).
 * - preload 35 cau nhu PRELOAD_COUNT (worker pool 3 nhu TRANSLATE_MAX_WORKERS).
 * - Do raw_duration THAT bang MediaPlayer (thay ffprobe) de tinh speed
 *   theo cong thuc Python: speed = raw/target, kep 1.0..2.5.
 * - Phat rieng bang MediaPlayer (khong dung Google TTS) nen khong can atempo.
 *   Muốn vừa khít timeline: nếu raw dài hơn slot thì tăng rate Edge (+x%)
 *   rồi tải lại (tối đa 2 lần), giống render lại ffmpeg.
 */
public class EdgeVoiceEngine {

    public interface Listener {
        void onReady(int index, int cached, int total);
        void onError(int index, String msg);
        void onSpeakStart(int index);
        void onSpeakDone(int index);
    }

    public static final String VOICE = EdgeProtocol.VOICE_HOAIMY;
    public static final String VOICE_NAMMINH = EdgeProtocol.VOICE_NAMMINH;
    /** Giong Edge dang dung (HoaiMy mac dinh, them NamMinh v1.11). */
    protected volatile String engineVoice = EdgeProtocol.VOICE_HOAIMY;
    /** Preload 35 cau nhu PRELOAD_COUNT Python. */
    public static final int PRELOAD_COUNT = 35;
    /** Tran nhu MAX_SPEED Python. */
    public static final float MAX_SPEED = 2.5f;

    protected final Context appCtx;
    protected final Listener listener;
    protected final File cacheDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    /** Thread rieng cho prepare/start MediaPlayer (khong block main). */
    private final ExecutorService playerExec =
            Executors.newSingleThreadExecutor();
    /** Token: cau moi nhat thang, cau cu dang prepare tu huy. */
    private final AtomicInteger playToken = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected final Map<Integer, String> readyPath = new ConcurrentHashMap<>();
    protected final Map<Integer, Float> rawDur = new ConcurrentHashMap<>();
    protected final Map<String, Boolean> queued = new ConcurrentHashMap<>();
    protected final AtomicInteger generation = new AtomicInteger(0);

    private volatile List<SubtitleCue> subs =
            java.util.Collections.emptyList();
    private volatile float baseRate = 1.0f;
    private volatile int ttsVol = 100;
    private volatile boolean playing = false;
    protected volatile int currentIndex = -1;
    /** Loi tai gan nhat de hien ra notification thay vi im lang. */
    protected volatile String lastError;
    public String getLastError() { return lastError; }
    private MediaPlayer player;
    private final Object playerLock = new Object();

    public EdgeVoiceEngine(Context ctx, Listener listener) {
        this(ctx, listener, "edge_cache");
    }

    /** Dung cho lop con (vd VieneuServerEngine) voi thu muc cache rieng. */
    protected EdgeVoiceEngine(Context ctx, Listener listener, String cacheName) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        this.cacheDir = new File(appCtx.getCacheDir(), cacheName);
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public void setSubtitles(List<SubtitleCue> s) {
        generation.incrementAndGet();
        subs = s == null ? java.util.Collections.emptyList() : s;
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        lastError = null;
        stopPlayer();
        currentIndex = -1;
    }

    public void setBaseRate(float v) {
        if (v < 0.5f) v = 0.5f;
        if (v > 2.5f) v = 2.5f;
        baseRate = v;
    }

    public void setTtsVolume(int p) {
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        ttsVol = p;
        synchronized (playerLock) {
            if (player != null) {
                try {
                    player.setVolume(p / 100f, p / 100f);
                } catch (Exception ignored) {}
            }
        }
    }

    public void setPlaying(boolean p) {
        playing = p;
        if (!p) stopPlayer();
    }

    public int getCurrentIndex() { return currentIndex; }

    /** Tong dung luong mp3 HoaiMy dang chiem (byte). */
    public long cacheBytes() {
        long total = 0;
        try {
            File[] fs = cacheDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) total += f.length();
                }
            }
        } catch (Exception ignored) {}
        return total;
    }

    /** Xoa sach mp3 HoaiMy da tai (nut Xoa cache trong app). */
    public int clearCache() {
        generation.incrementAndGet();
        stopPlayer();
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        lastError = null;
        int removed = 0;
        try {
            File[] fs = cacheDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    try {
                        if (f.isFile() && f.delete()) removed++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return removed;
    }

    /**
     * Doi giong Edge (HoaiMy/NamMinh): xoa map tam (file mp3 giu lai,
     * key co ten giong nen khong lan), dung doc cau cu.
     */
    public void setEngineVoice(String name) {
        if (name == null || name.isEmpty() || name.equals(engineVoice)) return;
        engineVoice = name;
        generation.incrementAndGet();
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        lastError = null;
        stopPlayer();
        currentIndex = -1;
    }

    protected String key(int index, String text) {
        try {
            String raw = engineVoice + "|" + index + "|" + text;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "c" + index + "_" + text.length();
        }
    }

    protected File pathFor(int index, String text) {
        return new File(cacheDir, key(index, text) + ".mp3");
    }

    /** Preload 35 cau tu index (port preload_from Python). */
    public void preloadFrom(int index) {
        List<SubtitleCue> s = subs;
        if (s.isEmpty() || index < 0) return;
        int gen = generation.get();
        int end = Math.min(s.size(), index + PRELOAD_COUNT + 1);
        for (int i = index; i < end; i++) {
            final int idx = i;
            String qk = gen + ":" + idx;
            if (readyPath.containsKey(idx) || queued.containsKey(qk)) continue;
            queued.put(qk, true);
            pool.execute(() -> fetch(idx, gen));
        }
    }

    /** So cue hien tai (lop con dung de bao tien do preload). */
    protected int subCount() {
        List<SubtitleCue> s = subs;
        return s == null ? 0 : s.size();
    }

    private void fetch(int index, int gen) {
        List<SubtitleCue> s = subs;
        if (index < 0 || index >= s.size()) {
            queued.remove(gen + ":" + index);
            return;
        }
        if (gen != generation.get()) {
            queued.remove(gen + ":" + index);
            return;
        }
        try {
            SubtitleCue cue = s.get(index);
            File f = pathFor(index, cue.text);
            if (f.exists() && f.length() > 0) {
                float d = probeDuration(f.getAbsolutePath());
                if (d > 0) {
                    rawDur.put(index, d);
                    readyPath.put(index, f.getAbsolutePath());
                    lastError = null;
                    if (listener != null) {
                        listener.onReady(index, readyPath.size(),
                                Math.min(s.size(), PRELOAD_COUNT + 1));
                    }
                    queued.remove(gen + ":" + index);
                    return;
                } else {
                    f.delete();
                }
            }
            // Async: KHONG remove queued o day (bug cu remove ngay nen
            // preload goi lai gay tai trung). Chi remove trong callback.
            fetchAudio(index, gen, cue, f);
        } catch (Exception e) {
            queued.remove(gen + ":" + index);
            lastError = e.getMessage();
            if (listener != null) listener.onError(index, e.getMessage());
        }
    }

    /**
     * Tai audio that cho 1 cue (ban goc: WebSocket Edge TTS).
     * Lop con (vd VieneuServerEngine) override de doi nguon tai
     * (HTTP server nha) ma giu nguyen cache/preload/play.
     */
    protected void fetchAudio(final int index, final int gen,
                              SubtitleCue cue, final File f) {
        EdgeTtsClient.synthesize(cue.text, engineVoice, "+0%", new EdgeTtsClient.Callback() {
                @Override public void onAudio(byte[] mp3) {
                    try {
                        if (gen != generation.get()) return;
                        try {
                            FileOutputStream out = new FileOutputStream(f);
                            out.write(mp3);
                            out.close();
                        } catch (Exception e) {
                            lastError = e.getMessage();
                            if (listener != null) listener.onError(index, e.getMessage());
                            return;
                        }
                        float d = probeDuration(f.getAbsolutePath());
                        if (d <= 0) {
                            f.delete();
                            lastError = "mp3 loi";
                            if (listener != null) listener.onError(index, "mp3 loi");
                            return;
                        }
                        rawDur.put(index, d);
                        readyPath.put(index, f.getAbsolutePath());
                        lastError = null;
                        if (listener != null) {
                            listener.onReady(index, readyPath.size(),
                                    Math.min(subs.size(), PRELOAD_COUNT + 1));
                        }
                    } finally {
                        queued.remove(gen + ":" + index);
                    }
                }
                @Override public void onError(String msg) {
                    try {
                        lastError = msg;
                        if (listener != null) listener.onError(index, msg);
                    } finally {
                        queued.remove(gen + ":" + index);
                    }
                }
            });
        } catch (Exception e) {
            queued.remove(gen + ":" + index);
            lastError = e.getMessage();
            if (listener != null) listener.onError(index, e.getMessage());
        }
    }

    protected static float probeDuration(String path) {
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(path);
            mp.prepare();
            return mp.getDuration() / 1000.0f;
        } catch (Exception e) {
            return 0;
        } finally {
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Doc 1 cau theo slot. Port create_speed_audio Python:
     * speed = raw/slot kep 1..2.5.
     * prepare() chay tren thread rieng (khong block main thread gay khung),
     * start() ngay khi xong. Nhan cau lien tiep thi cau moi nhat thang
     * (token): cau cu dang prepare do se tu huy im lang.
     * Neu mp3 chua tai xong: tra false de ticker giu cau, preload gap.
     */
    public boolean speak(int index, double slotSec, int stream) {
        List<SubtitleCue> s = subs;
        if (index < 0 || index >= s.size()) return false;
        final String path = readyPath.get(index);
        if (path == null) {
            // Chua tai xong: queue gap + preload (port queue_tts priority=0).
            preloadFrom(index);
            return false;
        }
        currentIndex = index;
        Float raw = rawDur.get(index);
        float speed = 1.0f;
        if (raw != null && raw > 0 && slotSec > 0.05) {
            speed = raw / (float) slotSec;
            if (speed < baseRate) speed = baseRate;
            if (speed > MAX_SPEED) speed = MAX_SPEED;
        } else if (baseRate > 1.0f) {
            speed = baseRate;
        }
        final float fSpeed = speed;
        final int fStream = stream;
        final float fVol = ttsVol / 100f;
        playerExec.execute(() -> {
            MediaPlayer mp = new MediaPlayer();
            synchronized (playerLock) {
                stopPlayerLocked();
                player = mp;
            }
            final int tk = playToken.get();
            try {
                mp.setAudioStreamType(fStream);
                mp.setDataSource(path);
                mp.setVolume(fVol, fVol);
                mp.prepare();
                // Trong luc prepare ma co lenh moi/stop -> huy im lang.
                if (tk != playToken.get()) {
                    safeRelease(mp);
                    clearIfSame(mp);
                    return;
                }
                try {
                    if (Math.abs(fSpeed - 1.0f) > 0.01) {
                        mp.setPlaybackParams(
                                mp.getPlaybackParams().setSpeed(fSpeed));
                    }
                } catch (Exception ignored) {}
                final MediaPlayer fmp = mp;
                mp.setOnCompletionListener(done -> {
                    if (tk == playToken.get() && listener != null) {
                        listener.onSpeakDone(index);
                    }
                    if (fmp != null) {
                        try { fmp.release(); } catch (Exception ignored) {}
                        clearIfSame(fmp);
                    }
                });
                mp.start();
                mainHandler.post(() -> {
                    if (tk == playToken.get() && listener != null) {
                        listener.onSpeakStart(index);
                    }
                });
            } catch (Exception e) {
                safeRelease(mp);
                clearIfSame(mp);
                if (tk == playToken.get() && listener != null) {
                    final String msg = e.getMessage();
                    mainHandler.post(() -> {
                        if (listener != null) listener.onError(index, msg);
                    });
                }
            }
        });
        return true;
    }

    private void safeRelease(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Exception ignored) {}
        try { mp.release(); } catch (Exception ignored) {}
    }

    private void clearIfSame(MediaPlayer mp) {
        synchronized (playerLock) {
            if (player == mp) player = null;
        }
    }

    private void stopPlayerLocked() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    public void stopPlayer() {
        // Tang token de cau dang prepare do tu huy im lang.
        playToken.incrementAndGet();
        synchronized (playerLock) {
            stopPlayerLocked();
        }
        currentIndex = -1;
    }

    public int readyCount() { return readyPath.size(); }

    public void shutdown() {
        generation.incrementAndGet();
        playToken.incrementAndGet();
        stopPlayer();
        pool.shutdownNow();
        playerExec.shutdownNow();
    }
}
