package com.example.webtts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V1.19: Engine giong Google offline render truoc file wav.
 *
 * Ke thua EdgeVoiceEngine de tai dung cache/preload/play/speed/notif:
 * chi thay doi NGUON TAI — tts.synthesizeToFile() thay vi mang.
 * Ly do: SubtitleSpeaker cu doc live tung cau (QUEUE_FLUSH) nen
 * may yeu (Fire) load cau lau; render truoc + phat MediaPlayer
 * het tre.
 *
 * - Cache wav theo sha1("google"|voice|index|text), synth o 1.0x,
 *   toc do dieu chinh luc phat nhu Edge (raw/slot, kep 1..2.5).
 * - TTS may chi synth 1 luong: preload tuan tu tren 1 thread.
 * - So cau preload dung chung pref preload_vn voi Vieneu (1-30).
 * - setLanguage(vi-VN) reset voice ve default nen phai ap lai
 *   voice da chon truoc moi lan synth.
 */
public class GoogleFileEngine extends EdgeVoiceEngine {

    /** TTS he thong (do service so huu, doi khi doi engine). */
    private volatile TextToSpeech tts;
    private volatile int preloadCount = 10;
    /** Offset/stream de driveEngines dung chung kieu Edge (service truyen). */
    private volatile float audioOffset = 0f;
    private volatile int ttsStream = android.media.AudioManager.STREAM_MUSIC;

    /** Synth tuan tu (TTS may 1 luong). */
    private final ExecutorService ggExec =
            Executors.newSingleThreadExecutor();
    private final AtomicInteger utterSeq = new AtomicInteger(0);
    /** utterId -> {index, gen, path}. */
    private final Map<String, int[]> pending = new ConcurrentHashMap<>();
    private final Map<String, String> pendingPath = new ConcurrentHashMap<>();

    private volatile List<SubtitleCue> lastSubs =
            java.util.Collections.emptyList();

    public GoogleFileEngine(Context ctx, Listener listener, TextToSpeech tts) {
        super(ctx, listener, "google_cache");
        setTts(tts);
    }

    /** Doi TTS he thong (khi doi engine trong app): gan lai listener. */
    public void setTts(TextToSpeech t) {
        tts = t;
        if (t == null) return;
        try {
            t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onStop(String id, boolean interrupted) {
                    onSynthError(id, "stop");
                }
                @Override public void onDone(String id) {
                    onSynthDone(id);
                }
                @Override public void onError(String id) {
                    onSynthError(id, "error");
                }
            });
        } catch (Exception ignored) {}
    }

    /** So cau preload (dung chung pref preload_vn voi Vieneu, 1-30). */
    public void setPreloadCount(int n) {
        if (n < 1) n = 1;
        if (n > 30) n = 30;
        preloadCount = n;
    }

    public int getPreloadCount() { return preloadCount; }

    // ----- tuong thich cho noi speaker cu -----
    public void resetIndex() {
        stopPlayer();
    }

    public void setAudioOffset(float v) { audioOffset = v; }
    public float getAudioOffset() { return audioOffset; }

    /** Toc do doc co so (map sang baseRate cua lop cha). */
    public void setSpeechRate(float v) { setBaseRate(v); }

    public void setTtsStream(int stream) { ttsStream = stream; }
    public int getTtsStream() { return ttsStream; }

    public void stop() { stopPlayer(); }

    /** Ten voice Google dang dung (luu trong engineVoice lop cha). */
    public void setTtsVoiceName(String name) {
        setEngineVoice(name == null ? "" : name);
    }

    public void setSubtitlesIfNew(List<SubtitleCue> s) {
        if (s != lastSubs) setSubtitles(s);
    }

    @Override
    public void setSubtitles(List<SubtitleCue> s) {
        lastSubs = s == null ? java.util.Collections.emptyList() : s;
        super.setSubtitles(s);
    }

    @Override
    public void stopPlayer() {
        super.stopPlayer();
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {}
    }

    @Override
    protected String key(int index, String text) {
        try {
            String raw = "google|" + engineVoice + "|" + index + "|" + text;
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "gg" + index + "_" + text.length();
        }
    }

    @Override
    protected File pathFor(int index, String text) {
        return new File(cacheDir, key(index, text) + ".wav");
    }

    /** Preload tuan tu (TTS may 1 luong, tranh de don synth). */
    @Override
    public void preloadFrom(int index) {
        List<SubtitleCue> s = lastSubs;
        if (s.isEmpty() || index < 0) return;
        int gen = generation.get();
        int end = Math.min(s.size(), index + preloadCount + 1);
        for (int i = index; i < end; i++) {
            final int idx = i;
            String qk = gen + ":" + idx;
            if (readyPath.containsKey(idx) || queued.containsKey(qk)) continue;
            queued.put(qk, true);
            ggExec.execute(() -> fetchOne(idx, gen));
        }
    }

    /** Ban fetch() gon cho thread tuan tu (giong logic lop cha). */
    private void fetchOne(int index, int gen) {
        List<SubtitleCue> s = lastSubs;
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
                                Math.min(s.size(), preloadCount + 1));
                    }
                    queued.remove(gen + ":" + index);
                    return;
                } else {
                    f.delete();
                }
            }
            // Chi remove queued trong callback synth (tranh synth trung).
            fetchAudio(index, gen, cue, f);
        } catch (Exception e) {
            queued.remove(gen + ":" + index);
            lastError = e.getMessage();
            if (listener != null) listener.onError(index, e.getMessage());
        }
    }

    @Override
    protected void fetchAudio(final int index, final int gen,
                              SubtitleCue cue, final File f) {
        TextToSpeech t = tts;
        if (t == null) {
            finishError(index, gen, "TTS chua san sang");
            return;
        }
        try {
            t.setLanguage(new Locale("vi", "VN"));
        } catch (Exception ignored) {}
        // setLanguage reset voice -> ap lai voice da chon.
        try {
            String want = engineVoice;
            if (want != null && !want.isEmpty()) {
                java.util.Set<android.speech.tts.Voice> vs = t.getVoices();
                if (vs != null) {
                    for (android.speech.tts.Voice v : vs) {
                        if (v == null || v.getLocale() == null) continue;
                        if (!"vi".equals(v.getLocale().getLanguage())) continue;
                        if (want.equals(v.getName())) {
                            t.setVoice(v);
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        // Synth luon o 1.0x (toc do dieu chinh luc phat nhu Edge).
        try {
            t.setSpeechRate(1.0f);
        } catch (Exception ignored) {}
        String utterId = "gg-" + gen + "-" + index + "-"
                + utterSeq.incrementAndGet();
        pending.put(utterId, new int[]{index, gen});
        pendingPath.put(utterId, f.getAbsolutePath());
        int rc;
        try {
            rc = t.synthesizeToFile(cue.text, new Bundle(), f, utterId);
        } catch (Exception e) {
            finishError(index, gen, e.getMessage());
            pending.remove(utterId);
            pendingPath.remove(utterId);
            return;
        }
        if (rc != TextToSpeech.SUCCESS) {
            finishError(index, gen, "synth tu choi");
            pending.remove(utterId);
            pendingPath.remove(utterId);
        }
        // Thanh cong: cho onDone/onError (khong remove queued o day).
    }

    private void onSynthDone(String utterId) {
        int[] meta = pending.remove(utterId);
        String path = pendingPath.remove(utterId);
        if (meta == null || path == null) return;
        int index = meta[0], gen = meta[1];
        try {
            if (gen != generation.get()) return;
            File f = new File(path);
            if (!f.exists() || f.length() == 0) {
                finishError(index, gen, "wav rong");
                return;
            }
            float d = probeDuration(path);
            if (d <= 0) {
                try { f.delete(); } catch (Exception ignored) {}
                finishError(index, gen, "wav loi");
                return;
            }
            rawDur.put(index, d);
            readyPath.put(index, path);
            lastError = null;
            if (listener != null) {
                listener.onReady(index, readyPath.size(),
                        Math.min(subCount(), preloadCount + 1));
            }
        } finally {
            queued.remove(gen + ":" + index);
        }
    }

    private void onSynthError(String utterId, String what) {
        int[] meta = pending.remove(utterId);
        pendingPath.remove(utterId);
        if (meta == null) return;
        // Stop chu dong (pause/seek/doi giong): gen da doi -> im lang.
        if (meta[1] != generation.get()) {
            queued.remove(meta[1] + ":" + meta[0]);
            return;
        }
        finishError(meta[0], meta[1], what);
    }

    private void finishError(int index, int gen, String msg) {
        try {
            lastError = msg;
            if (listener != null) listener.onError(index, msg);
        } finally {
            queued.remove(gen + ":" + index);
        }
    }

    public void shutdownGg() {
        try {
            ggExec.shutdownNow();
        } catch (Exception ignored) {}
    }
}
