package com.byd.clusterassist;

import android.os.SystemClock;
import android.util.Log;

/**
 * 调试日志总开关。
 *
 * 默认关：车机上每秒十几次 Loeg.e 会吃掉实实在在的 CPU 和 IO，仪表卡片是常驻的，
 * 没必要为了看日志一直写。要看的时候在"仪表助理"里点一下「日志」，或者手动
 * touch /sdcard/clusterlab_debug.txt。
 *
 * 用文件而不是 SharedPreferences：卡片服务和助理是两个包、两个进程，
 * 一个文件两边都能读，改完 5 秒内生效，不用重启。
 */
public final class Dbg {
    public static final String FLAG = "/sdcard/clusterlab_debug.txt";

    private static final Object LOCK = new Object();
    private static volatile boolean on = false;
    private static volatile long checkedAt = 0L;

    /** 是否写日志；5 秒缓存，别让每行日志都去 stat 文件。 */
    public static boolean on() {
        long now = SystemClock.elapsedRealtime();
        if (now - checkedAt < 5000) return on;
        checkedAt = now;
        try {
            on = new java.io.File(FLAG).exists();
        } catch (Throwable t) {
            on = false;
        }
        return on;
    }

    /** 立即改写开关（助理按钮用）。 */
    public static boolean set(boolean enable) {
        File0(enable);
        return on;
    }

    private static void File0(boolean enable) {
        try {
            java.io.File f = new java.io.File(FLAG);
            if (enable) {
                if (!f.exists()) {
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    try { fo.write((System.currentTimeMillis() + "\n").getBytes("UTF-8")); }
                    finally { try { fo.close(); } catch (Throwable ignored) {} }
                }
            } else if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {}
        on = enable;
        checkedAt = SystemClock.elapsedRealtime();
    }

    public static void e(String tag, String msg) { if (on()) Log.e(tag, msg); }
    public static void w(String tag, String msg) { if (on()) Log.w(tag, msg); }
    public static void i(String tag, String msg) { if (on()) Log.i(tag, msg); }
    public static void d(String tag, String msg) { if (on()) Log.d(tag, msg); }
    public static void e(String tag, String msg, Throwable t) { if (on()) Log.e(tag, msg, t); }
    public static void w(String tag, String msg, Throwable t) { if (on()) Log.w(tag, msg, t); }
    public static void e(String tag, Throwable t) { if (on()) Log.e(tag, "", t); }
}
