package com.jad.tvglow;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Copies an RGBA_8888 image plane into packed 0xRRGGBB pixels as fast as Java allows. */
public final class FrameCopier {
    private FrameCopier() {}

    /**
     * @return the row buffer to reuse next time (may be reallocated)
     */
    public static int[] copy(ByteBuffer buf, int pixelStride, int rowStride, int w, int h,
                             int[] dst, int dstW, int[] rowBuf) {
        if (pixelStride == 4 && rowStride % 4 == 0) {
            // Bulk path: read whole rows as little-endian ints (memory R,G,B,A = 0xAABBGGRR).
            ByteBuffer d = buf.duplicate();
            d.order(ByteOrder.LITTLE_ENDIAN);
            d.position(0);
            IntBuffer ib = d.asIntBuffer();
            if (rowBuf == null || rowBuf.length < w) rowBuf = new int[w];
            int strideInts = rowStride / 4;
            for (int y = 0; y < h; y++) {
                ib.position(y * strideInts);
                ib.get(rowBuf, 0, w);
                int base = y * dstW;
                for (int x = 0; x < w; x++) {
                    int v = rowBuf[x];
                    dst[base + x] = (v & 0xFF) << 16 | (v & 0xFF00) | ((v >> 16) & 0xFF);
                }
            }
            return rowBuf;
        }
        for (int y = 0; y < h; y++) {
            int row = y * rowStride;
            for (int x = 0; x < w; x++) {
                int o = row + x * pixelStride;
                dst[y * dstW + x] = (buf.get(o) & 0xFF) << 16 | (buf.get(o + 1) & 0xFF) << 8 | (buf.get(o + 2) & 0xFF);
            }
        }
        return rowBuf;
    }
}
