package com.example.webtts;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sub TTS — ban rieng chay song song app Netflix goc.
 * Bo WebView + offset (khong can: xem app goc, can bang nut noi +-1s).
 * Giu nguyen: sub thu cong, 2 giong Google/HoaiMy, nut noi 30%,
 * xoa cache, toc do, volume sub.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SRT = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_OVERLAY = 1003;
    private static final int REQ_BACKUP = 1004;
    private static final int REQ_RESTORE = 1005;

    private List<SubtitleCue> subs = new ArrayList<>();

    private TextView tvStatus;
    private TextView tvCue;
    private TextView tvTime;
    /** Gio con lai dem nguoc nhu Netflix. */
    private TextView tvRemain;
    private android.widget.EditText etTotalLen;
    private TextView tvRate;
    private TextView tvTtsVol;
    private TextView tvVoiceBadge;
    private Button btnPlayPause;
    private Button btnFloat;
    private Button btnPrevCue;
    private Button btnNextCue;
    private Button btnSetTotal;
    private Button btnJumpRemain;
    private Button btnSubSearch;
    private Button btnSubSettings;
    private Button btnBackup;
    private Button btnRestore;
    private Button btnSleep;
    private Button btnExit;
    private Button btnTabWatch;
    private Button btnTabSearch;
    private Button btnTabSaved;
    private LinearLayout paneWatch;
    private LinearLayout paneSearch;
    private LinearLayout paneSetup;
    private LinearLayout paneSaved;
    private Button btnClearSavedSubs;
    private LinearLayout llSavedList;
    private Button btnVoiceGoogle;
    private Button btnGoogleVoice;
    private Button btnVoiceHoaiMy;
    private Button btnVoiceNamMinh;
    private Button btnVoiceVieneu;
    private Button btnVieneuServer;
    private Button btnVieneuPreload;
    private Button btnClearCache;
    private int voice = TtsPlaybackService.VOICE_GOOGLE;
    private SeekBar seekPos;
    private SeekBar seekRate;
    private SeekBar seekTtsVol;
    private SeekBar seekCueSize;
    private TextView tvCueSize;
    /** Co chu cau dang doc (sp), mac dinh 17. */
    private float cueSize = 17;
    /** true = user dang keo thanh timeline, tam dung tu dong cap nhat. */
    private boolean seeking = false;

    private boolean playing = false;
    private double position = 0;
    private double duration = 0;
    /** Ten hien thi + id + uri phim dang doc (de luu/tiep tuc). */
    private String filmId = "";
    private String filmName = "phim";
    private String filmUri = "";
    /** Tong thoi luong phim (giay) do user nhap theo Netflix. */
    private double totalLen = 0;
    private float rate = 1.0f;
    private int ttsVol = 100;

    private Timer uiTimer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvCue = findViewById(R.id.tvCue);
        tvTime = findViewById(R.id.tvTime);
        tvRemain = findViewById(R.id.tvRemain);
        etTotalLen = findViewById(R.id.etTotalLen);
        tvRate = findViewById(R.id.tvRate);
        tvTtsVol = findViewById(R.id.tvTtsVol);
        tvVoiceBadge = findViewById(R.id.tvVoiceBadge);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnClearSavedSubs = findViewById(R.id.btnClearSavedSubs);
        llSavedList = findViewById(R.id.llSavedList);
        btnFloat = findViewById(R.id.btnFloat);
        btnPrevCue = findViewById(R.id.btnPrevCue);
        btnNextCue = findViewById(R.id.btnNextCue);
        btnSetTotal = findViewById(R.id.btnSetTotal);
        btnJumpRemain = findViewById(R.id.btnJumpRemain);
        btnSubSearch = findViewById(R.id.btnSubSearch);
        btnSubSettings = findViewById(R.id.btnSubSettings);
        btnBackup = findViewById(R.id.btnBackup);
        btnRestore = findViewById(R.id.btnRestore);
        btnSleep = findViewById(R.id.btnSleep);
        btnExit = findViewById(R.id.btnExit);
        btnVoiceGoogle = findViewById(R.id.btnVoiceGoogle);
        btnGoogleVoice = findViewById(R.id.btnGoogleVoice);
        btnVoiceHoaiMy = findViewById(R.id.btnVoiceHoaiMy);
        btnVoiceNamMinh = findViewById(R.id.btnVoiceNamMinh);
        btnVoiceVieneu = findViewById(R.id.btnVoiceVieneu);
        btnVieneuServer = findViewById(R.id.btnVieneuServer);
        btnVieneuPreload = findViewById(R.id.btnVieneuPreload);
        btnClearCache = findViewById(R.id.btnClearCache);
        seekPos = findViewById(R.id.seekPos);
        seekRate = findViewById(R.id.seekRate);
        seekTtsVol = findViewById(R.id.seekTtsVol);
        seekCueSize = findViewById(R.id.seekCueSize);
        tvCueSize = findViewById(R.id.tvCueSize);
        btnTabWatch = findViewById(R.id.btnTabWatch);
        btnTabSearch = findViewById(R.id.btnTabSearch);
        btnTabSaved = findViewById(R.id.btnTabSaved);
        paneWatch = findViewById(R.id.paneWatch);
        paneSearch = findViewById(R.id.paneSearch);
        paneSaved = findViewById(R.id.paneSaved);
        paneSetup = findViewById(R.id.paneSetup);

        SharedPreferences sp =
                getSharedPreferences("webtts", MODE_PRIVATE);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        voice = sp.getInt("voice", TtsPlaybackService.VOICE_GOOGLE);
        totalLen = sp.getFloat("totalLen", 0);
        etTotalLen.setText(totalLen > 0 ? fmtHMS(totalLen) : "");

        refreshVoiceUi();
        refreshCacheBtn();
        seekRate.setProgress(Math.round((rate - 0.5f) / 2.0f * 200));
        tvRate.setText(String.format(Locale.US, "%.1fx", rate));
        seekTtsVol.setProgress(ttsVol);
        tvTtsVol.setText(ttsVol + "%");
        cueSize = sp.getFloat("cueSize", 17);
        if (cueSize < 12) cueSize = 12;
        if (cueSize > 30) cueSize = 30;
        tvCue.setTextSize(cueSize);
        seekCueSize.setProgress(Math.round(cueSize - 12));
        tvCueSize.setText(String.valueOf(Math.round(cueSize)));
        refreshPlayBtn();
        showPane(sp.getInt("tab", 0));

        // Tu nap sub da luu cua phim gan nhat (neu co) + hoi xem tiep.
        // Chi nap khi chua co sub (khong de len sub user vua chon).
        try {
            String lastId = sp.getString("curfilm_id", "");
            if (subs.isEmpty() && !lastId.isEmpty()) {
                String savedPath = sp.getString("ffile_" + lastId, "");
                if (!savedPath.isEmpty()) {
                    File sf = new File(savedPath);
                    if (sf.isFile()) {
                        String body = readAllText(sf);
                        if (!body.isEmpty() && body.charAt(0) == 0xFEFF) {
                            body = body.substring(1);
                        }
                        List<SubtitleCue> auto =
                                SubtitleParser.parseSrt(body);
                        if (!auto.isEmpty()) {
                            String lname = sp.getString(
                                    "curfilm_name", "phim");
                            useLoadedCues(auto,
                                    "Da nap " + lname + " (da luu).",
                                    lastId, lname, "");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        findViewById(R.id.btnPickSrt).setOnClickListener(v -> pickSrt());
        btnPlayPause.setOnClickListener(v -> setPlaying(!playing));
        btnFloat.setOnClickListener(v -> ensureFloat());
        btnPrevCue.setOnClickListener(v -> jumpCueUi(-1));
        btnNextCue.setOnClickListener(v -> jumpCueUi(1));
        btnSetTotal.setOnClickListener(v -> saveTotalUi());
        btnJumpRemain.setOnClickListener(v -> jumpRemainDialog());
        btnSubSearch.setOnClickListener(v -> openSubSearch());
        btnSubSettings.setOnClickListener(v -> openSubSettings());
        btnBackup.setOnClickListener(v -> startBackup());
        btnRestore.setOnClickListener(v -> startRestore());
        btnSleep.setOnClickListener(v -> sleepDialog());
        btnTabWatch.setOnClickListener(v -> showPane(0));
        btnTabSearch.setOnClickListener(v -> showPane(1));
        btnTabSaved.setOnClickListener(v -> showPane(2));
        btnClearSavedSubs.setOnClickListener(v -> clearAllSaved());
        btnExit.setOnClickListener(v -> exitAppUi());
        btnVoiceGoogle.setOnClickListener(v -> setVoice(
                TtsPlaybackService.VOICE_GOOGLE));
        btnVoiceHoaiMy.setOnClickListener(v -> setVoice(
                TtsPlaybackService.VOICE_HOAIMY));
        btnVoiceNamMinh.setOnClickListener(v -> setVoice(
                TtsPlaybackService.VOICE_NAMMINH));
        btnVoiceVieneu.setOnClickListener(v -> pickVieneuVoice());
        btnVieneuServer.setOnClickListener(v -> showVieneuServerDialog(false));
        btnVieneuPreload.setOnClickListener(v -> showVieneuPreloadDialog());
        refreshPreloadBtn();
        btnGoogleVoice.setOnClickListener(v -> showGoogleVoiceDialog());
        refreshGoogleBtn();
        btnClearCache.setOnClickListener(v -> clearCacheUi());

        seekPos.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                double base = TtsPlaybackService.syncBase();
                if (base <= 0) base = duration;
                if (fromUser && base > 0) {
                    // Keo: chi xem truoc gio dich, tha tay moi nhay that.
                    // Thang theo moc dem nguoc (tong phim neu da nhap).
                    seeking = true;
                    position = base * p / 1000.0;
                    if (position < 0) position = 0;
                    if (duration > 0 && position > duration) {
                        position = duration;
                    }
                    tvTime.setText(fmtMmSs(position) + " / " + fmtMmSs(duration));
                    int idx = SubtitleParser.findIndex(subs, position);
                    if (idx >= 0 && idx < subs.size()) {
                        tvCue.setText(subs.get(idx).text);
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (duration > 0) {
                    TtsPlaybackService.setPosition(position);
                }
                syncUi();
            }
        });
        seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                rate = 0.5f + p / 200.0f * 2.0f;
                tvRate.setText(String.format(Locale.US, "%.1fx", rate));
                TtsPlaybackService.setRate(rate);
                sp.edit().putFloat("rate", rate).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekTtsVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                ttsVol = p;
                tvTtsVol.setText(p + "%");
                TtsPlaybackService.setTtsVolume(p);
                sp.edit().putInt("ttsVol", p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        // Co chu cau dang doc: 12sp - 30sp.
        seekCueSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                cueSize = 12 + p;
                tvCue.setTextSize(cueSize);
                tvCueSize.setText(String.valueOf(12 + p));
                sp.edit().putFloat("cueSize", cueSize).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        uiTimer = new Timer();
        uiTimer.schedule(new TimerTask() {
            @Override public void run() {
                runOnUiThread(() -> {
                    boolean p = TtsPlaybackService.isPlayingNow();
                    if (p != playing) {
                        playing = p;
                        refreshPlayBtn();
                    }
                    String cue = TtsPlaybackService.currentCueText();
                    if (cue != null && !cue.equals(tvCue.getText().toString())) {
                        tvCue.setText(cue);
                    }
                    tvTime.setText(TtsPlaybackService.timeText());
                    tvRemain.setText(TtsPlaybackService.remainingText());
                    refreshSleepBtn();
                    double sp2 = TtsPlaybackService.getPosition();
                    if (duration > 0 && Math.abs(sp2 - position) > 0.6) {
                        position = sp2;
                        syncUi();
                    }
                    // Hien loi Edge (neu co) de user biet tai sao im lang.
                    int gv = TtsPlaybackService.getVoice();
                    if (!subs.isEmpty()
                            && (gv == TtsPlaybackService.VOICE_HOAIMY
                            || gv == TtsPlaybackService.VOICE_NAMMINH
                            || gv == TtsPlaybackService.VOICE_VIENEUPC)) {
                        String err = TtsPlaybackService.edgeError();
                        String prog = TtsPlaybackService.edgeProgress();
                        if (err != null && "0 cau".equals(prog)) {
                            String s = "Loi (" + TtsPlaybackService.voiceName()
                                    + "): " + err;
                            if (s.length() > 120) s = s.substring(0, 120);
                            if (!s.equals(tvStatus.getText().toString())) {
                                tvStatus.setText(s);
                            }
                        }
                    }
                });
            }
        }, 500, 500);

        askNotif();
    }

    private void pickSrt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/x-subrip", "text/plain"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_SRT);
        } catch (Exception e) {
            toast("Khong mo duoc chon file: " + e.getMessage());
        }
    }

    /** Ten file hien thi cua Uri (de nhan dien phim). */
    private static String displayName(android.content.Context ctx, Uri uri) {
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{
                            android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        String p = uri.getPath();
        if (p == null || p.isEmpty()) return "phim";
        int k = p.lastIndexOf('/');
        return k >= 0 ? p.substring(k + 1) : p;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SRT && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            loadSrt(data.getData());
        } else if (requestCode == REQ_OVERLAY) {
            if (TtsPlaybackService.canDrawOverlay(this)) {
                TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_SHOW_FLOAT);
                toast("Da bat nut noi — sang Netflix van dieu khien duoc.");
            }
        } else if (requestCode == REQ_BACKUP && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            writeBackup(data.getData());
        } else if (requestCode == REQ_RESTORE && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            readRestore(data.getData());
        }
    }

    private void loadSrt(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] buf = new byte[256 * 1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            String content = sb.toString();
            if (!content.isEmpty() && content.charAt(0) == 0xFEFF) {
                content = content.substring(1);
            }
            List<SubtitleCue> parsed = SubtitleParser.parseSrt(content);
            if (parsed.isEmpty()) {
                toast("SRT khong co phu de hop le.");
                return;
            }
            String fname = displayName(this, uri);
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            String fid = "uri:"
                    + Integer.toHexString(uri.toString().hashCode());
            useLoadedCues(parsed, "Da nap " + parsed.size() + " cau phu de.",
                    fid, fname, uri.toString());
        } catch (Exception e) {
            toast("Khong doc duoc SRT: " + e.getMessage());
        }
    }

    /** Dung chung cho nap .srt tay va sub tai tu online. */
    private void useLoadedCues(List<SubtitleCue> parsed, String label,
                               String fid, String fname, String furi) {
        subs = parsed;
        duration = 0;
        for (SubtitleCue c : subs) duration = Math.max(duration, c.end);
        position = 0;
        filmId = fid == null ? "" : fid;
        filmName = (fname == null || fname.isEmpty()) ? "phim" : fname;
        filmUri = furi == null ? "" : furi;
        // Ghi ca vao prefs phong khi service chua chay (setFilm se mat).
        getSharedPreferences("webtts", MODE_PRIVATE).edit()
                .putString("curfilm_id", filmId)
                .putString("curfilm_name", filmName)
                .apply();
        ensureService();
        TtsPlaybackService.setSharedSubtitles(subs);
        TtsPlaybackService.setFilm(filmId, filmName);
        tvStatus.setText(label);
        toast(label + ". Bam Play roi sang Netflix.");
        refreshCacheBtn();
        // Ho so phim: khoi phuc tong gio da luu (tranh lan phim khac).
        // Doc thang tu prefs (khong qua service) de chay ca khi
        // service chua kip khoi dong (mo app sau khi kill).
        SharedPreferences rspf =
                getSharedPreferences("webtts", MODE_PRIVATE);
        float savedT = rspf.getFloat("ftotal_" + filmId, 0);
        if (savedT > 0) {
            totalLen = savedT;
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putFloat("totalLen", savedT).apply();
            TtsPlaybackService.setTotalLen(savedT);
            if (etTotalLen != null) etTotalLen.setText(fmtHMS(savedT));
        } else {
            totalLen = 0;
            getSharedPreferences("webtts", MODE_PRIVATE)
                    .edit().putFloat("totalLen", 0).apply();
            TtsPlaybackService.setTotalLen(0);
            if (etTotalLen != null) etTotalLen.setText("");
        }
        // Tu luu file sub de lan sau tu nap (khong can bam nut).
        autoSaveSub();
        syncUi();
        // Hoi xem tiep neu lan truoc dung giua chung.
        float savedP = rspf.getFloat("fpos_" + filmId, -1);
        if (savedP > 10 && duration > 0 && savedP < duration - 5) {
            final double resumeAt = savedP;
            new AlertDialog.Builder(this)
                    .setTitle("Xem tiep " + filmName + "?")
                    .setMessage("Lan truoc dung o " + fmtMmSs(resumeAt) + ".")
                    .setPositiveButton("Tiep tuc", (d, w) -> {
                        TtsPlaybackService.setPosition(resumeAt);
                        position = TtsPlaybackService.getPosition();
                        syncUi();
                    })
                    .setNegativeButton("Tu dau", null)
                    .show();
        }
    }

    // ---------- tim + tai sub online (SubDL / OpenSubtitles / SubSource) ----------

    private LinearLayout dialogRow(String labelText, android.view.View v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 6, 0, 6);
        TextView lb = new TextView(this);
        lb.setText(labelText);
        lb.setTextColor(0xFF8B949E);
        lb.setTextSize(12);
        row.addView(lb);
        row.addView(v);
        return row;
    }

    private android.widget.EditText dialogEdit(String hint, String pre) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(hint);
        et.setText(pre == null ? "" : pre);
        et.setSingleLine(true);
        return et;
    }

    private void openSubSearch() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8, 0, 8, 0);
        final android.widget.EditText etQ = dialogEdit("Ten phim (vd: Inside Out 2)", "");
        etQ.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final CheckBox cbTv = new CheckBox(this);
        cbTv.setText("Phim bo (can nhap tap)");
        final Button btnViEn = new Button(this);
        btnViEn.setText("VI→EN");
        btnViEn.setTextSize(12);
        btnViEn.setOnClickListener(v -> openTmdbResolve(etQ, cbTv.isChecked()));
        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        qRow.addView(etQ);
        qRow.addView(btnViEn);
        root.addView(dialogRow("Ten phim (tieng Anh tim sub chuan nhat)", qRow));
        root.addView(cbTv);
        LinearLayout seRow = new LinearLayout(this);
        seRow.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.EditText etS = dialogEdit("Season", "1");
        final android.widget.EditText etE = dialogEdit("Tap", "1");
        etS.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        etE.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        seRow.addView(etS);
        seRow.addView(etE);
        root.addView(dialogRow("Season / Tap (chi dung cho phim bo)", seRow));
        final CheckBox cbDl = new CheckBox(this);
        cbDl.setText("SubDL");
        cbDl.setChecked(true);
        final CheckBox cbOs = new CheckBox(this);
        cbOs.setText("OpenSubtitles");
        cbOs.setChecked(true);
        final CheckBox cbSs = new CheckBox(this);
        cbSs.setText("SubSource");
        cbSs.setChecked(true);
        root.addView(cbDl);
        root.addView(cbOs);
        root.addView(cbSs);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        new AlertDialog.Builder(this)
                .setTitle("Tim sub online (uu tien sub Viet)")
                .setView(sv)
                .setPositiveButton("Tim", (d, w) -> {
                    String q = etQ.getText().toString().trim();
                    if (q.isEmpty()) {
                        toast("Go ten phim truoc.");
                        return;
                    }
                    int s = 1, e = 1;
                    try {
                        s = Math.max(1, Integer.parseInt(
                                etS.getText().toString().trim()));
                        e = Math.max(1, Integer.parseInt(
                                etE.getText().toString().trim()));
                    } catch (Exception ignored) {}
                    runSubSearch(q, cbTv.isChecked(), s, e,
                            cbDl.isChecked(), cbOs.isChecked(), cbSs.isChecked());
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    /**
     * Doi ten phim tieng Viet sang ten tieng Anh (TMDB) roi dien vao o tim sub.
     * target: o nhap ten phim cua dialog tim sub. isTv: trang thai tick Phim bo.
     */
    private void openTmdbResolve(final android.widget.EditText target,
                                 final boolean isTv) {
        String start = target.getText().toString().trim();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8, 0, 8, 0);
        final android.widget.EditText etVi = dialogEdit(
                "Ten tieng Viet tren Netflix", start);
        root.addView(dialogRow("Ten tieng Viet", etVi));
        final ListView lv = new ListView(this);
        final TmdbMovieAdapter ad = new TmdbMovieAdapter(this,
                new ArrayList<TmdbResolver.Movie>());
        lv.setAdapter(ad);
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 700);
        lv.setLayoutParams(lpp);
        root.addView(dialogRow("Cham 1 phim de dien ten Anh", lv));
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Doi ten Viet → Anh (TMDB)")
                .setView(sv)
                .setPositiveButton("Tim", null)
                .setNegativeButton("Huy", null)
                .create();
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            final TmdbResolver.Movie m = ad.getItem(pos);
            if (m == null) return;
            toast("Dang lay ten tieng Anh...");
            TmdbResolver.englishTitle(this, m,
                    new TmdbResolver.TitleCb() {
                        @Override public void onOk(String en) {
                            target.setText(en);
                            toast("Da dien ten Anh: " + en + " — bam Tim sub.");
                            if (dlg.isShowing()) dlg.dismiss();
                        }
                        @Override public void onError(String msg) {
                            toast(msg);
                        }
                    });
        });
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String q = etVi.getText().toString().trim();
            if (q.isEmpty()) {
                toast("Go ten tieng Viet truoc.");
                return;
            }
            toast("Dang hoi TMDB...");
            TmdbResolver.search(this, q, isTv ? "tv" : "movie",
                    new TmdbResolver.Cb() {
                        @Override public void onOk(List<TmdbResolver.Movie> ms) {
                            ad.clear();
                            ad.addAll(ms);
                            ad.notifyDataSetChanged();
                            toast("Cham 1 phim de dien ten Anh.");
                        }
                        @Override public void onError(String msg) {
                            toast(msg);
                        }
                    });
        });
        // Tu tim ngay voi ten dang co san (neu co).
        if (!start.isEmpty()) {
            TmdbResolver.search(this, start, isTv ? "tv" : "movie",
                    new TmdbResolver.Cb() {
                        @Override public void onOk(List<TmdbResolver.Movie> ms) {
                            if (!dlg.isShowing()) return;
                            ad.clear();
                            ad.addAll(ms);
                            ad.notifyDataSetChanged();
                        }
                        @Override public void onError(String msg) {
                            if (dlg.isShowing()) toast(msg);
                        }
                    });
        }
    }

    private void runSubSearch(final String q, final boolean isTv,
                              final int season, final int episode,
                              boolean useDl, boolean useOs, boolean useSs) {
        tvStatus.setText("Dang tim sub online...");
        SubSearchManager.searchAll(this, q, isTv ? "tv" : "movie",
                season, episode, useDl, useOs, useSs,
                new SubSearchManager.SearchCb() {
                    @Override public void onOk(List<SubResult> rs, String warn) {
                        if (!warn.isEmpty()) tvStatus.setText(shortMsg(warn));
                        showSubResults(rs);
                    }
                    @Override public void onError(String msg) {
                        tvStatus.setText(shortMsg(msg));
                        toast(shortMsg(msg));
                    }
                });
    }

    private void showSubResults(final List<SubResult> rs) {
        if (rs.isEmpty()) {
            toast("Khong tim thay sub.");
            return;
        }
        SubResultAdapter ad = new SubResultAdapter(this, rs);
        new AlertDialog.Builder(this)
                .setTitle("Chon sub (" + rs.size() + ") — chon dung ban phim")
                .setAdapter(ad, (d, which) -> downloadSub(rs.get(which)))
                .setNegativeButton("Huy", null)
                .show();
    }

    private void downloadSub(final SubResult r) {
        tvStatus.setText("Dang tai sub tu " + r.provider + "...");
        SubSearchManager.downloadAndParse(this, r,
                new SubSearchManager.LoadCb() {
                    @Override public void onOk(List<SubtitleCue> cues, String label) {
                        String oid = "online:"
                                + Integer.toHexString(label.hashCode());
                        useLoadedCues(cues, "Da nap " + label,
                                oid, label, "");
                    }
                    @Override public void onError(String msg) {
                        tvStatus.setText(shortMsg(msg));
                        toast(shortMsg(msg));
                    }
                });
    }

    private void openSubSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8, 0, 8, 0);
        final android.widget.EditText etDl = dialogEdit("SubDL key (subdl.com/panel/api)",
                SubSearchManager.str(this, SubSearchManager.K_SUBDL));
        final android.widget.EditText etSs = dialogEdit("SubSource key (trang profile)",
                SubSearchManager.str(this, SubSearchManager.K_SUBSOURCE));
        final android.widget.EditText etOs = dialogEdit("OpenSubtitles key",
                SubSearchManager.str(this, SubSearchManager.K_OS));
        final android.widget.EditText etUser = dialogEdit("OpenSubtitles user (de tai)",
                SubSearchManager.str(this, SubSearchManager.K_OS_USER));
        final android.widget.EditText etPass = dialogEdit("OpenSubtitles pass (de tai)",
                SubSearchManager.str(this, SubSearchManager.K_OS_PASS));
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final android.widget.EditText etTmdb = dialogEdit("TMDB key (doi ten Viet-Anh)",
                SubSearchManager.str(this, SubSearchManager.K_TMDB));
        root.addView(dialogRow("SubDL key", etDl));
        root.addView(dialogRow("SubSource key", etSs));
        root.addView(dialogRow("OpenSubtitles key", etOs));
        root.addView(dialogRow("OpenSubtitles user", etUser));
        root.addView(dialogRow("OpenSubtitles pass", etPass));
        root.addView(dialogRow("TMDB key (themoviedb.org → Settings → API)", etTmdb));
        final CheckBox cbDl = new CheckBox(this);
        cbDl.setText("Bat SubDL");
        cbDl.setChecked(SubSearchManager.enabled(
                this, SubSearchManager.K_EN_SUBDL));
        final CheckBox cbOs = new CheckBox(this);
        cbOs.setText("Bat OpenSubtitles");
        cbOs.setChecked(SubSearchManager.enabled(
                this, SubSearchManager.K_EN_OS));
        final CheckBox cbSs = new CheckBox(this);
        cbSs.setText("Bat SubSource");
        cbSs.setChecked(SubSearchManager.enabled(
                this, SubSearchManager.K_EN_SUBSOURCE));
        root.addView(cbDl);
        root.addView(cbOs);
        root.addView(cbSs);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        new AlertDialog.Builder(this)
                .setTitle("Key sub online (luu tren may, khong gui di dau)")
                .setView(sv)
                .setPositiveButton("Luu", (d, w) -> {
                    SubSearchManager.saveKeys(this,
                            etDl.getText().toString(),
                            etSs.getText().toString(),
                            etOs.getText().toString(),
                            etUser.getText().toString(),
                            etPass.getText().toString(),
                            etTmdb.getText().toString(),
                            cbDl.isChecked(), cbOs.isChecked(), cbSs.isChecked());
                    toast("Da luu key sub online.");
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    // ---------- sao luu / khoi phuc cai dat + key ----------

    /** Cho user chon cho luu file backup JSON. */
    private void startBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "subtts-backup.json");
            startActivityForResult(i, REQ_BACKUP);
        } catch (Exception e) {
            toast("Khong mo duoc chon cho luu: " + e.getMessage());
        }
    }

    private void startRestore() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/json", "text/plain"});
            startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            toast("Khong mo duoc chon file: " + e.getMessage());
        }
    }

    private void writeBackup(Uri uri) {
        try {
            SharedPreferences sp =
                    getSharedPreferences("webtts", MODE_PRIVATE);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("app", "subtts");
            o.put("v", 1);
            o.put("rate", (double) sp.getFloat("rate", 1.0f));
            o.put("ttsVol", sp.getInt("ttsVol", 100));
            o.put("voice", sp.getInt("voice", 0));
            o.put("splitAudio", sp.getBoolean("splitAudio", true));
            o.put("offset", (double) sp.getFloat("offset", 0f));
            o.put("totalLen", (double) sp.getFloat("totalLen", 0f));
            o.put("cueSize", (double) sp.getFloat("cueSize", 17f));
            putStr(o, sp, SubSearchManager.K_SUBDL);
            putStr(o, sp, SubSearchManager.K_SUBSOURCE);
            putStr(o, sp, SubSearchManager.K_OS);
            putStr(o, sp, SubSearchManager.K_OS_USER);
            putStr(o, sp, SubSearchManager.K_OS_PASS);
            putStr(o, sp, SubSearchManager.K_TMDB);
            putStr(o, sp, "os_token");
            putStr(o, sp, "gvoice");
            o.put(SubSearchManager.K_EN_SUBDL,
                    sp.getBoolean(SubSearchManager.K_EN_SUBDL, true));
            o.put(SubSearchManager.K_EN_OS,
                    sp.getBoolean(SubSearchManager.K_EN_OS, true));
            o.put(SubSearchManager.K_EN_SUBSOURCE,
                    sp.getBoolean(SubSearchManager.K_EN_SUBSOURCE, true));
            // Ho so phim (xem tiep): fpos_*, ftotal_*, fname_*, curfilm_*.
            try {
                java.util.Map<String, ?> all = sp.getAll();
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    String k = e.getKey();
                    if (k == null || !(k.startsWith("fpos_")
                            || k.startsWith("ftotal_")
                            || k.startsWith("fname_")
                            || k.startsWith("curfilm_"))) continue;
                    Object v = e.getValue();
                    if (v instanceof Number) o.put(k, ((Number) v).doubleValue());
                    else if (v instanceof Boolean) o.put(k, (Boolean) v);
                    else if (v != null) o.put(k, v.toString());
                }
            } catch (Exception ignored) {}
            java.io.OutputStream out =
                    getContentResolver().openOutputStream(uri);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
            toast("Da luu sao luu (giu file nay can than, co key).");
        } catch (Exception e) {
            toast("Luu that bai: " + e.getMessage());
        }
    }

    private static void putStr(org.json.JSONObject o, SharedPreferences sp,
                               String k) {
        try {
            o.put(k, sp.getString(k, ""));
        } catch (Exception ignored) {}
    }

    private void readRestore(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            byte[] buf = new byte[65536];
            StringBuilder sb = new StringBuilder();
            int total = 0, n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 65536) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            if (!"subtts".equals(o.optString("app", ""))) {
                toast("File nay khong phai sao luu Sub TTS.");
                return;
            }
            SharedPreferences sp =
                    getSharedPreferences("webtts", MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            ed.putFloat("rate", (float) o.optDouble("rate", 1.0));
            ed.putInt("ttsVol", o.optInt("ttsVol", 100));
            ed.putInt("voice", o.optInt("voice", 0));
            ed.putBoolean("splitAudio", o.optBoolean("splitAudio", true));
            ed.putFloat("offset", (float) o.optDouble("offset", 0));
            ed.putFloat("totalLen", (float) o.optDouble("totalLen", 0));
            ed.putFloat("cueSize", (float) o.optDouble("cueSize", 17));
            getStr(o, ed, SubSearchManager.K_SUBDL);
            getStr(o, ed, SubSearchManager.K_SUBSOURCE);
            getStr(o, ed, SubSearchManager.K_OS);
            getStr(o, ed, SubSearchManager.K_OS_USER);
            getStr(o, ed, SubSearchManager.K_OS_PASS);
            getStr(o, ed, SubSearchManager.K_TMDB);
            getStr(o, ed, "os_token");
            getStr(o, ed, "gvoice");
            ed.putBoolean(SubSearchManager.K_EN_SUBDL,
                    o.optBoolean(SubSearchManager.K_EN_SUBDL, true));
            ed.putBoolean(SubSearchManager.K_EN_OS,
                    o.optBoolean(SubSearchManager.K_EN_OS, true));
            ed.putBoolean(SubSearchManager.K_EN_SUBSOURCE,
                    o.optBoolean(SubSearchManager.K_EN_SUBSOURCE, true));
            // Ho so phim (xem tiep).
            try {
                java.util.Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (k == null || !(k.startsWith("fpos_")
                            || k.startsWith("ftotal_")
                            || k.startsWith("fname_")
                            || k.startsWith("curfilm_"))) continue;
                    Object v = o.opt(k);
                    if (v instanceof Number) {
                        ed.putFloat(k, (float) ((Number) v).doubleValue());
                    } else if (v instanceof Boolean) {
                        ed.putBoolean(k, (Boolean) v);
                    } else if (v != null && !(v instanceof org.json.JSONObject)
                            && !(v instanceof org.json.JSONArray)) {
                        ed.putString(k, v.toString());
                    }
                }
            } catch (Exception ignored) {}
            ed.apply();
            refreshAllUi();
            toast("Da khoi phuc xong.");
        } catch (Exception e) {
            toast("Khoi phuc that bai: " + e.getMessage());
        }
    }

    private static void getStr(org.json.JSONObject o,
                               SharedPreferences.Editor ed, String k) {
        try {
            if (o.has(k)) ed.putString(k, o.optString(k, ""));
        } catch (Exception ignored) {}
    }

    /** Nap lai toan bo UI + day xuong service sau khi khoi phuc. */
    private void refreshAllUi() {
        SharedPreferences sp =
                getSharedPreferences("webtts", MODE_PRIVATE);
        rate = sp.getFloat("rate", 1.0f);
        ttsVol = sp.getInt("ttsVol", 100);
        voice = sp.getInt("voice", TtsPlaybackService.VOICE_GOOGLE);
        totalLen = sp.getFloat("totalLen", 0);
        seekRate.setProgress(Math.round((rate - 0.5f) / 2.0f * 200));
        tvRate.setText(String.format(Locale.US, "%.1fx", rate));
        seekTtsVol.setProgress(ttsVol);
        tvTtsVol.setText(ttsVol + "%");
        if (etTotalLen != null) {
            etTotalLen.setText(totalLen > 0 ? fmtHMS(totalLen) : "");
        }
        cueSize = sp.getFloat("cueSize", 17);
        if (cueSize < 12) cueSize = 12;
        if (cueSize > 30) cueSize = 30;
        seekCueSize.setProgress(Math.round(cueSize - 12));
        tvCueSize.setText(String.valueOf(Math.round(cueSize)));
        tvCue.setTextSize(cueSize);
        refreshPlayBtn();
        refreshVoiceUi();
        refreshCacheBtn();
        ensureService();
        TtsPlaybackService.setRate(rate);
        TtsPlaybackService.setTtsVolume(ttsVol);
        TtsPlaybackService.setVoice(voice);
        TtsPlaybackService.setGoogleVoice(sp.getString("gvoice", ""));
        TtsPlaybackService.setTotalLen(totalLen);
        TtsPlaybackService.setSplitAudio(sp.getBoolean("splitAudio", true));
        syncUi();
    }

    private void setPlaying(boolean p) {
        if (p && subs.isEmpty()) {
            toast("Chon file .srt truoc.");
            return;
        }
        if (p && voice == TtsPlaybackService.VOICE_HOAIMY) {
            toast("HoaiMy online: dang tai 35 cau dau, cho chut...");
        }
        playing = p;
        refreshPlayBtn();
        ensureService();
        if (p) TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_PLAY);
        else TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_PAUSE);
    }

    /** "hh:mm:ss,mmm" de ghi file .srt. */
    private static String fmtSrt(double sec) {
        if (sec < 0) sec = 0;
        long ms = Math.round(sec * 1000);
        return String.format(Locale.US, "%02d:%02d:%02d,%03d",
                ms / 3600000, (ms % 3600000) / 60000,
                (ms % 60000) / 1000, ms % 1000);
    }

    private static String safeFileName(String s) {
        if (s == null || s.isEmpty()) s = "phim";
        s = s.replaceAll("[^\\p{L}\\p{Nd} _.-]+", "_").trim();
        if (s.length() > 60) s = s.substring(0, 60);
        if (s.isEmpty()) s = "phim";
        return s;
    }

    private static String readAllText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            StringBuilder sb = new StringBuilder();
            int total = 0, n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 2 * 1024 * 1024) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Tu luu sub dang doc thanh file .srt trong bo nho rieng (khong can
     * quyen) de lan sau tu nap + hoi xem tiep. Ghi de theo ten phim.
     */
    private void autoSaveSub() {
        if (subs.isEmpty() || filmId.isEmpty()) return;
        try {
            File dir = new File(getFilesDir(), "subs");
            if (!dir.exists()) dir.mkdirs();
            String base = safeFileName(
                    filmName == null || filmName.isEmpty() ? "phim" : filmName);
            File out = new File(dir, base + ".srt");
            StringBuilder sb = new StringBuilder();
            int no = 1;
            for (SubtitleCue c : subs) {
                sb.append(no++).append('\n');
                sb.append(fmtSrt(c.start)).append(" --> ")
                        .append(fmtSrt(c.end)).append('\n');
                sb.append(c.text).append("\n\n");
            }
            java.io.OutputStream os = new java.io.FileOutputStream(out);
            os.write(sb.toString().getBytes("UTF-8"));
            os.close();
            getSharedPreferences("webtts", MODE_PRIVATE).edit()
                    .putString("ffile_" + filmId, out.getAbsolutePath())
                    .putLong("ftime_" + filmId, System.currentTimeMillis())
                    .putInt("fcues_" + filmId, subs.size())
                    // Ghi luon ten phim (truoc day chi ghi khi pause
                    // nen mo tab Da luu som se thay ten mac dinh "phim").
                    .putString("fname_" + filmId,
                            filmName == null || filmName.isEmpty()
                                    ? "phim" : filmName)
                    .apply();
        } catch (Exception ignored) {}
    }

    private static class SavedItem {
        String id = "";
        String name = "phim";
        String path = "";
        int cues = 0;
        long time = 0;
    }

    /** List sub da luu, moi nhat len truoc. */
    private List<SavedItem> collectSaved() {
        List<SavedItem> out = new ArrayList<>();
        try {
            SharedPreferences sp =
                    getSharedPreferences("webtts", MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            if (all == null) return out;
            for (String k : all.keySet()) {
                if (k == null || !k.startsWith("ffile_")) continue;
                String id = k.substring(6);
                if (id.isEmpty()) continue;
                String path = sp.getString(k, "");
                if (path.isEmpty() || !(new File(path).isFile())) continue;
                SavedItem it = new SavedItem();
                it.id = id;
                it.path = path;
                it.name = sp.getString("fname_" + id, "phim");
                it.cues = sp.getInt("fcues_" + id, 0);
                it.time = sp.getLong("ftime_" + id, 0);
                out.add(it);
            }
            Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        } catch (Exception ignored) {}
        return out;
    }

    private void refreshSavedList() {
        if (llSavedList == null) return;
        llSavedList.removeAllViews();
        List<SavedItem> items = collectSaved();
        if (items.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Chua co sub nao duoc luu. Nap sub la app tu luu.");
            t.setTextColor(0xFF8B949E);
            t.setTextSize(13);
            t.setPadding(4, 12, 4, 12);
            llSavedList.addView(t);
            return;
        }
        java.text.SimpleDateFormat df =
                new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US);
        for (SavedItem it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(0xFF161B22);
            row.setPadding(14, 12, 14, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 12);
            row.setLayoutParams(lp);
            TextView t1 = new TextView(this);
            t1.setText(it.name);
            t1.setTextColor(0xFFFFFFFF);
            t1.setTextSize(15);
            t1.setTypeface(t1.getTypeface(),
                    android.graphics.Typeface.BOLD);
            t1.setMaxLines(2);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t1);
            TextView t2 = new TextView(this);
            String meta = it.cues > 0 ? (it.cues + " cau • ") : "";
            if (it.time > 0) {
                try {
                    meta += df.format(new java.util.Date(it.time)) + " • ";
                } catch (Exception ignored) {}
            }
            t2.setText(meta + "cham de nap, giu de xoa");
            t2.setTextColor(0xFF8B949E);
            t2.setTextSize(12);
            row.addView(t2);
            final SavedItem f = it;
            row.setOnClickListener(v -> loadSavedSub(f));
            row.setOnLongClickListener(v -> {
                confirmDeleteSaved(f);
                return true;
            });
            llSavedList.addView(row);
        }
    }

    /** Nap sub da luu (tu hoi xem tiep nhu nap thuong). */
    private void loadSavedSub(SavedItem it) {
        try {
            String body = readAllText(new File(it.path));
            if (!body.isEmpty() && body.charAt(0) == 0xFEFF) {
                body = body.substring(1);
            }
            List<SubtitleCue> parsed = SubtitleParser.parseSrt(body);
            if (parsed.isEmpty()) {
                toast("File sub da luu bi loi.");
                return;
            }
            showPane(0);
            useLoadedCues(parsed, "Da nap " + it.name + " (da luu).",
                    it.id, it.name, "");
        } catch (Exception e) {
            toast("Khong doc duoc file da luu.");
        }
    }

    private void confirmDeleteSaved(final SavedItem it) {
        new AlertDialog.Builder(this)
                .setTitle("Xoa sub da luu?")
                .setMessage(it.name + "\n(Xoa file + diem xem tiep cua phim nay)")
                .setPositiveButton("Xoa", (d, w) -> {
                    deleteSavedItem(it.id);
                    refreshSavedList();
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private void deleteSavedItem(String id) {
        if (id == null || id.isEmpty()) return;
        try {
            SharedPreferences sp =
                    getSharedPreferences("webtts", MODE_PRIVATE);
            String path = sp.getString("ffile_" + id, "");
            if (!path.isEmpty()) {
                try {
                    new File(path).delete();
                } catch (Exception ignored) {}
            }
            sp.edit().remove("fpos_" + id).remove("ftotal_" + id)
                    .remove("fname_" + id).remove("ffile_" + id)
                    .remove("ftime_" + id).remove("fcues_" + id).apply();
        } catch (Exception ignored) {}
    }

    private void clearAllSaved() {
        new AlertDialog.Builder(this)
                .setTitle("Xoa tat ca sub da luu?")
                .setMessage("Xoa het file + diem xem tiep. Khong khoi phuc duoc.")
                .setPositiveButton("Xoa het", (d, w) -> {
                    try {
                        File dir = new File(getFilesDir(), "subs");
                        File[] fs = dir.listFiles();
                        if (fs != null) {
                            for (File f : fs) {
                                try {
                                    if (f.isFile()) f.delete();
                                } catch (Exception ignored) {}
                            }
                        }
                        SharedPreferences sp = getSharedPreferences(
                                "webtts", MODE_PRIVATE);
                        Map<String, ?> all = sp.getAll();
                        SharedPreferences.Editor ed = sp.edit();
                        if (all != null) {
                            for (String k : all.keySet()) {
                                if (k != null && (k.startsWith("fpos_")
                                        || k.startsWith("ftotal_")
                                        || k.startsWith("fname_")
                                        || k.startsWith("ffile_")
                                        || k.startsWith("ftime_")
                                        || k.startsWith("fcues_"))) {
                                    ed.remove(k);
                                }
                            }
                        }
                        ed.apply();
                    } catch (Exception ignored) {}
                    refreshSavedList();
                    toast("Da xoa tat ca sub da luu.");
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private void refreshPlayBtn() {
        if (playing) {
            btnPlayPause.setText("⏸  Pause");
            btnPlayPause.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFA40E26));
        } else {
            btnPlayPause.setText("▶  Play");
            btnPlayPause.setBackgroundTintList(null);
        }
    }

    private void setVoice(int v) {
        voice = v;
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putInt("voice", v).apply();
        ensureService();
        TtsPlaybackService.setVoice(v);
        refreshVoiceUi();
        toast(v == TtsPlaybackService.VOICE_HOAIMY
                ? "Giong HoaiMy (online) — can mang, preload 35 cau."
                : (v == TtsPlaybackService.VOICE_NAMMINH
                ? "Giong NamMinh (online) — can mang, preload 35 cau."
                : (v == TtsPlaybackService.VOICE_VIENEUPC
                ? "Giong Vieneu (PC) — can mang LAN toi server nha."
                : "Giong Google (offline) — khong can mang.")));
    }

    /** Chon giong Vieneu: tai danh sach tu server PC roi chon. */
    private void pickVieneuVoice() {
        ensureService();
        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        String url = sp.getString("vieneu_url", "");
        if (url == null || url.trim().isEmpty()) {
            toast("Nhap dia chi server Vieneu truoc.");
            showVieneuServerDialog(true);
            return;
        }
        final String key = sp.getString("vieneu_key", "");
        toast("Dang tai danh sach giong tu server...");
        VieneuVoiceClient.fetchVoices(url, key,
                new VieneuVoiceClient.VoicesCallback() {
            @Override public void onVoices(
                    final java.util.List<VieneuVoiceClient.Voice> voices) {
                runOnUiThread(() -> {
                    if (voices == null || voices.isEmpty()) {
                        toast("Server khong tra giong nao — nhap ten tay.");
                        showVieneuVoiceManualDialog();
                    } else {
                        showVieneuVoiceListDialog(voices);
                    }
                });
            }
            @Override public void onError(final String msg) {
                runOnUiThread(() -> {
                    toast("Khong noi duoc server: " + msg);
                    showVieneuVoiceManualDialog();
                });
            }
        });
    }

    private void showVieneuVoiceListDialog(
            final java.util.List<VieneuVoiceClient.Voice> voices) {
        String[] labels = new String[voices.size()];
        for (int i = 0; i < voices.size(); i++) labels[i] = voices.get(i).toString();
        new AlertDialog.Builder(this)
                .setTitle("Chon giong Vieneu (PC)")
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < voices.size()) {
                        selectVieneuVoice(voices.get(which).id);
                    }
                })
                .setNegativeButton("Nhap ten tay", (d, w) -> showVieneuVoiceManualDialog())
                .show();
    }

    private void showVieneuVoiceManualDialog() {
        final EditText et = new EditText(this);
        et.setHint("Ten giong (vd Clone, Mai Anh)");
        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        et.setText(sp.getString("vieneu_voice", "Clone"));
        new AlertDialog.Builder(this)
                .setTitle("Ten giong Vieneu")
                .setView(et)
                .setPositiveButton("Chon", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) selectVieneuVoice(n);
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private void selectVieneuVoice(String name) {
        ensureService();
        TtsPlaybackService.setVieneuVoice(name);
        setVoice(TtsPlaybackService.VOICE_VIENEUPC);
        toast("Giong Vieneu (PC): " + name);
    }

    /** Nhap so cau preload Vieneu (1-30). */
    private void showVieneuPreloadDialog() {
        ensureService();
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - 30 (mac dinh 10)");
        int cur = getSharedPreferences("webtts", MODE_PRIVATE)
                .getInt("preload_vn", 10);
        et.setText(String.valueOf(cur));
        new AlertDialog.Builder(this)
                .setTitle("So cau preload Vieneu")
                .setView(et)
                .setPositiveButton("Luu", (d, w) -> {
                    int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        toast("Nhap so 1-30.");
                        return;
                    }
                    if (n < 1 || n > 30) {
                        toast("Nhap so 1-30.");
                        return;
                    }
                    TtsPlaybackService.setPreloadVn(n);
                    refreshPreloadBtn();
                    toast("Preload Vieneu: " + n + " cau.");
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private void refreshPreloadBtn() {
        if (btnVieneuPreload == null) return;
        int n = getSharedPreferences("webtts", MODE_PRIVATE)
                .getInt("preload_vn", 10);
        btnVieneuPreload.setText("⚙  Preload Vieneu: " + n + " cau");
    }

    /** Chon voice Google offline trong cac giong Viet co tren may. */
    private void showGoogleVoiceDialog() {
        ensureService();
        final List<String> labels = TtsPlaybackService.listGoogleVoices();
        if (labels.isEmpty()) {
            toast("Khong doc duoc danh sach giong Viet (may chi co giong mac dinh).");
            return;
        }
        final String cur = getSharedPreferences("webtts", MODE_PRIVATE)
                .getString("gvoice", "");
        final String[] arr = labels.toArray(new String[0]);
        int checked = 0;
        for (int i = 0; i < arr.length; i++) {
            String plain = arr[i].endsWith(" (online)")
                    ? arr[i].substring(0, arr[i].length() - 9) : arr[i];
            if (plain.equals(cur)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Giong Google (offline)")
                .setSingleChoiceItems(arr, checked, (d, w) -> {
                    ensureService();
                    TtsPlaybackService.setGoogleVoice(arr[w]);
                    refreshGoogleBtn();
                    d.dismiss();
                    toast("Giong Google: " + arr[w]);
                })
                .setNegativeButton("Mac dinh", (d, w) -> {
                    ensureService();
                    TtsPlaybackService.setGoogleVoice("");
                    refreshGoogleBtn();
                    toast("Giong Google: mac dinh he thong.");
                })
                .show();
    }

    private void refreshGoogleBtn() {
        if (btnGoogleVoice == null) return;
        String cur = getSharedPreferences("webtts", MODE_PRIVATE)
                .getString("gvoice", "");
        if (cur == null || cur.isEmpty()) {
            btnGoogleVoice.setText("🎙  Giong Google: mac dinh");
        } else {
            String s = cur;
            if (s.length() > 24) s = s.substring(0, 24) + "...";
            btnGoogleVoice.setText("🎙  Giong Google: " + s);
        }
    }

    /** Nhap dia chi server Vieneu + API key (neu co). */
    private void showVieneuServerDialog(final boolean thenPickVoice) {
        ensureService();
        SharedPreferences sp = getSharedPreferences("webtts", MODE_PRIVATE);
        LinearLayout lo = new LinearLayout(this);
        lo.setOrientation(LinearLayout.VERTICAL);
        lo.setPadding(48, 32, 48, 16);
        final EditText etUrl = new EditText(this);
        etUrl.setHint("Dia chi server (vd http://192.168.1.10:8000)");
        etUrl.setText(sp.getString("vieneu_url", ""));
        final EditText etKey = new EditText(this);
        etKey.setHint("API key (de trong neu khong co)");
        etKey.setText(sp.getString("vieneu_key", ""));
        lo.addView(etUrl);
        lo.addView(etKey);
        new AlertDialog.Builder(this)
                .setTitle("Server Vieneu (PC)")
                .setView(lo)
                .setPositiveButton("Luu", (d, w) -> {
                    String u = etUrl.getText().toString().trim();
                    String k = etKey.getText().toString().trim();
                    TtsPlaybackService.setVieneuUrl(u);
                    TtsPlaybackService.setVieneuKey(k);
                    toast(u.isEmpty() ? "Da xoa dia chi server."
                            : "Da luu server: " + u);
                    if (thenPickVoice && !u.isEmpty()) pickVieneuVoice();
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private void refreshVoiceUi() {
        boolean g = voice == TtsPlaybackService.VOICE_GOOGLE;
        boolean h = voice == TtsPlaybackService.VOICE_HOAIMY;
        boolean n = voice == TtsPlaybackService.VOICE_NAMMINH;
        boolean vn = voice == TtsPlaybackService.VOICE_VIENEUPC;
        String vname = getSharedPreferences("webtts", MODE_PRIVATE)
                .getString("vieneu_voice", "Clone");
        tvVoiceBadge.setText(g ? "Google" : (h ? "HoaiMy"
                : (n ? "NamMinh" : "Vieneu:" + vname)));
        // Nut dang chon hien ro, nut kia mo.
        btnVoiceGoogle.setAlpha(g ? 1.0f : 0.5f);
        btnVoiceHoaiMy.setAlpha(h ? 1.0f : 0.5f);
        btnVoiceNamMinh.setAlpha(n ? 1.0f : 0.5f);
        btnVoiceVieneu.setAlpha(vn ? 1.0f : 0.5f);
    }

    /** Thoat han app: dung service chay nen + dong man hinh. */
    private void exitAppUi() {
        if (TtsPlaybackService.isRunning()) {
            TtsPlaybackService.cmdExit(this);
        }
        finishAffinity();
    }

    private void clearCacheUi() {        ensureService();
        int removed = TtsPlaybackService.clearCache();
        refreshCacheBtn();
        toast(removed > 0 ? "Da xoa " + removed + " file cache."
                : "Cache dang trong.");
    }

    private void refreshCacheBtn() {
        if (btnClearCache == null) return;
        try {
            btnClearCache.setText("🗑  " + TtsPlaybackService.cacheText());
        } catch (Exception e) {
            btnClearCache.setText("Xoa cache");
        }
    }

    private void ensureService() {
        Intent i = new Intent(this, TtsPlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    private void ensureFloat() {
        ensureService();
        if (!TtsPlaybackService.canDrawOverlay(this)) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY);
                toast("Cap quyen 'Hien thi tren ung dung khac' roi quay lai.");
            } catch (Exception e) {
                toast("Khong mo duoc xin quyen: " + e.getMessage());
            }
            return;
        }
        TtsPlaybackService.cmd(this, TtsPlaybackService.ACTION_SHOW_FLOAT);
        toast("Da bat nut noi — sang Netflix van dieu khien duoc.");
    }

    /** Nhan nut Cau truoc/sau: nhay toi dau cue, phan hoi ngay tren UI. */
    private void jumpCueUi(int dir) {
        if (subs.isEmpty()) {
            toast("Chon file .srt truoc.");
            return;
        }
        ensureService();
        TtsPlaybackService.jumpCue(dir);
        position = TtsPlaybackService.getPosition();
        syncUi();
    }

    private static String fmtMmSs(double sec) {
        if (sec < 0) sec = 0;
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60);
    }

    /** "01:32:00" de hien tong thoi luong phim da luu. */
    private static String fmtHMS(double sec) {
        if (sec <= 0) return "";
        int s = (int) sec;
        return String.format(Locale.US, "%02d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * Parse tong thoi luong phim: "1:32:00", "92:00", "1h32m", "92m".
     * So tran khong don vi hieu la PHUT (Netflix bao "1h 32m" -> go 92).
     * Tra ve giay, -1 neu sai.
     */
    private static double parseLen(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase(Locale.US).replace(" ", "");
        if (s.isEmpty()) return -1;
        try {
            if (s.contains("h") || (s.contains("m") && !s.contains(":"))) {
                double total = 0;
                Matcher mh = Pattern.compile("(\\d+(?:\\.\\d+)?)h").matcher(s);
                if (mh.find()) total += Double.parseDouble(mh.group(1)) * 3600;
                Matcher mm = Pattern.compile("(\\d+(?:\\.\\d+)?)m").matcher(s);
                if (mm.find()) total += Double.parseDouble(mm.group(1)) * 60;
                Matcher ms = Pattern.compile("(\\d+(?:\\.\\d+)?)s").matcher(s);
                if (ms.find()) total += Double.parseDouble(ms.group(1));
                return total > 0 ? total : -1;
            }
            if (s.contains(":")) {
                double total = 0;
                for (String p : s.split(":")) total = total * 60 + Double.parseDouble(p);
                return total;
            }
            return Double.parseDouble(s) * 60;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Parse gio con lai thay tren Netflix: "25:30", "1:05:00", "-25:30". */
    private static double parseRemain(String s) {
        if (s == null) return -1;
        s = s.trim().replace(" ", "");
        if (s.startsWith("-") || s.startsWith("+")) s = s.substring(1);
        if (s.isEmpty()) return -1;
        try {
            if (!s.contains(":")) return Double.parseDouble(s);
            double total = 0;
            for (String p : s.split(":")) total = total * 60 + Double.parseDouble(p);
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Luu tong thoi luong phim (nhap 1 lan cho moi phim). */
    private void saveTotalUi() {
        double v = parseLen(etTotalLen.getText().toString());
        if (v <= 0) {
            toast("Sai dinh dang. Vd: 1:32:00 hoac 92m");
            return;
        }
        totalLen = v;
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putFloat("totalLen", (float) v).apply();
        ensureService();
        TtsPlaybackService.setTotalLen(v);
        TtsPlaybackService.saveFilmNow();
        etTotalLen.setText(fmtHMS(v));
        syncUi();
        toast("Da luu tong " + fmtHMS(v)
                + ". Gio so gio CON LAI voi Netflix.");
    }

    /** Chon hen gio tat doc: Tat / 15 / 30 / 60 / 90 phut. */
    private void sleepDialog() {
        final String[] items = {"Tat hen gio", "15 phut", "30 phut",
                "60 phut", "90 phut"};
        final int[] vals = {0, 15, 30, 60, 90};
        int leftMin = (TtsPlaybackService.getSleepLeftSec() + 30) / 60;
        int checked = 0;
        for (int i = 1; i < vals.length; i++) {
            if (leftMin >= vals[i] - 7 && leftMin <= vals[i] + 7) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hen gio tat doc")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    ensureService();
                    TtsPlaybackService.setSleepMinutes(vals[w]);
                    toast(vals[w] == 0 ? "Da tat hen gio."
                            : "Se dung doc sau " + vals[w] + " phut.");
                    refreshSleepBtn();
                    d.dismiss();
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    private static String fmtSleep(int sec) {
        int m = (sec + 30) / 60;
        if (m >= 60) {
            return (m / 60) + "h"
                    + String.format(Locale.US, "%02d", m % 60) + "p";
        }
        return m + "p";
    }

    private void refreshSleepBtn() {
        if (btnSleep == null) return;
        int left = TtsPlaybackService.getSleepLeftSec();
        String t = left > 0 ? ("Hen gio tat: " + fmtSleep(left))
                : "Hen gio tat: Tat";
        if (!t.equals(btnSleep.getTag())) {
            btnSleep.setTag(t);
            btnSleep.setText("⏾  " + t);
        }
    }

    /** Nhap con so dem nguoc tren Netflix -> nhay sub toi dung cho. */
    private void jumpRemainDialog() {
        if (subs.isEmpty()) {
            toast("Chon file .srt truoc.");
            return;
        }
        ensureService();
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("vd: 25:30");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Gio con lai tren Netflix")
                .setMessage("Nhin dong ho dem nguoc cua Netflix roi go vao, vd 25:30")
                .setView(et)
                .setPositiveButton("Nhay", (d, w) -> {
                    double rem = parseRemain(et.getText().toString());
                    if (rem < 0) {
                        toast("Sai dinh dang gio.");
                        return;
                    }
                    TtsPlaybackService.jumpToRemaining(rem);
                    position = TtsPlaybackService.getPosition();
                    syncUi();
                    toast("Da nhay toi " + fmtMmSs(position)
                            + ". Tinh chinh bang -1s/+1s.");
                })
                .setNegativeButton("Huy", null)
                .show();
    }

    /** Chuyen tab: 0 = Xem (+Cai dat), 1 = Tim sub, 2 = Sub da luu. */
    private void showPane(int i) {
        if (paneWatch == null) return;
        if (i < 0 || i > 2) i = 0;
        paneWatch.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        paneSetup.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        paneSearch.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        if (paneSaved != null) {
            paneSaved.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        }
        btnTabWatch.setAlpha(i == 0 ? 1.0f : 0.5f);
        btnTabSearch.setAlpha(i == 1 ? 1.0f : 0.5f);
        if (btnTabSaved != null) btnTabSaved.setAlpha(i == 2 ? 1.0f : 0.5f);
        android.content.res.ColorStateList red =
                android.content.res.ColorStateList.valueOf(0xFFE50914);
        btnTabWatch.setBackgroundTintList(i == 0 ? red : null);
        btnTabSearch.setBackgroundTintList(i == 1 ? red : null);
        if (btnTabSaved != null) {
            btnTabSaved.setBackgroundTintList(i == 2 ? red : null);
        }
        getSharedPreferences("webtts", MODE_PRIVATE)
                .edit().putInt("tab", i).apply();
        if (i == 2) refreshSavedList();
    }

    private void syncUi() {
        double base = TtsPlaybackService.syncBase();
        if (base <= 0) base = duration;
        if (base > 0 && !seeking) {
            seekPos.setProgress((int) (position / base * 1000));
        }
        tvTime.setText(TtsPlaybackService.timeText());
        tvRemain.setText(TtsPlaybackService.remainingText());
        int idx = SubtitleParser.findIndex(subs, position);
        if (idx >= 0 && idx < subs.size()) {
            tvCue.setText(subs.get(idx).text);
        }
    }

    private void askNotif() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Cat thong bao con ngan (<=300 ky tu) de khong tran man hinh. */
    private static String shortMsg(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    @Override
    protected void onPause() {
        try {
            TtsPlaybackService.saveFilmNow();
        } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        playing = TtsPlaybackService.isPlayingNow();
        voice = TtsPlaybackService.getVoice();
        position = TtsPlaybackService.getPosition();
        totalLen = getSharedPreferences("webtts", MODE_PRIVATE)
                .getFloat("totalLen", 0);
        if (etTotalLen != null) {
            etTotalLen.setText(totalLen > 0 ? fmtHMS(totalLen) : "");
        }
        refreshPlayBtn();
        refreshVoiceUi();
        refreshCacheBtn();
        refreshPreloadBtn();
        refreshGoogleBtn();
        syncUi();
        refreshSavedList();
    }

    @Override
    protected void onDestroy() {
        if (uiTimer != null) uiTimer.cancel();
        super.onDestroy();
    }
}
