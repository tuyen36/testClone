package com.example.webtts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Dieu phoi tim + tai sub tu 3 nguon. Key va cong tac luu trong
 * SharedPreferences "webtts" (user tu nhap, KHONG nhung cung vao APK).
 */
public final class SubSearchManager {

    public static final String K_SUBDL = "subdl_key";
    public static final String K_SUBSOURCE = "subsource_key";
    public static final String K_OS = "os_key";
    public static final String K_OS_USER = "os_user";
    public static final String K_OS_PASS = "os_pass";
    public static final String K_TMDB = "tmdb_key";
    public static final String K_EN_SUBDL = "prov_subdl";
    public static final String K_EN_OS = "prov_os";
    public static final String K_EN_SUBSOURCE = "prov_subsource";

    public interface SearchCb {
        void onOk(List<SubResult> rs, String warn);
        void onError(String msg);
    }

    public interface LoadCb {
        void onOk(List<SubtitleCue> cues, String label);
        void onError(String msg);
    }

    private SubSearchManager() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences("webtts", Context.MODE_PRIVATE);
    }

    public static String str(Context ctx, String k) {
        try {
            return sp(ctx).getString(k, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean enabled(Context ctx, String k) {
        try {
            return sp(ctx).getBoolean(k, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void saveKeys(Context ctx, String subdl, String subsource,
                                String osKey, String osUser, String osPass,
                                String tmdb,
                                boolean enDl, boolean enOs, boolean enSs) {
        sp(ctx).edit()
                .putString(K_SUBDL, subdl.trim())
                .putString(K_SUBSOURCE, subsource.trim())
                .putString(K_OS, osKey.trim())
                .putString(K_OS_USER, osUser.trim())
                .putString(K_OS_PASS, osPass)
                .putString(K_TMDB, tmdb.trim())
                .putBoolean(K_EN_SUBDL, enDl)
                .putBoolean(K_EN_OS, enOs)
                .putBoolean(K_EN_SUBSOURCE, enSs)
                .apply();
    }

    /** Chay tren thread nen, tra ket qua ve UI thread. Sub Viet len truoc. */
    public static void searchAll(final Context ctx, final String query,
                                 final String type, final int season,
                                 final int episode,
                                 final boolean useDl, final boolean useOs,
                                 final boolean useSs, final SearchCb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<SubProvider> ps = new ArrayList<>();
            if (useDl && enabled(ctx, K_EN_SUBDL)) {
                ps.add(new SubDlProvider(str(ctx, K_SUBDL)));
            }
            if (useOs && enabled(ctx, K_EN_OS)) {
                ps.add(new OpenSubtitlesProvider(str(ctx, K_OS),
                        str(ctx, K_OS_USER), str(ctx, K_OS_PASS),
                        OpenSubtitlesProvider.androidPrefs(ctx)));
            }
            if (useSs && enabled(ctx, K_EN_SUBSOURCE)) {
                ps.add(new SubSourceProvider(str(ctx, K_SUBSOURCE)));
            }
            if (ps.isEmpty()) {
                ui.post(() -> cb.onError("Chua bat nguon nao (tick chon + nhap key)."));
                return;
            }
            final List<SubResult> merged = new ArrayList<>();
            final StringBuilder warn = new StringBuilder();
            for (SubProvider p : ps) {
                try {
                    List<SubResult> rs = p.search(query, type, season, episode);
                    if (rs != null) merged.addAll(rs);
                } catch (Exception e) {
                    String m = e.getMessage();
                    if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
                    if (warn.length() > 0) warn.append("; ");
                    warn.append(p.name()).append(": ").append(m);
                }
            }
            Collections.sort(merged, new Comparator<SubResult>() {
                @Override public int compare(SubResult a, SubResult b) {
                    boolean av = a.isVietnamese(), bv = b.isVietnamese();
                    if (av != bv) return av ? -1 : 1;
                    return a.provider.compareTo(b.provider);
                }
            });
            final String w = warn.toString();
            ui.post(() -> {
                if (merged.isEmpty()) {
                    cb.onError(w.isEmpty() ? "Khong tim thay sub." : w);
                } else {
                    cb.onOk(merged, w);
                }
            });
        }, "SubSearch").start();
    }

    /** Tai file sub -> bung zip (neu co) -> parse SRT/VTT -> cues. */
    public static void downloadAndParse(final Context ctx, final SubResult r,
                                        final LoadCb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                SubProvider p = providerFor(ctx, r.provider);
                byte[] raw = p.download(r);
                if (raw == null || raw.length == 0) {
                    throw new Exception("File tai ve rong.");
                }
                byte[] text = unzipFirst(raw);
                String body = new String(text, "UTF-8");
                if (!body.isEmpty() && body.charAt(0) == 0xFEFF) {
                    body = body.substring(1);
                }
                List<SubtitleCue> cues = SubtitleParser.parseSrt(body);
                if (cues.isEmpty()) {
                    throw new Exception("Khong doc duoc phu de (dinh dang la).");
                }
                final List<SubtitleCue> f = cues;
                ui.post(() -> cb.onOk(f, r.filmLine().isEmpty()
                        ? (cues.size() + " cau (" + r.provider + ")")
                        : (r.filmLine() + ": " + cues.size() + " cau (" + r.provider + ")")));
            } catch (Exception e) {
                String m = e.getMessage();
                if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
                final String fm = m;
                ui.post(() -> cb.onError("Tai sub loi: " + fm));
            }
        }, "SubDownload").start();
    }

    private static SubProvider providerFor(Context ctx, String name) throws Exception {
        if ("SubDL".equals(name)) return new SubDlProvider(str(ctx, K_SUBDL));
        if ("OpenSubtitles".equals(name)) return new OpenSubtitlesProvider(str(ctx, K_OS),
                str(ctx, K_OS_USER), str(ctx, K_OS_PASS),
                OpenSubtitlesProvider.androidPrefs(ctx));
        if ("SubSource".equals(name)) {
            return new SubSourceProvider(str(ctx, K_SUBSOURCE));
        }
        throw new Exception("Nguon la: " + name);
    }

    /** Neu la zip thi lay file .srt/.vtt/.txt dau tien, khong thi giu nguyen. */
    private static byte[] unzipFirst(byte[] raw) throws Exception {
        if (raw.length < 4 || raw[0] != 0x50 || raw[1] != 0x4B) return raw;
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(raw));
        try {
            ZipEntry e;
            byte[] fallback = null;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase(Locale.US);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int total = 0, n2;
                while ((n2 = zin.read(buf)) > 0) {
                    total += n2;
                    if (total > 3 * 1024 * 1024) break;
                    out.write(buf, 0, n2);
                }
                byte[] b = out.toByteArray();
                if (b.length == 0) continue;
                if (n.endsWith(".srt") || n.endsWith(".vtt")) return b;
                if (fallback == null
                        && (n.endsWith(".txt") || n.endsWith(".ass")
                        || n.endsWith(".ssa") || n.endsWith(".sub"))) {
                    fallback = b;
                }
            }
            if (fallback != null) return fallback;
            throw new Exception("Zip khong co file sub.");
        } finally {
            try { zin.close(); } catch (Exception ignored) {}
        }
    }
}
