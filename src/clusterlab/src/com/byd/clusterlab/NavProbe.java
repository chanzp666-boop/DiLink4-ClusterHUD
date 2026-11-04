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

    /** 从 raw 里读某一行的 [x0,x1) 段，返回 RGBA 字节数组。 */
    private static byte[] readRow(java.io.RandomAccessFile f, long dataStart,
                                  int w, int y, int x0, int x1) {
        try {
            int rowBytes = w * 4;
            int start = Math.min(x0, w);
            int end = Math.min(x1, w);
            if (end <= start) return null;
            f.seek(dataStart + (long) y * rowBytes + (long) start * 4);
            byte[] buf = new byte[(end - start) * 4];
            f.readFully(buf);
            return buf;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean analyze(int[] img) {
        int w = img[0], h = img[1];

        // 1) 卡片本体在不在：上半区有多少"极暗底色"（卡片底色 #0A0F17，地图 #3A495A）
        int tot = 0, dark = 0;
        for (int y = Y0; y < Y1 && y < h; y += 2) {
            for (int x = X0; x < X1 && x < w; x += 2) {
                int c = img[2 + y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                tot++;
                // 卡片底色：深色模式 #0A0F17=(10,15,23)，浅色模式同样是很深的一层
                // 实测浅色模式卡片内 (36,49,63)、深色模式 (10,15,23)，都远暗于地图
                // 深色地图最暗 ~ (20,25,35)，浅色地图 (193,217,238)
                // 用"明显暗于地图"的双阈值判断
                boolean veryDark = (r <= 12 && g <= 18 && b <= 26);
                boolean cardDark = (r <= 45 && g <= 58 && b <= 72);
                if (veryDark || cardDark) dark++;
            }
        }
        if (tot == 0) { lastRatio = -1f; return lastResult; }
        float darkRatio = dark * 100f / tot;
        // 实测：卡片在时占据相当面积；不在时只有地图噪声
        boolean hasCard = darkRatio >= 50f;   // 实测：导航 95%，未导航 0%

        // 2) 到达时间下方有没有蓝条：决定大卡 / 小卡
        //    注意排除我们的歌词（白字 + 蓝色高亮），否则小卡会误判成大卡
        int bt = 0, blue = 0, white = 0;
        for (int y = BY0; y < BY1 && y < h; y += 2) {
            for (int x = BX0; x < BX1 && x < w; x += 2) {
                int c = img[2 + y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                bt++;
                if (r > 200 && g > 200 && b > 200) white++;
                if (b > 190 && b - r > 120 && g > 70 && g < 165) blue++;
            }
        }
        lastRatio = bt == 0 ? -1f : blue * 100f / bt;
        float whiteRatio = bt == 0 ? 0f : white * 100f / bt;
        // 这块是我们的白色歌词时（白色占比高），说明音乐在小卡位置（大卡时音乐在 402 不会盖到 330~390），
        // 所以直接判小卡，而不是"跳过更新"——跳过会沿用旧值，导致永远卡在大卡。
        if (whiteRatio >= 12f) {
            hasArrowBar = false;
        } else {
            hasArrowBar = lastRatio >= BLUE_THRESHOLD;
        }
        cardBottom = hasArrowBar ? 390 : 326;

        Dbg.e("CLUSTERLAB-NAV", "采样: 卡片暗底 "
                + String.format(java.util.Locale.US, "%.1f", darkRatio)
                + "% 蓝条 " + String.format(java.util.Locale.US, "%.1f", lastRatio)
                + "% → " + (hasArrowBar ? "大卡" : "小卡") + " 导航=" + hasCard);
        lastResult = hasCard;
        return hasCard;
    }

    /** 解 PNG（8bit RGB/RGBA、无交错）。返回 {w, h, ...像素}。 */
    private static int[] readPng(String path) {
        java.io.DataInputStream in = null;
        try {
            in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    new java.io.FileInputStream(path), 1 << 16));
            if (in.readInt() != 0x89504E47) return null;
            in.readInt();
            int w = 0, h = 0, bitDepth = 8, colorType = 6;
            java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
            while (true) {
                int len = in.readInt();
                int type = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                in.readInt();
                if (type == 0x49484452) {
                    w = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                            | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    h = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                            | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                    bitDepth = data[8] & 0xFF;
                    colorType = data[9] & 0xFF;
                } else if (type == 0x49444154) {
                    idat.write(data);
                } else if (type == 0x49454E44) {
                    break;
                }
            }
            if (bitDepth != 8 || (colorType != 2 && colorType != 6)) return null;
            int ch = colorType == 6 ? 4 : 3;
            byte[] raw = unzip(idat.toByteArray());
            if (raw == null) return null;

            int stride = w * ch;
            int[] out = new int[2 + w * h];
            out[0] = w; out[1] = h;
            byte[] prev = new byte[stride];
            byte[] cur = new byte[stride];
            int p = 0;
            for (int y = 0; y < h; y++) {
                int filter = raw[p++] & 0xFF;
                System.arraycopy(raw, p, cur, 0, stride);
                p += stride;
                unfilter(filter, cur, prev, ch);
                for (int x = 0; x < w; x++) {
                    int i = x * ch;
                    int r = cur[i] & 0xFF, g = cur[i + 1] & 0xFF, b = cur[i + 2] & 0xFF;
                    out[2 + y * w + x] = (r << 16) | (g << 8) | b;
                }
                byte[] t = prev; prev = cur; cur = t;
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 最近一次"干净帧"的导航结论。 */
    private static boolean lastResult = false;

    /**
     * 检测我们自己的窗口是否进入了扫描区。
     * 音乐窗口特征：左上角有圆角深色封面（#101A2B 附近），旁边是蓝色歌词（#1170FF）。
     * 只要在扫描区的左列 (x 200~260) 出现"纯黑封面"就认为被污染。
     */
    private static boolean contaminated(int[] img, int w, int h) {
        int dark = 0, tot = 0;
        for (int y = 340; y < 420 && y < h; y += 2) {
            for (int x = 190; x < 250 && x < w; x += 2) {
                int c = img[2 + y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                tot++;
                // 封面是纯黑/接近纯黑（#101A2B），地图底色是 #324054 明显更亮
                if (r < 25 && g < 35 && b < 50) dark++;
            }
        }
        return tot > 0 && dark * 100f / tot > 40f;
    }

    private static void unfilter(int f, byte[] cur, byte[] prev, int ch) {
        int n = cur.length;
        switch (f) {
            case 1:
                for (int i = ch; i < n; i++) cur[i] = (byte) (cur[i] + cur[i - ch]);
                break;
            case 2:
                for (int i = 0; i < n; i++) cur[i] = (byte) (cur[i] + prev[i]);
                break;
            case 3:
                for (int i = 0; i < n; i++) {
                    int a = i >= ch ? cur[i - ch] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    cur[i] = (byte) ((cur[i] & 0xFF) + ((a + b) >> 1));
                }
                break;
            case 4:
                for (int i = 0; i < n; i++) {
                    int a = i >= ch ? cur[i - ch] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= ch ? prev[i - ch] & 0xFF : 0;
                    int pp = a + b - c;
                    int pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                    int pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    cur[i] = (byte) ((cur[i] & 0xFF) + pr);
                }
                break;
            default:
                break;
        }
    }

    private static byte[] unzip(byte[] in) {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        try {
            inf.setInput(in);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(in.length * 4);
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { inf.end(); } catch (Throwable ignored) {}
        }
    }
}
