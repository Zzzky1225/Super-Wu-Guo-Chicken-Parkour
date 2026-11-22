package com.wuguo.chicken;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GLES2 的 2D 绘制层：所有东西最终都是一个贴了纹理的四边形。
 * 坐标系一律是设计坐标（1920×1080），内部换算成 NDC。
 * 纹理按预乘 alpha 上传，混合固定 GL_ONE / GL_ONE_MINUS_SRC_ALPHA，纯色也输出预乘。
 */
public final class Gl {
    private static final String TAG = "wuguo-gl";

    private static final String VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "uniform vec4 uRect;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  vUV = aUV;\n" +
            "  gl_Position = vec4(uRect.x + aPos.x * uRect.z, uRect.y + aPos.y * uRect.w, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAG_TEX =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec4 uColor;\n" +
            "varying vec2 vUV;\n" +
            "void main(){ gl_FragColor = texture2D(uTex, vUV) * uColor; }\n";

    private static final String FRAG_SOLID =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main(){ gl_FragColor = vec4(uColor.rgb * uColor.a, uColor.a); }\n";

    /** 整页模糊用的 9 抽样核（权重和 = 1），反复迭代叠加成高斯，见 blurScene */
    private static final String FRAG_BLUR =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec2 uStep;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  vec4 c = texture2D(uTex, vUV) * 0.227027;\n" +
            "  c += (texture2D(uTex, vUV + uStep) + texture2D(uTex, vUV - uStep)) * 0.1945946;\n" +
            "  c += (texture2D(uTex, vUV + uStep * 2.0) + texture2D(uTex, vUV - uStep * 2.0)) * 0.1216216;\n" +
            "  c += (texture2D(uTex, vUV + uStep * 3.0) + texture2D(uTex, vUV - uStep * 3.0)) * 0.054054;\n" +
            "  c += (texture2D(uTex, vUV + uStep * 4.0) + texture2D(uTex, vUV - uStep * 4.0)) * 0.016216;\n" +
            "  gl_FragColor = c;\n" +
            "}\n";

    /**
     * 游戏内控件磨砂专用的核：和电脑版 FS_BLUR 一模一样 —— ±radius 之间 31 个抽样，
     * t = i/5、w = exp(-0.5t²)，所以实际 σ ≈ radius/5（半径 26 时 σ≈5.2px），
     * 是一层细磨砂而不是把整块糊平。分母 1/Σw 已经算好写死（Σw = 12.509308）。
     */
    private static final String FRAG_BLUR31 =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec2 uStep;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  vec4 acc = vec4(0.0);\n" +
            "  for (int i = -15; i <= 15; ++i) {\n" +
            "    float t = float(i) / 5.0;\n" +
            "    float w = exp(-0.5 * t * t);\n" +
            "    acc += texture2D(uTex, vUV + uStep * float(i)) * w;\n" +
            "  }\n" +
            "  gl_FragColor = acc * 0.0799404;\n" +
            "}\n";


    public static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;

    private int progTex, progSolid, progBlur, progFrost;
    private int texPos, texUv, texRect, texColor, texTex;
    private int solPos, solRect, solColor;
    private int blPos, blUv, blRect, blStep, blTex;
    private int frPos, frUv, frRect, frStep, frTex;

    private final FloatBuffer posBuf;
    private final FloatBuffer uvBuf;
    private final FloatBuffer uvFlipBuf;
    /** 切块贴图用的临时 UV（8 个 float，每帧复用） */
    private final FloatBuffer sliceBuf;
    /** 死亡时整个人物转 90° 那一次用的 UV（原来每次 new 一块 DirectBuffer） */
    private final FloatBuffer uvRot90Buf;

    private int viewW = 1, viewH = 1;
    private boolean ready;

    private int sceneTex, sceneFbo, blurTexA, blurTexB, blurFboA, blurFboB;
    /** 背景整页模糊单独一套，别和场景模糊共用纹理，否则菜单底图会变成上一局的画面 */
    private int bgTexA, bgTexB, bgFboA, bgFboB;
    private static final int BW = 960, BH = 540;
    private int sceneW = 1920, sceneH = 1080;

    private int bgBlurTex = 0;
    /** 上下文重建计数：外部缓存的纹理 id（比如删除键字形）靠它判断要不要重做 */
    public int gen = 0;
    private float bgBlurMul = -1f;

    /** 画进离屏场景时，映射不能算黑边偏移，必须按场景纹理自身尺寸 */
    private boolean inScene;

    private final Map<Bitmap, Integer> texCache = new HashMap<>();
    private final Map<String, TextTex> textCache = new LinkedHashMap<String, TextTex>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TextTex> e) {
            if (size() > 260) {
                TextTex dead = e.getValue();
                GLES20.glDeleteTextures(1, new int[]{dead.id}, 0);
                // 快取引用跟着作废，否则下次会拿着已删除的 id 去贴图
                if (dead == lastText) lastText = null;
                for (int i = 0; i < DIGIT_SET_N; i++) {
                    TextTex[] set = digitSets[i];
                    if (set == null) continue;
                    for (int d = 0; d < 10; d++) if (set[d] == dead) set[d] = null;
                }
                return true;
            }
            return false;
        }
    };

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 文字纹理的一次性命中（同一句字每帧贴着画，省掉拼 key + 查表） */
    private String lastTextS;
    private int lastTextSize = -1, lastTextColor = -1;
    private TextTex lastText;

    /** 0~9 单字形纹理：按 size/颜色 成套缓存（HUD 三行三色，四套够用，不会来回重建） */
    private static final int DIGIT_SET_N = 4;
    private final TextTex[][] digitSets = new TextTex[DIGIT_SET_N][];
    private final int[] digitSetSize = new int[DIGIT_SET_N];
    private final int[] digitSetColor = new int[DIGIT_SET_N];
    private int digitSetUsed, digitSetNext;

    private static final class TextTex {
        int id;
        int w, h;
    }

    public Gl() {
        ByteBuffer bb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
        posBuf = bb.asFloatBuffer();
        posBuf.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);

        bb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
        uvBuf = bb.asFloatBuffer();
        uvBuf.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);

        bb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
        uvFlipBuf = bb.asFloatBuffer();
        uvFlipBuf.put(new float[]{0, 1, 1, 1, 0, 0, 1, 0}).position(0);

        bb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
        sliceBuf = bb.asFloatBuffer();
        sliceBuf.put(new float[]{0, 0, 1, 0, 0, 1, 1, 1}).position(0);

        bb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder());
        uvRot90Buf = bb.asFloatBuffer();
        uvRot90Buf.put(new float[]{0, 1, 0, 0, 1, 1, 1, 0}).position(0);
    }

    /** 场景纹理 id：游戏内按钮的玻璃底要在这里切片，不能拿菜单背景糊上去 */
    public int sceneTexId() {
        return sceneTex;
    }

    /**
     * 在纹理里切一块画出来：u/v 是「设计坐标左上角原点」的 0~1 比例。
     * bitmap 纹理（flipY=true，行 0 在 v=0）直接用；FBO 纹理是 GL bottom-up，内部把 v 翻过来。
     */
    public void drawTexUV(int id, float x, float y, float w, float h,
                          float u0, float v0, float u1, float v1, boolean flipY) {
        if (id == 0 || w <= 0 || h <= 0) return;
        float[] r = ndc(x, y, w, h);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, r[0], r[1], r[2], r[3]);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        float t0 = flipY ? v0 : 1f - v0;
        float t1 = flipY ? v1 : 1f - v1;
        sliceBuf.clear();
        sliceBuf.put(u0).put(t0).put(u1).put(t0).put(u0).put(t1).put(u1).put(t1);
        sliceBuf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, sliceBuf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    public boolean isReady() {
        return ready;
    }

    public int scenePixelW() {
        return sceneW;
    }

    public int scenePixelH() {
        return sceneH;
    }

    // ---------- 初始化 ----------
    public void onSurfaceCreated() {
        // 上下文可能换了：先丢掉所有旧的 program/纹理/FBO 与缓存 id，绝不沿用
        textCache.clear();
        lastText = null;
        lastTextS = null;
        lastTextSize = -1;
        lastTextColor = -1;
        for (int i = 0; i < DIGIT_SET_N; i++) if (digitSets[i] != null) {
            for (int d = 0; d < 10; d++) digitSets[i][d] = null;
        }
        texCache.clear();
        progTex = progSolid = progBlur = progFrost = 0;
        sceneTex = blurTexA = blurTexB = bgTexA = bgTexB = 0;
        sceneFbo = blurFboA = blurFboB = bgFboA = bgFboB = 0;
        bgBlurTex = 0;
        bgBlurMul = -1f;
        capTex = 0;
        slotTex[0] = slotTex[1] = 0;
        slotFbo[0] = slotFbo[1] = 0;
        frostA = frostB = 0;
        frostFboA = frostFboB = 0;
        frostCapW = frostCapH = 0;
        gen++;
        progTex = program(VERT, FRAG_TEX);
        progSolid = program(VERT, FRAG_SOLID);
        progBlur = program(VERT, FRAG_BLUR);
        progFrost = program(VERT, FRAG_BLUR31);
        if (progTex == 0 || progSolid == 0 || progBlur == 0 || progFrost == 0) {
            ready = false;
            Log.e(TAG, "shader 编译失败");
            return;
        }
        texPos = GLES20.glGetAttribLocation(progTex, "aPos");
        texUv = GLES20.glGetAttribLocation(progTex, "aUV");
        texRect = GLES20.glGetUniformLocation(progTex, "uRect");
        texColor = GLES20.glGetUniformLocation(progTex, "uColor");
        texTex = GLES20.glGetUniformLocation(progTex, "uTex");

        solPos = GLES20.glGetAttribLocation(progSolid, "aPos");
        solRect = GLES20.glGetUniformLocation(progSolid, "uRect");
        solColor = GLES20.glGetUniformLocation(progSolid, "uColor");

        blPos = GLES20.glGetAttribLocation(progBlur, "aPos");
        blUv = GLES20.glGetAttribLocation(progBlur, "aUV");
        blRect = GLES20.glGetUniformLocation(progBlur, "uRect");
        blStep = GLES20.glGetUniformLocation(progBlur, "uStep");
        blTex = GLES20.glGetUniformLocation(progBlur, "uTex");

        frPos = GLES20.glGetAttribLocation(progFrost, "aPos");
        frUv = GLES20.glGetAttribLocation(progFrost, "aUV");
        frRect = GLES20.glGetUniformLocation(progFrost, "uRect");
        frStep = GLES20.glGetUniformLocation(progFrost, "uStep");
        frTex = GLES20.glGetUniformLocation(progFrost, "uTex");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        ready = true;
        Log.i(TAG, "GLES2 2D 层就绪 " + GLES20.glGetString(GLES20.GL_RENDERER));
    }

    public void onSurfaceChanged(int w, int h) {
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
        Design.update(viewW, viewH);
        GLES20.glViewport(0, 0, viewW, viewH);
        if (sceneTex != 0) {
            GLES20.glDeleteTextures(5, new int[]{sceneTex, blurTexA, blurTexB, bgTexA, bgTexB}, 0);
            GLES20.glDeleteFramebuffers(5, new int[]{sceneFbo, blurFboA, blurFboB, bgFboA, bgFboB}, 0);
            sceneTex = blurTexA = blurTexB = bgTexA = bgTexB = 0;
            sceneFbo = blurFboA = blurFboB = bgFboA = bgFboB = 0;
            bgBlurTex = 0;
            bgBlurMul = -1f;
        }
        if (capTex != 0) {
            GLES20.glDeleteTextures(1, new int[]{capTex}, 0);
            capTex = 0;
        }
        if (slotTex[0] != 0) {
            GLES20.glDeleteTextures(2, new int[]{slotTex[0], slotTex[1]}, 0);
            GLES20.glDeleteFramebuffers(2, new int[]{slotFbo[0], slotFbo[1]}, 0);
            slotTex[0] = slotTex[1] = 0;
            slotFbo[0] = slotFbo[1] = 0;
        }
        if (frostA != 0) {
            GLES20.glDeleteTextures(2, new int[]{frostA, frostB}, 0);
            GLES20.glDeleteFramebuffers(2, new int[]{frostFboA, frostFboB}, 0);
            frostA = frostB = 0;
            frostFboA = frostFboB = 0;
        }
        gen++;
    }

    private int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != 1) {
            Log.e(TAG, "link 失败: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != 1) {
            Log.e(TAG, "shader 编译失败: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    // ---------- 每帧 ----------
    public void beginFrame() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
    }

    /**
     * 设计坐标 → NDC 的 (x, y, w, h)，写进 ndcScratch 返回。
     * 「每次绘制不产生垃圾」是这层的硬约束：没有第二个 ndc 结果同时在用，所以复用同一份就够。
     */
    private final float[] ndcScratch = new float[4];

    private float[] ndc(float x, float y, float w, float h) {
        float[] r = ndcScratch;
        if (inScene) {
            float sx = sceneW / (float) Design.W;
            float sy = sceneH / (float) Design.H;
            r[0] = 2f * x * sx / sceneW - 1f;
            r[1] = 1f - 2f * y * sy / sceneH;
            r[2] = 2f * w * sx / sceneW;
            r[3] = -2f * h * sy / sceneH;
        } else {
            r[0] = 2f * (Design.offX + x * Design.sx) / viewW - 1f;
            r[1] = 1f - 2f * (Design.offY + y * Design.sy) / viewH;
            r[2] = 2f * w * Design.sx / viewW;
            r[3] = -2f * h * Design.sy / viewH;
        }
        return r;
    }

    public void fill(float x, float y, float w, float h, int color) {
        if (w <= 0 || h <= 0) return;
        float[] r = ndc(x, y, w, h);
        GLES20.glUseProgram(progSolid);
        GLES20.glUniform4f(solRect, r[0], r[1], r[2], r[3]);
        GLES20.glUniform4f(solColor, ((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f,
                (color & 0xFF) / 255f, ((color >>> 24) & 0xFF) / 255f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(solPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(solPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** 加色叠色（等价于电脑版 BLENDMODE_ADD 的 tint），用于页面底图的暖/冷色调 */
    public void fillAdd(float x, float y, float w, float h, float r, float g, float b, float amt) {
        if (w <= 0 || h <= 0 || amt <= 0) return;
        float[] rc = ndc(x, y, w, h);
        GLES20.glUseProgram(progSolid);
        GLES20.glUniform4f(solRect, rc[0], rc[1], rc[2], rc[3]);
        GLES20.glUniform4f(solColor, r, g, b, amt);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(solPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(solPos);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
    }

    public void stroke(float x, float y, float w, float h, int color, float t) {
        if (t <= 0 || w <= 0 || h <= 0) return;
        fill(x, y, w, t, color);
        fill(x, y + h - t, w, t, color);
        fill(x, y + t, t, h - 2 * t, color);
        fill(x + w - t, y + t, t, h - 2 * t, color);
    }

    public void drawTex(int id, float x, float y, float w, float h, boolean flipY) {
        drawTexTint(id, x, y, w, h, flipY, 0xFFFFFFFF);
    }

    public void drawTexTint(int id, float x, float y, float w, float h, boolean flipY, int tint) {
        if (id == 0 || w <= 0 || h <= 0) return;
        float[] r = ndc(x, y, w, h);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, r[0], r[1], r[2], r[3]);
        GLES20.glUniform4f(texColor, ((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f,
                (tint & 0xFF) / 255f, ((tint >>> 24) & 0xFF) / 255f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        // Bitmap 纹理是 top-down（行 0 在顶部），FBO 纹理是 GL 的 bottom-up，两者 UV 相反
        FloatBuffer uv = flipY ? uvBuf : uvFlipBuf;
        uv.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** 顺时针转 90° 画一张 bitmap，w/h 是换算后的屏幕尺寸（w = 原图高） */    public void drawTexRot90(int id, float x, float y, float w, float h) {
        if (id == 0 || w <= 0 || h <= 0) return;
        float[] r = ndc(x, y, w, h);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, r[0], r[1], r[2], r[3]);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        uvRot90Buf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, uvRot90Buf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    public int texture(Bitmap b) {        if (b == null) return 0;
        Integer cached = texCache.get(b);
        if (cached != null) return cached;
        int id = newTexture(b);
        texCache.put(b, id);
        return id;
    }

    private int newTexture(Bitmap b) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }

    // ---------- 文字 ----------
    /** 以 (x, top) 为锚点画一行字；align 决定水平锚点。返回宽度 */
    public float text(String s, float x, float top, int size, int color, int align) {
        TextTex t = textTex(s, size, color);
        if (t == null) return 0;
        float dx = x;
        if (align == ALIGN_CENTER) dx = x - t.w / 2f;
        else if (align == ALIGN_RIGHT) dx = x - t.w;
        drawTex(t.id, dx, top, t.w, t.h, true);
        return t.w;
    }

    public float textCenter(String s, float cx, float cy, int size, int color) {
        TextTex t = textTex(s, size, color);
        if (t == null) return 0;
        drawTex(t.id, cx - t.w / 2f, cy - t.h / 2f, t.w, t.h, true);
        return t.w;
    }

    /** 左对齐、垂直居中 */
    public float textLeft(String s, float x, float cy, int size, int color) {
        TextTex t = textTex(s, size, color);
        if (t == null) return 0;
        drawTex(t.id, x, cy - t.h / 2f, t.w, t.h, true);
        return t.w;
    }

    public float textWidth(String s, int size) {
        textPaint.setTypeface(Assets.font());
        textPaint.setTextSize(size);
        return textPaint.measureText(s);
    }

    // ---------- 每帧会变的数字：按单字形拼，不新建整串纹理 ----------
    private static final String[] DIGIT_CH = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private final float[] digitAdv = new float[10];
    private int digitAdvSize = -1;
    private final int[] digitScratch = new int[10];

    /** 只算一次每个字形的步进宽度（数字在 Noto 里是等宽的，拼接和老版整串排版一致） */
    private void ensureDigitAdv(int size) {
        if (digitAdvSize == size) return;
        textPaint.setTypeface(Assets.font());
        textPaint.setTextSize(size);
        for (int i = 0; i < 10; i++) digitAdv[i] = textPaint.measureText(DIGIT_CH[i]);
        digitAdvSize = size;
    }

    /** 画一个整数，返回宽度。字形纹理按「单个字符」缓存，分数每变一次也不会新建纹理 */
    private float drawInt(int v, float x, float top, int size, int color) {
        ensureDigitAdv(size);
        int n = Math.abs(v);
        int cnt = 1;
        while (n >= 10) {
            n /= 10;
            cnt++;
        }
        n = Math.abs(v);
        for (int i = cnt - 1; i >= 0; i--) {
            digitScratch[i] = n % 10;
            n /= 10;
        }
        float cx = x;
        if (v < 0) cx += text("-", cx, top, size, color, ALIGN_LEFT);
        return drawDigits(cnt, cx, top, size, color) - x;
    }

    /** 取（size, 颜色）这一套的 0~9 字形纹理；满了轮换替换，正常只会用到 3 套 */
    private TextTex[] digitSet(int size, int color) {
        for (int i = 0; i < digitSetUsed; i++) {
            if (digitSetSize[i] == size && digitSetColor[i] == color) return digitSets[i];
        }
        int slot;
        if (digitSetUsed < DIGIT_SET_N) {
            slot = digitSetUsed++;
        } else {
            slot = digitSetNext;
            digitSetNext = (digitSetNext + 1) % DIGIT_SET_N;
        }
        TextTex[] set = digitSets[slot];
        if (set == null) {
            set = new TextTex[10];
            digitSets[slot] = set;
        } else {
            for (int d = 0; d < 10; d++) set[d] = null;
        }
        digitSetSize[slot] = size;
        digitSetColor[slot] = color;
        return set;
    }

    /** 把 digitScratch[0..cnt) 逐个贴出来（字形纹理成套复用，不再拼 key、不再查表） */
    private float drawDigits(int cnt, float x, float top, int size, int color) {
        TextTex[] set = digitSet(size, color);
        float cx = x;
        for (int i = 0; i < cnt; i++) {
            int d = digitScratch[i];
            TextTex t = set[d];
            if (t == null) {
                t = textTex(DIGIT_CH[d], size, color);
                set[d] = t;
                if (t == null) continue;
            }
            // 字形纹理左边有 1px 内边距，减掉后墨迹正好落在 cx 上；步进用真实宽度，不叠额外内边距
            drawTex(t.id, cx - 1f, top, t.w, t.h, true);
            cx += digitAdv[d];
        }
        return cx;
    }

    /** 「标签 + 整数」，标签照常走整串缓存（标签不常变） */
    public void textInt(String label, int v, float x, float top, int size, int color) {
        float cx = x;
        if (label != null && !label.isEmpty()) cx += text(label, cx, top, size, color, ALIGN_LEFT);
        drawInt(v, cx, top, size, color);
    }

    /** 「标签 + 整数 + 中缀 + 整数」，HP:2/5 这种 */
    public void textInt2(String label, int v1, String mid, int v2, float x, float top, int size, int color) {
        float cx = x;
        if (label != null && !label.isEmpty()) cx += text(label, cx, top, size, color, ALIGN_LEFT);
        cx += drawInt(v1, cx, top, size, color);
        if (mid != null && !mid.isEmpty()) cx += text(mid, cx, top, size, color, ALIGN_LEFT);
        drawInt(v2, cx, top, size, color);
    }

    /** 墨迹包围盒（相对基线），和桌面版 get_bounding_rect 一个用途 */
    public Rect inkBounds(String s, int size) {
        textPaint.setTypeface(Assets.font());
        textPaint.setTextSize(size);
        Rect b = new Rect();
        textPaint.getTextBounds(s, 0, s.length(), b);
        return b;
    }

    /** 墨迹左边缘对齐 x、墨迹垂直中心对齐 cy（桌面版星号、光标那种对法） */
    public void textInk(String s, float inkLeft, float inkCenterY, int size, int color, boolean centerH) {
        TextTex t = textTex(s, size, color);
        if (t == null) return;
        Rect b = inkBounds(s, size);
        float base = Math.round(size * 0.885f);
        float inkCenterInTex = base + b.top + b.height() / 2f;
        float wantLeft = centerH ? inkLeft - b.width() / 2f : inkLeft;
        drawTex(t.id, wantLeft - (1f + b.left), inkCenterY - inkCenterInTex, t.w, t.h, true);
    }

    private TextTex textTex(String s, int size, int color) {
        if (s == null || s.isEmpty()) return null;
        // 一次性命中缓存：HUD 的「分数:」这类字每帧传的都是同一个常量 String
        // （连 key 字符串都不用拼，直接引用比较就命中）
        if (s == lastTextS && size == lastTextSize && color == lastTextColor && lastText != null) {
            return lastText;
        }
        String key = size + "|" + color + "|" + s;
        TextTex t = textCache.get(key);
        if (t == null) {
            textPaint.setTypeface(Assets.font());
            textPaint.setTextSize(size);
            textPaint.setColor(color);
            textPaint.setAntiAlias(true);
            // 和桌面版 pygame 的 text_surf 对齐：surface 高 ≈ 1.104 em，基线 ≈ 0.885 em
            // 直接用 FontMetrics 的 top 会把文字整体压到锚点下方 0.5em（Noto 的行距太大）
            int w = Math.max(1, (int) Math.ceil(textPaint.measureText(s)) + 2);
            int h = Math.max(1, (int) Math.round(size * 1.104f) + 1);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawText(s, 1f, Math.round(size * 0.885f), textPaint);
            t = new TextTex();
            t.id = newTexture(bmp);
            t.w = w;
            t.h = h;
            bmp.recycle();
            textCache.put(key, t);
        }
        lastTextS = s;
        lastTextSize = size;
        lastTextColor = color;
        lastText = t;
        return t;
    }

    // ---------- 离屏场景 + 模糊 ----------
    private void ensureTargets() {
        if (sceneTex != 0) return;
        int[] t = new int[5];
        GLES20.glGenTextures(5, t, 0);
        sceneTex = t[0];
        blurTexA = t[1];
        blurTexB = t[2];
        bgTexA = t[3];
        bgTexB = t[4];
        for (int i = 0; i < 5; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        sceneW = Math.max(1, viewW);
        sceneH = Math.max(1, viewH);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sceneW, sceneH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        for (int i = 1; i < 5; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BW, BH, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }

        int[] f = new int[5];
        GLES20.glGenFramebuffers(5, f, 0);
        sceneFbo = f[0];
        blurFboA = f[1];
        blurFboB = f[2];
        bgFboA = f[3];
        bgFboB = f[4];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, sceneTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFboA);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, blurTexA, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFboB);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, blurTexB, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bgFboA);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, bgTexA, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bgFboB);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, bgTexB, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO 不完整: 0x" + Integer.toHexString(status));
        }
    }

    public void beginScene() {
        ensureTargets();
        inScene = true;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo);
        GLES20.glViewport(0, 0, sceneW, sceneH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    public void endScene() {
        inScene = false;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    public void drawScene() {
        drawTex(sceneTex, 0, 0, Design.W, Design.H, false);
    }

    /** 把整张场景纹理模糊成 blurTexA（暂停/死亡页用） */
    public int blurScene(float mul) {
        ensureTargets();
        // 先降采样到 960×540
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFboA);
        GLES20.glViewport(0, 0, BW, BH);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, -1f, -1f, 2f, 2f);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        uvBuf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        float step = 1.25f * mul;
        blurPass(blurTexA, blurFboB, step / BW, 0);
        blurPass(blurTexB, blurFboA, 0f, step / BH);
        blurPass(blurTexA, blurFboB, step / BW, 0);
        blurPass(blurTexB, blurFboA, 0f, step / BH);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        return blurTexA;
    }

    /** 背景整页模糊（菜单/锁屏底图），按档位缓存；用独立的一套纹理 */
    public int bgBlur(float mul) {
        ensureTargets();
        if (bgBlurTex != 0 && Math.abs(bgBlurMul - mul) < 0.01f) return bgBlurTex;
        int tex = texture(Assets.bg());
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bgFboA);
        GLES20.glViewport(0, 0, BW, BH);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, -1f, -1f, 2f, 2f);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        uvFlipBuf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, uvFlipBuf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        float step = 1.25f * mul;
        blurPass(bgTexA, bgFboB, step / BW, 0);
        blurPass(bgTexB, bgFboA, 0f, step / BH);
        blurPass(bgTexA, bgFboB, step / BW, 0);
        blurPass(bgTexB, bgFboA, 0f, step / BH);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        bgBlurTex = bgTexA;
        bgBlurMul = mul;
        return bgBlurTex;
    }

    public int bgBlurCached() {
        return bgBlurTex;
    }

    // ---------- 整帧抓取 + 模糊（数据页/确认层：桌面对整页回读再糊） ----------
    private int capTex = 0;
    private final int[] slotTex = new int[2];
    private final int[] slotFbo = new int[2];

    private void ensureSlots() {
        if (slotTex[0] != 0) return;
        int[] t = new int[2];
        GLES20.glGenTextures(2, t, 0);
        int[] f = new int[2];
        GLES20.glGenFramebuffers(2, f, 0);
        for (int i = 0; i < 2; i++) {
            slotTex[i] = t[i];
            slotFbo[i] = f[i];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BW, BH, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, t[i], 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * 把「当前已经画完的整帧」抓下来，按 960×540 做双可分离高斯，结果存进 slot 0/1 并返回纹理 id。
     * 桌面对整页回读再糊（数据页、确认弹层都这么干），这里照同一套口径。
     */
    public int blurFrame(int slot, float mul) {
        ensureTargets();
        ensureSlots();
        if (capTex == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            capTex = t[0];
        }
        // 1) 抓帧（当前读的就是默认帧缓冲）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, capTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, viewW, viewH, 0);

        // 2) 降采样到 960×540
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFboA);
        GLES20.glViewport(0, 0, BW, BH);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, capTex);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, -1f, -1f, 2f, 2f);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        uvBuf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 3) 四趟高斯
        float step = 1.25f * mul;
        blurPass(blurTexA, blurFboB, step / BW, 0);
        blurPass(blurTexB, blurFboA, 0f, step / BH);
        blurPass(blurTexA, blurFboB, step / BW, 0);
        blurPass(blurTexB, blurFboA, 0f, step / BH);

        // 4) 落到 slot 纹理里，isReady 之外还能反复贴
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slotFbo[slot]);
        GLES20.glViewport(0, 0, BW, BH);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTexA);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, -1f, -1f, 2f, 2f);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        uvBuf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        return slotTex[slot];
    }

    /** 界面切换后背景变了，缓存作废 */
    public void invalidateBgBlur() {
        bgBlurTex = 0;
        bgBlurMul = -1f;
    }

    // ---------- 游戏内控件的区域磨砂（对应电脑版 gpu_frost） ----------
    /**
     * 磨砂缓冲按控件实际像素尺寸分配、只增不减（只在变大的那一帧重建一次，稳态零开销）。
     * 键位能被拖到 460 设计像素（≈490 设备像素），固定 256 的缓冲会把大控件贴图拉伸，
     * 那就不再是 1:1 的电脑版观感，所以这里跟着尺寸走；上限 1024 只是兜底。
     */
    private static final int FROST_MAX = 1024;
    private int frostA, frostB, frostFboA, frostFboB;
    /** 两个来回缓冲的 id（只在建的时候填一次，省掉每帧 new 数组） */
    private final int[] frostTexIds = new int[2];
    private final int[] frostFboIds = new int[2];
    /** 当前缓冲的像素尺寸 */
    private int frostCapW, frostCapH;
    /** 最近一次 frostRegion 实际用到的缓冲像素尺寸，drawFrost 直接拿来贴 */
    private int frostOutW = 1, frostOutH = 1;

    private void ensureFrost(int needW, int needH) {
        if (frostA != 0 && frostCapW >= needW && frostCapH >= needH) return;
        int cw = Math.min(FROST_MAX, Math.max(needW, frostA == 0 ? 0 : frostCapW));
        int ch = Math.min(FROST_MAX, Math.max(needH, frostA == 0 ? 0 : frostCapH));
        cw = (cw + 15) & ~15;
        ch = (ch + 15) & ~15;
        if (frostA == 0) {
            int[] t = new int[2];
            GLES20.glGenTextures(2, t, 0);
            frostA = frostTexIds[0] = t[0];
            frostB = frostTexIds[1] = t[1];
            int[] f = new int[2];
            GLES20.glGenFramebuffers(2, f, 0);
            frostFboA = frostFboIds[0] = f[0];
            frostFboB = frostFboIds[1] = f[1];
        }
        frostCapW = cw;
        frostCapH = ch;
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frostTexIds[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, cw, ch, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frostFboIds[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, frostTexIds[i], 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * 从场景纹理里截出设计坐标矩形 (x,y,w,h) 做各向同性高斯，返回缓冲纹理 id（0 = 不可用）。
     * 走的是电脑版 gpu_frost 那条路子：**1:1 截取 → 横一遍 31 抽样 → 竖一遍 31 抽样**，
     * 抽样铺满 ±radius、权重 exp(-0.5(i/5)²)，所以实际 σ≈radius/5（半径 26 时 σ≈5.2 设备像素）。
     * 1:1 是关键：细节保留、观感是细磨砂；降采样把 σ 摊平之后整块控件就变成一块糊色，
     * 那就和电脑版不一样了。缓冲里的一个纹素正好等于一个设备像素。
     */
    public int frostRegion(float x, float y, float w, float h, float radiusPx) {
        ensureTargets();
        if (sceneTex == 0 || radiusPx <= 0f || w < 4f || h < 4f) return 0;
        int sw = Math.max(4, Math.min(FROST_MAX, (int) Math.round(w * Design.sx)));
        int sh = Math.max(4, Math.min(FROST_MAX, (int) Math.round(h * Design.sy)));
        ensureFrost(sw, sh);

        float px0 = Design.offX + x * Design.sx, py0 = Design.offY + y * Design.sy;
        float px1 = px0 + w * Design.sx, py1 = py0 + h * Design.sy;
        float u0 = Math.max(0f, px0 / sceneW), u1 = Math.min(1f, px1 / sceneW);
        float vTop = Math.min(1f, 1f - py0 / sceneH), vBot = Math.max(0f, 1f - py1 / sceneH);

        // 截取：posBuf 的 (0,0) 在 FBO 里落在左下角，所以左下要对应场景的 vBot
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frostFboA);
        GLES20.glViewport(0, 0, sw, sh);
        GLES20.glUseProgram(progTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex);
        GLES20.glUniform1i(texTex, 0);
        GLES20.glUniform4f(texRect, -1f, -1f, 2f, 2f);
        GLES20.glUniform4f(texColor, 1f, 1f, 1f, 1f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(texPos);
        sliceBuf.clear();
        sliceBuf.put(u0).put(vBot).put(u1).put(vBot).put(u0).put(vTop).put(u1).put(vTop);
        sliceBuf.position(0);
        GLES20.glVertexAttribPointer(texUv, 2, GLES20.GL_FLOAT, false, 0, sliceBuf);
        GLES20.glEnableVertexAttribArray(texUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 抽样间距 = radius/15（电脑版 u_step × i × radius/15）；缓冲 1:1，uv 步长 = radius/(15*cap) 分轴算
        float ru = sw / (float) frostCapW, rv = sh / (float) frostCapH;
        float radDev = radiusPx * Design.sy;
        frostPass(frostA, frostFboB, radDev / (15f * frostCapW), 0f, sw, sh, ru, rv);
        frostPass(frostB, frostFboA, 0f, radDev / (15f * frostCapH), sw, sh, ru, rv);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        frostOutW = sw;
        frostOutH = sh;
        return frostA;
    }

    /**
     * 把 frostRegion 的结果按设计坐标贴回去（1:1，不缩放）。
     * 缓冲是 FBO（bottom-up）：屏幕上沿要取 v 大的一端，所以 v0/v1 反过来给（v0 在屏幕顶）。
     * 取到最外一圈纹理中心为止（-0.5 texel），免得贴图边缘的采样跨出缓冲读到上一条控件的残留。
     */
    public void drawFrost(int id, float x, float y, float w, float h) {
        float v = Math.max(0f, (frostOutH - 0.5f) / frostCapH);
        float u = Math.max(0f, (frostOutW - 0.5f) / frostCapW);
        drawTexUV(id, x, y, w, h, 0f, v, u, 0f, true);
    }

    /** 只在缓冲里 [0,0]-[u1,v1] 这块内容上取样，否则会读到缓冲外的垃圾像素。走 31 抽样核 */
    private void frostPass(int src, int dstFbo, float sx, float sy, int vw, int vh,
                           float u1, float v1) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo);
        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glUseProgram(progFrost);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src);
        GLES20.glUniform1i(frTex, 0);
        GLES20.glUniform2f(frStep, sx, sy);
        GLES20.glUniform4f(frRect, -1f, -1f, 2f, 2f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(frPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(frPos);
        sliceBuf.clear();
        sliceBuf.put(0f).put(0f).put(u1).put(0f).put(0f).put(v1).put(u1).put(v1);
        sliceBuf.position(0);
        GLES20.glVertexAttribPointer(frUv, 2, GLES20.GL_FLOAT, false, 0, sliceBuf);
        GLES20.glEnableVertexAttribArray(frUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void blurPass(int src, int dstFbo, float sx, float sy) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo);
        GLES20.glViewport(0, 0, BW, BH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progBlur);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src);
        GLES20.glUniform1i(blTex, 0);
        GLES20.glUniform2f(blStep, sx, sy);
        GLES20.glUniform4f(blRect, -1f, -1f, 2f, 2f);
        posBuf.position(0);
        GLES20.glVertexAttribPointer(blPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(blPos);
        uvBuf.position(0);
        GLES20.glVertexAttribPointer(blUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glEnableVertexAttribArray(blUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}

