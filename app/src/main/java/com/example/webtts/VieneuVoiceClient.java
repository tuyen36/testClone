package com.example.webtts;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client HTTP goi server Vieneu tren PC (apps/openai_speech.py).
 * - GET {base}/v1/voices -> {"object":"list","data":[{id|name,...}]}.
 * - POST {base}/v1/audio/speech {model, input, voice,
 *   response_format:"wav", sample_rate:24000} -> wav 24kHz.
 * Chay tren thread rieng (giong EdgeTtsClient), khong block UI.
 */
public class VieneuVoiceClient {

    public interface Callback {
        void onAudio(byte[] wav);
        void onError(String msg);
    }

    public interface VoicesCallback {
        void onVoices(List<Voice> voices);
        void onError(String msg);
    }

    public static class Voice {
        public final String id;
        public final String label;
        public Voice(String id, String label) {
            this.id = id == null ? "" : id;
            this.label = (label == null || label.isEmpty()) ? this.id : label;
        }
        @Override public String toString() { return label; }
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 180000;

    public static String normBase(String base) {
        if (base == null) return "";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String authHeader(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) return null;
        return "Bearer " + apiKey.trim();
    }

    public static void fetchVoices(final String baseUrl, final String apiKey,
                                   final VoicesCallback cb) {
        new Thread(() -> {
            try {
                String base = normBase(baseUrl);
                if (base.isEmpty()) {
                    cb.onError("Chua nhap dia chi server.");
                    return;
                }
                HttpURLConnection c = (HttpURLConnection)
                        new URL(base + "/v1/voices").openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(30000);
                c.setRequestMethod("GET");
                String auth = authHeader(apiKey);
                if (auth != null) c.setRequestProperty("Authorization", auth);
                int code = c.getResponseCode();
                if (code == 401 || code == 403) {
                    cb.onError("Server doi API key (401/403).");
                    return;
                }
                if (code < 200 || code >= 300) {
                    cb.onError("Server tra HTTP " + code);
                    return;
                }
                String body = readAll(c.getInputStream());
                cb.onVoices(parseVoices(body));
            } catch (Exception e) {
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "VieneuVoices").start();
    }

    /** Parse {"object":"list","data":[{id|name, description?}|"Ten"]} that nhat co the. */
    static List<Voice> parseVoices(String body) {
        List<Voice> out = new ArrayList<>();
        if (body == null) return out;
        try {
            int di = body.indexOf("\"data\"");
            if (di < 0) return out;
            int a = body.indexOf('[', di);
            int b = body.lastIndexOf(']');
            if (a < 0 || b < 0 || b <= a) return out;
            String arr = body.substring(a + 1, b);
            // Cat theo doi tuong {...} hoac chuoi "..." o cap ngoai cung.
            int depth = 0;
            boolean inStr = false;
            boolean esc = false;
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                char ch = arr.charAt(i);
                if (inStr) {
                    cur.append(ch);
                    if (esc) esc = false;
                    else if (ch == '\\') esc = true;
                    else if (ch == '"') inStr = false;
                    continue;
                }
                if (ch == '"') {
                    inStr = true;
                    cur.append(ch);
                } else if (ch == '{') {
                    depth++;
                    cur.append(ch);
                } else if (ch == '}') {
                    depth--;
                    cur.append(ch);
                } else if (ch == ',' && depth == 0) {
                    addVoiceToken(out, cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
            addVoiceToken(out, cur.toString().trim());
        } catch (Exception ignored) {}
        return out;
    }

    private static void addVoiceToken(List<Voice> out, String tok) {
        if (tok == null || tok.isEmpty()) return;
        try {
            if (tok.startsWith("{")) {
                String id = jsonStr(tok, "id");
                if (id.isEmpty()) id = jsonStr(tok, "name");
                if (id.isEmpty()) return;
                String label = jsonStr(tok, "description");
                if (label.isEmpty()) label = jsonStr(tok, "label");
                out.add(new Voice(id, label));
            } else if (tok.startsWith("\"") && tok.endsWith("\"") && tok.length() >= 2) {
                String s = tok.substring(1, tok.length() - 1);
                if (!s.isEmpty()) out.add(new Voice(s, ""));
            }
        } catch (Exception ignored) {}
    }

    /** Doc gia tri chuoi cua key JSON don gian (khong dung lib ngoai). */
    private static String jsonStr(String obj, String key) {
        String k = "\"" + key + "\"";
        int i = obj.indexOf(k);
        if (i < 0) return "";
        int c = obj.indexOf(':', i + k.length());
        if (c < 0) return "";
        int q1 = obj.indexOf('"', c + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = q1 + 1; j < obj.length(); j++) {
            char ch = obj.charAt(j);
            if (ch == '\\' && j + 1 < obj.length()) {
                char nx = obj.charAt(j + 1);
                if (nx == '"' || nx == '\\' || nx == '/') {
                    sb.append(nx);
                    j++;
                    continue;
                }
                if (nx == 'u' && j + 5 < obj.length()) {
                    try {
                        sb.append((char) Integer.parseInt(obj.substring(j + 2, j + 6), 16));
                        j += 5;
                        continue;
                    } catch (Exception ignored) {}
                }
                sb.append(ch);
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    public static void synthesize(final String baseUrl, final String apiKey,
                                  final String text, final String voice,
                                  final Callback cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                String base = normBase(baseUrl);
                if (base.isEmpty()) {
                    cb.onError("Chua nhap dia chi server.");
                    return;
                }
                if (text == null || text.trim().isEmpty()) {
                    cb.onError("Cau trong.");
                    return;
                }
                String body = "{\"model\":\"vieneu-v3-turbo\","
                        + "\"input\":" + jsonQuote(text) + ","
                        + "\"voice\":" + jsonQuote(voice == null ? "" : voice) + ","
                        + "\"response_format\":\"wav\","
                        + "\"sample_rate\":24000}";
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                c = (HttpURLConnection)
                        new URL(base + "/v1/audio/speech").openConnection();
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String auth = authHeader(apiKey);
                if (auth != null) c.setRequestProperty("Authorization", auth);
                OutputStream os = c.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
                int code = c.getResponseCode();
                if (code == 401 || code == 403) {
                    cb.onError("Server doi API key (401/403).");
                    return;
                }
                if (code == 429) {
                    cb.onError("Server ban (429), thu lai sau.");
                    return;
                }
                if (code < 200 || code >= 300) {
                    cb.onError("Server tra HTTP " + code);
                    return;
                }
                byte[] wav = readBytes(c.getInputStream());
                if (wav.length < 100) {
                    cb.onError("Server tra am thanh rong.");
                    return;
                }
                cb.onAudio(wav);
            } catch (Exception e) {
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
        }, "VieneuTts").start();
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') sb.append("\\\"");
            else if (ch == '\\') sb.append("\\\\");
            else if (ch == '\n') sb.append("\\n");
            else if (ch == '\r') sb.append("\\r");
            else if (ch == '\t') sb.append("\\t");
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        sb.append('"');
        return sb.toString();
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        return bos.toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }
}
