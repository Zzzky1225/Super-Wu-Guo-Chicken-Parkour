package com.wuguo.chicken;

/**
 * 设计画布与窗口映射。
 *
 * 桌面版客户区是 1920×1017，所有几何都按这个坐标写。手机屏幕比例千差万别，
 * 这里分两种情况：
 *
 * 1) 屏幕比 1920:1017（1.888）更"宽"（红米 2340×1080 = 2.167、2160×1080 = 2.0…）：
 *    按高度取一个统一比例 sy，画布宽度 W = 屏宽 / sy（比 1920 宽），横向多出来的
 *    部分全部用来"多看到一些背景"，几何本身一个像素都不变形。
 * 2) 屏幕比它更"方"（1920×1080 的 16:9 等）：等比铺满必然要裁边，为了不留黑边，
 *    退回到横纵各按屏宽屏高铺满（纵向 6% 的拉伸），也就是原本对桌面逐点校过的那套。
 *
 * 高度 H 始终是 1017：纵向不做固定值以外的拉伸，所以每一行控件的 y 与桌面完全一致；
 * 横向靠 cx() / dx() 两个偏移把"居中内容"和"贴右边内容"推到该去的位置。
 */
public final class Design {
    /** 桌面客户区尺寸，所有坐标的基准 */
    public static final int BASE_W = 1920;
    public static final int BASE_H = 1017;

    public static final float BASE_ASPECT = BASE_W / (float) BASE_H;

    /** 当前画布尺寸（等比时 W 会被加宽到屏宽的等效值） */
    public static float W = BASE_W;
    public static float H = BASE_H;

    /** 设计坐标 → 屏幕：等比时 sx == sy */
    public static float sx = 1f;
    public static float sy = 1f;
    public static float offX = 0f;
    public static float offY = 0f;

    private Design() {
    }

    /** 居中内容（原本以 W/2 为轴的那一整块）的横向偏移 */
    public static float cx() {
        return (W - BASE_W) / 2f;
    }

    /** 贴右边的内容（原本贴着 1920 右沿的元素）的横向偏移 */
    public static float dx() {
        return W - BASE_W;
    }

    public static float scale() {
        return sy;
    }

    public static void update(int viewW, int viewH) {
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        float aspect = viewW / (float) viewH;
        if (aspect >= BASE_ASPECT) {
            // 宽屏：严格等比，画布横向加宽，内容不变形
            sy = viewH / (float) BASE_H;
            sx = sy;
            W = viewW / sx;
        } else {
            // 比 1.888 更方：两轴各自铺满（换取没有黑边）
            sx = viewW / (float) BASE_W;
            sy = viewH / (float) BASE_H;
            W = BASE_W;
        }
        H = BASE_H;
        offX = 0f;
        offY = 0f;
    }

    public static float toDesignX(float px) {
        return (px - offX) / sx;
    }

    public static float toDesignY(float py) {
        return (py - offY) / sy;
    }
}
