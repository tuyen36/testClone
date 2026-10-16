package com.example.webtts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Dong ket qua doi ten phim: poster + ten Viet (dam) + ten Anh/nam (xam).
 * Tai lai SubThumbLoader de hien poster TMDB.
 */
public class TmdbMovieAdapter extends ArrayAdapter<TmdbResolver.Movie> {

    private static class Holder {
        ImageView iv;
        TextView tvVi;
        TextView tvEn;
    }

    public TmdbMovieAdapter(Context ctx, List<TmdbResolver.Movie> ms) {
        super(ctx, 0, ms);
    }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        Context ctx = getContext();
        TmdbResolver.Movie m = getItem(pos);
        Holder h;
        if (cv == null) {
            float den = ctx.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding((int) (12 * den), (int) (8 * den),
                    (int) (12 * den), (int) (8 * den));
            ImageView iv = new ImageView(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd((int) (10 * den));
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            iv.setMinimumHeight((int) (90 * den));
            iv.setBackgroundColor(0xFF1C2128);
            row.addView(iv);
            LinearLayout txtCol = new LinearLayout(ctx);
            txtCol.setOrientation(LinearLayout.VERTICAL);
            txtCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView tvVi = new TextView(ctx);
            tvVi.setTextColor(0xFFFFFFFF);
            tvVi.setTextSize(15);
            tvVi.setTypeface(tvVi.getTypeface(),
                    android.graphics.Typeface.BOLD);
            tvVi.setMaxLines(2);
            tvVi.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txtCol.addView(tvVi);
            TextView tvEn = new TextView(ctx);
            tvEn.setTextColor(0xFF8B949E);
            tvEn.setTextSize(13);
            tvEn.setMaxLines(3);
            tvEn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txtCol.addView(tvEn);
            row.addView(txtCol);
            h = new Holder();
            h.iv = iv;
            h.tvVi = tvVi;
            h.tvEn = tvEn;
            row.setTag(h);
            cv = row;
        } else {
            h = (Holder) cv.getTag();
        }
        if (m != null) {
            String vi = m.viTitle;
            if (!m.year.isEmpty()) vi += " (" + m.year + ")";
            h.tvVi.setText(vi);
            String en = m.enTitle.isEmpty() ? m.origTitle : m.enTitle;
            h.tvEn.setText(en.isEmpty() ? "Cham de dung ten nay" : ("EN: " + en));
            SubThumbLoader.load(h.iv, m.posterUrl);
        }
        return cv;
    }
}
