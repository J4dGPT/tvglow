package com.jad.tvglow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A small TV with its glow: the strip's current colors drawn around the screen, in the same
 * zone order the strip uses (up the left, across the top, down the right, back along the bottom).
 */
final class GlowPreview extends View {
    private static final int LEFT = 0, UP = 1, RIGHT = 2, DOWN = 3;
    private static final int IDLE = 0x2E2E40;

    private int[] colors;
    private int[] counts = {2, 4, 2, 4};
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bezel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    GlowPreview(Context c) {
        super(c);
        setFocusable(false);
        body.setColor(0xFF07070C);
        bezel.setStyle(Paint.Style.STROKE);
        bezel.setStrokeWidth(c.getResources().getDisplayMetrics().density);
        bezel.setColor(0xFF2C2C40);
    }

    /** Shows these zone colors, or a quiet idle outline when colors is null. */
    void show(int[] colors, int[] counts) {
        if (colors == this.colors && (counts == null || counts == this.counts)) return;
        this.colors = colors;
        if (counts != null) this.counts = counts;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float pad = h * 0.17f;
        float th = h - 2 * pad, tw = th * 16f / 9f;
        if (tw > w - 2 * pad) {
            tw = w - 2 * pad;
            th = tw * 9f / 16f;
        }
        float x0 = (w - tw) / 2f, y0 = (h - th) / 2f, x1 = x0 + tw, y1 = y0 + th;

        int[] c = counts;
        int total = c[0] + c[1] + c[2] + c[3];
        int[] col = colors != null && colors.length == total ? colors : null;
        int k = 0;
        for (int side = 0; side < 4; side++) {
            int m = c[side];
            for (int j = 0; j < m; j++, k++) {
                int rgb = col == null ? IDLE : visible(col[k]);
                float a = (float) j / m, b = (float) (j + 1) / m;
                switch (side) {
                    case 0: // left, bottom to top
                        segment(canvas, x0 - pad, y1 - b * th, x0, y1 - a * th, rgb, LEFT);
                        break;
                    case 1: // top, left to right
                        segment(canvas, x0 + a * tw, y0 - pad, x0 + b * tw, y0, rgb, UP);
                        break;
                    case 2: // right, top to bottom
                        segment(canvas, x1, y0 + a * th, x1 + pad, y0 + b * th, rgb, RIGHT);
                        break;
                    default: // bottom, right to left
                        segment(canvas, x1 - b * tw, y1, x1 - a * tw, y1 + pad, rgb, DOWN);
                        break;
                }
            }
        }
        float r = h * 0.03f;
        rect.set(x0, y0, x1, y1);
        canvas.drawRoundRect(rect, r, r, body);
        canvas.drawRoundRect(rect, r, r, bezel);
    }

    /** One zone: solid at the TV's edge, fading out toward the wall. */
    private void segment(Canvas canvas, float l, float t, float r, float b, int rgb, int dir) {
        int solid = 0xF0000000 | (rgb & 0xFFFFFF), clear = rgb & 0xFFFFFF;
        LinearGradient g;
        switch (dir) {
            case LEFT:
                g = new LinearGradient(r, 0, l, 0, solid, clear, Shader.TileMode.CLAMP);
                break;
            case UP:
                g = new LinearGradient(0, b, 0, t, solid, clear, Shader.TileMode.CLAMP);
                break;
            case RIGHT:
                g = new LinearGradient(l, 0, r, 0, solid, clear, Shader.TileMode.CLAMP);
                break;
            default:
                g = new LinearGradient(0, t, 0, b, solid, clear, Shader.TileMode.CLAMP);
                break;
        }
        glow.setShader(g);
        canvas.drawRect(l, t, r, b, glow);
    }

    /**
     * LED values look much darker on a screen than on a wall, so lift the dark end
     * (same hue, brighter) to keep a dim cozy glow readable in the preview.
     */
    static int visible(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        if (max == 0) return IDLE;
        float lift = (float) (255 * Math.pow(max / 255.0, 0.55)) / max;
        return Math.min(255, Math.round(r * lift)) << 16
                | Math.min(255, Math.round(g * lift)) << 8
                | Math.min(255, Math.round(b * lift));
    }
}
