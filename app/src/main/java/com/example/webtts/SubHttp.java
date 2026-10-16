package com.example.webtts;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** HTTP helper dung chung cho cac SubProvider (GET/POST JSON + tai bytes). */
final class SubHttp {
    private SubHttp() {}

    static final int CONNECT_MS = 15000;
    static final int READ_MS = 25000;
    static final int MAX_BYTES = 8 * 1024 * 1024;

    static String get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, null, headers, true);
    }

    static String post(String url, String jsonBody, Map<String, String> headers)
            throws Exception {
        return request("POST", url, jsonBody, headers, true);
    }

    static byte[] getBytes(String url, Map<String, String> headers)
            throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "NetflixTTS/1.4 (Android)");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " " + snippet(c));
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0, n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("File sub qua lon.");
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static String request(String method, String url, String body,
                                  Map<String, String> headers, boolean readErr)
            throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "NetflixTTS/1.4 (Android)");
            c.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? c.getInputStream() : c.getErrorStream();
            // Thanh cong: doc toi 512KB (list ket qua co khi >64KB).
            // Loi: chi giu 300 ky tu dau de hien, tranh tran man hinh.
            String text = in == null ? "" : readText(in, 512 * 1024);
            if (code < 200 || code >= 300) {
                String shortText = text.length() > 300
                        ? text.substring(0, 300) : text;
                throw new Exception("HTTP " + code
                        + (shortText.isEmpty() ? "" : ": " + shortText));
            }
            return text;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static String readText(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) break;
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String snippet(HttpURLConnection c) {
        try {
            InputStream err = c.getErrorStream();
            if (err == null) return "";
            String s = readText(err, 300);
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Exception e) {
            return "";
        }
    }

    static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static String sizeText(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB",
                bytes / (1024.0 * 1024.0));
    }
}
