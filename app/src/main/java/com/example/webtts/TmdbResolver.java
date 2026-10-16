package com.example.webtts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Doi ten phim tieng Viet sang ten tieng Anh bang TMDB (mien phi).
 * 1. search/movie|tv?query=&language=vi-VN -> ten Viet + ten goc + poster.
 * 2. Neu ten goc khong phai tieng Anh: goi detail ?language=en-US
 *    lay ten tieng Anh quoc te (can de search sub theo release).
 */
public final class TmdbResolver {

    public static class Movie {
        public final long id;
        /** "movie" hoac "tv". */
        public final String kind;
        public final String viTitle;
        /** Ten tieng Anh ("" = can goi englishTitle lay sau). */
        public final String enTitle;
        public final String year;
        public final String posterUrl;
        final String origTitle;
        final String origLang;

        Movie(long id, String kind, String viTitle, String enTitle,
              String origTitle, String origLang, String year, String posterUrl) {
            this.id = id;
            this.kind = kind;
            this.viTitle = viTitle == null ? "" : viTitle;
            this.enTitle = enTitle == null ? "" : enTitle;
            this.origTitle = origTitle == null ? "" : origTitle;
            this.origLang = origLang == null ? "" : origLang;
            this.year = year == null ? "" : year;
            this.posterUrl = posterUrl == null ? "" : posterUrl;
        }
    }

    public interface Cb {
        void onOk(List<Movie> ms);
        void onError(String msg);
    }

    public interface TitleCb {
        void onOk(String enTitle);
        void onError(String msg);
    }

    private TmdbResolver() {}

    private static String key(Context ctx) {
        return SubSearchManager.str(ctx, SubSearchManager.K_TMDB);
    }

    /** Tim phim theo ten tieng Viet. type: "movie" hoac "tv". */
    public static void search(final Context ctx, final String query,
                              final String type, final Cb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String k = key(ctx);
                if (k.isEmpty()) {
                    throw new Exception("Chua nhap TMDB key (man Key sub).");
                }
                boolean tv = "tv".equals(type);
                String u = "https://api.themoviedb.org/3/search/"
                        + (tv ? "tv" : "movie")
                        + "?api_key=" + SubHttp.enc(k)
                        + "&query=" + SubHttp.enc(query)
                        + "&language=vi-VN&include_adult=false&page=1";
                JSONObject o = new JSONObject(SubHttp.get(u, null));
                JSONArray rs = o.optJSONArray("results");
                List<Movie> out = new ArrayList<>();
                if (rs != null) {
                    for (int i = 0; i < rs.length() && out.size() < 20; i++) {
                        JSONObject m = rs.optJSONObject(i);
                        if (m == null) continue;
                        String vi = m.optString(tv ? "name" : "title", "");
                        if (vi.isEmpty()) continue;
                        String orig = m.optString(
                                tv ? "original_name" : "original_title", "");
                        String lang = m.optString("original_language", "");
                        String date = m.optString(
                                tv ? "first_air_date" : "release_date", "");
                        String year = date.length() >= 4
                                ? date.substring(0, 4) : "";
                        String pp = m.optString("poster_path", "");
                        String poster = pp.isEmpty() ? ""
                                : "https://image.tmdb.org/t/p/w185" + pp;
                        String en = "en".equals(lang) && !orig.isEmpty()
                                ? orig : "";
                        out.add(new Movie(m.optLong("id", 0),
                                tv ? "tv" : "movie", vi, en,
                                orig, lang, year, poster));
                    }
                }
                final List<Movie> f = out;
                ui.post(() -> {
                    if (f.isEmpty()) cb.onError("TMDB khong thay phim.");
                    else cb.onOk(f);
                });
            } catch (Exception e) {
                String m = e.getMessage();
                if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
                final String fm = m;
                ui.post(() -> cb.onError("TMDB loi: " + fm));
            }
        }, "TmdbSearch").start();
    }

    /** Lay ten tieng Anh (goi detail en-US neu can). */
    public static void englishTitle(final Context ctx, final Movie m,
                                    final TitleCb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        if (!m.enTitle.isEmpty()) {
            ui.post(() -> cb.onOk(m.enTitle));
            return;
        }
        new Thread(() -> {
            try {
                String k = key(ctx);
                if (k.isEmpty()) throw new Exception("Chua nhap TMDB key.");
                boolean tv = "tv".equals(m.kind);
                String u = "https://api.themoviedb.org/3/"
                        + (tv ? "tv" : "movie") + "/" + m.id
                        + "?api_key=" + SubHttp.enc(k) + "&language=en-US";
                JSONObject o = new JSONObject(SubHttp.get(u, null));
                String en = o.optString(tv ? "name" : "title", "");
                if (en.isEmpty()) en = m.origTitle;
                if (en.isEmpty()) en = m.viTitle;
                final String f = en;
                ui.post(() -> cb.onOk(f));
            } catch (Exception e) {
                final String fb = m.origTitle.isEmpty()
                        ? m.viTitle : m.origTitle;
                ui.post(() -> cb.onOk(fb));
            }
        }, "TmdbTitle").start();
    }
}
