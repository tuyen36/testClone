package com.example.webtts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Locale;

/**
 * V1.7: Service doc sub CHAY NEN + nut noi dieu khien + 2 giong.
 *
 * - Giong GOOGLE (offline): TextToSpeech + SubtitleSpeaker nhu cu.
 * - Giong HOAIMY Edge (online): EdgeVoiceEngine tai mp3 + preload 35 cau,
 *   do raw THAT bang MediaPlayer, speed = raw/target (port Python).
 * - MainActivity chi gui lenh (play/pause/seek/offset/rate/volume/voice) + sub.
 * - Nut noi tron mo 50% luon tren cung (TYPE_APPLICATION_OVERLAY),
 *   keo di duoc. Bam vao bung panel: Play/Pause, -1s/+1s, gio chay,
 *   volume giong doc, chon giong, Stop.
 * - Ticker chay doc lap 250ms nen tat man / sang app Netflix van doc.
 */
public class TtsPlaybackService extends Service
        implements TextToSpeech.OnInitListener {

    // ----- lenh tu Activity / nut noi -----
    public static final String ACTION_PLAY = "com.example.webtts.PLAY";
    public static final String ACTION_PAUSE = "com.example.webtts.PAUSE";
    public static final String ACTION_TOGGLE = "com.example.webtts.TOGGLE";
    public static final String ACTION_STOP_ALL = "com.example.webtts.STOP_ALL";
    public static final String ACTION_NUDGE = "com.example.webtts.NUDGE";
    public static final String ACTION_SHOW_FLOAT = "com.example.webtts.SHOW_FLOAT";
    public static final String ACTION_HIDE_FLOAT = "com.example.webtts.HIDE_FLOAT";
    public static final String ACTION_EXIT = "com.example.webtts.EXIT";
    public static final String EXTRA_DELTA = "delta";

    private static final String CH_ID = "webtts_playback";
    private static final int NOTIF_ID = 1001;

    // Do mo nut noi: 50%, nen den, bo tron (ban Netflix).
    private static final float FLOAT_ALPHA = 0.5f;

    // ----- state dung chung (static de Activity doc nhanh) -----
    private static List<SubtitleCue> sharedSubs =
            java.util.Collections.emptyList();
    private static int sharedCount = 0;

    public static void setSharedSubtitles(List<SubtitleCue> subs) {
        sharedSubs = subs == null
                ? java.util.Collections.emptyList() : subs;
        sharedCount = sharedSubs.size();
        if (running != null) running.applyShared();
        // Nap sub moi: moc gio ve 00:00 + dung doc + chot moc moi.
        if (running != null) running.resetToStart();
    }

    /** Nap sub moi -> ve 00:00, dung doc, san sang Play tu dau. */
    private void resetToStart() {
        setPlaying(false);
        position = 0;
        playBase = 0;
        playStartElapsed = android.os.SystemClock.elapsedRealtime();
        lastCueText = "...";
        sleepAtElapsed = 0;
        sleepLeftMs = 0;
        if (speaker != null) speaker.resetIndex();
        if (edge != null) edge.stopPlayer();
        if (vn != null) vn.stopPlayer();
        refreshFloatTime();
        updateNotif();
    }

    private static TtsPlaybackService running;

    public static boolean isRunning() { return running != null; }
    public static boolean isPlayingNow() {
        return running != null && running.playing;
    }
    public static String currentCueText() {
        if (running == null) return "...";
        return running.lastCueText;
    }

    // ----- instance -----
    private TextToSpeech tts;
    private SubtitleSpeaker speaker;
    private boolean ttsReady = false;

    /** 0 = Google offline, 1 = HoaiMy Edge online, 2 = NamMinh Edge online,
     *  3 = Vieneu Turbo tren PC nha (online LAN). */
    public static final int VOICE_GOOGLE = 0;
    public static final int VOICE_HOAIMY = 1;
    public static final int VOICE_NAMMINH = 2;
    public static final int VOICE_VIENEUPC = 3;
    private int voice = VOICE_GOOGLE;
    private EdgeVoiceEngine edge;
    /** Engine Vieneu PC (ke thua EdgeVoiceEngine: chung cache/preload/play). */
    private VieneuServerEngine vn;
    /** Ten giong Vieneu dang chon (vd "Clone", "Mai Anh"). */
    private String vieneuVoiceName = "Clone";
    private String vieneuUrl = "";
    private String vieneuKey = "";
    /** Package engine TTS dang dung (rong = mac dinh he thong). */
    private String ttsEngine = "";
    /** Ten voice Google offline da chon (rong = mac dinh he thong). */
    private String gvoiceName = "";
    /** So cau preload Vieneu do user chon (mac dinh 10, 1-30). */
    private int preloadVn = VieneuServerEngine.PRELOAD_VN;

    /** true = 1 trong 2 giong Edge online. */
    private static boolean isEdgeVoice(int v) {
        return v == VOICE_HOAIMY || v == VOICE_NAMMINH;
    }

    /** true = giong chay bang file mp3/wav tai san (Edge hoac Vieneu PC). */
    private static boolean isMp3Voice(int v) {
        return isEdgeVoice(v) || v == VOICE_VIENEUPC;
    }

    /** Engine mp3 dang dung (Edge hoac Vieneu PC). */
    private EdgeVoiceEngine mp3() {
        return (voice == VOICE_VIENEUPC && vn != null) ? vn : edge;
    }

    private boolean playing = false;
    private double position = 0;
    private double duration = 0;
    /**
     * Tong thoi luong phim that (giay, do user nhap theo Netflix).
     * 0 = chua nhap -> dung duration (cuoi sub) lam moc.
     */
    private double totalLen = 0;
    /** Dong ho that (thay position += 0.25): moc he thong khi Play. */
    private long playStartElapsed = 0;
    private double playBase = 0;
    private float offset = 0f;
    private float rate = 1.0f;
    private int ttsVol = 100;
    /** true = sub ra loa bao thuc (tach khoi Netflix), false = chung media. */
    private boolean splitAudio = true;
    private String lastCueText = "...";

    /** Ho so phim dang doc de luu/tiep tuc vi tri (setFilm tu Activity). */
    private String filmId = "";
    private String filmName = "";
    /** Hen gio tat doc: moc elapsedRealtime, 0 = tat. */
    private long sleepAtElapsed = 0;
    /** Phan con lai (ms) khi dang pause — Play thi dem tiep. */
    private long sleepLeftMs = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (playing && duration > 0) {
                // Dong ho he thong (sai so ms), khong cong tay de het lech tich luy.
                position = playBase
                        + (android.os.SystemClock.elapsedRealtime()
                                - playStartElapsed) / 1000.0;
                if (position >= duration) {
                    position = duration;
                    setPlaying(false);
                    cleanupFinishedFilm();
                }
                // Hen gio tat doc (chi dem khi dang phat).
                if (playing && sleepAtElapsed > 0
                        && android.os.SystemClock.elapsedRealtime() >= sleepAtElapsed) {
                    sleepAtElapsed = 0;
                    sleepLeftMs = 0;
                    setPlaying(false);
                    try {
                        android.widget.Toast.makeText(TtsPlaybackService.this,
                                "Het gio hen — da dung doc.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
                driveEngines();
                updateNotif();
            }
            refreshFloatTime();
            handler.postDelayed(this, 100);
        }
    };

    /** Day position cho engine dang chon (Google, Edge hoac Vieneu PC). */
    private void driveEngines() {
        EdgeVoiceEngine mp3 = mp3();
        if (isMp3Voice(voice) && mp3 != null) {
            int idx = SubtitleParser.findIndex(sharedSubs, position + offset);
            if (idx >= 0) {
                SubtitleCue cue = sharedSubs.get(idx);
                if (idx != mp3.getCurrentIndex()) {
                    double slot = cue.end - position - offset;
                    if (slot < 0.5) slot = 0.5;
                    if (slot > cue.duration()) slot = cue.duration();
                    int stream = splitAudio
                            ? android.media.AudioManager.STREAM_ALARM
                            : android.media.AudioManager.STREAM_MUSIC;
                    boolean ok = mp3.speak(idx, slot, stream);
                    if (ok) {
                        lastCueText = cue.text;
                        if (floatCue != null) floatCue.setText(lastCueText);
                        mp3.preloadFrom(idx);
                    }
                }
            }
        } else if (ttsReady && speaker != null) {
            speaker.onPosition(position);
        }
    }

    // ----- nut noi -----
    private WindowManager wm;
    private View floatRoot;
    private Button floatDot;
    private LinearLayout floatPanel;
    private TextView floatCue;
    private TextView floatTime;
    /** Gio con lai dem nguoc nhu Netflix. */
    private TextView floatRemain;
    /** Cum mo rong (an theo mac dinh): seek, +-1s, volume, giong, ... */
    private LinearLayout floatMore;
    private SeekBar floatSeek;
    private Button floatPlayPause;
    private Button floatVoiceBtn;
    private WindowManager.LayoutParams floatParams;
    private boolean floatShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        loadPrefs();
        startFg();
        createTts();
        edge = new EdgeVoiceEngine(this, new EdgeVoiceEngine.Listener() {
            @Override public void onReady(int index, int cached, int total) {
                handler.post(() -> updateNotif());
            }
            @Override public void onError(int index, String msg) {
                handler.post(() -> updateNotif());
            }
            @Override public void onSpeakStart(int index) {
                handler.post(() -> {
                    if (index >= 0 && index < sharedSubs.size()) {
                        lastCueText = sharedSubs.get(index).text;
                        if (floatCue != null) floatCue.setText(lastCueText);
                    }
                });
            }
            @Override public void onSpeakDone(int index) {}
        });
        edge.setSubtitles(sharedSubs);
        edge.setBaseRate(rate);
        edge.setTtsVolume(ttsVol);
        // Service khoi dong lai (vd sau reboot): dong bo giong Edge theo prefs.
        edge.setEngineVoice(voice == VOICE_NAMMINH
                ? EdgeVoiceEngine.VOICE_NAMMINH
                : EdgeProtocol.VOICE_HOAIMY);
        vn = new VieneuServerEngine(this, new EdgeVoiceEngine.Listener() {
            @Override public void onReady(int index, int cached, int total) {
                handler.post(() -> updateNotif());
            }
            @Override public void onError(int index, String msg) {
                handler.post(() -> updateNotif());
            }
            @Override public void onSpeakStart(int index) {
                handler.post(() -> {
                    if (index >= 0 && index < sharedSubs.size()) {
                        lastCueText = sharedSubs.get(index).text;
                        if (floatCue != null) floatCue.setText(lastCueText);
                    }
                });
            }
            @Override public void onSpeakDone(int index) {}
        });
        vn.setSubtitles(sharedSubs);
        vn.setBaseRate(rate);
        vn.setTtsVolume(ttsVol);
        vn.setServerUrl(vieneuUrl);
        vn.setApiKey(vieneuKey);
        vn.setPreloadCount(preloadVn);
        vn.setServerVoice(vieneuVoiceName);
        handler.post(ticker);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        offset = sp.getFloat("offset", 0f);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        splitAudio = sp.getBoolean("splitAudio", true);
        voice = sp.getInt("voice", VOICE_GOOGLE);
        vieneuVoiceName = sp.getString("vieneu_voice", "Clone");
        if (vieneuVoiceName == null || vieneuVoiceName.isEmpty()) vieneuVoiceName = "Clone";
        vieneuUrl = sp.getString("vieneu_url", "");
        if (vieneuUrl == null) vieneuUrl = "";
        vieneuKey = sp.getString("vieneu_key", "");
        if (vieneuKey == null) vieneuKey = "";
        gvoiceName = sp.getString("gvoice", "");
        if (gvoiceName == null) gvoiceName = "";
        ttsEngine = sp.getString("tts_engine", "");
        if (ttsEngine == null) ttsEngine = "";
        preloadVn = sp.getInt("preload_vn", VieneuServerEngine.PRELOAD_VN);
        if (preloadVn < VieneuServerEngine.PRELOAD_VN_MIN) {
            preloadVn = VieneuServerEngine.PRELOAD_VN_MIN;
        }
        if (preloadVn > VieneuServerEngine.PRELOAD_VN_MAX) {
            preloadVn = VieneuServerEngine.PRELOAD_VN_MAX;
        }
        totalLen = sp.getFloat("totalLen", 0f);
        filmId = sp.getString("curfilm_id", "");
        filmName = sp.getString("curfilm_name", "phim");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts.setLanguage(new Locale("vi", "VN"));
            } catch (Exception ignored) {}
            ttsReady = true;
            speaker = new SubtitleSpeaker(tts, new SubtitleSpeaker.Listener() {
                @Override public void onSpeakStart(int index) {
                    handler.post(() -> {
                        if (index >= 0 && index < sharedSubs.size()) {
                            lastCueText = sharedSubs.get(index).text;
                            if (floatCue != null) floatCue.setText(lastCueText);
                        }
                    });
                }
                @Override public void onSpeakDone(int index) {}
                @Override public void onTtsError(int index) {}
            });
            speaker.setSubtitles(sharedSubs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            speaker.setTtsVolume(ttsVol);
            speaker.setTtsVoiceName(gvoiceName);
            applySplitAudio();
            recomputeDuration();
            updateNotif();
        } else if (!ttsEngine.isEmpty()) {
            // Engine da chon loi (vd bi go cai) -> roi ve mac dinh he thong.
            ttsEngine = "";
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putString("tts_engine", "").apply();
            createTts();
        }
    }

    /** Tao TextToSpeech theo engine da chon (rong = mac dinh he thong). */
    private void createTts() {
        ttsReady = false;
        if (speaker != null) {
            try { speaker.stop(); } catch (Exception ignored) {}
            speaker = null;
        }
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        try {
            if (ttsEngine != null && !ttsEngine.isEmpty()) {
                tts = new TextToSpeech(getApplicationContext(), this,
                        ttsEngine);
            } else {
                tts = new TextToSpeech(getApplicationContext(), this);
            }
        } catch (Exception e) {
            ttsEngine = "";
            tts = new TextToSpeech(getApplicationContext(), this);
        }
    }

    /**
     * Tach/gop stream am thanh giong doc.
     * GHI CHU (gioi han Android): Google TTS mac dinh phat tren STREAM_MUSIC
     * chung voi Netflix -> phim cung to/nho theo. Dat PARAM_STREAM=ALARM thi
     * phim cung media khong anh huong sub; sub doi sang volume bao thuc.
     * Nhuoc: may de che do im lang / volume bao thuc nho thi sub nho theo.
     */
    private void applySplitAudio() {
        if (speaker == null) return;
        try {
            if (splitAudio) {
                speaker.setTtsStream(android.media.AudioManager.STREAM_ALARM);
            } else {
                speaker.setTtsStream(android.media.AudioManager.STREAM_MUSIC);
            }
        } catch (Exception ignored) {}
    }

    public static void setSplitAudio(boolean v) {
        if (running != null) {
            running.splitAudio = v;
            running.applySplitAudio();
        }
    }

    public static boolean getSplitAudio() {
        return running == null || running.splitAudio;
    }

    /** "mm:ss / mm:ss" de can dong bo voi Netflix. */
    public static String timeText() {
        if (running == null) return "00:00 / 00:00";
        return fmtTime(running.position) + " / " + fmtTime(running.duration);
    }

    private static String fmtTime(double sec) {
        if (sec < 0) sec = 0;
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60);
    }

    private void startFg() {
        startForeground(NOTIF_ID, buildNotif());
    }

    /** Dung chung cho startFg + updateNotif (khong de mat nut Play/Pause). */
    private Notification buildNotif() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Sub TTS", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Intent tgI = new Intent(this, TtsPlaybackService.class);
        tgI.setAction(ACTION_TOGGLE);
        PendingIntent tgPi = PendingIntent.getService(this, 2, tgI, flags);

        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Sub TTS dang chay nen")
                .setContentText(notifText())
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", tgPi)
                .setOngoing(true)
                .build();
    }

    private String notifText() {
        String v = voice == VOICE_HOAIMY ? "HoaiMy"
                : (voice == VOICE_NAMMINH ? "NamMinh"
                : (voice == VOICE_VIENEUPC ? "Vieneu:" + vieneuVoiceName : "Google"));
        if (sharedCount > 0) {
            String base = (playing ? "Dang doc" : "Tam dung") + " "
                    + sharedCount + " cau (" + v + ")";
            EdgeVoiceEngine mp3 = mp3();
            if (isMp3Voice(voice) && mp3 != null) {
                if (mp3.readyCount() > 0) {
                    base += " - tai " + mp3.readyCount() + " cau";
                } else if (mp3.getLastError() != null) {
                    String e = mp3.getLastError();
                    if (e.length() > 60) e = e.substring(0, 60);
                    base += " - loi: " + e;
                } else {
                    base += " - dang tai...";
                }
            }
            return base;
        }
        return "Cho nap phu de .srt (" + v + ")";
    }

    /** Loi engine mp3 gan nhat de MainActivity hien (null = khong loi). */
    public static String edgeError() {
        if (running == null) return null;
        EdgeVoiceEngine mp3 = running.mp3();
        if (mp3 == null) return null;
        return mp3.getLastError();
    }

    /** Han che spam notification: doi trang thai/cau moi cap nhat ngay,
     *  khong thi toi da 1 lan/2 giay (ticker goi 10 lan/giay). */
    private long lastNotifAt = 0;
    private String lastNotifSig = "";

    private void updateNotif() {
        try {
            String sig = playing + "|" + lastCueText + "|"
                    + (mp3() != null ? mp3().readyCount() : -1) + "|"
                    + voice + "|" + sharedCount;
            long now = android.os.SystemClock.elapsedRealtime();
            if (sig.equals(lastNotifSig) && now - lastNotifAt < 2000) return;
            lastNotifSig = sig;
            lastNotifAt = now;
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotif());
        } catch (Exception ignored) {}
    }

    // ----- nhan lenh -----
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_PLAY.equals(a)) setPlaying(true);
            else if (ACTION_PAUSE.equals(a)) setPlaying(false);
            else if (ACTION_TOGGLE.equals(a)) setPlaying(!playing);
            else if (ACTION_STOP_ALL.equals(a)) {
                setPlaying(false);
                hideFloat();
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_NUDGE.equals(a)) {
                double d = intent.getDoubleExtra(EXTRA_DELTA, 0);
                nudge(d);
            } else if (ACTION_SHOW_FLOAT.equals(a)) {
                showFloat();
            } else if (ACTION_HIDE_FLOAT.equals(a)) {
                hideFloat();
            } else if (ACTION_EXIT.equals(a)) {
                exitApp();
            }
        }
        startFg();
        return START_STICKY;
    }

    private void setPlaying(boolean p) {
        if (p && sharedSubs.isEmpty()) {
            playing = false;
            updateNotif();
            return;
        }
        if (p && !playing) {
            // Bam Play: chot moc gio that (het lech tich luy).
            syncClock();
        }
        if (!p && playing) {
            // Bam Pause: dong bang position theo dong ho that.
            position = clockNow();
        }
        playing = p;
        if (p) {
            // Tiep tuc dem hen gio con do khi pause.
            if (sleepLeftMs > 0 && sleepAtElapsed <= 0) {
                sleepAtElapsed = android.os.SystemClock.elapsedRealtime()
                        + sleepLeftMs;
                sleepLeftMs = 0;
            }
        } else {
            // Pause: dong bang hen gio, Play thi dem tiep.
            if (sleepAtElapsed > 0) {
                sleepLeftMs = Math.max(0, sleepAtElapsed
                        - android.os.SystemClock.elapsedRealtime());
                sleepAtElapsed = 0;
            }
            saveFilmNow();
        }
        if (speaker != null) speaker.setPlaying(p);
        if (edge != null) edge.setPlaying(p);
        if (vn != null) vn.setPlaying(p);
        if (!p) {
            if (tts != null) {
                try { tts.stop(); } catch (Exception ignored) {}
            }
            if (edge != null) edge.stopPlayer();
            if (vn != null) vn.stopPlayer();
        } else {
            // Bam Play: cat cau cu + preload ngay cau mp3.
            if (speaker != null) speaker.resetIndex();
            if (edge != null) edge.stopPlayer();
            if (vn != null) vn.stopPlayer();
            driveEngines();
            if (isMp3Voice(voice) && mp3() != null) {
                int idx = SubtitleParser.findIndex(sharedSubs, position + offset);
                if (idx < 0) idx = 0;
                mp3().preloadFrom(idx);
            }
        }
        refreshFloatPlayBtn();
        updateNotif();
    }

    /** Chot moc: gio = position hien tai, dem tu bay gio. */
    private void syncClock() {
        playBase = position;
        playStartElapsed = android.os.SystemClock.elapsedRealtime();
    }

    /** Gio that hien tai theo moc (dung khi Pause/nudge). */
    private double clockNow() {
        if (!playing) return position;
        return playBase + (android.os.SystemClock.elapsedRealtime()
                - playStartElapsed) / 1000.0;
    }

    private void nudge(double delta) {
        // Nudge theo dong ho that + chot moc lai de khong troi gio.
        double now = playing ? clockNow() : position;
        position = now + delta;
        if (position < 0) position = 0;
        if (duration > 0 && position > duration) position = duration;
        if (playing) syncClock();
        if (isMp3Voice(voice) && mp3() != null) {
            mp3().stopPlayer();
            int idx = SubtitleParser.findIndex(sharedSubs, position + offset);
            if (idx >= 0) mp3().preloadFrom(idx);
            driveEngines();
        } else if (speaker != null) {
            speaker.resetIndex();
            speaker.onPosition(position);
        }
        refreshFloatTime();
    }

    private void applyShared() {
        if (speaker != null) {
            speaker.setSubtitlesIfNew(sharedSubs);
            speaker.setAudioOffset(offset);
            speaker.setSpeechRate(rate);
            speaker.setTtsVolume(ttsVol);
        }
        if (edge != null) {
            edge.setSubtitles(sharedSubs);
            edge.setBaseRate(rate);
            edge.setTtsVolume(ttsVol);
        }
        if (vn != null) {
            vn.setSubtitles(sharedSubs);
            vn.setBaseRate(rate);
            vn.setTtsVolume(ttsVol);
        }
        recomputeDuration();
        updateNotif();
    }

    private void recomputeDuration() {
        duration = 0;
        for (SubtitleCue c : sharedSubs) duration = Math.max(duration, c.end);
    }

    // ----- API static cho Activity / nut noi -----
    public static void cmd(Context ctx, String action) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(action);
        startSvc(ctx, i);
    }

    /** Thoat han app: dung service + go notification (goi tu Activity/nut noi). */
    public static void cmdExit(Context ctx) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(ACTION_EXIT);
        startSvc(ctx, i);
    }

    /** Thoat han: dung doc + go nut noi + go notification + dung service. */
    private void exitApp() {
        setPlaying(false);
        hideFloat();
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        stopSelf();
    }

    public static void cmdNudge(Context ctx, double delta) {
        Intent i = new Intent(ctx, TtsPlaybackService.class);
        i.setAction(ACTION_NUDGE);
        i.putExtra(EXTRA_DELTA, delta);
        startSvc(ctx, i);
    }

    private static void startSvc(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static void setOffset(float v) {
        if (running != null) {
            running.offset = v;
            if (running.speaker != null) running.speaker.setAudioOffset(v);
        }
    }

    public static float getOffset() {
        return running != null ? running.offset : 0f;
    }

    public static void setRate(float v) {
        if (running != null) {
            running.rate = v;
            if (running.speaker != null) running.speaker.setSpeechRate(v);
            if (running.edge != null) running.edge.setBaseRate(v);
            if (running.vn != null) running.vn.setBaseRate(v);
        }
    }

    public static void setTtsVolume(int p) {
        if (running != null) {
            running.ttsVol = p;
            if (running.speaker != null) running.speaker.setTtsVolume(p);
            if (running.edge != null) running.edge.setTtsVolume(p);
            if (running.vn != null) running.vn.setTtsVolume(p);
        }
    }

    public static int getTtsVolume() {
        return running != null ? running.ttsVol : 100;
    }

    /** 0 = Google offline, 1 = HoaiMy Edge online, 2 = NamMinh Edge online,
     *  3 = Vieneu Turbo tren PC nha (online LAN). */
    public static void setVoice(int v) {
        if (running != null) {
            boolean changed = running.voice != v;
            running.voice = v;
            if (changed) {
                if (running.edge != null) {
                    running.edge.setEngineVoice(v == VOICE_NAMMINH
                            ? EdgeVoiceEngine.VOICE_NAMMINH
                            : EdgeProtocol.VOICE_HOAIMY);
                }
                // Doi giong: dung engine cu, preload lai engine moi.
                if (running.speaker != null) running.speaker.stop();
                if (running.tts != null) {
                    try { running.tts.stop(); } catch (Exception ignored) {}
                }
                if (running.edge != null) running.edge.stopPlayer();
                if (running.vn != null) running.vn.stopPlayer();
                if (isMp3Voice(v) && running.mp3() != null
                        && running.playing) {
                    int idx = SubtitleParser.findIndex(sharedSubs,
                            running.position + running.offset);
                    if (idx < 0) idx = 0;
                    running.mp3().preloadFrom(idx);
                }
                running.updateNotif();
                running.refreshFloatVoiceBtn();
            }
        }
    }

    public static int getVoice() {
        return running != null ? running.voice : VOICE_GOOGLE;
    }

    /** Ten giong Vieneu dang chon (mac dinh "Clone"). */
    public static String getVieneuVoice() {
        if (running != null && running.vieneuVoiceName != null
                && !running.vieneuVoiceName.isEmpty()) {
            return running.vieneuVoiceName;
        }
        return "Clone";
    }

    /** Doi ten giong Vieneu: luu prefs + ap vao engine + preload lai. */
    public static void setVieneuVoice(String name) {
        if (name == null || name.trim().isEmpty()) return;
        String n = name.trim();
        if (running != null) {
            running.vieneuVoiceName = n;
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putString("vieneu_voice", n).apply();
            if (running.vn != null) {
                running.vn.setServerVoice(n);
                if (running.voice == VOICE_VIENEUPC && running.playing) {
                    int idx = SubtitleParser.findIndex(sharedSubs,
                            running.position + running.offset);
                    if (idx < 0) idx = 0;
                    running.vn.preloadFrom(idx);
                }
            }
            running.updateNotif();
            running.refreshFloatVoiceBtn();
        }
    }

    public static String getVieneuUrl() {
        return running != null ? running.vieneuUrl : "";
    }

    /** Doi dia chi server Vieneu (vd http://192.168.1.10:8000). */
    public static void setVieneuUrl(String url) {
        String u = VieneuVoiceClient.normBase(url == null ? "" : url);
        if (running != null) {
            running.vieneuUrl = u;
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putString("vieneu_url", u).apply();
            if (running.vn != null) running.vn.setServerUrl(u);
        }
    }

    public static String getVieneuKey() {
        return running != null ? running.vieneuKey : "";
    }

    /** So cau preload Vieneu hien tai. */
    public static int getPreloadVn() {
        if (running != null) return running.preloadVn;
        return VieneuServerEngine.PRELOAD_VN;
    }

    /** Doi so cau preload Vieneu (1-30): luu prefs + ap ngay. */
    public static void setPreloadVn(int n) {
        if (n < VieneuServerEngine.PRELOAD_VN_MIN) {
            n = VieneuServerEngine.PRELOAD_VN_MIN;
        }
        if (n > VieneuServerEngine.PRELOAD_VN_MAX) {
            n = VieneuServerEngine.PRELOAD_VN_MAX;
        }
        if (running != null) {
            running.preloadVn = n;
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putInt("preload_vn", n).apply();
            if (running.vn != null) {
                running.vn.setPreloadCount(n);
                if (running.voice == VOICE_VIENEUPC && running.playing) {
                    int idx = SubtitleParser.findIndex(sharedSubs,
                            running.position + running.offset);
                    if (idx < 0) idx = 0;
                    running.vn.preloadFrom(idx);
                }
            }
        }
    }

    /** Doi API key server Vieneu (trong = khong dung key). */
    public static void setVieneuKey(String key) {
        String k = key == null ? "" : key.trim();
        if (running != null) {
            running.vieneuKey = k;
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putString("vieneu_key", k).apply();
            if (running.vn != null) running.vn.setApiKey(k);
        }
    }

    /** Ten voice Google offline dang chon (rong = mac dinh he thong). */
    public static String getGoogleVoiceName() {
        return running != null ? running.gvoiceName : "";
    }

    /** Doi voice Google offline: luu prefs + ap ngay (khoi dong lai van nho). */
    public static void setGoogleVoice(String name) {
        String n = name == null ? "" : name;
        // listGoogleVoices them hau to " (online)" de hien thi -> cat khi luu.
        if (n.endsWith(" (online)")) n = n.substring(0, n.length() - 9);
        if (running != null) {
            running.gvoiceName = n;
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putString("gvoice", n).apply();
            if (running.speaker != null) running.speaker.setTtsVoiceName(n);
        }
    }

    /** Package engine TTS dang dung (rong = mac dinh he thong). */
    public static String getTtsEngine() {
        return running != null ? running.ttsEngine : "";
    }

    /** Doi engine TTS (can khoi tao lai TTS, mat ~1-2 giay). */
    public static void setTtsEngine(String pkg) {
        String p = pkg == null ? "" : pkg;
        if (running != null) {
            if (p.equals(running.ttsEngine)) return;
            running.ttsEngine = p;
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putString("tts_engine", p).apply();
            running.createTts();
        }
    }

    /** Danh sach engine TTS co tren may (ten hien thi). */
    public static java.util.List<String> listTtsEngines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            if (running == null || running.tts == null) return out;
            java.util.List<android.speech.tts.TextToSpeech.EngineInfo> es =
                    running.tts.getEngines();
            if (es == null) return out;
            for (android.speech.tts.TextToSpeech.EngineInfo e : es) {
                if (e == null) continue;
                out.add(e.label + "\n" + e.name);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Danh sach ten voice tieng Viet co tren may (rong = khong co/khong doc duoc). */
    public static java.util.List<String> listGoogleVoices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            if (running == null || running.tts == null) return out;
            java.util.Set<android.speech.tts.Voice> vs =
                    running.tts.getVoices();
            if (vs == null) return out;
            for (android.speech.tts.Voice v : vs) {
                if (v == null || v.getLocale() == null) continue;
                if (!"vi".equals(v.getLocale().getLanguage())) continue;
                String label = v.getName();
                try {
                    if (v.isNetworkConnectionRequired()) label += " (online)";
                } catch (Exception ignored) {}
                out.add(label);
            }
            java.util.Collections.sort(out);
        } catch (Exception ignored) {}
        return out;
    }

    public static String voiceName() {
        if (getVoice() == VOICE_HOAIMY) return "HoaiMy (online)";
        if (getVoice() == VOICE_NAMMINH) return "NamMinh (online)";
        if (getVoice() == VOICE_VIENEUPC) {
            String n = running != null ? running.vieneuVoiceName : "";
            return "Vieneu:" + n + " (PC)";
        }
        return "Google (offline)";
    }

    /** So cau mp3 da tai xong de hien tien do preload. */
    public static String edgeProgress() {
        if (running == null) return "";
        EdgeVoiceEngine mp3 = running.mp3();
        if (mp3 == null) return "";
        return mp3.readyCount() + " cau";
    }

    /** Tong MB cache mp3 (Edge + Vieneu) de hien tren nut Xoa cache. */
    public static String cacheText() {
        if (running == null) return "Xoa cache (0MB)";
        long b = 0;
        if (running.edge != null) b += running.edge.cacheBytes();
        if (running.vn != null) b += running.vn.cacheBytes();
        long mb = b / (1024 * 1024);
        return "Xoa cache (" + mb + "MB)";
    }

    /** Xoa sach cache mp3 ca 2 engine. Tra ve so file da xoa. */
    public static int clearCache() {
        if (running == null) return 0;
        int n = 0;
        if (running.edge != null) n += running.edge.clearCache();
        if (running.vn != null) n += running.vn.clearCache();
        return n;
    }

    public static void setPosition(double pos) {
        if (running != null) {
            running.position = pos;
            // Seek tu Activity/WebView: chot moc lai + cat cau cu.
            if (running.playing) running.syncClock();
            if (running.speaker != null) running.speaker.resetIndex();
            if (running.edge != null) running.edge.stopPlayer();
            if (running.vn != null) running.vn.stopPlayer();
            running.driveEngines();
        }
    }

    public static double getPosition() {
        if (running == null) return 0;
        // Tra gio that (dung cho UI), khong phai bien position cu.
        return running.playing ? running.clockNow() : running.position;
    }

    /**
     * Che do DEM NGUOC theo Netflix (Netflix chi bao gio con lai).
     * User nhap tong thoi luong phim 1 lan -> app hien gio con lai
     * giong het Netflix de so truc tiep, khoi tu tru.
     */
    public static void setTotalLen(double v) {
        if (running != null) {
            running.totalLen = Math.max(0, v);
            running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putFloat("totalLen", (float) running.totalLen).apply();
            running.refreshFloatTime();
            running.updateNotif();
        }
    }

    public static double getTotalLen() {
        return running != null ? running.totalLen : 0;
    }

    /** Moc de so voi Netflix: totalLen neu da nhap, khong thi duration. */
    public static double syncBase() {
        if (running == null) return 0;
        return running.totalLen > 0 ? running.totalLen : running.duration;
    }

    /** So giay con lai (dem nguoc nhu Netflix). */
    public static double remainingNow() {
        if (running == null) return 0;
        double rem = syncBase() - getPosition();
        return rem < 0 ? 0 : rem;
    }

    /** "-mm:ss" de so truc tiep voi dong ho Netflix. */
    public static String remainingText() {
        if (running == null || syncBase() <= 0) return "--:--";
        return "-" + fmtTime(remainingNow());
    }

    /**
     * Nhap con so gio con lai DANG THAY tren Netflix -> nhay sub
     * toi dung cho do. Xong tinh chinh bang nut -1s/+1s.
     */
    public static void jumpToRemaining(double remSec) {
        if (running == null || sharedSubs.isEmpty()) return;
        double base = syncBase();
        if (base <= 0) return;
        double pos = base - Math.max(0, remSec);
        if (pos < 0) pos = 0;
        if (running.duration > 0 && pos > running.duration) {
            pos = running.duration;
        }
        setPosition(pos);
    }

    /** Xem het phim: xoa file sub da luu + ho so phim cho khoi day may. */
    private void cleanupFinishedFilm() {
        if (filmId.isEmpty() || duration <= 0) return;
        if (position < duration - 2) return;
        try {
            SharedPreferences sp =
                    getSharedPreferences("webtts", MODE_PRIVATE);
            String path = sp.getString("ffile_" + filmId, "");
            if (!path.isEmpty()) {
                try {
                    new java.io.File(path).delete();
                } catch (Exception ignored) {}
            }
            sp.edit().remove("fpos_" + filmId).remove("ftotal_" + filmId)
                    .remove("fname_" + filmId).remove("ffile_" + filmId)
                    .apply();
        } catch (Exception ignored) {}
    }

    /**
     * Dat phim dang doc (Activity goi ngay sau setSharedSubtitles).
     * id ben vung theo file/sub ("uri:<hash>" hoac "online:<hash>").
     */
    public static void setFilm(String id, String name) {
        if (running != null) {
            running.filmId = id == null ? "" : id;
            running.filmName = name == null ? "" : name;
        }
    }

    /** Luu vi tri + tong phim hien tai (goi khi pause/seek/thoat).
     *  Dung commit() dong bo de kill app ngay van khong mat. */
    public static void saveFilmNow() {
        TtsPlaybackService r = running;
        if (r == null || r.filmId.isEmpty()) return;
        try {
            double pos = r.playing ? r.clockNow() : r.position;
            r.getSharedPreferences("webtts", MODE_PRIVATE).edit()
                    .putFloat("fpos_" + r.filmId, (float) pos)
                    .putFloat("ftotal_" + r.filmId, (float) r.totalLen)
                    .putString("fname_" + r.filmId, r.filmName)
                    .commit();
        } catch (Exception ignored) {}
    }

    /** Vi tri da luu cua phim (-1 = chua co). */
    public static float savedPos(String id) {
        if (running == null || id == null || id.isEmpty()) return -1;
        try {
            return running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .getFloat("fpos_" + id, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Tong phim da luu (0 = chua co). */
    public static float savedTotal(String id) {
        if (running == null || id == null || id.isEmpty()) return 0;
        try {
            return running.getSharedPreferences("webtts", MODE_PRIVATE)
                    .getFloat("ftotal_" + id, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Hen gio tat doc (phut, 0 = tat). Pause thi dem tiep khi Play. */
    public static void setSleepMinutes(int m) {
        if (running != null) {
            if (m <= 0) {
                running.sleepAtElapsed = 0;
                running.sleepLeftMs = 0;
            } else if (running.playing) {
                running.sleepAtElapsed =
                        android.os.SystemClock.elapsedRealtime() + m * 60L * 1000L;
                running.sleepLeftMs = 0;
            } else {
                running.sleepLeftMs = m * 60L * 1000L;
                running.sleepAtElapsed = 0;
            }
            running.updateNotif();
        }
    }

    /** So giay con lai cua hen gio (0 = tat/het). */
    public static int getSleepLeftSec() {
        if (running == null) return 0;
        if (running.sleepAtElapsed > 0) {
            long left = (running.sleepAtElapsed
                    - android.os.SystemClock.elapsedRealtime()) / 1000;
            return left < 0 ? 0 : (int) left;
        }
        if (running.sleepLeftMs > 0) return (int) (running.sleepLeftMs / 1000);
        return 0;
    }

    /**
     * Nhay toi dau cau phu de truoc/sau (dir = -1/+1).
     * Nhan lui khi dang doc doan sau cua 1 cau (>2s): ve dau cau do
     * (giong nut lui cua trinh phat nhac). Ngoai vung cue: nhay toi
     * cue ke tiep (dir>0) hoac cue truoc do (dir<0).
     */
    public static void jumpCue(int dir) {
        if (running == null || sharedSubs.isEmpty()) return;
        TtsPlaybackService r = running;
        double now = r.playing ? r.clockNow() : r.position;
        int idx = SubtitleParser.findIndex(sharedSubs, now);
        int target;
        if (idx < 0) {
            target = -1;
            if (dir > 0) {
                for (int i = 0; i < sharedSubs.size(); i++) {
                    if (sharedSubs.get(i).start > now) {
                        target = i;
                        break;
                    }
                }
                if (target < 0) target = sharedSubs.size() - 1;
            } else {
                for (int i = sharedSubs.size() - 1; i >= 0; i--) {
                    if (sharedSubs.get(i).end <= now) {
                        target = i;
                        break;
                    }
                }
                if (target < 0) target = 0;
            }
        } else if (dir < 0 && now - sharedSubs.get(idx).start > 2.0) {
            target = idx;
        } else {
            target = idx + dir;
            if (target < 0) target = 0;
            if (target >= sharedSubs.size()) target = sharedSubs.size() - 1;
        }
        r.position = sharedSubs.get(target).start;
        if (r.position < 0) r.position = 0;
        // Doi ca chu hien thi (khong thi timer 500ms ghi de ve cau cu).
        r.lastCueText = sharedSubs.get(target).text;
        if (r.floatCue != null) {
            r.handler.post(() -> {
                try {
                    if (r.floatCue != null) r.floatCue.setText(r.lastCueText);
                } catch (Exception ignored) {}
            });
        }
        if (r.playing) r.syncClock();
        if (r.speaker != null) r.speaker.resetIndex();
        if (r.edge != null) r.edge.stopPlayer();
        if (r.vn != null) r.vn.stopPlayer();
        // Pause: chi doi moc gio + chu, khong phat tieng.
        if (r.playing) r.driveEngines();
        r.refreshFloatTime();
        r.updateNotif();
    }

    // ----- nut noi -----
    public static boolean canDrawOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    private void showFloat() {
        if (floatShown) return;
        if (!canDrawOverlay(this)) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            buildFloatViews();
            int type;
            if (Build.VERSION.SDK_INT >= 26) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_PHONE;
            }
            floatParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            floatParams.gravity = Gravity.TOP | Gravity.END;
            floatParams.x = 16;
            floatParams.y = 200;
            wm.addView(floatRoot, floatParams);
            floatShown = true;
        } catch (Exception ignored) {}
    }

    private void hideFloat() {
        if (!floatShown) return;
        try {
            if (wm != null && floatRoot != null) wm.removeView(floatRoot);
        } catch (Exception ignored) {}
        floatShown = false;
        floatRoot = null;
        floatSeek = null;
        floatMore = null;
    }

    private void buildFloatViews() {
        Context ctx = this;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        floatDot = new Button(ctx);
        floatDot.setText("TTS");
        floatDot.setTextSize(13);
        floatDot.setAlpha(FLOAT_ALPHA);
        // Ban Netflix: nen den + bo tron (thay mau xanh cu).
        try {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setCornerRadius(200f);
            floatDot.setBackground(bg);
        } catch (Exception e) {
            floatDot.setBackgroundColor(0xFF000000);
        }
        floatDot.setTextColor(0xFFFFFFFF);
        floatDot.setPadding(24, 24, 24, 24);
        floatDot.setOnClickListener(v -> togglePanel());
        floatDot.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, dx, dy;
            boolean dragged;
            final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (floatParams == null || wm == null) return false;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        dx = floatParams.x - e.getRawX();
                        dy = floatParams.y - e.getRawY();
                        dragged = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        // Chi tinh la keo khi di qua nguong cua may.
                        // Cham nhe (trong nguong) thi de yen cho Button
                        // xu ly nhu tap binh thuong.
                        if (!dragged
                                && (Math.abs(e.getRawX() - downRawX) > slop
                                || Math.abs(e.getRawY() - downRawY) > slop)) {
                            dragged = true;
                        }
                        if (!dragged) return false;
                        floatParams.x = (int) (e.getRawX() + dx);
                        floatParams.y = (int) (e.getRawY() + dy);
                        try {
                            wm.updateViewLayout(floatRoot, floatParams);
                        } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Vua keo xong: nuot UP de click khong kich hoat
                        // (khong tu bung panel). Tap nhe: tha cho click.
                        if (dragged) {
                            dragged = false;
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });

        floatPanel = new LinearLayout(ctx);
        floatPanel.setOrientation(LinearLayout.VERTICAL);
        floatPanel.setBackgroundColor(0xEE222222);
        floatPanel.setPadding(12, 12, 12, 12);
        floatPanel.setVisibility(View.GONE);

        floatCue = new TextView(ctx);
        floatCue.setText(lastCueText);
        floatCue.setTextColor(0xFFFFFFFF);
        floatCue.setTextSize(13);
        floatCue.setMaxLines(2);
        floatPanel.addView(floatCue);

        // Dong ho chay de can dong bo voi Netflix: "mm:ss / mm:ss".
        floatTime = new TextView(ctx);
        floatTime.setText(timeText());
        floatTime.setTextColor(0xFF90CAF9);
        floatTime.setTextSize(13);
        floatPanel.addView(floatTime);

        // Gio con lai dem nguoc y het Netflix de so truc tiep.
        floatRemain = new TextView(ctx);
        floatRemain.setText(remainingText());
        floatRemain.setTextColor(0xFFFFB74D);
        floatRemain.setTextSize(12);
        floatPanel.addView(floatRemain);

        // Mac dinh gon: cau + gio + Play/⏮/⏭ + nut ⋯.
        // Bam ⋯ thi hien them: timeline, +-1s, volume, giong, An/Tach/Thoat/Cai dat.
        floatMore = new LinearLayout(ctx);
        floatMore.setOrientation(LinearLayout.VERTICAL);
        floatMore.setVisibility(View.GONE);

        // Thanh timeline tren nut noi: keo de nhay gio ngay ca khi
        // dang xem Netflix, khong can mo lai app.
        floatSeek = new SeekBar(ctx);
        floatSeek.setMax(1000);
        floatSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {}
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                double base = syncBase();
                if (base <= 0) base = duration;
                if (base > 0) {
                    double pos = base * s.getProgress() / 1000.0;
                    if (pos < 0) pos = 0;
                    if (duration > 0 && pos > duration) pos = duration;
                    setPosition(pos);
                }
                refreshFloatTime();
            }
        });
        floatMore.addView(floatSeek);

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        floatPlayPause = new Button(ctx);
        floatPlayPause.setText(playing ? "Pause" : "Play");
        floatPlayPause.setTextSize(12);
        floatPlayPause.setOnClickListener(v -> setPlaying(!playing));
        Button bPrevCue = new Button(ctx);
        bPrevCue.setText("⏮");
        bPrevCue.setTextSize(12);
        bPrevCue.setOnClickListener(v -> jumpCue(-1));
        Button bNextCue = new Button(ctx);
        bNextCue.setText("⏭");
        bNextCue.setTextSize(12);
        bNextCue.setOnClickListener(v -> jumpCue(1));
        Button bMore = new Button(ctx);
        bMore.setText("⋯");
        bMore.setTextSize(12);
        bMore.setOnClickListener(v -> toggleMore());
        row1.addView(floatPlayPause);
        row1.addView(bPrevCue);
        row1.addView(bNextCue);
        row1.addView(bMore);
        floatPanel.addView(row1);

        LinearLayout rowNudge = new LinearLayout(ctx);
        rowNudge.setOrientation(LinearLayout.HORIZONTAL);
        Button bBack = new Button(ctx);
        bBack.setText("-10s");
        bBack.setTextSize(12);
        bBack.setOnClickListener(v -> nudge(-10.0));
        Button bFwd = new Button(ctx);
        bFwd.setText("+10s");
        bFwd.setTextSize(12);
        bFwd.setOnClickListener(v -> nudge(10.0));
        rowNudge.addView(bBack);
        rowNudge.addView(bFwd);
        floatMore.addView(rowNudge);

        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = new TextView(ctx);
        lb.setText("Sub:");
        lb.setTextColor(0xFFCCCCCC);
        lb.setTextSize(12);
        SeekBar vol = new SeekBar(ctx);
        vol.setMax(100);
        vol.setProgress(ttsVol);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vol.setLayoutParams(lp);
        final TextView vv = new TextView(ctx);
        vv.setText(ttsVol + "%");
        vv.setTextColor(0xFFFFFFFF);
        vv.setTextSize(12);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                ttsVol = p;
                vv.setText(p + "%");
                getSharedPreferences("webtts", MODE_PRIVATE)
                        .edit().putInt("ttsVol", p).apply();
                if (speaker != null) speaker.setTtsVolume(p);
                if (edge != null) edge.setTtsVolume(p);
                if (vn != null) vn.setTtsVolume(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row2.addView(lb);
        row2.addView(vol);
        row2.addView(vv);
        floatMore.addView(row2);

        // Hang chon giong: Google offline / HoaiMy online.
        LinearLayout rowVoice = new LinearLayout(ctx);
        rowVoice.setOrientation(LinearLayout.HORIZONTAL);
        floatVoiceBtn = new Button(ctx);
        floatVoiceBtn.setTextSize(12);
        refreshFloatVoiceBtn();
        floatVoiceBtn.setOnClickListener(v -> {
            int nv = (voice == VOICE_GOOGLE) ? VOICE_HOAIMY
                    : (voice == VOICE_HOAIMY ? VOICE_NAMMINH
                    : (voice == VOICE_NAMMINH ? VOICE_VIENEUPC : VOICE_GOOGLE));
            setVoice(nv);
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putInt("voice", nv).apply();
        });
        rowVoice.addView(floatVoiceBtn);
        floatMore.addView(rowVoice);

        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button bHide = new Button(ctx);
        bHide.setText("An nut");
        bHide.setTextSize(12);
        bHide.setOnClickListener(v -> hideFloat());
        Button bSplit = new Button(ctx);
        bSplit.setText(splitAudio ? "Gop loa" : "Tach loa");
        bSplit.setTextSize(12);
        bSplit.setOnClickListener(v -> {
            splitAudio = !splitAudio;
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putBoolean("splitAudio", splitAudio).apply();
            applySplitAudio();
            bSplit.setText(splitAudio ? "Gop loa" : "Tach loa");
            android.widget.Toast.makeText(ctx, splitAudio
                    ? "Sub ra loa bao thuc — phim cung media khong anh huong."
                    : "Sub chung loa media — phim cung to/nho theo.",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        Button bExit = new Button(ctx);
        bExit.setText("Thoat");
        bExit.setTextSize(12);
        bExit.setOnClickListener(v -> exitApp());
        Button bOpen = new Button(ctx);
        bOpen.setText("Cai dat");
        bOpen.setTextSize(12);
        bOpen.setOnClickListener(v -> {
            // Ban Netflix: chi 1 man Cai dat.
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        row3.addView(bHide);
        row3.addView(bSplit);
        row3.addView(bExit);
        row3.addView(bOpen);
        floatMore.addView(row3);
        floatPanel.addView(floatMore);

        root.addView(floatDot);
        root.addView(floatPanel);
        floatRoot = root;
    }

    private void togglePanel() {
        if (floatPanel == null) return;
        boolean show = floatPanel.getVisibility() != View.VISIBLE;
        floatPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (floatCue != null) floatCue.setText(lastCueText);
            refreshFloatTime();
        }
    }

    /** An/hien cum mo rong (timeline, volume, giong, ...). */
    private void toggleMore() {
        if (floatMore == null) return;
        floatMore.setVisibility(floatMore.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
    }

    /** Cap nhat "mm:ss / mm:ss" tren nut noi de can voi Netflix. */
    private void refreshFloatTime() {
        if (!floatShown || floatTime == null) return;
        try {
            floatTime.setText(timeText());
            if (floatRemain != null) floatRemain.setText(remainingText());
            // Khong danh thanh keo khi user dang keo (isPressed).
            // Thang theo moc dem nguoc (tong phim neu da nhap).
            if (floatSeek != null && !floatSeek.isPressed()) {
                double base = syncBase();
                if (base > 0) {
                    floatSeek.setProgress(
                            (int) (getPosition() / base * 1000));
                }
            }
        } catch (Exception ignored) {}
    }

    private void refreshFloatPlayBtn() {
        if (floatPlayPause != null) {
            floatPlayPause.setText(playing ? "Pause" : "Play");
        }
    }

    private void refreshFloatVoiceBtn() {
        if (floatVoiceBtn != null) {
            String t;
            if (voice == VOICE_HOAIMY) t = "Giong: HoaiMy (online)";
            else if (voice == VOICE_NAMMINH) t = "Giong: NamMinh (online)";
            else if (voice == VOICE_VIENEUPC) t = "Giong: " + vieneuVoiceName + " (PC)";
            else t = "Giong: Google (offline)";
            floatVoiceBtn.setText(t);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        hideFloat();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        try {
            if (edge != null) edge.shutdown();
        } catch (Exception ignored) {}
        try {
            if (vn != null) {
                vn.shutdownVn();
                vn.shutdown();
            }
        } catch (Exception ignored) {}
        running = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
