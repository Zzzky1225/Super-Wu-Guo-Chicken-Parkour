package com.wuguo.chicken;

import android.graphics.Bitmap;

/** 逐像素遮罩（对应桌面版的 pygame.mask.Mask）：alpha 超过阈值算实体 */
public final class Mask {
    public final int w, h;
    private final boolean[] bits;

    public Mask(Bitmap b, int alphaMin) {
        w = b.getWidth();
        h = b.getHeight();
        bits = new boolean[w * h];
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int a = (px[i] >>> 24);
            bits[i] = a > alphaMin;
        }
    }

    /** 把 o 贴到本遮罩的 (ox, oy) 处，看有没有重叠像素（与 pygame 的 overlap 同义） */
    public boolean overlap(Mask o, int ox, int oy) {
        int x0 = Math.max(0, ox);
        int y0 = Math.max(0, oy);
        int x1 = Math.min(w, ox + o.w);
        int y1 = Math.min(h, oy + o.h);
        if (x0 >= x1 || y0 >= y1) return false;
        for (int y = y0; y < y1; y++) {
            int rowA = y * w;
            int rowB = (y - oy) * o.w - ox;
            for (int x = x0; x < x1; x++) {
                if (bits[rowA + x] && o.bits[rowB + x]) return true;
            }
        }
        return false;
    }
}
