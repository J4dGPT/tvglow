package com.jad.tvglow;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared TV styling: dark night palette, glowing focus so the selected button is obvious. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0B0B12, CARD = 0xFF15151F, BTN = 0xFF1F1F2E, BTN_EDGE = 0xFF2C2C40;
    static final int TEXT = 0xFFECECF4, MUTED = 0xFF8C8CA3, ACCENT_A = 0xFFFF4FD8, ACCENT_B = 0xFF7C4DFF;

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(LinearLayout parent, String s, int sp, int color, boolean bold) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        parent.addView(t);
        return t;
    }

    static LinearLayout row(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, dp(c, 6), 0, dp(c, 6));
        parent.addView(r, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private static GradientDrawable rounded(Context c, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 14));
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    /** Equal-width button with a glow gradient, white outline and slight zoom when focused. */
    static Button button(LinearLayout parent, String label, View.OnClickListener l, boolean primary) {
        Context c = parent.getContext();
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setOnClickListener(l);
        b.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        b.setMinHeight(dp(c, 58));
        b.setStateListAnimator(null);
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT_A, ACCENT_B});
        focused.setCornerRadius(dp(c, 14));
        focused.setStroke(dp(c, 3), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{}, primary ? rounded(c, 0xFF3A1F4F, ACCENT_B) : rounded(c, BTN, BTN_EDGE));
        b.setBackground(states);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, primary ? 0xFFF1D9FF : TEXT}));
        b.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
            v.setElevation(has ? dp(c, 10) : 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(c, 12));
        parent.addView(b, lp);
        return b;
    }

    /** A non-focusable label that takes a button's slot (used for the "TV" in the 3x3 grid). */
    static TextView slot(LinearLayout parent, String label) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(MUTED);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 58), 1f);
        lp.setMarginEnd(dp(c, 12));
        parent.addView(t, lp);
        return t;
    }
}
