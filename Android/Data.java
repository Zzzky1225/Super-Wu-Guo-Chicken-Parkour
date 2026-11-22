package com.wuguo.chicken;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 存档层，与桌面版 data.dat 同一套格式：
 * 文件 = XOR(签名16位 | 正文)，正文一行一个 key=value，keys 一行形如 L60,837,120,120|R...。
 * 密钥 = SHA256(salt|机器ID) 的字节流；签名 = sha256(正文 + salt|机器ID) 十六进制前 16 位。
 * 机器ID：安卓取 Settings.Secure.ANDROID_ID（同一次安装内稳定）。
 */
public final class Data {
    private static final String TAG = "wuguo-data";
    private static final byte[] SALT = "SuperWuGuoChicken/7355608/lock-state/v2".getBytes(StandardCharsets.UTF_8);

    // 六个成绩 + 锁屏惩罚 + 渲染偏好 + 触屏键位
    public int s0, s1, c1, s2, c2;
    public int fails, pen, dis;
    public double until;
    public String render = "gl";
    public final int[][] keys = {         // L / R / J / C（和电脑版 _TOUCH_DEFAULT 完全同一组坐标，1920×1017 基准）
            {60, (int) Design.H - 180, 120, 120},
            {210, (int) Design.H - 180, 120, 120},
            {Design.BASE_W - 200, (int) Design.H - 370, 150, 150},
            {Design.BASE_W - 200, (int) Design.H - 200, 150, 150}
    };
    public static final String[] KEY_TAGS = {"L", "R", "J", "C"};

    private final Context ctx;
    private final File file;

    public Data(Context c) {
        ctx = c.getApplicationContext();
        file = new File(ctx.getFilesDir(), "data.dat");
    }

    // ---------- 机器标识 ----------
    private String machineId() {
        String v = "";
        try {
            v = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
        }
        if (v == null) v = "";
        v = v.trim();
        return v.isEmpty() ? "android-unknown" : v;
    }

    private byte[] storeKey() {
        return sha256(SALT, ("|" + machineId()).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] storeSalt() {
        byte[] mid = ("|" + machineId()).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[SALT.length + mid.length];
        System.arraycopy(SALT, 0, out, 0, SALT.length);
        System.arraycopy(mid, 0, out, SALT.length, mid.length);
        return out;
    }

    private static byte[] sha256(byte[] a, byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(a);
            return md.digest(b);
        } catch (Exception e) {
            return new byte[32];
        }
    }

    private static byte[] sha256(byte[] a) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(a);
        } catch (Exception e) {
            return new byte[32];
        }
    }

    private static String hex16(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    private static byte[] xor(byte[] data, byte[] key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ key[i % key.length]);
        return out;
    }

    // ---------- 读 ----------
    public void load() {
        byte[] cipher;
        try {
            cipher = java.nio.file.Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            Log.i(TAG, "没有旧存档，用默认值");
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            byte[] key = pass == 0 ? storeKey() : sha256(SALT);
            byte[] salt = pass == 0 ? storeSalt() : SALT;
            byte[] raw = xor(cipher, key);
            String text = new String(raw, StandardCharsets.UTF_8);
            int bar = text.indexOf('|');
            if (bar < 0) continue;
            String sig = text.substring(0, bar);
            String body = text.substring(bar + 1);
            String want = hex16(sha256(concat(body.getBytes(StandardCharsets.UTF_8), salt)));
            if (!want.equals(sig)) continue;
            parse(body);
            if (pass == 1) save();      // 旧的无设备方案：读出来立刻按新方案重写
            Log.i(TAG, "存档读取成功（" + (pass == 0 ? "本机密钥" : "旧方案") + "）");
            return;
        }
        Log.w(TAG, "存档签名对不上，整套回默认");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void parse(String body) {
        for (String line : body.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "s0": s0 = Integer.parseInt(v); break;
                    case "s1": s1 = Integer.parseInt(v); break;
                    case "c1": c1 = Integer.parseInt(v); break;
                    case "s2": s2 = Integer.parseInt(v); break;
                    case "c2": c2 = Integer.parseInt(v); break;
                    case "fails": fails = Integer.parseInt(v); break;
                    case "pen": pen = Integer.parseInt(v); break;
                    case "dis": dis = Integer.parseInt(v); break;
                    case "until": until = Double.parseDouble(v); break;
                    case "render":
                        if ("gl".equals(v) || "soft".equals(v)) render = v;
                        break;
                    case "keys":
                        parseKeys(v);
                        break;
                    default:
                        break;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void parseKeys(String v) {
        for (String part : v.split("\\|")) {
            if (part.length() < 6) continue;
            int idx = -1;
            for (int i = 0; i < KEY_TAGS.length; i++) if (part.startsWith(KEY_TAGS[i])) idx = i;
            if (idx < 0) continue;
            String[] nums = part.substring(1).split(",");
            if (nums.length != 4) continue;
            try {
                int x = Integer.parseInt(nums[0].trim());
                int y = Integer.parseInt(nums[1].trim());
                int w = Integer.parseInt(nums[2].trim());
                int h = Integer.parseInt(nums[3].trim());
                if (keysOk(x, y, w, h)) {
                    keys[idx][0] = x;
                    keys[idx][1] = y;
                    keys[idx][2] = w;
                    keys[idx][3] = h;
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean keysOk(int x, int y, int w, int h) {
        if (w < 60 || h < 60 || w > 460 || h > 460) return false;
        // 坐标以 1920×1017 为基准存；宽屏上右侧两键靠 dx 偏移贴右沿，这里只拦明显跑飞的值
        return x >= -Design.BASE_W && y >= 0 && x + w <= Design.BASE_W * 2 && y + h <= Design.BASE_H;
    }

    // ---------- 写 ----------
    public boolean save() {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("s0=" + s0);
            lines.add("s1=" + s1);
            lines.add("c1=" + c1);
            lines.add("c2=" + c2);
            lines.add("s2=" + s2);
            lines.add("fails=" + fails);
            lines.add("until=" + until);
            lines.add("pen=" + pen);
            lines.add("dis=" + dis);
            lines.add("render=" + render);
            StringBuilder kb = new StringBuilder();
            for (int i = 0; i < KEY_TAGS.length; i++) {
                if (kb.length() > 0) kb.append('|');
                kb.append(KEY_TAGS[i]).append(keys[i][0]).append(',').append(keys[i][1])
                        .append(',').append(keys[i][2]).append(',').append(keys[i][3]);
            }
            lines.add("keys=" + kb);
            String body = String.join("\n", lines);
            String sig = hex16(sha256(concat(body.getBytes(StandardCharsets.UTF_8), storeSalt())));
            byte[] plain = (sig + "|" + body).getBytes(StandardCharsets.UTF_8);
            byte[] cipher = xor(plain, storeKey());
            File tmp = new File(file.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), cipher);
            if (!tmp.renameTo(file)) {
                java.nio.file.Files.deleteIfExists(file.toPath());
                if (!tmp.renameTo(file)) throw new IOException2("替换失败");
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "写存档失败: " + e);
            return false;
        }
    }

    private static final class IOException2 extends Exception {
        IOException2(String m) {
            super(m);
        }
    }

    // ---------- 统计 ----------
    public int best(int mode, boolean coins) {
        switch (mode) {
            case 0: return s0;
            case 1: return coins ? c1 : s1;
            default: return coins ? c2 : s2;
        }
    }

    public void clearStats() {
        s0 = s1 = c1 = s2 = c2 = 0;
        save();
    }

    public void commitStats(int mode, int score, int coins) {
        boolean changed = false;
        if (mode == 0) {
            if (score > s0) {
                s0 = score;
                changed = true;
            }
        } else if (mode == 1) {
            if (score > s1) {
                s1 = score;
                changed = true;
            }
            if (coins > c1) {
                c1 = coins;
                changed = true;
            }
        } else {
            if (score > s2) {
                s2 = score;
                changed = true;
            }
            if (coins > c2) {
                c2 = coins;
                changed = true;
            }
        }
        if (changed) save();
    }
}
