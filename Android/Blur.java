package com.wuguo.chicken;

import android.graphics.Bitmap;

/**
 * 软件模糊（Android 11 及以下用）。
 * 思路和桌面版一致：先缩小、在低分辨率上叠几遍盒式模糊、再由 GPU 放大回屏幕，
 * 既便宜又不会出网格。Android 12+ 走 RenderEffect 直接在 GPU 上糊，不用这里。
 */
public final class Blur {
    private static Bitmap cache;
    private static float cacheRadius = -1f;

    private Blur() {
    }

    public static Bitmap pageBlur(Bitmap src, float radiusDesign) {
        if (cache != null && Math.abs(cacheRadius - radiusDesign) < 0.01f) {
            return cache;
        }
        int sw = 960;
        int sh = Math.max(1, (int) Math.round(sw * (Design.H / (float) Design.W)));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        float r = radiusDesign * sw / Design.W;
        box(small, Math.max(1, (int) Math.round(r)));
        box(small, Math.max(1, (int) Math.round(r)));
        cache = small;
        cacheRadius = radiusDesign;
        return cache;
    }

    /** 横向+纵向各一遍盒式模糊，就地改 */
    private static void box(Bitmap b, int radius) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);

        int div = radius * 2 + 1;
        int[] tmp = new int[Math.max(w, h)];

        // 横向
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            for (int x = -radius; x <= radius; x++) {
                int c = px[row + clamp(x, 0, w - 1)];
                rSum += (c >> 16) & 0xFF;
                gSum += (c >> 8) & 0xFF;
                bSum += c & 0xFF;
                aSum += (c >>> 24);
            }
            for (int x = 0; x < w; x++) {
                tmp[x] = ((aSum / div) << 24) | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                int add = px[row + clamp(x + radius + 1, 0, w - 1)];
                int del = px[row + clamp(x - radius, 0, w - 1)];
                aSum += ((add >>> 24) - (del >>> 24));
                rSum += (((add >> 16) & 0xFF) - ((del >> 16) & 0xFF));
                gSum += (((add >> 8) & 0xFF) - ((del >> 8) & 0xFF));
                bSum += ((add & 0xFF) - (del & 0xFF));
            }
            System.arraycopy(tmp, 0, px, row, w);
        }

        // 纵向
        for (int x = 0; x < w; x++) {
            int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            for (int y = -radius; y <= radius; y++) {
                int c = px[clamp(y, 0, h - 1) * w + x];
                rSum += (c >> 16) & 0xFF;
                gSum += (c >> 8) & 0xFF;
                bSum += c & 0xFF;
                aSum += (c >>> 24);
            }
            for (int y = 0; y < h; y++) {
                tmp[y] = ((aSum / div) << 24) | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                int add = px[clamp(y + radius + 1, 0, h - 1) * w + x];
                int del = px[clamp(y - radius, 0, h - 1) * w + x];
                aSum += ((add >>> 24) - (del >>> 24));
                rSum += (((add >> 16) & 0xFF) - ((del >> 16) & 0xFF));
                gSum += (((add >> 8) & 0xFF) - ((del >> 8) & 0xFF));
                bSum += ((add & 0xFF) - (del & 0xFF));
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = tmp[y];
            }
        }

        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
