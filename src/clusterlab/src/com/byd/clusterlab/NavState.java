package com.byd.clusterlab;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 导航状态，优先级从高到低：
 * 1. 手动覆盖 /sdcard/clusterlab_nav.txt（1=导航中 0=未导航）
 * 2. 仪表屏左上角有没有高德导航卡片（截图像素判定，见 NavProbe）—— 主判据
 * 3. 无障碍文本兜底："288 米 后" / "5.2公里 16分钟" / "14:35 到"
 *
 * 踩过的坑，别再试：
 *   - MeterActivity 窗口常驻（引导页也在）→ 不能当判据
 *   - NaviService 高德一启动就有 → 不能当判据
 *   - 画中画：本项目的高德是把卡片投到 display 1，不是 PiP
 *   - uiautomator dump 在车机上被 OOM kill（rc=137）
 */
public final class NavState {
    public static volatile boolean navigating = false;
    public static volatile String source = "none";

    private static long lastTrueAt = 0L;
    /** 最后一次收到高德广播的时刻（不管 EXTRA_STATE 是 0 还是非 0）。 */
    private static volatile long lastBroadcastAt = 0L;

    private static volatile long boostUntil = 0L;

    /** 有事件提示导航状态可能变了（高德广播 / 无障碍事件）：短时间把探测提到 300ms 一轮。
     *  这是「大小窗切换慢」的正解：平时低频省 CPU，有事立刻高频确认。 */
    public static void boost(long ms) {
        long t = android.os.SystemClock.elapsedRealtime() + ms;
        if (t > boostUntil) boostUntil = t;
    }

    static boolean boostActive() {
        return android.os.SystemClock.elapsedRealtime() < boostUntil;
    }

    /** 高德广播 90 秒内有效：这段时间完全不需要截图探测（screencap 是全场最贵的一次操作）。 */
    public static boolean broadcastFresh() {
        return android.os.SystemClock.elapsedRealtime() - lastBroadcastAt < 3000L;
    }

    /** 高德广播链路还活着：只要 3 秒内来过包，就不必高频截图（screencap 一次 5.5MB）。 */
    public static void noteBroadcast() {
        lastBroadcastAt = android.os.SystemClock.elapsedRealtime();
    }

    private static long lastOverrideRead = 0L;
    private static boolean overrideOn = false;
    private static boolean overrideValue = false;

    private NavState() {}

    /** false 的时候如果刚才是 true，且在保持期内，就不改（卡片文本会闪）。 */
    public static void setWithHold(boolean nav, String src, long holdMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (nav) { lastTrueAt = now; set(true, src); return; }
        if (navigating && now - lastTrueAt < holdMs) return;
        set(false, src);
    }

    public static void set(boolean nav, String src) {
        if (nav != navigating) {
            Dbg.e("CLUSTERLAB-NAV", "导航状态: " + (nav ? "开始" : "结束") + " (" + src + ")");
        NavLog.w("导航状态: " + (nav ? "开始" : "结束") + " 来源=" + src + " 采样=" + NavProbe.lastRatio, true);
        }
        navigating = nav;
        source = src;
        boost(4000);
        // 立刻通知界面重绘，不等下一个绘制周期
        Runnable cb = onChange;
        if (cb != null) {
            try { cb.run(); } catch (Throwable ignored) {}
        }
    }

    /** 界面注册的重绘回调（CardActivity 在 onCreate 里设）。 */
    public static volatile Runnable onChange = null;

    /**
     * 最终状态：手动覆盖 > 仪表导航卡片（像素） > 无障碍文本。
     *
     * 绝对不能在这里做实际测量：isNavigating() 每帧（16ms）都会被 onDraw /
     * ensureStatic / drawLyrics 各调一次，而 NavProbe.sample() 要起一次
     * screencap 子进程 + 读 raw 文件（实测 200~500ms）。放在这里 = 每帧阻塞
     * UI 线程几百毫秒 = 歌词动画整块卡死、掉帧到个位数。
     *
     * 所以这里只读缓存值，真正的测量全部交给 startPoller() 的后台线程。
     * readOverride() 自己也带 2s 节流，代价可忽略。
     */
    public static boolean isNavigating() {
        readOverride();
        if (overrideOn) return overrideValue;
        startPoller();
        return dashCard || navigating;
    }

    /** 仪表上有没有高德导航卡片（像素判定，见 NavProbe）。 */
    private static volatile boolean dashCard = false;
    private static volatile boolean pollerStarted = false;
    private static int confirmCount = 0;
    private static volatile long lastSwitchAt = 0L;

    private static void startPoller() {
        if (pollerStarted) return;
        pollerStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {

                    try {
                        // sample() 返回 false 有两种情况：真的没卡片，或者截图失败。
                        // 用 lastRatio<0 区分：<0 表示这一轮没测出来，直接跳过，别改状态。
                        boolean on = NavProbe.sample();
                        if (NavProbe.lastRatio < 0 && !on) {
                            // 截图失败/空帧：本帧不参与判定（注意别 continue 跳过下面的 sleep，会变成死循环截图）
                            try { Thread.sleep(700); } catch (Throwable ignored) {}
                            continue;
                        }
                        // 连续 3 次一致（0.5s × 3 ≈ 1.5 秒）才切换，
                        // 避免 screencap 偶尔拿到空帧导致大小窗来回跳
                        // 刚切过就锁 1.5 秒：防止高德重绘把状态来回翻。
                        if (on == dashCard
                                || android.os.SystemClock.elapsedRealtime() - lastSwitchAt < 1500L) {
                            confirmCount = 0;
                        } else {
                            confirmCount++;
                            // 进出都要 3 次一致才切：单次误判就翻窗，用户会觉得「切错窗口」。
                            // boost 期间 300ms 一轮，3 次 ≈ 0.9 秒，速度不受影响。
                            // 进入 3 次、退出 4 次。实测高德刷新卡片的一两帧会整块变亮
                            // （暗底 95% → 0%，蓝条 100% → 0%），2 次确认会被这种帧骗过去，
                            // 表现就是「1 秒内小窗↔大窗连翻两次」。
                            int need = dashCard ? 4 : 3;
                            if (confirmCount >= need) {
                                Dbg.e("CLUSTERLAB-NAV", "布局切换: "
                                        + (on ? "小窗(导航中)" : "大窗(未导航)"));
                                NavLog.w("布局切换: " + (on ? "小窗" : "大窗") + " 来源=dash", true);
                                dashCard = on;
                                confirmCount = 0;
                                lastSwitchAt = android.os.SystemClock.elapsedRealtime();
                                set(on, "dash");
                            }
                        }
                    } catch (Throwable ignored) {}
                    // screencap 自己就要 200~500ms，再 sleep(10) 只是空转抢 CPU，
                    // 给 120ms 让位、也顺便压低一次测量的调用频率。
                    // 兜底探测降频：没导航 5 秒一次、导航中 3 秒一次就够了，
                    // 2~3 秒的切换延迟用户感知不到，但省掉 90% 的截图开销。
                    // 节拍：高德广播在报（导航中）= 800ms 一轮；广播停了但刚有事件 = 300ms；
                    // 平时 2 秒。常驻 300ms 会把 CPU 顶到 20%+（实测 screencap 5.5MB/次）。
                    try {
                        long gap;
                        if (broadcastFresh()) gap = 800;
                        else if (boostActive()) gap = 300;
                        else gap = dashCard ? 500 : 700;
                        Thread.sleep(gap);
                    } catch (Throwable ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void readOverride() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOverrideRead < 2000) return;
        lastOverrideRead = now;
        File f = new File("/sdcard/clusterlab_nav.txt");
        if (!f.exists()) { overrideOn = false; return; }
        boolean on = false, val = false;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            if (line != null) {
                String v = line.trim();
                if (v.equals("1")) { on = true; val = true; }
                else if (v.equals("0")) { on = true; val = false; }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        overrideOn = on;
        overrideValue = val;
    }
}
