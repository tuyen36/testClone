package com.example.webtts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SubDL (subdl.com): REST API + key mien phi.
 * GET api.subdl.com/api/v1/subtitles?api_key=&film_name=&type=&languages=VI
 * Tai: https://dl.subdl.com + url (co the la .zip -> app tu bung).
 */
public class SubDlProvider implements SubProvider {

    private final String apiKey;

    public SubDlProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override public String name() { return "SubDL"; }

    @Override
    public List<SubResult> search(String query, String type, int season, int episode)
            throws Exception {
        if (apiKey.isEmpty()) throw new Exception("Chua nhap SubDL key.");
        StringBuilder u = new StringBuilder(
                "https://api.subdl.com/api/v1/subtitles?api_key=")
                .append(SubHttp.enc(apiKey))
                .append("&film_name=").append(SubHttp.enc(query))
                .append("&type=").append("tv".equals(type) ? "tv" : "movie")
                .append("&languages=VI&subs_per_page=30");
        if ("tv".equals(type)) {
            u.append("&season_number=").append(season)
                    .append("&episode_number=").append(episode);
        }
        JSONObject o = new JSONObject(SubHttp.get(u.toString(), null));
        if (!o.optBoolean("status", false)) {
            throw new Exception(o.optString("error", "SubDL bao loi."));
        }
        JSONArray results = o.optJSONArray("results");
        String film = query;
        int year = 0;
        if (results != null && results.length() > 0) {
            JSONObject r0 = results.optJSONObject(0);
            if (r0 != null) {
                film = r0.optString("name", query);
                year = r0.optInt("year", 0);
            }
        }
        String mediaType = "tv".equals(type) ? "Phim bo" : "Phim le";
        List<SubResult> out = new ArrayList<>();
        JSONArray subs = o.optJSONArray("subtitles");
        if (subs == null) return out;
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null) continue;
            String lang = s.optString("language", "");
            String rel = s.optString("release_name", "");
            if (rel.isEmpty()) rel = s.optString("name", "");
            String url = s.optString("url", "");
            if (url.isEmpty()) continue;
            if (url.startsWith("/")) url = "https://dl.subdl.com" + url;
            out.add(new SubResult(name(), film, lang, rel, "", url,
                    "", year, mediaType));
        }
        return out;
    }

    @Override
    public byte[] download(SubResult r) throws Exception {
        return SubHttp.getBytes(r.ref, null);
    }
}
