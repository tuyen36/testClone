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
 * Dong ket qua tim sub: thumbnail trai + ten ban release phai.
 * Khong poster (SubDL) thi an anh, chu chiem full chieu rong.
 */
public class SubResultAdapter extends ArrayAdapter<SubResult> {

    private static class Holder {
        ImageView iv;
        TextView tvFilm;
        TextView tvRelease;
    }

    public SubResultAdapter(Context ctx, List<SubResult> rs) {
        super(ctx, 0, rs);
    }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        Context ctx = getContext();
        SubResult r = getItem(pos);
        Holder h;
        if (cv == null) {
            float den = ctx.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding((int) (12 * den), (int) (8 * den),
                    (int) (12 * den), (int) (8 * den));
            ImageView iv = new ImageView(ctx);
            // Anh chiem 1/2 ngang dong (weight 1 vs chu weight 1),
            // giu ti le poster, tu gian cao.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd((int) (10 * den));
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            iv.setMinimumHeight((int) (120 * den));
            iv.setBackgroundColor(0xFF1C2128);
            row.addView(iv);
            LinearLayout txtCol = new LinearLayout(ctx);
            txtCol.setOrientation(LinearLayout.VERTICAL);
            txtCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView tvFilm = new TextView(ctx);
            tvFilm.setTextColor(0xFFFFFFFF);
            tvFilm.setTextSize(15);
            tvFilm.setTypeface(tvFilm.getTypeface(),
                    android.graphics.Typeface.BOLD);
            tvFilm.setMaxLines(2);
            tvFilm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txtCol.addView(tvFilm);
            TextView tv = new TextView(ctx);
            tv.setTextColor(0xFF8B949E);
            tv.setTextSize(13);
            tv.setMaxLines(3);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txtCol.addView(tv);
            row.addView(txtCol);
            h = new Holder();
            h.iv = iv;
            h.tvFilm = tvFilm;
            h.tvRelease = tv;
            row.setTag(h);
            cv = row;
        } else {
            h = (Holder) cv.getTag();
        }
        if (r != null) {
            h.tvFilm.setText(r.filmLine());
            h.tvRelease.setText(r.label());
            SubThumbLoader.load(h.iv, r.posterUrl);
        }
        return cv;
    }
}
