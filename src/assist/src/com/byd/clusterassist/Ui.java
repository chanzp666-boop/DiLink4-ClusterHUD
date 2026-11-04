package com.byd.clusterassist;

import android.content.Context;
import android.provider.Settings;
import android.os.SystemClock;

/**
 * 助理自己的配色：跟随系统深浅色（settings secure ui_night_mode，1 = 浅色）。
 * overlay 用 /sdcard/clusterlab_theme.txt 手动覆盖；助理这边只跟系统，避免两套规则打架。
 */
public final class Ui {
    public static volatile boolean night = true;
    private static long lastRead = 0L;

    private Ui() {}

    /** 每秒最多读一次系统设置；返回当前是否深色。 */
    public static boolean refresh(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRead < 1000) return night;
        lastRead = now;
        try {
            // 以系统 Configuration 为准（ui_night_mode 可能是 2 = 自动，但 framework 一定准）
            night = (c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
        }
        return night;
    }

    public static int bg()        { return night ? 0xFF070C15 : 0xFFF3F6FB; }
    public static int card()      { return night ? 0xFF101A2B : 0xFFFFFFFF; }
    public static int card2()     { return night ? 0xFF17243A : 0xFFE4EBF5; }
    public static int txt()       { return night ? 0xFFE9EFF7 : 0xFF0F1622; }
    public static int dim()       { return night ? 0xFF8496AE : 0xFF5B6A7D; }
    public static int accent()    { return night ? 0xFF4DA3FF : 0xFF1170FF; }
    public static int ok()        { return night ? 0xFF2FD79B : 0xFF0E9F6E; }
    public static int warn()      { return night ? 0xFFFFB020 : 0xFFB26A00; }
    public static int bad()       { return night ? 0xFFFF6B6B : 0xFFD93A3A; }
    public static int sel()       { return night ? 0xFF2C6FBF : 0xFF1170FF; }
    public static int primary()   { return night ? 0xFF1D4E89 : 0xFF1170FF; }
    public static int styleBtn()  { return night ? 0xFF2C4A73 : 0xFF0E5AC4; }
    public static int logText()   { return night ? 0xFFB8C7DA : 0xFF243244; }
    /** 绿/暗红这类按钮上的字色（浅色背景下用白字）。 */
    public static int onAccent()  { return 0xFF06251B; }
    public static int offBadge()  { return night ? 0xFF5A2030 : 0xFFF3C7CE; }
    public static int offBadgeText() { return night ? 0xFFFFD9D9 : 0xFF8C1B28; }
    public static int swatchBorder() { return night ? 0x66FFFFFF : 0x33000000; }
    public static int previewBg() { return night ? 0xFF0E1725 : 0xFFEAF0F8; }
}
