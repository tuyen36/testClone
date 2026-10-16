package com.example.webtts;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Engine giong Vieneu tren PC nha (Turbo 48kHz).
 *
 * Ke thua EdgeVoiceEngine de tai dung toan bo cache/preload/play/speed:
 * chi thay doi NGUON TAI â€” HTTP POST /v1/audio/speech thay vi
 * WebSocket Edge. Moi cue: text + voice=ten_giong -> wav 24kHz.
 *
 * Luu y server: CPU chi phuc vu 1-2 dong thoi nen preload it (10 cau)
 * tren 1 thread, tranh de hang doi 429. Enroll giong lam tren PC
 * (POST /v1/voices), app chi chon ten giong da co.
 */
public class VieneuServerEngine extends EdgeVoiceEngine {

    /** Preload mac dinh (server CPU nha yeu, tranh 429). User doi duoc. */
    public static final int PRELOAD_VN = 10;
    public static final int PRELOAD_VN_MIN = 1;
    public static final int PRELOAD_VN_MAX = 30;
    private volatile int preloadCount = PRELOAD_VN;

    private volatile String serverUrl = "";
    private volatile String apiKey = "";
    private volatile String serverVoice = "Clone";

    /** Thread tai tuan tu, khong de server nghe CPU. */
    private final ExecutorService vnExec =
            Executors.newSingleThreadExecutor();
    /** Chi 1 HTTP bay mot luc (server CPU 1 luong; qua tai la 429). */
    private final java.util.concurrent.atomic.AtomicBoolean inflight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Cho nghi sau loi de khong de chet server (ms theo index). */
    private final Map<Integer, Long> errCool = new ConcurrentHashMap<>();
    private static final long ERR_COOL_MS = 30000;

    public VieneuServerEngine(Context ctx, Listener listener) {
        super(ctx, listener, "vieneu_cache");
    }

    /** So cau preload do user chon (mac dinh PRELOAD_VN). */
    public void setPreloadCount(int n) {
        if (n < PRELOAD_VN_MIN) n = PRELOAD_VN_MIN;
        if (n > PRELOAD_VN_MAX) n = PRELOAD_VN_MAX;
        preloadCount = n;
    }

    public int getPreloadCount() { return preloadCount; }

    public void setServerUrl(String url) {
        String u = VieneuVoiceClient.normBase(url == null ? "" : url);
        if (u.equals(serverUrl)) return;
        serverUrl = u;
        // Doi server = audio khac -> xoa map tam (file cache giu lai).
        generation.incrementAndGet();
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        lastError = null;
        stopPlayer();
        currentIndex = -1;
    }

    public String getServerUrl() { return serverUrl; }

    public void setApiKey(String key) {
        apiKey = key == null ? "" : key;
    }

    /** Doi ten giong Vieneu (vd "Clone", "Mai Anh"): xoa map tam. */
    public void setServerVoice(String name) {
        if (name == null || name.isEmpty() || name.equals(serverVoice)) return;
        serverVoice = name;
        generation.incrementAndGet();
        readyPath.clear();
        rawDur.clear();
        queued.clear();
        lastError = null;
        stopPlayer();
        currentIndex = -1;
    }

    public String getServerVoice() { return serverVoice; }

    @Override
    public void setEngineVoice(String name) {
        setServerVoice(name);
    }

    @Override
    protected String key(int index, String text) {
        try {
            String raw = "vieneu|" + serverVoice + "|" + index + "|" + text;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "vn" + index + "_" + text.length();
        }
    }

    @Override
    protected File pathFor(int index, String text) {
        return new File(cacheDir, key(index, text) + ".wav");
    }

    /** Preload it (10 cau) tuan tu de nhe server CPU nha. */
    @Override
    public void preloadFrom(int index) {
        List<SubtitleCue> s = subList();
        if (s.isEmpty() || index < 0) return;
        int gen = generation.get();
        int end = Math.min(s.size(), index + preloadCount + 1);
        for (int i = index; i < end; i++) {
            final int idx = i;
            String qk = gen + ":" + idx;
            if (readyPath.containsKey(idx) || queued.containsKey(qk)) continue;
            queued.put(qk, true);
            vnExec.execute(() -> fetchOne(idx, gen));
        }
    }

    /** Ban fetch() gon cho thread tuan tu (giong logic lop cha). */
    private void fetchOne(int index, int gen) {
        List<SubtitleCue> s = subList();
        if (index < 0 || index >= s.size()) {
            queued.remove(gen + ":" + index);
            return;
        }
        if (gen != generation.get()) {
            queued.remove(gen + ":" + index);
            return;
        }
        // Vua loi gan day: nghi cho server hoi, khong de them.
        Long et = errCool.get(index);
        if (et != null
                && android.os.SystemClock.elapsedRealtime() - et < ERR_COOL_MS) {
            queued.remove(gen + ":" + index);
            return;
        }
        try {
            SubtitleCue cue = s.get(index);
            java.io.File f = pathFor(index, cue.text);
            if (f.exists() && f.length() > 0) {
                float d = probeDuration(f.getAbsolutePath());
                if (d > 0) {
                    rawDur.put(index, d);
                    readyPath.put(index, f.getAbsolutePath());
                    lastError = null;
                    if (listener != null) {
                        listener.onReady(index, readyPath.size(),
                                Math.min(s.size(), preloadCount + 1));
                    }
                    queued.remove(gen + ":" + index);
                    return;
                } else {
                    f.delete();
                }
            }
            // Server 1 luong: dang co request khac thi bo qua, tick sau thu lai.
            if (!inflight.compareAndSet(false, true)) {
                queued.remove(gen + ":" + index);
                return;
            }
            try {
                fetchAudio(index, gen, cue, f);
            } catch (Exception e) {
                inflight.set(false);
                throw e;
            }
        } catch (Exception e) {
            queued.remove(gen + ":" + index);
            lastError = e.getMessage();
            if (listener != null) listener.onError(index, e.getMessage());
        }
    }

    @Override
    protected void fetchAudio(final int index, final int gen,
                              SubtitleCue cue, final File f) {
        final String url = serverUrl;
        final String key = apiKey;
        final String voice = serverVoice;
        VieneuVoiceClient.synthesize(url, key, cue.text, voice,
                new VieneuVoiceClient.Callback() {
            @Override public void onAudio(byte[] wav) {
                try {
                    if (gen != generation.get()) return;
                    try {
                        FileOutputStream out = new FileOutputStream(f);
                        out.write(wav);
                        out.close();
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        if (listener != null) listener.onError(index, e.getMessage());
                        return;
                    }
                    // Server tra wav streaming (size de 0xFFFFFFFF):
                    // MediaPlayer Android tu choi prepare -> sua header
                    // cho dung truoc khi do duration/phat.
                    fixWavHeader(f);
                    float d = probeDuration(f.getAbsolutePath());
                    if (d <= 0) {
                        f.delete();
                        lastError = "wav loi";
                        if (listener != null) listener.onError(index, "wav loi");
                        return;
                    }
                    rawDur.put(index, d);
                    readyPath.put(index, f.getAbsolutePath());
                    lastError = null;
                    errCool.remove(index);
                    if (listener != null) {
                        listener.onReady(index, readyPath.size(),
                                Math.min(subCount(), preloadCount + 1));
                    }
                } finally {
                    inflight.set(false);
                    queued.remove(gen + ":" + index);
                }
            }
            @Override public void onError(String msg) {
                try {
                    lastError = msg;
                    errCool.put(index, android.os.SystemClock.elapsedRealtime());
                    if (listener != null) listener.onError(index, msg);
                } finally {
                    inflight.set(false);
                    queued.remove(gen + ":" + index);
                }
            }
        });
    }

    /** Lay list sub tu lop cha (subs la private). */
    private List<SubtitleCue> subList() {
        // preloadFrom/speak cua lop cha tu quan ly subs; o day can list
        // de chon cue: dung currentIndex lam neo khi khong co cach khac.
        // Don gian nhat: nho list tu setSubtitles.
        return lastSubs;
    }

    private volatile List<SubtitleCue> lastSubs =
            java.util.Collections.emptyList();

    @Override
    public void setSubtitles(List<SubtitleCue> s) {
        lastSubs = s == null ? java.util.Collections.emptyList() : s;
        super.setSubtitles(s);
    }

    /** Sua header wav streaming (RIFF/data size 0xFFFFFFFF) thanh dung
     *  de MediaPlayer Android chiu prepare. File cue nho nen sua truc tiep. */
    private static boolean fixWavHeader(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "rw");
            long len = raf.length();
            if (len < 44) return false;
            byte[] riff = new byte[4];
            raf.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') {
                return false;
            }
            writeLe32(raf, 4, (int) (len - 8));
            long pos = 12;
            byte[] ch = new byte[8];
            while (pos + 8 <= len) {
                raf.seek(pos);
                raf.readFully(ch);
                if (ch[0] == 'd' && ch[1] == 'a' && ch[2] == 't' && ch[3] == 'a') {
                    writeLe32(raf, pos + 4, (int) (len - (pos + 8)));
                    return true;
                }
                long csz = le32(ch, 4) & 0xFFFFFFFFL;
                if (csz < 0 || csz > len) return false;
                pos += 8 + csz + (csz & 1);
            }
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void writeLe32(java.io.RandomAccessFile raf, long pos, int v)
            throws Exception {
        raf.seek(pos);
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    public void shutdownVn() {
        try {
            vnExec.shutdownNow();
        } catch (Exception ignored) {}
    }
}
