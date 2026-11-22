package com.wuguo.chicken;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.MotionEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 整个游戏的状态机与界面，逐块对应桌面版 main.py 的各页面。
 * 所有坐标都是设计坐标（1920×1080）；触摸也是换算成设计坐标后进来。
 * onTouch / update / draw 全在 GL 线程上跑（Activity 用 queueEvent 投递触摸）。
 */
public final class Game {
    public enum St {LOCK, SETUP, GAME, PAUSED, GAMEOVER, SETTINGS, KEYLAYOUT}

    // 与 Ui 一致的玻璃色值
    public static final int GLASS_LIGHT = 0x84FCFCFE;
    public static final int GLASS_LIGHT_PRESS = 0xA5FCFCFE;
    public static final int GLASS_DARK = 0xC41E232D;
    public static final int GLASS_DARK_PRESS = 0xE2141923;
    public static final int RIM_LIGHT = 0x3AFFFFFF;
    public static final int RIM_DARK = 0x1EFFFFFF;
    public static final int RIM_DARK_BIG = 0x48FFFFFF;
    public static final int KEY_FILL = 0x2EFFFFFF;
    public static final int KEY_FILL_PRESS = 0x96FFFFFF;
    public static final int KEY_ACCENT = 0x6E0A84FF;
    public static final int KEY_ACCENT_PRESS = 0xAA0A84FF;
    public static final int KEY_RIM = 0x78FFFFFF;

    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_GRAY = 0xFFDCDCDC;
    private static final int C_GOLD = 0xFFFFD73C;
    private static final int C_RED = 0xFFFF5050;
    private static final int C_WARN = 0xFFFFCD46;
    private static final int C_SUB = 0xFFC8C8C8;

    private static final String LOCK_TARGET = "7355608";
    private static final String[] MODE_NAMES = {"跑步鸡", "滑步鸡", "吴国鸡"};
    private static final String[] TABS = {"控制设置", "模糊设置", "参数设置", "作弊选项", "渲染器"};
    private static final String[] BLUR_NAMES = {"弱", "中", "强"};
    private static final float[] BLUR_MULS = {0.45f, 1.0f, 1.65f};
    /** 页面底图模糊基准：桌面版 page_blur_px() = 7×1.5×blur_mul()，档位就乘在这个基准上 */
    private static final float PAGE_BLUR = 0.8f;
    /**
     * 游戏内控件磨砂半径（设计像素）。电脑版 BLUR_CTRL_PX=26 是**屏幕像素**，它默认窗口 1280×678
     * 的 scale = 678/1017 = 0.667，换算到设计坐标就是 26×1017/678 ≈ 39，σ 才同为半径的 1/5、
     * 占按钮宽度的 5.2%（26 直接用会变成 3.5%，比电脑薄 1.5 倍）
     */
    private static final float CTRL_BLUR_PX = 39f;
    /** 暂停键磨砂半径，电脑版 BLUR_PAUSE_PX=40 屏幕像素 → 40×1017/678 ≈ 60 设计像素 */
    private static final float PAUSE_BLUR_PX = 60f;

    public final World world = new World();
    private Gl gl;
    private Context ctx;
    private Data data;
    private DialogHost host;

    public St st = St.LOCK, prev = St.SETUP;
    private int blurLevel = 1;
    private int settingsTab = 0;
    private int inputMode = 1;          // 0=键盘操控 1=触屏操控（与电脑版 game_state["input_mode"] 同义）

    // 锁屏
    private String lockInput = "";
    private int lockErr;
    private long lockShowUntil;
    private int pressedKey = -1;

    // 主菜单参数行
    private String[] labels = new String[0];
    private String[] keys = new String[0];
    private String[] texts = new String[0];
    private double[] values = new double[0];
    private int dragSlider = -1;
    private int pressedBtn = -999;

    // 多指触控：每个 pointerId 各自记住压着哪个触屏键，松手指只清自己那一路
    private static final int K_NONE = 0, K_JUMP = 1, K_CROUCH = 2, K_LEFT = 3, K_RIGHT = 4;
    private final int[] ptrKind = new int[32];
    /** 非对局页面只认第一根按下的手指，第二根手指不会乱点按钮 */
    private int primaryId = -1;

    // 覆盖层
    private boolean statsOpen, statsConfirm;
    private boolean runCheated;

    // 键位编辑
    private int klPick = -1, klDragMode = 0;
    private float klOffX, klOffY;

    private float downX, downY;

    // 结算
    private float deadAngle;

    public interface DialogHost {
        void askNumber(String title, String initial, boolean integer, boolean decimalAllowed,
                       java.util.function.Consumer<String> onOk);
    }

    public void setGl(Gl g) {
        gl = g;
    }

    public void init(Context c) {
        ctx = c.getApplicationContext();
        data = new Data(ctx);
        data.load();
        world.mode = 0;
        buildParams();
        world.applyCfg(world.cfg());
        world.reset();
    }

    public void setDialogHost(DialogHost h) {
        host = h;
    }

    // =====================================================================
    // 参数行
    // =====================================================================
    private void buildParams() {
        Map<String, Double> cfg = world.cfg();
        labels = World.LABELS[world.mode];
        keys = World.KEYS[world.mode];
        texts = new String[keys.length];
        values = new double[keys.length];
        int i = 0;
        for (Map.Entry<String, Double> e : cfg.entrySet()) {
            if (i >= keys.length) break;
            values[i] = e.getValue();
            texts[i] = fmtInitial(e.getKey(), e.getValue());
            i++;
        }
    }

    /** 桌面版是 str(v)：整数参数给整数，小数参数至少留一位（20.5 / 1.8 / -40.0 / 0.85） */
    private String fmt(double v, boolean isInt) {
        if (isInt) return String.valueOf((long) Math.round(v));
        double r = Math.round(v * 100) / 100.0;
        String s = String.format(Locale.US, "%.2f", r);
        while (s.endsWith("0") && !s.endsWith(".0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 初始值照抄桌面版 cfg 里的字面量：int 写法显示成整数（间隔 90 / HP 5），float 写法留一位（20.0 / 1.0） */
    private static final java.util.Set<String> INT_LITERAL = new java.util.HashSet<>(java.util.Arrays.asList(
            "spawn_interval", "max_hp", "hurt_duration", "player_x", "anim_speed", "jitter"));

    private String fmtInitial(String key, double v) {
        boolean integral = Math.abs(v - Math.round(v)) < 1e-9;
        if (integral && INT_LITERAL.contains(key)) return String.valueOf((long) Math.round(v));
        return fmt(v, false);
    }

    private void commitParam(int i, double v) {
        v = Math.max(-9999.5, Math.min(9999.5, v));
        String k = keys[i];
        values[i] = v;
        texts[i] = fmt(v, World.isIntKey(k));
        world.cfg().put(k, v);
    }

    // =====================================================================
    // 尺寸相关布局
    // =====================================================================
    private float lineGap() {
        int n = keys.length;
        float topY = 210f, bottomY = Design.H - 140f;
        return Math.min(60f, (bottomY - topY) / Math.max(1, n));
    }

    private float rowY(int i) {
        return 210f + i * lineGap() + 22f;
    }

    private float col1X() {
        return 335f + Design.cx();
    }

    private float col2X() {
        return 810f + Design.cx();
    }

    private float col3X() {
        return 1095f + Design.cx();
    }

    private float boxX() {
        return 1149f + Design.cx();
    }

    private float plusX() {
        return 1439f + Design.cx();
    }

    private float resetX() {
        return 1503f + Design.cx();
    }

    private float btnY() {
        return Design.H - 140f + 20f;
    }

    private float groupLeft() {
        return 202.5f + Design.cx();
    }

    // =====================================================================
    // 绘制基础
    // =====================================================================
    /** 当前页的模糊底图（玻璃按钮从它切片）与这一页的压暗色，和电脑版 draw_ios_glass 同一套做法 */
    private int pageBlurTex;
    private int pageDim;
    private int lastGlGen = -1;

    private void setPageBg(int blurTex, int dim) {
        pageBlurTex = blurTex;
        pageDim = dim;
    }

    /** 模糊档位乘到页面基准半径上：设置页切「弱/中/强」整页都要跟着变，否则档位就是空转 */
    private float pageBlur() {
        return PAGE_BLUR * BLUR_MULS[blurLevel];
    }

    private void glass(float x, float y, float w, float h, boolean pressed, boolean dark) {
        glassA(x, y, w, h, pressed, dark, 132);
    }

    /**
     * 玻璃块，a 就是电脑版 GlassButton.glass_alpha（默认 132；暂停页 225、游戏内暂停 178）。
     * 底 = 模糊层切片，面 = 叠色，边 = 一圈高光：缺了切片就是一块死灰，边上的高光也就没了。
     */
    private void glassA(float x, float y, float w, float h, boolean pressed, boolean dark, int a) {
        if (pageBlurTex != 0) {
            gl.drawTexUV(pageBlurTex, x, y, w, h,
                    x / Design.W, y / Design.H, (x + w) / Design.W, (y + h) / Design.H, false);
            if (pageDim != 0) gl.fill(x + 1f, y + 1f, w - 2f, h - 2f, pageDim);
        }
        // 电脑版的叠色层只铺内部：1px 边留着「模糊切片 + 白描边」，边上那圈高光才是亮的
        gl.fill(x + 1f, y + 1f, w - 2f, h - 2f, glassColor(pressed, dark, a));
        gl.stroke(x, y, w, h, dark ? (a == 132 ? RIM_DARK : RIM_DARK_BIG) : RIM_LIGHT, 1f);
    }

    /** 和电脑版 _glass_fill_color 完全同一套取值 */
    private int glassColor(boolean pressed, boolean dark, int a) {
        if (dark) {
            if (a == 132) return pressed ? 0xE2141923 : 0xC41E232D;
            int al = pressed ? Math.min(255, a + 25) : a;
            return (al << 24) | (pressed ? 0x58606E : 0x48505E);
        }
        int al = pressed ? Math.min(255, a + 33) : a;
        return (al << 24) | 0xFCFCFE;
    }

    private void btn(float x, float y, float w, float h, String text, int size, boolean dark,
                     boolean pressed, boolean selected, int textColor) {
        btnA(x, y, w, h, text, size, dark, pressed, selected, textColor, 132);
    }

    private void btnA(float x, float y, float w, float h, String text, int size, boolean dark,
                      boolean pressed, boolean selected, int textColor, int alpha) {
        glassA(x, y, w, h, pressed, dark, alpha);
        if (selected) {
            // 电脑版选中态：先铺一层很淡的白，再套 2px 半透明细框
            gl.fill(x, y, w, h, 0x22FFFFFF);
            gl.stroke(x, y, w, h, 0xB2FFFFFF, 2f);
        }
        // 亮玻璃铺白字会糊，电脑版也是直接换深色字；文字整体比几何中心低 1px
        gl.textCenter(text, x + w / 2f, y + h / 2f + 1f, size, dark ? textColor : 0xFF1E2228);
    }

    private void bgPage(int dimColor) {
        int tex = gl.bgBlur(pageBlur());
        gl.drawTex(tex, 0, 0, Design.W, Design.H, false);
        gl.fill(0, 0, Design.W, Design.H, dimColor);
        setPageBg(tex, dimColor);
    }

    /**
     * 只铺模糊底、不叠压暗：电脑版 get_tinted_blur 的 tint 是加色（黑色等于没加），
     * 页面压暗靠自己那层 ui_fill_alpha 单独画，玻璃切片里也不带压暗。
     */
    private void bgPageRaw() {
        int tex = gl.bgBlur(pageBlur());
        gl.drawTex(tex, 0, 0, Design.W, Design.H, false);
        setPageBg(tex, 0);
    }

    // =====================================================================
    // 每帧
    // =====================================================================
    public void update(float dt) {
        if (st == St.LOCK && lockErr > 0) lockErr -= 1;
    }

    public void draw() {
        // GL 上下文重建后，手里的纹理 id 全成了垃圾，全部重做
        if (lastGlGen != gl.gen) {
            lastGlGen = gl.gen;
            eraseTex = 0;
            pageBlurTex = 0;
            pageDim = 0;
            statsBgTex = 0;
            confirmBgTex = 0;
            gl.invalidateBgBlur();
        }
        gl.beginFrame();
        switch (st) {
            case LOCK: drawLock(); break;
            case SETUP: drawSetup(); break;
            case GAME:
                gl.beginScene();
                drawGame();
                gl.endScene();
                gl.drawScene();
                // 控件各自从场景纹理 1:1 取一块做磨砂，不再整页糊一张 960×540 再放大（省一次整页 blur）
                setPageBg(0, 0);
                drawGameUi();
                break;
            case PAUSED: drawPaused(); break;
            case GAMEOVER: drawGameOver(); break;
            case SETTINGS: drawSettings(); break;
            case KEYLAYOUT: drawKeyLayout(); break;
        }
    }

    // ---------------- 锁屏 ----------------
    private void drawLock() {
        // 桌面版锁屏：模糊底 + (4,6,10,140) 夜间压暗，再在 draw_lock_screen 里叠 (0,0,0,30)
        bgPage(0x8C04060A);
        gl.fill(0, 0, Design.W, Design.H, 0x1E000000);
        Calendar cal = Calendar.getInstance();
        String hhmm = String.format(Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        gl.text(hhmm, Design.W / 2f, 52f, 96, C_WHITE, Gl.ALIGN_CENTER);
        String date = (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH) + "日 星期"
                + "日一二三四五六".charAt(cal.get(Calendar.DAY_OF_WEEK) - 1);
        gl.text(date, Design.W / 2f, 178f, 24, 0xFFEBEBEB, Gl.ALIGN_CENTER);

        long now = System.currentTimeMillis();
        if (data.dis > 0) {
            gl.fill(0, 0, Design.W, Design.H, 0x5F000000);
            gl.text("游戏已停用", Design.W / 2f, 380f, 96, 0xFFD7D7D7, Gl.ALIGN_CENTER);
            return;
        }
        if (data.until > now / 1000.0) {
            gl.fill(0, 0, Design.W, Design.H, 0x5F000000);
            gl.text("游戏已锁定", Design.W / 2f, 300f, 32, 0xFFBEBEBE, Gl.ALIGN_CENTER);
            long left = (long) (data.until - now / 1000.0) + 1;
            String big = data.pen >= 3600 ? "1 小时"
                    : (data.pen >= 60 ? (data.pen / 60) + " 分钟" : data.pen + " 秒");
            gl.text(big, Design.W / 2f, 430f, 96, 0xFFD7D7D7, Gl.ALIGN_CENTER);
            if (lockShowUntil > now) {
                String remain = left >= 60 ? (left / 60) + ":" + String.format(Locale.US, "%02d", left % 60) : left + " 秒";
                gl.text(remain, Design.W / 2f, 540f, 24, C_SUB, Gl.ALIGN_CENTER);
            }
            return;
        }

        gl.text("输入密码", Design.W / 2f, 300f, 32, C_WHITE, Gl.ALIGN_CENTER);
        float shake = lockErr > 0 ? (float) Math.sin(lockErr * 0.9f) * 12f : 0f;
        float fx = 669f + Design.cx() + shake, fy = 412f, fw = 582f, fh = 92f;
        gl.fill(fx, fy, fw, fh, 0x2AFFFFFF);
        gl.stroke(fx, fy, fw, fh, lockErr > 0 ? 0xFFFF5A5A : 0x6EFFFFFF, 1.6f);

        float step = 26f;
        float innerL = fx + 22f;
        float cy = fy + fh / 2f;
        int maxn = Math.max(1, (int) ((fw - 60f) / step));
        int n = lockInput.length();
        int skip = Math.max(0, n - maxn);
        for (int i = skip; i < n; i++) {
            // 桌面版按星号墨迹垂直居中（星号在字形框里偏上），这里同样用墨迹对齐
            gl.textInk("*", innerL + (i - skip) * step, cy, 48, C_WHITE, false);
        }
        if ((now / 500) % 2 == 0) {
            gl.fill(innerL + (n - skip) * step + 3f, cy - 22f, 3f, 44f, C_WHITE);
        }

        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "好"};
        for (int i = 0; i < 12; i++) {
            String code = "好".equals(labels[i]) ? "ok" : (labels[i].isEmpty() ? "del" : labels[i]);
            boolean show = !(("ok".equals(code) || "del".equals(code)) && lockInput.isEmpty());
            if (!show) continue;
            float kx = 669f + Design.cx() + (i % 3) * 206f;
            float ky = 552f + (i / 3) * 118f;
            boolean accent = "ok".equals(code);
            gl.fill(kx, ky, 170f, 92f, accent
                    ? (pressedKey == i ? KEY_ACCENT_PRESS : KEY_ACCENT)
                    : (pressedKey == i ? KEY_FILL_PRESS : KEY_FILL));
            gl.stroke(kx, ky, 170f, 92f, KEY_RIM, 1.5f);
            if ("del".equals(code)) {
                if (eraseTex == 0) eraseTex = gl.texture(Assets.eraseGlyph(62f));
                float ew = 62f * 1.42f + 62f * 0.24f, eh = 62f * 0.775f + 62f * 0.24f;
                gl.drawTex(eraseTex, kx + 85f - ew / 2f, ky + 46f - eh / 2f, ew, eh, true);
            } else {
                gl.textCenter(labels[i], kx + 85f, ky + 46f, labels[i].length() == 1 ? 48 : 32, C_WHITE);
            }
        }
    }

    private int eraseTex = 0;
    private int statsBgTex = 0, confirmBgTex = 0;

    // ---------------- 主菜单 ----------------
    private void drawSetup() {
        bgPageRaw();
        // 桌面版 draw_setup_ui 先整页压 (6,8,12,105)，玻璃按钮切的是没压暗的模糊层，所以按钮比底亮
        gl.fill(0, 0, Design.W, Design.H, 0x6906080C);

        gl.text("自定义本局参数 (-9999.5 ~ 9999.5)", Design.W / 2f, 30f, 48, C_WHITE, Gl.ALIGN_CENTER);
        String[] info = {
                "当前游戏模式影响键位和玩法",
                "【跑步鸡】空格/▲跳跃，Shift/▼下蹲，障碍物向你飞来",
                "【滑步鸡】A/D 移动，空格/▲跳跃，Shift/▼下蹲",
                "【吴国鸡】A/D 移动，空格/▲跳跃，Shift/▼下蹲"
        };
        for (int i = 0; i < info.length; i++) {
            gl.text(info[i], Design.W / 2f, 85f + i * 26f, 24, C_GRAY, Gl.ALIGN_CENTER);
        }

        for (int i = 0; i < keys.length; i++) {
            float cy = rowY(i);
            drawSlider(i, cy);
            gl.textLeft(labels[i], col2X(), cy, 32, C_WHITE);
            btn(col3X(), cy - 22f, 44f, 44f, "-", 32, true, pressedBtn == 100 + i, false, C_WHITE);
            float bx = boxX();
            glassA(bx, cy - 22f, 280f, 44f, false, true, 132);
            gl.textLeft(texts[i], bx + 15f, cy, 26, C_WHITE);
            btn(plusX(), cy - 22f, 44f, 44f, "+", 32, true, pressedBtn == 200 + i, false, C_WHITE);
            btn(resetX(), cy - 16f, 64f, 32f, "重置", 28, true, pressedBtn == 300 + i, false, C_WHITE);
        }

        float by = btnY(), gl0 = groupLeft();
        btn(gl0, by, 360f, 80f, "开始游戏", 48, false, pressedBtn == 1, false, C_WHITE);
        btn(gl0 + 385f, by, 360f, 80f, "一键全部重置", 32, false, pressedBtn == 2, false, C_WHITE);
        btn(gl0 + 770f, by, 360f, 80f, "设置", 32, false, pressedBtn == 3, false, C_WHITE);
        btn(gl0 + 1155f, by, 360f, 80f, "退出游戏", 32, false, pressedBtn == 4, false, C_WHITE);

        btn(1570f + Design.dx(), 40, 300, 60, MODE_NAMES[world.mode], 32, false, pressedBtn == 5, false, C_WHITE);
        btn(1570f + Design.dx(), 112, 300, 60, "游戏数据", 32, false, pressedBtn == 6, false, C_WHITE);

        if (statsOpen) drawStats();
    }

    private void drawSlider(int i, float cy) {
        float x0 = col1X(), w = 450f;
        glassA(x0, cy - 11f, w, 22f, false, true, 132);
        float kx = x0 + (float) ((values[i] + 9999.5) / 19999.0) * w;
        if (kx > x0) gl.fill(x0, cy - 11f, kx - x0, 22f, 0xC8FFFFFF);
        gl.fill(kx - 3f, cy - 9f, 10f, 26f, 0x3C000000);
        gl.fill(kx - 5f, cy - 13f, 10f, 26f, C_WHITE);
        gl.stroke(kx - 5f, cy - 13f, 10f, 26f, 0xFF969696, 1f);
    }

    private void drawStats() {
        // 桌面版：数据页背景 = 当前整页（主菜单连 UI 一起）回读后按页面档模糊，只算一次并缓存，
        // 再叠 (0,0,0,168)。所以这里不能拿纯场景模糊当底 —— 那样看不到菜单控件的鬼影。
        if (statsBgTex == 0) statsBgTex = gl.blurFrame(0, pageBlur());
        gl.drawTex(statsBgTex, 0, 0, Design.W, Design.H, false);
        gl.fill(0, 0, Design.W, Design.H, 0xA8000000);
        gl.fill(Design.W / 2f - 470f, 214f, 940f, 544f, 0x1EFFFFFF);
        gl.textCenter("游戏数据", Design.W / 2f, 150f, 48, C_WHITE);
        gl.textCenter("分数", Design.W / 2f + 120f, 255f, 32, 0xFFC8C8C8);
        gl.textCenter("金币", Design.W / 2f + 330f, 255f, 32, 0xFFC8C8C8);
        String[] names = {"跑步鸡", "滑步鸡", "吴国鸡"};
        int[] s = {data.s0, data.s1, data.s2};
        int[] c = {-1, data.c1, data.c2};
        for (int i = 0; i < 3; i++) {
            float ly = 360f + i * 150f;
            gl.textCenter(names[i], Design.W / 2f - 330f, ly, 48, C_WHITE);
            gl.textCenter(String.valueOf(s[i]), Design.W / 2f + 120f, ly, 48, C_GOLD);
            if (c[i] < 0) gl.textCenter("无", Design.W / 2f + 330f, ly, 32, 0xFF969696);
            else gl.textCenter(String.valueOf(c[i]), Design.W / 2f + 330f, ly, 48, C_GOLD);
            if (i < 2) gl.fill(Design.W / 2f - 430f, ly + 70f, 860f, 1f, 0x28FFFFFF);
        }
        btn(Design.W / 2f - 400f, 830, 360, 80, "关闭", 32, false, pressedBtn == 20, false, C_WHITE);
        btn(Design.W / 2f + 40f, 830, 360, 80, "清除游戏数据", 32, true, pressedBtn == 21, false, 0xFFFF8C8C);

        if (statsConfirm) {
            // 确认层背景 = 数据页整页（含面板和按钮）的模糊版，只回读一次就缓存（桌面版同样置空重算）
            if (confirmBgTex == 0) confirmBgTex = gl.blurFrame(1, pageBlur());
            gl.drawTex(confirmBgTex, 0, 0, Design.W, Design.H, false);
            gl.fill(0, 0, Design.W, Design.H, 0x7D050505);
            float px = Design.W / 2f - 430f, py = 396f;
            // 面板 = 一整块玻璃：底从刚才那张整页模糊切片，面叠淡白 5（同桌面 light_alpha=5），边一圈高光
            setPageBg(confirmBgTex, 0);
            glassA(px, py, 860f, 344f, false, false, 5);
            gl.textCenter("清除游戏数据？", Design.W / 2f, 466f, 48, 0xFFFF7E7E);
            gl.textCenter("三个模式的最高分和金币会归零。", Design.W / 2f, 534f, 32, 0xFFE1E1E1);
            gl.textCenter("不影响其他数据", Design.W / 2f, 574f, 32, 0xFF969696);
            // 两个按钮照抄数据页那一对：取消 = 亮玻璃（同「关闭」），确认清除 = 暗玻璃 + 红字
            btn(px + 100f, 620f, 300f, 90f, "取消", 32, false, pressedBtn == 22, false, C_WHITE);
            btn(px + 460f, 620f, 300f, 90f, "确认清除", 32, true, pressedBtn == 23, false, 0xFFFF7E7E);
        }
    }

    // ---------------- 游戏 ----------------
    private void drawGame() {
        int bg = gl.texture(Assets.bg());
        // 背景按桌面那一格 1920×1017 铺底，宽屏只是多铺几格（多看到点风景），整体不拉伸
        float tw = Design.BASE_W, th = Design.H;
        float bxp = (world.mode == 2) ? -((int) world.camX % Design.BASE_W) : world.bgX;
        while (bxp > 0) bxp -= tw;
        while (bxp < Design.W) {
            gl.drawTex(bg, bxp, 0, tw, th, true);
            bxp += tw;
        }

        float cam = world.camOffset();
        for (World.Coin c : world.coinList) {
            float cx = c.x - cam;
            if (cx < -160 || cx > Design.W) continue;
            gl.drawTex(gl.texture(Assets.coin), cx, c.y, 150, 150, true);
        }
        for (World.Obs o : world.obsList) {
            float ox = o.x - cam;
            if (ox > Design.W || ox + o.w < 0) continue;
            gl.drawTex(gl.texture(o.img), ox, o.y, o.w, o.h, true);
        }
        drawPlayer();

        for (World.Popup p : world.popups) {
            // 桌面版是 blit 到 (x-w/2, y-55)：y 是字面顶部，不是中心
            gl.text(p.text, p.x - cam, p.y - 55f, 32, C_GOLD, Gl.ALIGN_CENTER);
        }
        // HUD 每帧都变，数字用单字形拼（gl.textInt）：不再每变一个分数就新建一张文字纹理
        gl.textInt("分数:", (int) world.score, 30f, 30f, 32, C_WHITE);
        gl.textInt2("HP:", world.hp, "/", (int) world.maxHp, 30f, 80f, 32, C_RED);
        if (world.mode != 0) gl.textInt("金币:", world.coinCnt, 30f, 130f, 32, C_GOLD);
    }

    private void drawPlayer() {
        boolean hurt = world.hurtTimer > 0 || st == St.GAMEOVER;
        Bitmap img;
        if (world.crouching) {
            img = hurt ? (world.facingRight ? Assets.hurtSitR : Assets.hurtSitL)
                    : (world.facingRight ? Assets.sitR : Assets.sitL);
        } else {
            Bitmap[] arr = hurt ? (world.facingRight ? Assets.hurtR : Assets.hurtL)
                    : (world.facingRight ? Assets.framesR : Assets.framesL);
            int idx = arr.length == 0 ? 0 : ((world.animIndex % arr.length) + arr.length) % arr.length;
            img = arr[idx];
        }
        float x = world.playerX - world.camOffset();
        if (st == St.GAMEOVER) {
            // 桌面版死亡时把人顺时针放倒 90°
            float siw = img.getWidth(), sih = img.getHeight();
            gl.drawTexRot90(gl.texture(img), x, world.playerY + sih - siw, sih, siw);
        } else {
            gl.drawTex(gl.texture(img), x, world.playerY, img.getWidth(), img.getHeight(), true);
        }
    }

    private void drawGameUi() {
        // Alpha 178 对应电脑版游戏内暂停键的 glass_alpha；触屏键 132
        gameBtn(Design.W - 160f, 20f, 140f, 60f, "暂停", 32, pressedBtn == 30, PAUSE_BLUR_PX, 178);
        float[] jr = touchRect(2);
        float[] cr = touchRect(3);
        if (world.mode != 0) {
            touchBtn(touchRect(0), "◀", 0);
            touchBtn(touchRect(1), "▶", 1);
        }
        touchBtn(jr, "▲", 2);
        touchBtn(cr, "▼", 3);
    }

    /**
     * 游戏内控件：先从场景纹理按自身矩形 1:1 截一块做磨砂（半径 ×档位，对应电脑版 gpu_frost），
     * 再叠玻璃色 + 1px 高光边。磨砂是原分辨率算的，贴回屏幕不放大，所以没有一块一块的颗粒。
     */
    private void gameBtn(float x, float y, float w, float h, String label, int size, boolean pressed,
                         float radiusPx, int alpha) {
        float r = radiusPx * BLUR_MULS[blurLevel];
        int id = gl.frostRegion(x, y, w, h, r);
        if (id != 0) gl.drawFrost(id, x, y, w, h);
        gl.fill(x + 1f, y + 1f, w - 2f, h - 2f, glassColor(pressed, false, alpha));
        gl.stroke(x, y, w, h, RIM_LIGHT, 1f);
        gl.textCenter(label, x + w / 2f, y + h / 2f + 1f, size, 0xFF1E2228);
    }

    /**
     * 触屏键位矩形（画布坐标）。存档里存的是 1920×1017 基准坐标，右侧两键（跳跃/蹲下）
     * 在比桌面更宽的屏上整体右移 dx，继续贴着屏幕右沿；左移的那两个键不偏移。
     * 返回的是复用的数组（每帧 4 次调用不产生垃圾），只读、别存起来。
     */
    private final float[][] rectScratch = new float[4][4];

    private float[] touchRect(int k) {
        int[] v = data.keys[k];
        float shift = k >= 2 ? Design.dx() : 0f;
        float[] r = rectScratch[k];
        r[0] = Math.max(0f, Math.min(Design.W - v[2], v[0] + shift));
        r[1] = v[1];
        r[2] = v[2];
        r[3] = v[3];
        return r;
    }

    /** 某个键位在画布上的横向偏移（拖完写回存档时要减掉） */
    private float keyShift(int k) {
        return k >= 2 ? Design.dx() : 0f;
    }

    private void touchBtn(float[] r, String label, int idx) {
        int size = Math.max(18, (int) Math.round(Math.min(r[2], r[3]) * 0.32));
        gameBtn(r[0], r[1], r[2], r[3], label, size, pressedBtn == 40 + idx, CTRL_BLUR_PX, 132);
    }

    private void drawPaused() {
        gl.beginScene();
        drawGame();
        gl.endScene();
        int blurTex = gl.blurScene(pageBlur());
        gl.drawTex(blurTex, 0, 0, Design.W, Design.H, false);
        gl.fill(0, 0, Design.W, Design.H, 0x9606080C);
        setPageBg(blurTex, 0);
        gl.textCenter("游戏暂停", Design.W / 2f, Design.H / 2f - 200f, 48, C_WHITE);
        btnA(Design.W / 2f - 150f, Design.H / 2f - 120f, 300, 80, "继续游戏", 32, true, pressedBtn == 31, false, C_WHITE, 225);
        btnA(Design.W / 2f - 150f, Design.H / 2f - 20f, 300, 80, "设置", 32, true, pressedBtn == 32, false, C_WHITE, 225);
        btnA(Design.W / 2f - 150f, Design.H / 2f + 80f, 300, 80, "返回主菜单", 32, true, pressedBtn == 33, false, C_WHITE, 225);
    }

    private void drawGameOver() {
        gl.beginScene();
        drawGame();
        gl.endScene();
        int blurTex = gl.blurScene(pageBlur());
        gl.drawTex(blurTex, 0, 0, Design.W, Design.H, false);
        // 电脑版 gameover 的 tint 是加色 (26,0,0,40)：底图先加一点红，再压暗
        gl.fillAdd(0, 0, Design.W, Design.H, 26f / 255f, 0f, 0f, 1f);
        gl.fill(0, 0, Design.W, Design.H, 0x9B080306);
        setPageBg(blurTex, 0);
        gl.textCenter("你死了", Design.W / 2f, Design.H / 2f - 50f, 48, C_WHITE);
        String result = world.mode == 0 ? "分数:" + (int) world.score
                : "分数:" + (int) world.score + "  金币:" + world.coinCnt;
        gl.textCenter(result, Design.W / 2f, Design.H / 2f, 32, C_WHITE);
        btnA(Design.W / 2f - 130f, Design.H / 2f + 100f, 260, 90, "重试", 32, true, pressedBtn == 34, false, C_WHITE, 225);
    }

    private void drawGameWorldScene() {
        drawGame();
    }

    // ---------------- 设置 ----------------
    /**
     * 设置页右侧整块（卡片 + 控件）的横向偏移。
     * 桌面版画布 1920 时右边一整块是 620~1880，靠右但左右留边基本等分（60 / 40）；
     * 宽屏画布 W 比 1920 宽，多出来的宽度按左右均分补上，整块相对画布居中，
     * 而不是全灌进左列与卡片之间的缝。W == 1920（16:9 取裁）时返回 0，和桌面逐像素一致。
     */
    private float setX() {
        return Design.cx();
    }

    private void drawSettings() {
        // 桌面版：模糊层只做加色（黑色不变），压暗全靠下面这层 (8,10,14,210)
        bgPageRaw();
        gl.fill(0, 0, Design.W, Design.H, 0xD2080A0E);
        gl.fill(0, 0, 560f, Design.H, 0x2D000000);
        gl.fill(560f, 0, 1f, Design.H, 0x2DFFFFFF);

        gl.textLeft("设置", 60f, 96f, 48, C_WHITE);
        for (int i = 0; i < TABS.length; i++) {
            float y = 190f + i * 104f;
            if (i == settingsTab) gl.fill(30f, y, 500f, 92f, 0x37FFFFFF);
            gl.textLeft(TABS[i], 60f, y + 46f, 32, i == settingsTab ? C_WHITE : 0xFFDEDEDE);
        }
        String tab = TABS[settingsTab];
        // 左边那一竖列贴着屏幕左沿；右边整块（卡片 + 控件）在画布里居中——宽屏多出来的宽度左右均分，
        // 不再全灌进左列和卡片之间的缝里，否则卡片会贴着屏幕右沿，里面的选项看着全偏右
        float dx = setX();
        gl.textLeft(tab, 620f + dx, 96f, 48, C_WHITE);

        boolean inGame = prev == St.PAUSED;
        if ("控制设置".equals(tab)) {
            gl.fill(620f + dx, 220f, 1260f, 300f, 0x1AFFFFFF);
            gl.textLeft("操作方式", 660f + dx, 300f, 32, C_WHITE);
            // 电脑版两个选项都可点、都走 style_option（暗玻璃+白字，选中加白框）
            btn(1010f + dx, 330f, 380f, 110f, "键盘操控", 48, true, pressedBtn == 48, inputMode == 0, C_WHITE);
            btn(1420f + dx, 330f, 380f, 110f, "触屏操控", 48, true, pressedBtn == 49, inputMode == 1, C_WHITE);
            gl.textLeft("键盘：空格跳跃，A/D 移动，Shift下蹲    触屏：方向键 + ▲跳跃/ ▼下蹲", 660f + dx, 588f, 24, 0xFFD7D7D7);
            if (inputMode == 1) {
                gl.fill(620f + dx, 620f, 1260f, 140f, 0x1AFFFFFF);
                gl.textLeft("触屏键位", 660f + dx, 690f, 32, C_WHITE);
                btn(1010f + dx, 648f, 380f, 96f, "键位控制", 32, true, pressedBtn == 50, false, C_WHITE);
            }
        } else if ("模糊设置".equals(tab)) {
            gl.fill(620f + dx, 220f, 1260f, 300f, 0x1AFFFFFF);
            gl.textLeft("模糊强度", 660f + dx, 300f, 32, C_WHITE);
            for (int i = 0; i < 3; i++) {
                btn(660f + dx + i * 400f, 330f, 380f, 110f, BLUR_NAMES[i], 48, true,
                        pressedBtn == 60 + i, i == blurLevel, C_WHITE);
            }
            gl.textLeft("弱 0.45×    中 1.0×    强 1.65×    控制全局模糊", 660f + dx, 588f, 24, 0xFFD7D7D7);
        } else if ("参数设置".equals(tab)) {
            if (!inGame) {
                gl.fill(620f + dx, 220f, 1260f, 300f, 0x1AFFFFFF);
                gl.textCenter("仅在游戏内可用", 1250f + dx, 330f, 48, 0xFFCDCDCD);
                gl.textCenter("进入游戏后从暂停页打开设置，就能边玩边调，改完立即生效", 1250f + dx, 400f, 24, 0xFFCDCDCD);
            } else {
                drawIngameParams();
            }
        } else if ("作弊选项".equals(tab)) {
            gl.fill(620f + dx, 220f, 1260f, 336f, 0x1AFFFFFF);
            drawCheatRow("无碰撞体积", "穿过障碍物，不判定撞击", world.noCollision, 1560f + dx, 250f, 70);
            drawCheatRow("无敌不掉血", "撞到障碍物不扣血", world.noDamage, 1560f + dx, 415f, 71);
            gl.fill(660f + dx, 380f, 1180f, 1f, 0x23FFFFFF);
            gl.textLeft("选项立即生效", 660f + dx, 600f, 24, 0xFFCDCDCD);
            boolean warn = inGame ? runCheated : (world.noCollision || world.noDamage);
            if (warn) {
                gl.textLeft(inGame ? "本局分数将不会保存" : "新的一局分数将不会保存",
                        660f + dx, 658f, 32, C_WARN);
            }
        } else {
            gl.fill(620f + dx, 214f, 1260f, 330f, 0x1AFFFFFF);
            gl.textLeft("渲染器选择", 660f + dx, 258f, 32, C_WHITE);
            btn(660f + dx, 292f, 380f, 104f, "OpenGL", 48, true, false, true, C_WHITE);
            btn(1060f + dx, 292f, 380f, 104f, "Vulkan", 48, true, false, false, 0xFFA5A5A5);
            btn(1460f + dx, 292f, 380f, 104f, "软件加速", 48, true, false, false, 0xFFA5A5A5);
            String[] desc = {
                    "OpenGL：支持更多画面效果，硬件加速",
                    "Vulkan：性能相较于OpenGL更优",
                    "软件加速：基础加速，基础画面效果"
            };
            for (int i = 0; i < desc.length; i++) {
                gl.textLeft(desc[i], 660f + dx, 436f + i * 28f, 24, 0xFFC8C8C8);
            }
            gl.textLeft("切换后重启游戏生效；命令行设置 WUGUO_RENDER 会覆盖此处的选择", 660f + dx, 588f, 24, 0xFFD7D7D7);

            gl.fill(620f + dx, 616f, 1260f, 232f, 0x1AFFFFFF);
            String[] k = {"渲染器", "待生效", "显卡", "GL 版本", "模糊引擎", "设计分辨率", "窗口尺寸", "模糊"};
            String[] v = {"OpenGL ES", "无", glRendererName(), glVersionShort(),
                    "GLSL 双可分离高斯", ((int) Design.W) + "×" + ((int) Design.H),
                    glScreenSize(), BLUR_NAMES[blurLevel] + " " + BLUR_MULS[blurLevel] + "×"};
            for (int i = 0; i < k.length; i++) {
                int col = i % 2, row = i / 2;
                float cx = 660f + dx + col * 640f;
                float cy = 660f + row * 46f;
                float maxRight = cx + (col == 0 ? 620f : 600f);
                gl.textLeft(k[i], cx, cy, 24, 0xFFBEBEBE);
                drawClipped(v[i], cx + 170f, cy, 24, C_WHITE, maxRight);
            }
        }
        btn(60f, 880f, 460f, 80f, "返回", 32, true, pressedBtn == 51, false, C_WHITE);
    }

    private void drawIngameParams() {
        // 电脑版先画「全部重置」再铺卡片（两者不重叠）
        float dx = setX();
        btn(1600f + dx, 96f, 260f, 64f, "全部重置", 32, true, pressedBtn == 695, false, C_WHITE);
        gl.fill(620f + dx, 190f, 1260f, 700f, 0x1AFFFFFF);
        for (int i = 0; i < keys.length; i++) {
            float cy = 216f + i * 47f + 17f;
            gl.textLeft(labels[i], 650f + dx, cy, 24, C_WHITE);
            float sx = 1020f + dx, sw = 300f;
            glassA(sx, cy - 11f, sw, 22f, false, true, 132);
            float kx = sx + (float) ((values[i] + 9999.5) / 19999.0) * sw;
            if (kx > sx) gl.fill(sx, cy - 11f, kx - sx, 22f, 0xC8FFFFFF);
            gl.fill(kx - 3f, cy - 9f, 10f, 26f, 0x3C000000);
            gl.fill(kx - 5f, cy - 13f, 10f, 26f, C_WHITE);
            gl.stroke(kx - 5f, cy - 13f, 10f, 26f, 0xFF969696, 1f);
            btn(1330f + dx, cy - 17f, 36f, 34f, "-", 26, true, pressedBtn == 400 + i, false, C_WHITE);
            glassA(1372f + dx, cy - 17f, 140f, 34f, false, true, 132);
            gl.textLeft(texts[i], 1387f + dx, cy, 26, C_WHITE);
            btn(1518f + dx, cy - 17f, 36f, 34f, "+", 26, true, pressedBtn == 500 + i, false, C_WHITE);
            btn(1740f + dx, cy - 16f, 90f, 32f, "重置", 26, true, pressedBtn == 600 + i, false, C_WHITE);
        }
        gl.textLeft("改完立即生效，同步主菜单设置页", 650f + dx, 915f, 24, 0xFFCDCDCD);
    }

    /** 超宽的一行字按像素宽度截断加省略号，免得顶出屏幕 */
    private void drawClipped(String s, float x, float y, int size, int color, float maxRight) {
        if (s == null) return;
        float w = gl.textWidth(s, size);
        if (x + w <= maxRight) {
            gl.textLeft(s, x, y, size, color);
            return;
        }
        int n = s.length();
        while (n > 1 && x + gl.textWidth(s.substring(0, n) + "…", size) > maxRight) n--;
        gl.textLeft(s.substring(0, n) + "…", x, y, size, color);
    }

    private void drawCheatRow(String label, String desc, boolean on, float bx, float by, int id) {
        float dx = setX();
        gl.textLeft(label, 680f + dx, by + 30f, 32, C_WHITE);
        gl.textLeft(desc, 680f + dx, by + 76f, 24, 0xFFD2D2D2);
        btn(bx, by, 240f, 96f, on ? "开" : "关", 32, true, pressedBtn == id, false,
                on ? 0xFF78F596 : 0xFFE1E1E1);
    }

    // ---------------- 键位编辑 ----------------
    private void drawKeyLayout() {
        // 桌面版：模糊层只做加色（黑），压暗是 (8,10,14,196)，外圈再套 1px 白 48
        bgPageRaw();
        gl.fill(0, 0, Design.W, Design.H, 0xC4080A0E);
        gl.stroke(0, 0, Design.W, Design.H, 0x30FFFFFF, 1f);
        gl.textLeft("键位控制", 60f, 66f, 48, C_WHITE);
        // 桌面版只留一行提示（kl_tips 是单元素元组，多行会一字一行往下堆），数值行紧跟其下
        String tip = "拖动方块 = 移动位置，拖右下角小方块 = 改大小";
        gl.textLeft(tip, 60f, 113f, 24, 0xFFD4D4D4);
        String[] names = {"向后", "向前", "跳跃", "蹲下"};
        String[] glyphs = {"◀", "▶", "▲", "▼"};
        for (int k = 0; k < 4; k++) {
            float[] r = touchRect(k);
            glass(r[0], r[1], r[2], r[3], false, false);
            gl.textCenter(glyphs[k], r[0] + r[2] / 2f, r[1] + r[3] / 2f,
                    Math.max(18, (int) Math.round(Math.min(r[2], r[3]) * 0.32)), 0xFF1E2228);
            if (klPick == k) gl.stroke(r[0], r[1], r[2], r[3], 0xD2FFFFFF, 2f);
            gl.fill(r[0] + r[2] - 40f, r[1] + r[3] - 40f, 40f, 40f, 0x50FFFFFF);
            gl.stroke(r[0] + r[2] - 40f, r[1] + r[3] - 40f, 40f, 40f, 0xC8FFFFFF, 1f);
        }
        String info;
        if (klPick >= 0) {
            float[] r = touchRect(klPick);
            info = names[klPick] + "：x=" + (int) r[0] + "  y=" + (int) r[1]
                    + "  宽=" + (int) r[2] + "  高=" + (int) r[3];
        } else {
            info = "点住任意方块开始编辑";
        }
        gl.textLeft(info, 60f, 154f, 32, C_GOLD);
        btn(620f + Design.cx(), 900f, 240, 80, "取消", 32, true, pressedBtn == 52, false, C_WHITE);
        btn(880f + Design.cx(), 900f, 240, 80, "恢复默认", 32, true, pressedBtn == 53, false, C_WHITE);
        btn(1140f + Design.cx(), 900f, 380, 80, "保存并返回", 32, false, pressedBtn == 54, false, C_WHITE);
    }

    // =====================================================================
    // 触摸
    // =====================================================================
    /**
     * 触摸入口。id 是 Android 的 pointerId：多指同时按时，每个手指的 DOWN / MOVE / UP
     * 都带着自己的 id 过来（Activity 把 ACTION_POINTER_DOWN/UP 也翻译成 DOWN/UP）。
     * 对局里按 id 记账，所以「按住 ▶ 的同时点 ▲」是两路同时生效的；
     * 其余页面是模态界面，只认第一根手指。
     */
    public void onTouch(int action, int id, float x, float y) {
        downX = x;
        downY = y;

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAllPointers();
            pressedBtn = -999;
            pressedKey = -1;
            primaryId = -1;
            return;
        }
        if (action == MotionEvent.ACTION_DOWN && primaryId == -1) primaryId = id;
        boolean mine = primaryId == -1 || id == primaryId;

        if (st == St.GAME) {
            touchGame(action, id, x, y);
        } else if (mine) {
            switch (st) {
                case LOCK: touchLock(action, x, y); break;
                case SETUP: touchSetup(action, x, y); break;
                case PAUSED: touchPaused(action, x, y); break;
                case GAMEOVER: touchGameOver(action, x, y); break;
                case SETTINGS: touchSettings(action, x, y); break;
                case KEYLAYOUT: touchKeyLayout(action, x, y); break;
            }
            if (action == MotionEvent.ACTION_UP) {
                pressedBtn = -999;
                pressedKey = -1;
            }
        }
        if (action == MotionEvent.ACTION_UP && id == primaryId) {
            primaryId = -1;
        }
    }

    /** 清掉所有手指的按键状态（切页面 / 暂停 / 死亡 / 应用退到后台时调用） */
    private void releaseAllPointers() {
        java.util.Arrays.fill(ptrKind, K_NONE);
        world.jumpHeld = false;
        world.crouchHeld = false;
        world.touchLeft = false;
        world.touchRight = false;
    }

    private void setPtrKind(int id, int kind) {
        if (id >= 0 && id < ptrKind.length) ptrKind[id] = kind;
    }

    private int kindOf(int id) {
        return (id >= 0 && id < ptrKind.length) ? ptrKind[id] : K_NONE;
    }

    /** 把所有手指的按键归并成世界里的四个布尔量 */
    private void syncHoldKeys() {
        boolean j = false, c = false, l = false, r = false;
        for (int k : ptrKind) {
            if (k == K_JUMP) j = true;
            else if (k == K_CROUCH) c = true;
            else if (k == K_LEFT) l = true;
            else if (k == K_RIGHT) r = true;
        }
        world.jumpHeld = j;
        world.crouchHeld = c;
        world.touchLeft = l;
        world.touchRight = r;
    }

    private static int btnOfKind(int kind) {
        switch (kind) {
            case K_JUMP: return 42;
            case K_CROUCH: return 43;
            case K_LEFT: return 40;
            case K_RIGHT: return 41;
            default: return -999;
        }
    }

    private boolean in(float x, float y, float l, float t, float w, float h) {
        return x >= l && x <= l + w && y >= t && y <= t + h;
    }

    // ---------------- 锁屏触摸 ----------------
    private void touchLock(int action, float x, float y) {
        long now = System.currentTimeMillis();
        if (data.dis > 0 || data.until > now / 1000.0) {
            if (action == MotionEvent.ACTION_UP) lockShowUntil = now + 3000;
            return;
        }
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return;
        for (int i = 0; i < 12; i++) {
            String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "好"};
            String code = "好".equals(labels[i]) ? "ok" : (labels[i].isEmpty() ? "del" : labels[i]);
            if (("ok".equals(code) || "del".equals(code)) && lockInput.isEmpty()) continue;
            float kx = 669f + Design.cx() + (i % 3) * 206f;
            float ky = 552f + (i / 3) * 118f;
            if (!in(x, y, kx, ky, 170f, 92f)) continue;
            if (action == MotionEvent.ACTION_DOWN) {
                pressedKey = i;
                if ("del".equals(code)) lockInput = lockInput.substring(0, lockInput.length() - 1);
                else if ("ok".equals(code)) submitLock();
                else if (lockInput.length() < 24) lockInput += code;
            }
            return;
        }
    }

    private void submitLock() {
        if (LOCK_TARGET.equals(lockInput)) {
            lockInput = "";
            lockErr = 0;
            data.fails = 0;
            data.until = 0;
            data.pen = 0;
            data.dis = 0;
            data.save();
            st = St.SETUP;
            return;
        }
        lockInput = "";
        lockErr = 24;
        data.fails += 1;
        int p = penalty(data.fails);
        long now = System.currentTimeMillis();
        if (p > 0) {
            data.until = now / 1000.0 + p;
            data.pen = p;
            data.dis = 0;
        } else if (p < 0) {
            data.until = 0;
            data.pen = 0;
            data.dis = 1;
        } else {
            data.until = 0;
            data.pen = 0;
            data.dis = 0;
        }
        data.save();
    }

    private int penalty(int fails) {
        if (fails < 5) return 0;
        if (fails == 5) return 60;
        if (fails == 6) return 300;
        return -1;
    }

    // ---------------- 主菜单触摸 ----------------
    private void touchSetup(int action, float x, float y) {
        if (statsOpen) {
            touchStats(action, x, y, true);
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            dragSlider = -1;
            // 滑条
            for (int i = 0; i < keys.length; i++) {
                float cy = rowY(i);
                if (in(x, y, col1X() - 10f, cy - 21f, 470f, 42f)) {
                    dragSlider = i;
                    setSliderFromX(i, x);
                    return;
                }
                if (in(x, y, boxX(), cy - 22f, 280f, 44f)) {
                    openInput(i);
                    return;
                }
                if (in(x, y, col3X(), cy - 22f, 44f, 44f)) {
                    pressedBtn = 100 + i;
                    return;
                }
                if (in(x, y, plusX(), cy - 22f, 44f, 44f)) {
                    pressedBtn = 200 + i;
                    return;
                }
                if (in(x, y, resetX(), cy - 16f, 64f, 32f)) {
                    pressedBtn = 300 + i;
                    return;
                }
            }
            float by = btnY(), gl0 = groupLeft();
            if (in(x, y, gl0, by, 360f, 80f)) pressedBtn = 1;
            else if (in(x, y, gl0 + 385f, by, 360f, 80f)) pressedBtn = 2;
            else if (in(x, y, gl0 + 770f, by, 360f, 80f)) pressedBtn = 3;
            else if (in(x, y, gl0 + 1155f, by, 360f, 80f)) pressedBtn = 4;
            else if (in(x, y, 1570f + Design.dx(), 40, 300, 60)) pressedBtn = 5;
            else if (in(x, y, 1570f + Design.dx(), 112, 300, 60)) pressedBtn = 6;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (dragSlider >= 0) setSliderFromX(dragSlider, x);
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (dragSlider >= 0) {
                dragSlider = -1;
                return;
            }
            int id = pressedBtn;
            pressedBtn = -999;
            fireSetupAction(id, x, y);
        }
    }

    private void fireSetupAction(int id, float x, float y) {
        if (id >= 100 && id < 200) {
            int i = id - 100;
            commitParam(i, values[i] - (World.isIntKey(keys[i]) ? 1 : 0.5));
        } else if (id >= 200 && id < 300) {
            int i = id - 200;
            commitParam(i, values[i] + (World.isIntKey(keys[i]) ? 1 : 0.5));
        } else if (id >= 300 && id < 400) {
            int i = id - 300;
            commitParam(i, World.DEFAULTS[world.mode].get(keys[i]));
        } else if (id == 1) {
            startGame();
        } else if (id == 2) {
            LinkedHashMap<String, Double> def = World.copy(World.DEFAULTS[world.mode]);
            world.cfg().clear();
            world.cfg().putAll(def);
            buildParams();
        } else if (id == 3) {
            prev = St.SETUP;
            st = St.SETTINGS;
        } else if (id == 4) {
            System.exit(0);
        } else if (id == 5) {
            world.mode = (world.mode + 1) % 3;
            buildParams();
        } else if (id == 6) {
            statsOpen = true;
            statsBgTex = 0;      // 打开时重拍一张整页模糊当背景（桌面版同样置空重算）
        }
    }

    private void setSliderFromX(int i, float x) {
        float ratio = Math.max(0f, Math.min(1f, (x - col1X()) / 450f));
        commitParam(i, -9999.5 + ratio * 19999.0);
    }

    private void touchStats(int action, float x, float y, boolean inSetup) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (statsConfirm) {
                if (in(x, y, Design.W / 2f - 330f, 620, 300, 90)) pressedBtn = 22;
                else if (in(x, y, Design.W / 2f + 30f, 620, 300, 90)) pressedBtn = 23;
            } else {
                if (in(x, y, Design.W / 2f - 400f, 830, 360, 80)) pressedBtn = 20;
                else if (in(x, y, Design.W / 2f + 40f, 830, 360, 80)) pressedBtn = 21;
            }
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            int id = pressedBtn;
            pressedBtn = -999;
            if (id == 20) { statsOpen = false; confirmBgTex = 0; }
            else if (id == 21) { statsConfirm = true; confirmBgTex = 0; }
            else if (id == 22) statsConfirm = false;
            else if (id == 23) {
                data.clearStats();
                statsConfirm = false;
            }
        }
    }

    private void openInput(final int i) {
        if (host == null) return;
        String k = keys[i];
        host.askNumber(labels[i], texts[i], World.isIntKey(k), !World.isIntKey(k), s -> {
            try {
                double v = Double.parseDouble(s.trim());
                commitParam(i, v);
            } catch (Exception ignored) {
            }
        });
    }

    private void startGame() {
        releaseAllPointers();
        runCheated = world.noCollision || world.noDamage;
        world.applyCfg(world.cfg());
        world.reset();
        st = St.GAME;
        for (int i = 0; i < keys.length; i++) {
            // 开局把输入框里的当前值推回配置表（桌面版 reset_game 的等价行为）
            world.cfg().put(keys[i], values[i]);
        }
        world.applyCfg(world.cfg());
        world.reset();
    }

    // ---------------- 游戏触摸（按 pointerId 记账，支持多指） ----------------
    private void touchGame(int action, int id, float x, float y) {
        float[] jr = touchRect(2), cr = touchRect(3), lr = touchRect(0), rr = touchRect(1);
        if (action == MotionEvent.ACTION_DOWN) {
            // 暂停键只认第一根手指（按住跳的时候第二根手指去点暂停也不会冲突）
            if ((primaryId == -1 || id == primaryId) && in(x, y, Design.W - 160f, 20f, 140f, 60f)) {
                releaseAllPointers();
                st = St.PAUSED;
                return;
            }
            if (in(x, y, jr[0], jr[1], jr[2], jr[3])) {
                setPtrKind(id, K_JUMP);
                syncHoldKeys();
                world.doJump();
                pressedBtn = 42;
                logTouch("down jump", id, x, y);
                return;
            }
            if (in(x, y, cr[0], cr[1], cr[2], cr[3])) {
                setPtrKind(id, K_CROUCH);
                syncHoldKeys();
                pressedBtn = 43;
                logTouch("down crouch", id, x, y);
                return;
            }
            if (world.mode != 0) {
                if (in(x, y, lr[0], lr[1], lr[2], lr[3])) {
                    setPtrKind(id, K_LEFT);
                    syncHoldKeys();
                    pressedBtn = 40;
                    logTouch("down left", id, x, y);
                    return;
                }
                if (in(x, y, rr[0], rr[1], rr[2], rr[3])) {
                    setPtrKind(id, K_RIGHT);
                    syncHoldKeys();
                    pressedBtn = 41;
                    logTouch("down right", id, x, y);
                    return;
                }
            }
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            int kind = kindOf(id);
            if (kind == K_NONE) return;
            setPtrKind(id, K_NONE);
            syncHoldKeys();
            // 松手截断跳跃（对应桌面版松空格：上升途中把速度砍半）
            if (kind == K_JUMP && world.mode != 0 && !world.jumpHeld && world.playerVy < 0) {
                world.playerVy *= 0.5f;
            }
            if (pressedBtn == btnOfKind(kind)) pressedBtn = -999;
            logTouch("up " + kind, id, x, y);
        }
    }

    /** 多指调试日志：每根手指的按下/抬起各一行（tag wuguo-touch） */
    private void logTouch(String what, int id, float x, float y) {
        android.util.Log.i("wuguo-touch", what + " pointer=" + id
                + " at " + (int) x + "," + (int) y
                + " | jump=" + world.jumpHeld + " crouch=" + world.crouchHeld
                + " L=" + world.touchLeft + " R=" + world.touchRight);
    }

    // ---------------- 暂停 / 死亡 ----------------
    private void touchPaused(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (in(x, y, Design.W / 2f - 150f, Design.H / 2f - 120f, 300, 80)) pressedBtn = 31;
            else if (in(x, y, Design.W / 2f - 150f, Design.H / 2f - 20f, 300, 80)) pressedBtn = 32;
            else if (in(x, y, Design.W / 2f - 150f, Design.H / 2f + 80f, 300, 80)) pressedBtn = 33;
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            int id = pressedBtn;
            pressedBtn = -999;
            if (id == 31) st = St.GAME;
            else if (id == 32) {
                prev = St.PAUSED;
                st = St.SETTINGS;
            } else if (id == 33) {
                st = St.SETUP;
            }
        }
    }

    private void touchGameOver(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (in(x, y, Design.W / 2f - 130f, Design.H / 2f + 100f, 260, 90)) pressedBtn = 34;
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            int id = pressedBtn;
            pressedBtn = -999;
            if (id == 34) st = St.SETUP;
        }
    }

    // ---------------- 设置触摸 ----------------
    private void touchSettings(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            for (int i = 0; i < TABS.length; i++) {
                if (in(x, y, 30f, 190f + i * 104f, 500f, 92f)) {
                    settingsTab = i;
                    return;
                }
            }
            if (in(x, y, 60f, 880f, 460f, 80f)) {
                pressedBtn = 51;
                return;
            }
            String tab = TABS[settingsTab];
            boolean inGame = prev == St.PAUSED;
            float dx = setX();
            if ("控制设置".equals(tab)) {
                if (in(x, y, 1010f + dx, 330f, 380f, 110f)) pressedBtn = 48;
                else if (in(x, y, 1420f + dx, 330f, 380f, 110f)) pressedBtn = 49;
                else if (inputMode == 1 && in(x, y, 1010f + dx, 648f, 380f, 96f)) pressedBtn = 50;
            } else if ("模糊设置".equals(tab)) {
                for (int i = 0; i < 3; i++) {
                    if (in(x, y, 660f + dx + i * 400f, 330f, 380f, 110f)) pressedBtn = 60 + i;
                }
            } else if ("参数设置".equals(tab) && inGame) {
                if (in(x, y, 1600f + dx, 96f, 260f, 64f)) {
                    pressedBtn = 695;
                    return;
                }
                for (int i = 0; i < keys.length; i++) {
                    float cy = 216f + i * 47f + 17f;
                    if (in(x, y, 1020f + dx - 10f, cy - 21f, 320f, 42f)) {
                        dragSlider = 100 + i;
                        setIngameSlider(i, x);
                        return;
                    }
                    if (in(x, y, 1330f + dx, cy - 17f, 36f, 34f)) {
                        pressedBtn = 400 + i;
                        return;
                    }
                    if (in(x, y, 1372f + dx, cy - 17f, 140f, 34f)) {
                        openInput(i);
                        return;
                    }
                    if (in(x, y, 1518f + dx, cy - 17f, 36f, 34f)) {
                        pressedBtn = 500 + i;
                        return;
                    }
                    if (in(x, y, 1740f + dx, cy - 16f, 90f, 32f)) {
                        pressedBtn = 600 + i;
                        return;
                    }
                }
            } else if ("作弊选项".equals(tab)) {
                if (in(x, y, 1560f + dx, 250f, 240f, 96f)) pressedBtn = 70;
                else if (in(x, y, 1560f + dx, 415f, 240f, 96f)) pressedBtn = 71;
            }
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (dragSlider >= 100) setIngameSlider(dragSlider - 100, x);
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (dragSlider >= 100) {
                dragSlider = -1;
                return;
            }
            int id = pressedBtn;
            pressedBtn = -999;
            if (id == 48) {
                inputMode = 0;
            } else if (id == 49) {
                inputMode = 1;
            } else if (id == 50) {
                st = St.KEYLAYOUT;
                klPick = -1;
            } else if (id == 51) {
                st = prev == St.PAUSED ? St.PAUSED : St.SETUP;
            } else if (id >= 60 && id < 70) {
                blurLevel = id - 60;
                // 整页底图、数据页和确认层都是算一次就缓存的纹理，改档位必须一起作废
                gl.invalidateBgBlur();
                statsBgTex = 0;
                confirmBgTex = 0;
                pageBlurTex = 0;
            } else if (id >= 400 && id < 500) {
                int i = id - 400;
                commitParam(i, values[i] - (World.isIntKey(keys[i]) ? 1 : 0.5));
                world.applyCfg(world.cfg());
            } else if (id >= 500 && id < 600) {
                int i = id - 500;
                commitParam(i, values[i] + (World.isIntKey(keys[i]) ? 1 : 0.5));
                world.applyCfg(world.cfg());
            } else if (id >= 600 && id < 600 + keys.length) {
                int i = id - 600;
                commitParam(i, World.DEFAULTS[world.mode].get(keys[i]));
                world.applyCfg(world.cfg());
            } else if (id == 695) {
                // 「全部重置」：整张表恢复成当前模式的默认值，和电脑版 default_cfgs 同一套
                for (int i = 0; i < keys.length; i++) {
                    commitParam(i, World.DEFAULTS[world.mode].get(keys[i]));
                }
                world.applyCfg(world.cfg());
            } else if (id == 70) {
                world.noCollision = !world.noCollision;
                if (world.noCollision) markCheated();
            } else if (id == 71) {
                world.noDamage = !world.noDamage;
                if (world.noDamage) markCheated();
            }
        }
    }

    private void markCheated() {
        runCheated = true;
    }

    private void setIngameSlider(int i, float x) {
        float ratio = Math.max(0f, Math.min(1f, (x - 1020f - setX()) / 300f));
        commitParam(i, -9999.5 + ratio * 19999.0);
        world.applyCfg(world.cfg());
    }

    // ---------------- 键位编辑触摸 ----------------
    private void touchKeyLayout(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            for (int k = 0; k < 4; k++) {
                float[] r = touchRect(k);
                if (in(x, y, r[0] + r[2] - 40f, r[1] + r[3] - 40f, 40f, 40f)) {
                    klDragMode = 2;
                    klPick = k;
                    return;
                }
                if (in(x, y, r[0], r[1], r[2], r[3])) {
                    klDragMode = 1;
                    klPick = k;
                    klOffX = x - r[0];
                    klOffY = y - r[1];
                    return;
                }
            }
            if (in(x, y, 620f + Design.cx(), 900f, 240, 80)) {
                pressedBtn = 52;
                return;
            }
            if (in(x, y, 880f + Design.cx(), 900f, 240, 80)) {
                pressedBtn = 53;
                return;
            }
            if (in(x, y, 1140f + Design.cx(), 900f, 380, 80)) {
                pressedBtn = 54;
                return;
            }
            klPick = -1;
            klDragMode = 0;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE && klPick >= 0 && klDragMode > 0) {
            int[] r = data.keys[klPick];
            float shift = keyShift(klPick);
            float rx = r[0] + shift;               // 当前方块在画布上的左沿
            if (klDragMode == 1) {
                float nxc = Math.max(0f, Math.min(Design.W - r[2], x - klOffX));
                r[0] = (int) Math.round(nxc) - (int) Math.round(shift);
                r[1] = (int) Math.round(Math.max(0f, Math.min(Design.H - r[3], y - klOffY)));
            } else {
                int nw = (int) Math.round(Math.max(60f, Math.min(Math.min(460f, Design.W - rx), x - rx)));
                int nh = (int) Math.round(Math.max(60f, Math.min(Math.min(460f, Design.H - r[1]), y - r[1])));
                r[2] = nw;
                r[3] = nh;
            }
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (klDragMode > 0) {
                klDragMode = 0;
                return;
            }
            int id = pressedBtn;
            pressedBtn = -999;
            if (id == 52) {
                st = St.SETTINGS;
            } else if (id == 53) {
                // 和电脑版 _TOUCH_DEFAULT 同一组坐标（按 1920×1017 设计画布算）
                data.keys[0] = new int[]{60, (int) Design.H - 180, 120, 120};
                data.keys[1] = new int[]{210, (int) Design.H - 180, 120, 120};
                data.keys[2] = new int[]{Design.BASE_W - 200, (int) Design.H - 370, 150, 150};
                data.keys[3] = new int[]{Design.BASE_W - 200, (int) Design.H - 200, 150, 150};
                klPick = -1;
            } else if (id == 54) {
                data.save();
                st = St.SETTINGS;
            }
        }
    }

    // =====================================================================
    // 供 Activity / 渲染器调用的钩子
    // =====================================================================
    public void onPauseApp() {
        releaseAllPointers();
        pressedBtn = -999;
        if (st == St.GAME) st = St.PAUSED;
    }

    public boolean isPlaying() {
        return st == St.GAME;
    }

    /** 固定 60Hz 步进一次游戏世界 */
    public void stepWorld() {
        if (st != St.GAME) return;
        if (!world.step()) {
            Assets.playDeath();
            releaseAllPointers();
            deadAngle = 90;
            world.deadAngle = 90;
            if (!runCheated) data.commitStats(world.mode, (int) world.score, world.coinCnt);
            st = St.GAMEOVER;
        }
    }

    private String glRendererName() {
        return GlInfo.renderer;
    }

    private String glVersion() {
        return GlInfo.version;
    }

    /** 电脑版只取 GL 版本串的第一个词（"4.6.0"），安卓照同一套口径 */
    private String glVersionShort() {
        String v = GlInfo.version == null ? "" : GlInfo.version.trim();
        if (v.isEmpty()) return "—";
        int sp = v.indexOf(' ');
        return sp > 0 ? v.substring(0, sp) : v;
    }

    private String glSlVersion() {
        return GlInfo.glsl;
    }

    private String glScreenSize() {
        return ((int) (Design.W * Design.sx) + "×" + (int) (Design.H * Design.sy));
    }

    /** GL 字符串在 Gl.onSurfaceCreated 里取一次，放这里给设置页展示 */
    public static final class GlInfo {
        public static String vendor = "—", renderer = "—", version = "—", glsl = "—";
    }
}




