package com.byd.clusterlab;

/**
 * 导航状态 + 导航卡片高度探针：截仪表屏（display 1）分析像素。
 *
 * 判据用"导航蓝"（高德卡片下半部那条饱和蓝 #237BD6）：
 *   地图底色是低饱和深灰蓝 (#324054)，蓝通道减红通道只有 30 左右；
 *   导航蓝的 b-r ≈ 160，区分度极大。
 *
 * 同时测出蓝条的上边界，用来区分大卡 / 小卡：
 *   大卡：蓝条在 y≈330 起步（三行信息：距离、路名、到达时间）
 *   小卡：蓝条更靠上（只有距离和箭头）
 */
public final class NavProbe {
    /**
     * 判据（按用户方案）：
     *   高德卡片里"到达时间"那一行位置是固定的（y≈300~315），
     *   它下面是蓝条 → 大卡；什么也没有 → 小卡。
     *
     * 扫描区只取 x 200~540 / y 320~400：
     *   - 大卡：这一带是整片导航蓝
     *   - 小卡：这一带是地图底色（或我们的音乐，但音乐在 y>=340 且只在 x<400）
     * 为彻底避开自家窗口，横向只扫 x 420~540（卡片右半，音乐到不了）。
     */
    /** 卡片本体扫描区：x 200~520 / y 200~320（卡片上半，音乐永远不会到这里） */
    /**
     * 导航判定只扫"卡片顶部这条带" y 172~196：
     * 两种卡片都从 y=170 开始，而我们的音乐窗口最早从 y=198 才开始画，
     * 所以这条带永远干净（实测导航时 95%，未导航 0%）。
     */
    private static final int X0 = 200, X1 = 540;
    private static final int Y0 = 172, Y1 = 196;
    /**
     * 大小卡判定区：y 328~338（移动窗口最低 y=340，这段永远不被遮挡）。
     *   大卡：这里已经是蓝条（实测蓝条 325~390）→ 蓝色占比高
     *   小卡：卡片在 326 就结束了，这里是地图底色 → 蓝色占比接近 0
     */
    private static final int BX0 = 200, BX1 = 540;
    private static final int BY0 = 328, BY1 = 338;

    /** 蓝条判定：蓝色占比超过这个值就是大卡。 */
    private static final float BLUE_THRESHOLD = 30f;

    /** 卡片底边（调试用）。 */
    public static volatile int cardBottom = -1;
    /** 是否检测到大卡（到达时间下方有蓝条）。 */
    public static volatile boolean hasArrowBar = false;
    /** 最近一次蓝色占比（调试用）。 */
    public static volatile float lastRatio = -1f;
    /** 兼容旧字段。 */
    public static volatile int blueTop = -1;

    private NavProbe() {}

    public static volatile long lastMs = 0;

    private static final String RAW = "/data/local/tmp/.nav_probe.raw";

    /**
     * 采样一次。
     * 用 raw 格式截图（比 PNG 快一倍：200ms vs 500ms），
     * 并只从文件里读需要的那两段行，不做整图解码。
     */
    public static boolean sample() {
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            YBAdb.exec("screencap -d 1 " + RAW + " 2>/dev/null; echo ok", 6000);
            java.io.RandomAccessFile f = new java.io.RandomAccessFile(RAW, "r");
            try {
                int w = readIntLE(f);
                int h = readIntLE(f);
                readIntLE(f);                       // format
                long dataStart = f.length() - (long) w * h * 4;
                if (w <= 0 || h <= 0 || dataStart < 0) return lastResult;

                // 1) 导航判定：卡片顶部那一条（y 172~196）
                int dark = 0, wht = 0, tot = 0;
                for (int y = Y0; y < Y1 && y < h; y += 2) {
                    byte[] row = readRow(f, dataStart, w, y, X0, X1);
                    if (row == null) continue;
                    for (int i = 0; i + 2 < row.length; i += 4) {
                        int r = row[i] & 0xFF, g = row[i + 1] & 0xFF, b = row[i + 2] & 0xFF;
                        tot++;
                        if ((r <= 12 && g <= 18 && b <= 26) || (r <= 45 && g <= 58 && b <= 72)) dark++;
                        else if (r >= 200 && g >= 200 && b >= 200) wht++;   // 卡片上的白字/箭头
                    }
                }
                if (tot == 0) return lastResult;
                float darkRatio = dark * 100f / tot;
                float whiteRatio = wht * 100f / tot;
                // 只看暗底会在迪友暗色桌面/地图全屏时误判；卡片顶部必然有白字或图标，
                // 所以要求：暗底够多 **且** 有白字。
                boolean hasCard = darkRatio >= 55f && whiteRatio >= 1.0f;

                // 2) 大小卡判定：到达时间下方那一段（y 330~390）
                int blue = 0, white = 0, bt = 0;
                for (int y = BY0; y < BY1 && y < h; y += 2) {
                    byte[] row = readRow(f, dataStart, w, y, BX0, BX1);
                    if (row == null) continue;
                    for (int i = 0; i + 2 < row.length; i += 4) {
                        int r = row[i] & 0xFF, g = row[i + 1] & 0xFF, b = row[i + 2] & 0xFF;
                        bt++;
                        if (r > 200 && g > 200 && b > 200) white++;
                        if (b > 190 && b - r > 120 && g > 70 && g < 165) blue++;
                    }
                }
                if (bt > 0) {
                    lastRatio = blue * 100f / bt;
                    hasArrowBar = lastRatio >= BLUE_THRESHOLD;
                }
                cardBottom = hasArrowBar ? 390 : 326;

                String line = "采样: 暗底 "
                        + String.format(java.util.Locale.US, "%.1f", darkRatio)
                        + "% 白字 " + String.format(java.util.Locale.US, "%.1f", whiteRatio)
                        + "% 蓝条 " + String.format(java.util.Locale.US, "%.1f", lastRatio)
                        + "% → " + (hasArrowBar ? "大卡" : "小卡") + " 导航=" + hasCard;
                Dbg.e("CLUSTERLAB-NAV", line);
                NavLog.w(line, false);
                lastResult = hasCard;
                return hasCard;
            } finally {
                try { f.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            return lastResult;
        } finally {
            // 不再每次 rm：文件会被下一次 screencap 覆盖，省掉一次 adb 往返+握手
            lastMs = android.os.SystemClock.elapsedRealtime() - t0;
        }
    }

    private static int readIntLE(java.io.RandomAccessFile f) throws java.io.IOException {
        int b0 = f.read(), b1 = f.read(), b2 = f.read(), b3 = f.read();
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }


    /** 只读文件里某一行（y）的 x0..x1 段，4 字节/像素。 */
    private static byte[] readRow(java.io.RandomAccessFile f, long dataStart,
                                 int w, int y, int x0, int x1) throws java.io.IOException {
        int start = (x0 >= 0 && x1 > x0 && x1 <= w) ? x0 : 0;
        int end = (x0 >= 0 && x1 > x0 && x1 <= w) ? x1 : w;
        if (end <= start) return null;
        f.seek(dataStart + (long) y * w * 4L + (long) start * 4L);
        byte[] buf = new byte[(end - start) * 4];
        f.readFully(buf);
        return buf;
    }

    private static boolean lastResult = false;
}
