package com.wuguo.chicken;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏本体逻辑，逐行对应桌面版 main.py：
 * 三个模式的物理、障碍生成、逐像素碰撞、金币、分数、红温。
 * 一切都是「每帧」单位（60Hz 固定步进），和桌面版完全一致。
 */
public final class World {
    /** 设计画布 1920×1080，地面线 = 1080 - 425 */
    public static final float GROUND_Y = Design.H - Assets.TARGET_H;

    public static final String[][] LABELS = {
            {"游戏速度：", "重力：", "跳跃初速度：", "障碍生成间隔：", "最大HP：", "受伤无敌帧：",
                    "玩家X坐标：", "动画速度：", "障碍缩放：", "背景速度：", "得分倍率：",
                    "跳跃空气阻尼：", "下落倍数（蹲下）：", "障碍随机偏移："},
            {"障碍速度：", "重力：", "跳跃初速度：", "障碍生成间隔：", "最大HP：", "受伤无敌帧：",
                    "动画速度：", "障碍缩放：", "背景速度：", "得分倍率：", "跳跃空气阻尼：",
                    "下落倍数（蹲下）：", "起步加速度：", "水平摩擦力（0急停）：", "障碍随机偏移："},
            {"移动速度上限：", "重力：", "跳跃初速度：", "障碍生成间隔：", "最大HP：", "受伤无敌帧：",
                    "动画速度：", "障碍缩放：", "得分倍率：", "跳跃空气阻尼：", "下落倍数（蹲下）：",
                    "起步加速度：", "水平摩擦力（0急停）：", "障碍随机偏移："}
    };

    public static final String[][] KEYS = {
            {"obs_speed", "gravity", "jump_power", "spawn_interval", "max_hp", "hurt_duration",
                    "player_x", "anim_speed", "obs_scale", "bg_scroll_speed_scale", "score_rate",
                    "jump_damping", "fall_scale", "jitter"},
            {"obs_speed", "gravity", "jump_power", "spawn_interval", "max_hp", "hurt_duration",
                    "anim_speed", "obs_scale", "bg_scroll_speed_scale", "score_rate", "jump_damping",
                    "fall_scale", "move_accel", "friction", "jitter"},
            {"move_speed", "gravity", "jump_power", "spawn_interval", "max_hp", "hurt_duration",
                    "anim_speed", "obs_scale", "score_rate", "jump_damping", "fall_scale",
                    "move_accel", "friction", "jitter"}
    };

    public static final LinkedHashMap<String, Double> CFG_RUN = new LinkedHashMap<>();
    public static final LinkedHashMap<String, Double> CFG_FREE = new LinkedHashMap<>();
    public static final LinkedHashMap<String, Double> CFG_MARIO = new LinkedHashMap<>();
    public static final LinkedHashMap<String, Double>[] DEFAULTS;

    /** 整数参数：输入框按 1 步进，其余按 0.5 */
    public static boolean isIntKey(String k) {
        return k.equals("spawn_interval") || k.equals("max_hp") || k.equals("hurt_duration")
                || k.equals("jitter") || k.equals("anim_speed") || k.equals("player_x");
    }

    static {
        put(CFG_RUN, "obs_speed", 20, "gravity", 1.8, "jump_power", -40.0, "spawn_interval", 90,
                "max_hp", 5, "hurt_duration", 15, "player_x", 200, "anim_speed", 8, "obs_scale", 1.0,
                "bg_scroll_speed_scale", 1.0, "score_rate", 0.5, "jump_damping", 0.0, "fall_scale", 1.8, "jitter", 45);
        put(CFG_FREE, "obs_speed", 20, "gravity", 1.8, "jump_power", -32.0, "spawn_interval", 100,
                "max_hp", 5, "hurt_duration", 15, "anim_speed", 8, "obs_scale", 1.0,
                "bg_scroll_speed_scale", 1.0, "score_rate", 0.5, "jump_damping", 30.0, "fall_scale", 1.8,
                "move_accel", 1.5, "friction", 0.85, "jitter", 30);
        put(CFG_MARIO, "move_speed", 20, "gravity", 1.8, "jump_power", -34.0, "spawn_interval", 180,
                "max_hp", 5, "hurt_duration", 45, "anim_speed", 8, "obs_scale", 1.0, "score_rate", 0.5,
                "jump_damping", 40.0, "fall_scale", 1.8, "move_accel", 2.0, "friction", 0.85, "jitter", 115);
        DEFAULTS = new LinkedHashMap[]{copy(CFG_RUN), copy(CFG_FREE), copy(CFG_MARIO)};
    }

    private static void put(LinkedHashMap<String, Double> m, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
    }

    public static LinkedHashMap<String, Double> copy(Map<String, Double> src) {
        return new LinkedHashMap<>(src);
    }

    // ---------- 运行时状态 ----------
    public int mode;                 // 0 跑步鸡 / 1 滑步鸡 / 2 吴国鸡
    public int inputMode;            // 安卓固定触屏（1）
    public double score;
    public int hp, coinCnt;
    public float playerX = 200, playerY = GROUND_Y, playerVx, playerVy;
    public boolean onGround = true, facingRight = true;
    public int animTimer, animIndex, hurtTimer;
    public int deadAngle;
    public boolean touchLeft, touchRight, jumpHeld, crouchHeld, crouching, crouchJump;
    public float bgX, camX;
    public int spawnTimer;
    public boolean noCollision, noDamage;

    public static final class Obs {
        public float x, y;
        public int w, h;
        public Bitmap img;
        public Mask mask;
    }

    public static final class Coin {
        public float x, y;
    }

    public static final class Popup {
        public float x, y;
        public int t;
        public String text;
    }

    public final List<Obs> obsList = new ArrayList<>();
    public final List<Coin> coinList = new ArrayList<>();
    public final List<Popup> popups = new ArrayList<>();

    // 当前模式的参数
    public double obsSpeed, gravity, jumpPower, spawnInterval, maxHp, hurtDuration, animSpeed,
            obsScale, bgScrollScale, scoreRate, jumpDamping, fallScale, jitter, moveAccel, friction,
            moveSpeed, playerXDefault;

    private final HashMap<String, Bitmap> scaleCache = new HashMap<>();
    private final HashMap<String, Mask> maskCache = new HashMap<>();

    public Map<String, Double> cfg() {
        return new LinkedHashMap[]{CFG_RUN, CFG_FREE, CFG_MARIO}[mode];
    }

    /** 把配置表的值推进运行时（开局与游戏内改参数都用它） */
    public void applyCfg(Map<String, Double> c) {
        obsSpeed = c.getOrDefault("obs_speed", 20.0);
        gravity = c.getOrDefault("gravity", 1.8);
        jumpPower = c.getOrDefault("jump_power", -40.0);
        spawnInterval = c.getOrDefault("spawn_interval", 90.0);
        maxHp = c.getOrDefault("max_hp", 5.0);
        hurtDuration = c.getOrDefault("hurt_duration", 15.0);
        animSpeed = c.getOrDefault("anim_speed", 8.0);
        obsScale = c.getOrDefault("obs_scale", 1.0);
        bgScrollScale = c.getOrDefault("bg_scroll_speed_scale", 1.0);
        scoreRate = c.getOrDefault("score_rate", 0.5);
        jumpDamping = c.getOrDefault("jump_damping", 0.0);
        fallScale = c.getOrDefault("fall_scale", 1.8);
        jitter = c.getOrDefault("jitter", 45.0);
        moveAccel = c.getOrDefault("move_accel", 1.5);
        friction = c.getOrDefault("friction", 0.85);
        moveSpeed = c.getOrDefault("move_speed", 20.0);
        playerXDefault = c.getOrDefault("player_x", 200.0);
        scaleCache.clear();
        maskCache.clear();
    }

    public void reset() {
        float px = mode == 0 ? (float) playerXDefault : (mode == 1 ? 400f : 200f);
        playerX = px;
        playerY = GROUND_Y;
        playerVy = 0;
        playerVx = 0;
        onGround = true;
        facingRight = true;
        spawnTimer = 0;
        bgX = 0;
        camX = 0;
        animTimer = 0;
        animIndex = 0;
        score = 0;
        hp = (int) maxHp;
        hurtTimer = 0;
        deadAngle = 0;
        touchLeft = touchRight = jumpHeld = crouchHeld = crouching = crouchJump = false;
        coinCnt = 0;
        obsList.clear();
        coinList.clear();
        popups.clear();
    }

    // ---------- 物理 ----------
    public void gravityAndFloor() {
        double g = gravity;
        if (jumpHeld && playerVy < 0) g *= (1 - jumpDamping * 0.01);
        if (crouching && (playerVy >= 0 || !crouchJump)) g *= fallScale;
        playerVy += (float) g;
        playerY += playerVy;
        if (playerY >= GROUND_Y) {
            playerY = GROUND_Y;
            playerVy = 0;
            onGround = true;
        } else {
            onGround = false;
        }
    }

    public void updateCrouch() {
        crouching = crouchHeld;
    }

    public boolean horizontal(float speedLimit) {
        boolean left = touchLeft, right = touchRight;
        if (left) {
            playerVx = (float) Math.max(playerVx - moveAccel, -speedLimit);
            facingRight = false;
        } else if (right) {
            playerVx = (float) Math.min(playerVx + moveAccel, speedLimit);
            facingRight = true;
        } else {
            playerVx *= (float) friction;
            if (Math.abs(playerVx) < 0.2f) playerVx = 0;
        }
        return left || right || Math.abs(playerVx) > 0;
    }

    public void doJump() {
        crouchJump = crouchHeld;
        if (onGround) {
            playerVy = (float) jumpPower;
            onGround = false;
        }
    }

    // ---------- 障碍与碰撞 ----------
    private Bitmap scaledObs(int idx) {
        double s = obsScale;
        String k = idx + "@" + s;
        Bitmap b = scaleCache.get(k);
        if (b != null) return b;
        Bitmap src = Assets.OBS.get(idx);
        int ow = Math.max(1, (int) (src.getWidth() * s));
        int oh = Math.max(1, (int) (src.getHeight() * s));
        b = Bitmap.createScaledBitmap(src, ow, oh, true);
        scaleCache.put(k, b);
        return b;
    }

    private Mask obsMask(int idx, Bitmap b) {
        String k = idx + "@" + obsScale;
        Mask m = maskCache.get(k);
        if (m != null) return m;
        m = new Mask(b, 40);
        maskCache.put(k, m);
        return m;
    }

    public void spawnObstacle(boolean scrolling) {
        if (Assets.OBS.isEmpty()) return;
        int pick = (int) (Math.random() * Assets.OBS.size());
        Bitmap b = scaledObs(pick);
        Mask m = obsMask(pick, b);
        float spawnX;
        if (scrolling) {
            float lastX = camX + Design.W;
            for (Obs o : obsList) lastX = Math.max(lastX, o.x);
            spawnX = Math.max(camX + Design.W + 300, lastX) + (float) (Math.random() * 500);
        } else {
            spawnX = Design.W;
        }
        Obs o = new Obs();
        o.x = spawnX;
        o.y = Design.H - b.getHeight();
        o.w = b.getWidth();
        o.h = b.getHeight();
        o.img = b;
        o.mask = m;
        obsList.add(o);
    }

    public Mask playerMask() {
        boolean crouch = crouching;
        boolean hurt = hurtTimer > 0;
        if (crouch) {
            if (hurt) return facingRight ? Assets.hurtSitRMask : Assets.hurtSitLMask;
            return facingRight ? Assets.sitRMask : Assets.sitLMask;
        }
        int ai = clampIndex(animIndex);
        if (hurt) return facingRight ? Assets.hurtRMask[ai] : Assets.hurtLMask[ai];
        return facingRight ? Assets.framesRMask[ai] : Assets.framesLMask[ai];
    }

    private int clampIndex(int i) {
        int n = Assets.framesRMask.length;
        if (n == 0) return 0;
        return ((i % n) + n) % n;
    }

    /** @return false = 玩家死了 */
    public boolean collisions() {
        Mask cur = playerMask();
        float cam = mode == 2 ? camX : 0f;
        float px = playerX - cam, py = playerY;
        int pmw = cur.w, pmh = cur.h;
        for (int i = obsList.size() - 1; i >= 0; i--) {
            Obs o = obsList.get(i);
            float ox = o.x - cam, oy = o.y;
            if (ox + o.w < 0) {
                obsList.remove(i);
                continue;
            }
            if (ox > px + pmw || ox + o.w < px || oy > py + pmh || oy + o.h < py) continue;
            if (cur.overlap(o.mask, (int) (ox - px), (int) (oy - py))) {
                if (noCollision) continue;
                obsList.remove(i);
                if (noDamage) continue;
                hp -= 1;
                hurtTimer = (int) hurtDuration;
                Assets.playHit();
                if (hp <= 0) return false;
            }
        }
        return true;
    }

    public void coinCollisions() {
        Mask cur = playerMask();
        float cam = mode == 2 ? camX : 0f;
        float px = playerX - cam, py = playerY;
        int pmw = cur.w, pmh = cur.h;
        int cw = Assets.coinMask.w, ch = Assets.coinMask.h;
        for (int i = coinList.size() - 1; i >= 0; i--) {
            Coin c = coinList.get(i);
            if (c.x < camX - 100) {
                coinList.remove(i);
                continue;
            }
            float cx = c.x - cam, cy = c.y;
            if (cx > px + pmw || cx + cw < px || cy > py + pmh || cy + ch < py) continue;
            if (cur.overlap(Assets.coinMask, (int) (cx - px), (int) (cy - py))) {
                coinList.remove(i);
                int amount = 5 + (int) (Math.random() * 96);
                score += amount;
                coinCnt += 1;
                Popup p = new Popup();
                p.x = (int) (px + Assets.framesR[0].getWidth() / 2f);
                p.y = (int) py;
                p.text = "+" + amount;
                p.t = 0;
                popups.add(p);
                Assets.playHit();
            }
        }
    }

    /** 一帧的完整推进；返回 false 表示这一帧玩家死亡 */
    public boolean step() {
        if (hurtTimer > 0) hurtTimer--;
        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup p = popups.get(i);
            p.y -= 1.5f;
            p.t++;
            if (p.t > 45) popups.remove(i);
        }

        boolean movedToStars = false;
        switch (mode) {
            case 0:
                score += scoreRate;
                bgX = (float) ((bgX - obsSpeed * bgScrollScale) % -Design.BASE_W);
                updateCrouch();
                gravityAndFloor();
                animTimer++;
                if (animTimer >= animSpeed) {
                    animTimer = 0;
                    animIndex = (animIndex + 1) % Math.max(1, Assets.framesR.length);
                }
                spawnTimer++;
                if (spawnTimer >= spawnInterval + rnd(jitter)) {
                    spawnObstacle(false);
                    spawnTimer = 0;
                }
                for (Obs o : obsList) o.x -= (float) obsSpeed;
                if (!collisions()) return false;
                break;
            case 1: {
                boolean moving = horizontal(10.0f);
                updateCrouch();
                playerX = Math.max(0, Math.min(Design.W - Assets.framesR[0].getWidth(), playerX + playerVx));
                gravityAndFloor();
                if (moving || !onGround) {
                    animTimer++;
                    if (animTimer >= animSpeed) {
                        animTimer = 0;
                        animIndex = (animIndex + 1) % Math.max(1, Assets.framesR.length);
                    }
                } else {
                    animTimer = 0;
                    animIndex = 0;
                }
                spawnTimer++;
                if (spawnTimer >= spawnInterval + rnd(jitter)) {
                    spawnObstacle(false);
                    spawnTimer = 0;
                }
                if (Math.random() < 0.02) {
                    Coin c = new Coin();
                    c.x = Design.W;
                    c.y = 220 + (float) (Math.random() * (GROUND_Y - 90 - 220));
                    coinList.add(c);
                }
                for (Obs o : obsList) o.x -= (float) obsSpeed;
                for (Coin c : coinList) c.x -= (float) obsSpeed;
                bgX = (float) ((bgX - obsSpeed * bgScrollScale) % -Design.BASE_W);
                coinCollisions();
                if (!collisions()) return false;
                break;
            }
            default: {
                boolean moving = horizontal((float) moveSpeed);
                updateCrouch();
                playerX = Math.max(camX, playerX + playerVx);
                gravityAndFloor();
                if (moving || !onGround) {
                    animTimer++;
                    if (animTimer >= animSpeed) {
                        animTimer = 0;
                        animIndex = (animIndex + 1) % Math.max(1, Assets.framesR.length);
                    }
                } else {
                    animTimer = 0;
                    animIndex = 0;
                }
                spawnTimer++;
                if (spawnTimer >= spawnInterval + rnd(jitter)) {
                    spawnObstacle(true);
                    spawnTimer = 0;
                }
                if (Math.random() < 0.05) {
                    float last = 0;
                    for (Coin c : coinList) last = Math.max(last, c.x);
                    float spawnX = Math.max(camX + Design.W + 200, last) + 100 + (float) (Math.random() * 300);
                    Coin c = new Coin();
                    c.x = spawnX;
                    c.y = 220 + (float) (Math.random() * (GROUND_Y - 90 - 220));
                    coinList.add(c);
                }
                camX = Math.max(camX, playerX - Design.W / 3f);
                bgX = -(camX % Design.BASE_W);
                coinCollisions();
                if (!collisions()) return false;
                break;
            }
        }
        return true;
    }

    private static double rnd(double jitter) {
        if (jitter <= 0) return 0;
        return (Math.random() * 2 - 1) * jitter;
    }

    /** 画玩家（用世界坐标），mode2 需要减去镜头 */
    public float camOffset() {
        return mode == 2 ? camX : 0f;
    }
}
