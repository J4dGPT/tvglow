package com.jad.tvglow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import io.nayuki.qrcodegen.QrCode;

/** A QR code for the phone remote's address: point the phone's camera at it to open the page. */
final class QrView extends View {
    private static final int QUIET = 3; // light border, in modules, so phone cameras lock on fast

    private QrCode qr;
    private String text = "";
    private final Paint dark = new Paint();
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    QrView(Context c) {
        super(c);
        setFocusable(false);
        dark.setColor(0xFF0B0B12);
        light.setColor(0xFFFFFFFF);
    }

    void setText(String t) {
        if (t == null) t = "";
        if (t.equals(text)) return;
        text = t;
        try {
            qr = t.isEmpty() ? null : QrCode.encodeText(t, QrCode.Ecc.MEDIUM);
        } catch (RuntimeException e) {
            qr = null;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        QrCode q = qr;
        if (q == null) return;
        float side = Math.min(getWidth(), getHeight());
        float cell = side / (q.size + 2 * QUIET);
        rect.set(0, 0, side, side);
        canvas.drawRoundRect(rect, cell * 1.5f, cell * 1.5f, light);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) {
                if (!q.getModule(x, y)) continue;
                float l = (x + QUIET) * cell, t = (y + QUIET) * cell;
                canvas.drawRect(l, t, l + cell + 0.5f, t + cell + 0.5f, dark); // overlap hides hairline gaps
            }
        }
    }
}
