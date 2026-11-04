package com.byd.clusterassist;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 元素样式配置（与 overlay 的 Style.java 对应）：/sdcard/clusterlab_style.txt
 * 十个元素 × 颜色(深/浅)、字号、透明度、描边(开关/粗细/颜色)。
 */
public final class Style {
    public static final String FILE = "/sdcard/clusterlab_style.txt";

    public static final String[] EL = {"cap", "num", "unit", "tnum", "tunit",
            "title", "artist", "lcur", "lprev", "lfar"};
    public static final String[] LABEL = {"标题", "数值", "单位", "胎压数值", "胎压单位",
            "曲名", "歌手", "当前歌词", "次行歌词", "歌词远行"};

    public static final int[] DEF_SIZE = {15, 44, 16, 44, 13, 26, 15, 26, 26, 20};
    public static final int[] MIN_SIZE = {8, 16, 8, 16, 8, 10, 8, 12, 12, 10};
    public static final int[] MAX_SIZE = {30, 80, 40, 80, 40, 48, 40, 48, 48, 40};
    public static final int[] DEF_ALPHA = {255, 255, 200, 255, 200, 255, 190, 255, 220, 170};

    public static final String[] DEF_C_NIGHT = {
            "#FFFFFF", "#FFFFFF", "#BFD2E6", "#FFFFFF", "#BFD2E6",
            "#FFFFFF", "#9AB0C8", "#FFFFFF", "#6E7F96", "#7C8DA6"
    };
    public static final String[] DEF_C_LIGHT = {
            "#060B12", "#060B12", "#243244", "#060B12", "#243244",
            "#060B12", "#5B6A7D", "#1170FF", "#7A8A9C", "#3E4C60"
    };
    public static final int[] DEF_STROKE_W = {3, 5, 3, 5, 3, 4, 3, 3, 3, 3};
    /** 字重：0=细 1=基本 2=加粗 3=超粗 */
    public static final String[] WEIGHT_NAME = {"细", "基本", "加粗", "超粗"};
    public static final int[] DEF_WEIGHT = {1, 2, 1, 2, 1, 2, 1, 2, 1, 1};
    public static final String DEF_STROKE_NIGHT = "#0A1220";
    public static final String DEF_STROKE_LIGHT = "#FFFFFF";

    private Style() {}

    public static String key(String el, String prop) { return el + "." + prop; }

    /** 内存缓存：避免每次设置都 shell 读一遍文件（原来一次点击要往返 3 次 shell，很卡）。 */
    private static Map<String, String> CACHE = null;
    private static long cacheAt = 0L;

    public static Map<String, String> load() {
        if (CACHE != null) {
            return new LinkedHashMap<String, String>(CACHE);
        }
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < EL.length; i++) {
            m.put(EL[i] + ".color.night", DEF_C_NIGHT[i]);
            m.put(EL[i] + ".color.light", DEF_C_LIGHT[i]);
            m.put(EL[i] + ".alpha", String.valueOf(DEF_ALPHA[i]));
            m.put(EL[i] + ".size", String.valueOf(DEF_SIZE[i]));
            m.put(EL[i] + ".stroke.on", "1");
            m.put(EL[i] + ".stroke.w", String.valueOf(DEF_STROKE_W[i]));
            m.put(EL[i] + ".stroke.night", DEF_STROKE_NIGHT);
            m.put(EL[i] + ".stroke.light", DEF_STROKE_LIGHT);
            m.put(EL[i] + ".weight", String.valueOf(DEF_WEIGHT[i]));
        }
        String raw = Shell.exec("cat " + FILE + " 2>/dev/null", 5000);
        if (raw != null) {
            for (String line : raw.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (m.containsKey(k) && v.length() > 0) m.put(k, v);
            }
        }
        CACHE = new LinkedHashMap<String, String>(m);
        cacheAt = System.currentTimeMillis();
        return m;
    }

    public static String get(String el, String prop) { return load().get(el + "." + prop); }

    public static int intOf(String el, String prop, int def) {
        try { return Math.round(Float.parseFloat(get(el, prop))); } catch (Throwable t) { return def; }
    }

    public static String colorHex(String el, boolean night, boolean stroke) {
        String v = get(el, (stroke ? "stroke." : "color.") + (night ? "night" : "light"));
        return v == null ? (stroke ? DEF_STROKE_NIGHT : DEF_C_NIGHT[0]) : v;
    }

    public static boolean set(String el, String prop, String value) {
        Map<String, String> m = load();
        m.put(el + "." + prop, value);
        boolean ok = write(m);
        if (ok) { CACHE = m; cacheAt = System.currentTimeMillis(); }
        return ok;
    }

    public static void reset() {
        Shell.exec("rm -f " + FILE, 5000);
        CACHE = null;
    }

    private static boolean write(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("printf '%b' '");
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append("\\n");
        }
        sb.append("' > ").append(FILE).append("; chmod 666 ").append(FILE);
        // 一次写入，不再额外 grep 验证（每次 shell 往返 ~100ms，点击会明显发卡）
        Shell.exec(sb.toString(), 8000);
        return true;
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
}
