package com.byd.clusterlab;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;

/**
 * 统一元素样式表：/sdcard/clusterlab_style.txt
 * 十个元素各自独立：颜色（深/浅）、字号、透明度、描边（开关/粗细/颜色）。
 *
 *   cap    标题（本次电耗 / 本次油耗 / 综合油耗）
 *   num    数值（三段油耗数字 + 四轮胎压数字）
 *   unit   单位（kW·h、L、L/100km、+ =）
 *   tnum   胎压数值（四轮 2xx）
 *   tunit  胎压单位（kPa）
 *   title  曲名
 *   artist 歌手
 *   lcur   当前歌词
 *   lprev  次行歌词（上一行）
 *   lfar   歌词远行（下面两行）
 *
 * 键名：<el>.color.night / .color.light / .alpha / .size
 *       <el>.stroke.on / .stroke.w / .stroke.night / .stroke.light
 * 每 2 秒最多重读一次；内容变了 version 自增，界面靠它判断要不要重画。
 */
public final class Style {
    public static final String FILE = "/sdcard/clusterlab_style.txt";

    public static final String[] EL = {"cap", "num", "unit", "tnum", "tunit",
            "title", "artist", "lcur", "lprev", "lfar"};
    public static final String[] LABEL = {"标题", "数值", "单位", "胎压数值", "胎压单位",
            "曲名", "歌手", "当前歌词", "次行歌词", "歌词远行"};

    public static final float[] DEF_SIZE = {15f, 44f, 16f, 44f, 13f, 26f, 15f, 26f, 26f, 20f};
    public static final float[] MIN_SIZE = {8f, 16f, 8f, 16f, 8f, 10f, 8f, 12f, 12f, 10f};
    public static final float[] MAX_SIZE = {30f, 80f, 40f, 80f, 40f, 48f, 40f, 48f, 48f, 40f};

    public static final int[] DEF_ALPHA = {255, 255, 200, 255, 200, 255, 190, 255, 220, 170};

    public static final String[] DEF_C_NIGHT = {
            "#FFFFFF", "#FFFFFF", "#BFD2E6", "#FFFFFF", "#BFD2E6",
            "#FFFFFF", "#9AB0C8", "#FFFFFF", "#6E7F96", "#7C8DA6"
    };
    public static final String[] DEF_C_LIGHT = {
            "#060B12", "#060B12", "#243244", "#060B12", "#243244",
            "#060B12", "#5B6A7D", "#1170FF", "#7A8A9C", "#3E4C60"
    };

    public static final float[] DEF_STROKE_W = {3f, 5f, 3f, 5f, 3f, 4f, 3f, 3f, 3f, 3f};

    /** 字重：0=细 1=基本 2=加粗 3=超粗 */
    public static final String[] WEIGHT_NAME = {"细", "基本", "加粗", "超粗"};
    /** 默认字重（按元素）：标题/数值/胎压数值/曲名/歌手/当前歌词偏重，其余常规 */
    public static final int[] DEF_WEIGHT = {1, 2, 1, 2, 1, 2, 1, 2, 1, 1};

    public static int weight(String el) {
        int i = idx(el);
        if (i < 0) return 1;
        maybeReload();
        return (int) num(el + ".weight", DEF_WEIGHT[i], 0f, 3f);
    }
    public static final String DEF_STROKE_NIGHT = "#0A1220";
    public static final String DEF_STROKE_LIGHT = "#FFFFFF";

    public static volatile int version = 0;

    private static final HashMap<String, String> MAP = new HashMap<String, String>();
    private static String lastRaw = "";
    private static long lastRead = 0L;

    /** 配置是否已至少装载过一次（首次装载必须成功，之后才允许按 mtime 跳过）。 */
    private static final class Rad {
        private volatile boolean loaded = false;
        boolean isLoaded() { return loaded; }
        void setLoaded() { loaded = true; }
    }
    private static final Rad rad = new Rad();

    private Style() {}

    public static int idx(String el) {
        for (int i = 0; i < EL.length; i++) if (EL[i].equals(el)) return i;
        return -1;
    }

    public static float size(String el) {
        int i = idx(el);
        if (i < 0) return 16f;
        return num(el + ".size", DEF_SIZE[i], MIN_SIZE[i], MAX_SIZE[i]);
    }

    public static int alpha(String el) {
        int i = idx(el);
        if (i < 0) return 255;
        return (int) num(el + ".alpha", DEF_ALPHA[i], 0f, 255f);
    }

    public static int color(String el, boolean night) {
        int i = idx(el);
        if (i < 0) return 0xFFFFFFFF;
        String def = night ? DEF_C_NIGHT[i] : DEF_C_LIGHT[i];
        return parse(get(el + ".color." + (night ? "night" : "light"), def), parse(def, 0xFFFFFFFF));
    }

    public static boolean strokeOn(String el) {
        return num(el + ".stroke.on", 1f, 0f, 1f) >= 0.5f;
    }

    /** 未配置时用调用方按字号算出来的 auto。 */
    public static float strokeW(String el, float auto) {
        if (!MAP.containsKey(el + ".stroke.w")) return auto;
        return num(el + ".stroke.w", auto, 0f, 14f);
    }

    public static int strokeColor(String el, boolean night) {
        String def = night ? DEF_STROKE_NIGHT : DEF_STROKE_LIGHT;
        return parse(get(el + ".stroke." + (night ? "night" : "light"), def), parse(def, 0xFF000000));
    }

    /** 颜色 + 该元素透明度。 */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static String get(String key, String def) {
        maybeReload();
        String v = MAP.get(key);
        return v == null ? def : v;
    }

    private static float num(String key, float def, float min, float max) {
        maybeReload();
        String v = MAP.get(key);
        float out = def;
        if (v != null) {
            try { out = Float.parseFloat(v); } catch (Throwable ignored) {}
        }
        if (out < min) out = min;
        if (out > max) out = max;
        return out;
    }

    public static int parse(String hex, int fallback) {
        if (hex == null) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return (int) (0xFF000000L | Long.parseLong(s, 16));
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (Throwable ignored) {}
        return fallback;
    }

    /** 配置重读节流：2s 一次，且 key 全部先过一遍 maybeReload，避免每帧 stat 文件。 */
    private static long lastStat = 0L;
    private static long lastMtime = -1L;

    private static void maybeReload() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStat < 2000) return;
        lastStat = now;
        File f = new File(FILE);
        long mtime = f.exists() ? f.lastModified() : -1L;
        if (mtime == lastMtime && rad.isLoaded()) return;   // 文件没动过，直接跳过解析
        lastMtime = mtime;
        reload();
        rad.setLoaded();
    }

    private static void reload() {
        String raw = "";
        File f = new File(FILE);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line.trim()).append('\n');
            raw = sb.toString();
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        if (raw.equals(lastRaw)) return;
        lastRaw = raw;
        HashMap<String, String> next = new HashMap<String, String>();
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.length() == 0 || v.length() == 0) continue;
            next.put(k, v);
        }
        synchronized (MAP) {
            MAP.clear();
            MAP.putAll(next);
        }
        version++;
    }
}
