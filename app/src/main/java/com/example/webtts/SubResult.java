package com.example.webtts;

/**
 * 1 ket qua sub tim duoc tu SubDL / OpenSubtitles / SubSource.
 * ref: tham chieu tai tuy nguon (url | file_id | subtitleId).
 */
public class SubResult {
    public final String provider;
    public final String film;
    public final String language;
    public final String release;
    public final String sizeText;
    public final String ref;
    /** Link anh bia phim (co the rong, vd SubDL khong co). */
    public final String posterUrl;
    /** Nam phat hanh (0 = khong ro). */
    public final int year;
    /** "Phim le" / "Phim bo" ("" = khong ro). */
    public final String mediaType;

    public SubResult(String provider, String film, String language,
                     String release, String sizeText, String ref) {
        this(provider, film, language, release, sizeText, ref, "", 0, "");
    }

    public SubResult(String provider, String film, String language,
                     String release, String sizeText, String ref,
                     String posterUrl) {
        this(provider, film, language, release, sizeText, ref, posterUrl, 0, "");
    }

    public SubResult(String provider, String film, String language,
                     String release, String sizeText, String ref,
                     String posterUrl, int year, String mediaType) {
        this.provider = provider == null ? "" : provider;
        this.film = film == null ? "" : film;
        this.language = language == null ? "" : language;
        this.release = release == null ? "" : release;
        this.sizeText = sizeText == null ? "" : sizeText;
        this.ref = ref == null ? "" : ref;
        this.posterUrl = posterUrl == null ? "" : posterUrl;
        this.year = year;
        this.mediaType = mediaType == null ? "" : mediaType;
    }

    /** "Ten phim • 2024 • Phim le" de hien dong tieu de. */
    public String filmLine() {
        String s = film.isEmpty() ? release : film;
        if (year > 0) s += " • " + year;
        if (!mediaType.isEmpty()) s += " • " + mediaType;
        return s;
    }

    /** Chuan hoa loai phim tu cac nguon ve "Phim le"/"Phim bo". */
    public static String typeLabel(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase(java.util.Locale.US);
        if (t.equals("movie")) return "Phim le";
        if (t.contains("tv") || t.contains("series") || t.contains("show")
                || t.contains("episode")) return "Phim bo";
        return "";
    }

    /** Dong hien trong list chon (uu tien doc release de chon dung ban). */
    public String label() {
        String r = release.isEmpty() ? "(khong ro ban)" : release;
        if (!sizeText.isEmpty()) r += " [" + sizeText + "]";
        return "[" + provider + "] " + r;
    }

    public boolean isVietnamese() {
        String l = language.toLowerCase(java.util.Locale.US);
        return l.startsWith("vi");
    }
}
