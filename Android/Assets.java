package com.wuguo.chicken;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 资源加载：assets 与桌面版那份 assets 目录完全同名，缩放规则也照抄桌面版 */
public final class Assets {
    private static final String TAG = "wuguo-assets";
    /** 桌面版 TARGET_HEIGHT：玩家、蹲姿、所有障碍都按这个高度缩放 */
    public static final int TARGET_H = 425;

    private static Context app;
    private static Bitmap bg;
    private static Typeface typeface;

    public static Bitmap[] framesR, framesL, hurtR, hurtL;
    public static Mask[] framesRMask, framesLMask, hurtRMask, hurtLMask;
    public static Bitmap sitR, sitL, hurtSitR, hurtSitL;
    public static Mask sitRMask, sitLMask, hurtSitRMask, hurtSitLMask;
    public static Bitmap coin;
    public static Mask coinMask;
    public static final List<Bitmap> OBS = new ArrayList<>();
    public static final List<Mask> OBS_MASK = new ArrayList<>();
    public static SoundPool pool;
    public static int[] hitSnd = new int[0], deathSnd = new int[0];

    private Assets() {
    }

    public static void init(Context c) {
        app = c.getApplicationContext();
        typeface = null;
        loadGame();
        loadSounds();
    }

    private static void loadGame() {
        if (framesR != null) return;
        Bitmap rawBg = img("bg.jpg");
        bg = rawBg;

        Bitmap[] f = new Bitmap[3];
        int n = 0;
        for (int i = 0; i < 3; i++) {
            Bitmap b = loadScaledHeight("run" + i + ".png", TARGET_H);
            if (b != null) f[n++] = b;
        }
        framesR = new Bitmap[n];
        System.arraycopy(f, 0, framesR, 0, n);
        framesL = new Bitmap[n];
        hurtR = new Bitmap[n];
        hurtL = new Bitmap[n];
        framesRMask = new Mask[n];
        framesLMask = new Mask[n];
        hurtRMask = new Mask[n];
        hurtLMask = new Mask[n];
        for (int i = 0; i < n; i++) {
            framesL[i] = flipH(framesR[i]);
            hurtR[i] = redTint(framesR[i]);
            hurtL[i] = flipH(hurtR[i]);
            framesRMask[i] = new Mask(framesR[i], 40);
            framesLMask[i] = new Mask(framesL[i], 40);
            hurtRMask[i] = new Mask(hurtR[i], 40);
            hurtLMask[i] = new Mask(hurtL[i], 40);
        }

        sitR = loadScaledHeight("sit.png", TARGET_H);
        if (sitR == null) sitR = framesR.length > 0 ? framesR[0] : Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        sitL = flipH(sitR);
        hurtSitR = redTint(sitR);
        hurtSitL = flipH(hurtSitR);
        sitRMask = new Mask(sitR, 40);
        sitLMask = new Mask(sitL, 40);
        hurtSitRMask = new Mask(hurtSitR, 40);
        hurtSitLMask = new Mask(hurtSitL, 40);

        Bitmap cn = img("coin.png");
        coin = cn == null ? Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
                : Bitmap.createScaledBitmap(cn, 150, 150, true);
        coinMask = new Mask(coin, 40);

        OBS.clear();
        OBS_MASK.clear();
        for (int i = 0; i < 22; i++) {
            Bitmap b = loadScaledHeight("ob" + i + ".png", TARGET_H);
            if (b != null) {
                OBS.add(b);
                OBS_MASK.add(new Mask(b, 40));
            }
        }
        Log.i(TAG, "玩家帧 " + n + " 张，障碍 " + OBS.size() + " 个");
    }

    private static void loadSounds() {
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(aa).build();
            List<Integer> hits = new ArrayList<>();
            List<Integer> deaths = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                int id = loadSnd("hit" + i + ".ogg");
                if (id != 0) hits.add(id);
            }
            for (int i = 1; i <= 5; i++) {
                int id = loadSnd("death" + i + ".ogg");
                if (id != 0) deaths.add(id);
            }
            hitSnd = new int[hits.size()];
            for (int i = 0; i < hits.size(); i++) hitSnd[i] = hits.get(i);
            deathSnd = new int[deaths.size()];
            for (int i = 0; i < deaths.size(); i++) deathSnd[i] = deaths.get(i);
        } catch (Exception e) {
            Log.e(TAG, "音效初始化失败: " + e);
        }
    }

    private static int loadSnd(String name) {
        try {
            android.content.res.AssetFileDescriptor afd = app.getAssets().openFd(name);
            int id = pool.load(afd, 1);
            afd.close();
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void playHit() {
        if (pool != null && hitSnd.length > 0) {
            pool.play(hitSnd[(int) (Math.random() * hitSnd.length)], 1f, 1f, 0, 0, 1f);
        }
    }

    public static void playDeath() {
        if (pool != null && deathSnd.length > 0) {
            pool.play(deathSnd[(int) (Math.random() * deathSnd.length)], 1f, 1f, 0, 0, 1f);
        }
    }

    public static Bitmap img(String name) {
        try (InputStream in = app.getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap loadScaledHeight(String name, int h) {
        Bitmap raw = img(name);
        if (raw == null) return null;
        if (raw.getHeight() == h) return raw;
        int w = Math.max(1, Math.round(raw.getWidth() * (h / (float) raw.getHeight())));
        return Bitmap.createScaledBitmap(raw, w, h, true);
    }

    public static Bitmap flipH(Bitmap s) {
        Matrix m = new Matrix();
        m.postScale(-1, 1);
        return Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), m, true);
    }

    /** 红温：整块按遮罩的 alpha 叠一层红，透明区保持透明（对应桌面版 mask.to_surface） */
    public static Bitmap redTint(Bitmap s) {
        Bitmap out = s.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        p.setColor(Color.argb(90, 255, 0, 0));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        c.drawRect(0, 0, out.getWidth(), out.getHeight(), p);
        return out;
    }

    public static Bitmap bg() {
        if (bg == null) bg = img("bg.jpg");
        return bg;
    }

    public static Typeface font() {
        if (typeface == null) {
            try {
                typeface = Typeface.createFromAsset(app.getAssets(), "NotoSansSC.ttf");
            } catch (Exception e) {
                typeface = Typeface.DEFAULT;
            }
        }
        return typeface;
    }

    /**
     * 退格键字形：桌面版用系统符号字体渲染 "⌫"（空心左向标签 + 内部 X）。
     * Noto Sans SC 没有这个字形，这里按同一套几何用 Path 画一张位图。
     */
    public static Bitmap eraseGlyph(float size) {
        float w = size * 1.42f, h = size * 0.775f, pad = size * 0.12f;
        float stroke = size * 0.058f;
        int bw = (int) Math.ceil(w + pad * 2), bh = (int) Math.ceil(h + pad * 2);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeJoin(Paint.Join.MITER);
        float l = pad, t = pad, r = pad + w, b = pad + h;
        float bend = l + w * 0.27f;
        float mid = (t + b) / 2f;
        android.graphics.Path tag = new android.graphics.Path();
        tag.moveTo(l, mid);
        tag.lineTo(bend, t);
        tag.lineTo(r, t);
        tag.lineTo(r, b);
        tag.lineTo(bend, b);
        tag.close();
        c.drawPath(tag, p);
        android.graphics.Path x = new android.graphics.Path();
        x.moveTo(bend + w * 0.12f, t + h * 0.14f);
        x.lineTo(r - w * 0.09f, b - h * 0.14f);
        x.moveTo(r - w * 0.09f, t + h * 0.14f);
        x.lineTo(bend + w * 0.12f, b - h * 0.14f);
        c.drawPath(x, p);
        return bmp;
    }
}
