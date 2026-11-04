package com.byd.clusterlab;

import java.io.File;
import java.io.FileWriter;

/** 导航判定专用日志：不跟 Dbg 走（Dbg 默认关），但只在状态变化和 30 秒一次的采样点写，体积可控。 */
final class NavLog {
    private static final String F = "/sdcard/clusterlab_nav.log";
    private static final long MAX = 64 * 1024;
    private static long lastAt = 0L;

    private NavLog() {}

    /** force=true 用于状态变化，必须记；false 用于周期采样，最多 30 秒一条。 */
    static synchronized void w(String s, boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastAt < 30000) return;
        lastAt = now;
        try {
            File f = new File(F);
            if (f.length() > MAX) {
                File old = new File(F + ".1");
                try { old.delete(); } catch (Throwable ignored) {}
                f.renameTo(old);
            }
            FileWriter fw = new FileWriter(f, true);
            try {
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
                fw.write(df.format(new java.util.Date()) + " " + s + "\n");
            } finally {
                try { fw.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}