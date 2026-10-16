package com.example.webtts;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenSubtitles.com: REST API v1.
 * Search: GET /subtitles?query=&languages=vi (can Api-Key).
 * Tai: POST /download {file_id} (can Bearer token tu /login) -> {link}.
 * Token luu vao prefs, het han (401) thi login lai 1 lan.
 */
public class OpenSubtitlesProvider implements SubProvider {

    private static final String BASE = "https://api.opensubtitles.com/api/v1";

    private final String apiKey;
    private final String username;
    private final String password;
    private final Prefs store;

    /** Doc/ghi token qua SharedPreferences de khoi login moi lan. */
    interface Prefs {
        String get(String k);
        void put(String k, String v);
    }

    static Prefs androidPrefs(final Context ctx) {
        return new Prefs() {
            @Override public String get(String k) {
                try {
                    SharedPreferences sp = ctx.getSharedPreferences(
                            "webtts", Context.MODE_PRIVATE);
                    return sp.getString(k, "");
                } catch (Exception e) {
                    return "";
                }
            }
            @Override public void put(String k, String v) {
                try {
                    ctx.getSharedPreferences("webtts", Context.MODE_PRIVATE)
                            .edit().putString(k, v).apply();
                } catch (Exception ignored) {}
            }
        };
    }

    public OpenSubtitlesProvider(String apiKey, String username,
                                 String password, Prefs store) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.store = store;
    }

    @Override public String name() { return "OpenSubtitles"; }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Api-Key", apiKey);
        h.put("User-Agent", "NetflixTTS v1.4");
        return h;
    }

    @Override
    public List<SubResult> search(String query, String type, int season, int episode)
            throws Exception {
        if (apiKey.isEmpty()) throw new Exception("Chua nhap OpenSubtitles key.");
        StringBuilder u = new StringBuilder(BASE + "/subtitles?query=")
                .append(SubHttp.enc(query))
                .append("&languages=vi");
        if ("tv".equals(type)) {
            u.append("&type=episode&season_number=").append(season)
                    .append("&episode_number=").append(episode);
        } else {
            u.append("&type=movie");
        }
        JSONObject o = new JSONObject(SubHttp.get(u.toString(), baseHeaders()));
        List<SubResult> out = new ArrayList<>();
        String film = query;
        JSONArray data = o.optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null) continue;
            JSONObject a = it.optJSONObject("attributes");
            if (a == null) continue;
            String lang = a.optString("language", "");
            String rel = a.optString("release", "");
            if (rel.isEmpty()) rel = a.optString("file_name", "");
            String size = "";
            JSONArray files = a.optJSONArray("files");
            if (files == null || files.length() == 0) continue;
            JSONObject f0 = files.optJSONObject(0);
            int fileId = f0 != null ? f0.optInt("file_id", 0) : 0;
            if (fileId == 0) continue;
            String fn = f0.optString("file_name", "");
            if (!fn.isEmpty() && rel.isEmpty()) rel = fn;
            int dl = a.optInt("download_count", -1);
            if (dl >= 0) size = dl + " tai";
            // Anh bia phim nam trong related_links (kem san, khong ton request).
            String poster = "";
            JSONArray rl = a.optJSONArray("related_links");
            if (rl != null && rl.length() > 0) {
                JSONObject r0 = rl.optJSONObject(0);
                if (r0 != null) poster = r0.optString("img_url", "");
            }
            // Nam + loai phim nam trong feature_details.
            int year = 0;
            String mediaType = "";
            JSONObject feat = a.optJSONObject("feature_details");
            if (feat != null) {
                year = feat.optInt("year", 0);
                mediaType = SubResult.typeLabel(feat.optString("feature_type", ""));
                if (film.isEmpty()) film = feat.optString("title", film);
            }
            out.add(new SubResult(name(), film, lang, rel, size,
                    String.valueOf(fileId), poster, year, mediaType));
            if (out.size() >= 30) break;
        }
        return out;
    }

    private String token(boolean forceLogin) throws Exception {
        String t = store != null ? store.get("os_token") : "";
        if (!t.isEmpty() && !forceLogin) return t;
        if (username.isEmpty() || password.isEmpty()) {
            throw new Exception("Nhap tai khoan OpenSubtitles trong Cai dat"
                    + " sub online de tai (muc user/pass).");
        }
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        JSONObject o = new JSONObject(
                SubHttp.post(BASE + "/login", body.toString(), baseHeaders()));
        t = o.optString("token", "");
        if (t.isEmpty()) throw new Exception("OpenSubtitles login that bai.");
        if (store != null) store.put("os_token", t);
        return t;
    }

    @Override
    public byte[] download(SubResult r) throws Exception {
        int fileId;
        try {
            fileId = Integer.parseInt(r.ref);
        } catch (Exception e) {
            throw new Exception("Tham chieu tai OpenSubtitles sai.");
        }
        JSONObject body = new JSONObject();
        body.put("file_id", fileId);
        String link = downloadLink(body.toString(), false);
        return SubHttp.getBytes(link, null);
    }

    private String downloadLink(String bodyJson, boolean retried) throws Exception {
        String t = token(false);
        Map<String, String> h = baseHeaders();
        h.put("Authorization", "Bearer " + t);
        try {
            JSONObject o = new JSONObject(SubHttp.post(BASE + "/download", bodyJson, h));
            String link = o.optString("link", "");
            if (link.isEmpty()) throw new Exception("OpenSubtitles het luot tai.");
            return link;
        } catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (!retried && (m.contains("401") || m.contains("expired"))) {
                if (store != null) store.put("os_token", "");
                String t2 = token(true);
                Map<String, String> h2 = baseHeaders();
                h2.put("Authorization", "Bearer " + t2);
                JSONObject o = new JSONObject(
                        SubHttp.post(BASE + "/download", bodyJson, h2));
                String link = o.optString("link", "");
                if (link.isEmpty()) throw new Exception("OpenSubtitles het luot tai.");
                return link;
            }
            throw e;
        }
    }
}
