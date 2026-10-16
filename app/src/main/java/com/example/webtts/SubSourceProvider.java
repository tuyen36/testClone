package com.example.webtts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubSource (subsource.net): REST API + key (header X-API-Key).
 * 2 buoc: /movies/search?q=&searchType=text -> movieId
 * -> /subtitles?movieId=&language=vietnamese -> subtitleId
 * Tai: GET /subtitles/{id}/download (file .srt).
 */
public class SubSourceProvider implements SubProvider {

    private static final String BASE = "https://api.subsource.net/api/v1";

    private final String apiKey;

    public SubSourceProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override public String name() { return "SubSource"; }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("X-API-Key", apiKey);
        return h;
    }

    @Override
    public List<SubResult> search(String query, String type, int season, int episode)
            throws Exception {
        if (apiKey.isEmpty()) throw new Exception("Chua nhap SubSource key.");
        String mu = BASE + "/movies/search?q=" + SubHttp.enc(query)
                + "&searchType=text";
        JSONObject mo = new JSONObject(SubHttp.get(mu, headers()));
        JSONArray movies = mo.optJSONArray("data");
        if (movies == null || movies.length() == 0) return new ArrayList<>();
        JSONObject m0 = movies.optJSONObject(0);
        long movieId = m0 != null ? m0.optLong("movieId", 0) : 0;
        if (movieId == 0) return new ArrayList<>();
        String film = m0.optString("title", query);
        int year = m0.optInt("releaseYear", 0);
        String mediaType = SubResult.typeLabel(m0.optString("type", ""));
        // Anh bia phim (SubSource kem san, khong ton them request).
        String poster = "";
        JSONObject posters = m0.optJSONObject("posters");
        if (posters != null) {
            poster = posters.optString("small", "");
            if (poster.isEmpty()) poster = posters.optString("medium", "");
        }

        String su = BASE + "/subtitles?movieId=" + movieId
                + "&language=vietnamese&limit=50";
        JSONObject so = new JSONObject(SubHttp.get(su, headers()));
        JSONArray data = so.optJSONArray("data");
        List<SubResult> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject s = data.optJSONObject(i);
            if (s == null) continue;
            long sid = s.optLong("subtitleId", 0);
            if (sid == 0) continue;
            String rel = joinRelease(s.optJSONArray("releaseInfo"));
            if (rel.isEmpty()) rel = s.optString("commentary", "");
            if (rel.length() > 120) rel = rel.substring(0, 120);
            out.add(new SubResult(name(), film, s.optString("language", ""),
                    rel, SubHttp.sizeText(s.optLong("size", 0)),
                    String.valueOf(sid), poster, year, mediaType));
        }
        return out;
    }

    private static String joinRelease(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        String first = arr.optString(0, "");
        if (arr.length() > 1) first += " (+" + (arr.length() - 1) + ")";
        return first;
    }

    @Override
    public byte[] download(SubResult r) throws Exception {
        return SubHttp.getBytes(BASE + "/subtitles/" + r.ref + "/download",
                headers());
    }
}
