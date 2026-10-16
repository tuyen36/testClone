package com.example.webtts;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tai thumbnail anh bia (SubSource/OpenSubtitles kem san link).
 * Cache RAM 4MB, tai nen, khong them thu vien ngoai.
 */
final class SubThumbLoader {
    private SubThumbLoader() {}

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                @Override protected int sizeOf(String k, Bitmap v) {
                    try {
                        return v.getByteCount();
                    } catch (Exception e) {
                        return 50 * 1024;
                    }
                }
            };

    static void load(final ImageView iv, final String url) {
        if (url == null || url.isEmpty()) {
            iv.setVisibility(android.view.View.GONE);
            return;
        }
        iv.setVisibility(android.view.View.VISIBLE);
        iv.setTag(url);
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageBitmap(null);
        iv.setBackgroundColor(0xFF1C2128);
        new Thread(() -> {
            Bitmap bm = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "NetflixTTS/1.4 (Android)");
                int code = c.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream in = c.getInputStream();
                    bm = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bm != null) CACHE.put(url, bm);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
            final Bitmap f = bm;
            iv.post(() -> {
                // View co the da tai dung cho dong khac (recycle).
                if (url.equals(iv.getTag()) && f != null) {
                    iv.setImageBitmap(f);
                }
            });
        }, "SubThumb").start();
    }
}
