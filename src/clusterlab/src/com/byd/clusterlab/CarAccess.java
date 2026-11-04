package com.byd.clusterlab;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.regex.Pattern;

/**
 * 两个用途：
 *  1) 从迪友桌面（com.dy.launcher）卡片抓「最近能耗」的实时值；
 *  2) 判定高德（com.byd.automap）是否在导航——画中画存在，或主窗口里读到"剩余 x 公里 / xx:xx 到"。
 * 画中画时 getRootInActiveWindow() 拿不到高德，必须遍历 getWindows() 按类型筛。
 */
public class CarAccess extends AccessibilityService {
    private static final String DY = "com.dy.launcher";
    private static final String AMAP = "com.byd.automap";

    private static final Pattern REMAIN = Pattern.compile("剩余\\s*\\d+(\\.\\d+)?\\s*(公里|米|km|m)");
    // 高德投屏卡片上的转向指令："187米后"、"随后281米"
    private static final Pattern TURN = Pattern.compile("\\d+\\s*(米|公里)\\s*后|随后\\s*\\d+\\s*米");

    private final Handler h = new Handler(Looper.getMainLooper());

    private long lastBaselineTry = 0L;
    private int baselineTries = 0;

    /**
     * 注意：tick 跑在主线程。无障碍服务的绑定回调同样在主线程，
     * 如果这里做 YBAdb.exec / Thread.sleep 之类的阻塞操作，系统会判定绑定超时，
     * 服务就永远卡在 "Binding services" 里，迪友数据也就抓不到。
     * 所以耗时任务一律丢到后台线程，主线程只做毫秒级的窗口遍历。
     */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { parseDiYou(); } catch (Throwable ignored) {}
            kickBaselineWorker();       // 只置标志 + 起线程，绝不阻塞
            try { checkNav(); } catch (Throwable t) {
                Dbg.e("CLUSTERLAB-NAV", "检查失败: " + t);
            }
            h.postDelayed(this, 600);
        }
    };

    /** 后台基线线程是否在跑，防重入。 */
    private volatile boolean baselineBusy = false;

    private void kickBaselineWorker() {
        if (baselineBusy) return;
        if (VehicleData.dyElec >= 0 && VehicleData.dyFuel >= 0) return;
        baselineBusy = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { ensureBaseline(); } catch (Throwable ignored) {}
                baselineBusy = false;
            }
        }, "dy-baseline");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 开机后必须建立迪友基线：没抓到时把迪友拉到前台抓一次，200ms 后切回原应用。
     * 只在"从没抓到过"时做，抓到之后永不打扰。
     */
    private void ensureBaseline() {
        boolean hasBaseline = VehicleData.dyElec >= 0 && VehicleData.dyFuel >= 0;
        if (hasBaseline) { baselineTries = 0; return; }

        long now = android.os.SystemClock.elapsedRealtime();
        // 开机阶段抓得积极些：前 5 次每 2 秒试一遍，之后放宽到 8 秒
        long gap = baselineTries < 5 ? 2000L : 8000L;
        if (now - lastBaselineTry < gap) return;
        if (baselineTries > 60) return;              // 约 8 分钟后放弃
        lastBaselineTry = now;
        baselineTries++;

        if (!YBAdb.ready()) {
            Dbg.e("CLUSTERLAB-A11Y", "ADB 未就绪，暂时无法拉起迪友");
            return;
        }
        Dbg.e("CLUSTERLAB-A11Y", "还没建立迪友基线，拉前台抓一次（第 " + baselineTries + " 次）");
        YBAdb.exec("am start-activity --display 0 -n com.dy.launcher/.ui.activity.MainActivity", 8000);
        try { Thread.sleep(700); } catch (Throwable ignored) {}
        try { parseDiYou(); } catch (Throwable ignored) {}
        // 抓完立刻把原应用切回去（迪友是桌面，用 HOME 回不到，这里用 clusterlab 自身的桌面角色兜底）
        YBAdb.exec("input keyevent KEYCODE_HOME", 5000);
        boolean ok = VehicleData.dyElec >= 0;
        Dbg.e("CLUSTERLAB-A11Y", "基线" + (ok ? "建立成功: 电耗 " + VehicleData.dyElec
                + " 油耗 " + VehicleData.dyFuel : "未建立"));
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e == null) return;
            CharSequence pkg = e.getPackageName();
            if (pkg == null) return;
            if (DY.contentEquals(pkg)) parseDiYou();
            // 只认高德/迪友自己的事件。别对所有 TYPE_WINDOW_CONTENT_CHANGED 提速——
            // 我们自己的 HUD 每帧都在改内容，会给无障碍刷事件，一提速就变成全程 300ms 采样。
            if (AMAP.contentEquals(pkg) || DY.contentEquals(pkg)) {
                NavState.boost(6000);
            }
        } catch (Throwable ignored) {}
    }

    /** 遍历所有交互窗口，找高德的画中画 / 主窗口导航文本。 */
    private long lastDump = 0L;
    private long lastEmptyLog = 0L;

    private void checkNav() {
        boolean nav = false;
        String src = "none";
        java.util.List<AccessibilityWindowInfo> wins = getWindows();
        if (wins == null || wins.isEmpty()) {
            long t = android.os.SystemClock.elapsedRealtime();
            if (t - lastEmptyLog > 30000) {
                lastEmptyLog = t;
                Dbg.e("CLUSTERLAB-NAV", "getWindows 返回空，无障碍收不到窗口");
            }
        }
        // 调试：每 20 秒把窗口清单和高德窗口文本打出来
        long nowDump = android.os.SystemClock.elapsedRealtime();
        if (nowDump - lastDump > 20000) {
            lastDump = nowDump;
            StringBuilder dbg = new StringBuilder("窗口: ");
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    CharSequence p = r == null ? null : r.getPackageName();
                    int dpy = android.os.Build.VERSION.SDK_INT >= 30 ? w.getDisplayId() : -1;
                    dbg.append("{t=").append(w.getType())
                       .append(" dpy=").append(dpy)
                       .append(" pip=").append(android.os.Build.VERSION.SDK_INT >= 26 && w.isInPictureInPictureMode())
                       .append(" pkg=").append(p == null ? "null" : p)
                       .append("}");
                    if (p != null && DY.contentEquals(p) && r.getChildCount() > 0) {
                        parseDiYouText(dump(r));
                    }
                    if (p != null && (AMAP.contentEquals(p) || DY.contentEquals(p))) {
                        String tx = dump(r);
                        dbg.append("[TEXT:").append(tx.length() > 300 ? tx.substring(0, 300) : tx).append("]");
                    }
                    dbg.append(' ');
                }
            }
            Dbg.e("CLUSTERLAB-NAV", dbg.toString());
        }
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (p == null || !AMAP.contentEquals(p)) continue;

                if (android.os.Build.VERSION.SDK_INT >= 26 && w.isInPictureInPictureMode()) {
                    nav = true;
                    src = "pip";
                    break;
                }
                // HUD 文本形如：288 | 米 | 后 | 广州大道北 | 5.2公里  16分钟 | 9 | 14:35 | 到 | …
                // 必须把 "|" 全去掉再匹配，否则 "14:35 | 到" 和 "288 | 米 | 后" 都断开了
                String text = dump(root).replace("|", " ");
                if (hudLooksLikeNav(text)) {
                    nav = true;
                    src = "hud";
                    break;
                }
            }
        }
        if (!nav) {
            // HUD 兜底：navigateToPip 读不到时，直接问无障碍——display 1 上高德卡片里的导航文本
            String hud = hudText();
            if (hud.length() > 0) {
                nav = true;
                src = "hud";
            }
        }
        // 只有"检测到导航"时才上报；判 false 不覆盖 NavProbe 的像素判定
        // （无障碍看不到 display 1，这里永远判 false，会把正确状态冲掉）
        if (nav) {
            NavState.setWithHold(true, src, 90000);
        }
    }

    /** 从所有窗口里挑出高德，读它的文本；用于 HUD（display 1）卡片。 */
    /**
     * HUD 的导航卡片必然带距离单位（m / km）或到达时间：
     *   288 m 后 / 5.2 km 16 分钟 / 14:36 到 / 剩余 3.2km
     * 只要命中其一就算导航中。中文"米/公里"和英文 m/km 都认。
     */
    private static final Pattern HUD_DIST = Pattern.compile(
            "\\d+(\\.\\d+)?\\s*(m|km|米|公里)(\\s*后)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HUD_MIN = Pattern.compile(
            "\\d+(\\.\\d+)?\\s*(km|公里)\\s*\\d+\\s*(min|分钟)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HUD_ETA = Pattern.compile("\\d{1,2}:\\d{2}\\s*到|到达时间");

    private boolean hudLooksLikeNav(String t) {
        if (HUD_DIST.matcher(t).find()) return true;
        if (HUD_MIN.matcher(t).find()) return true;
        if (HUD_ETA.matcher(t).find()) return true;
        if (REMAIN.matcher(t).find()) return true;
        return false;
    }

    private String hudText() {
        try {
            java.util.List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return "";
            for (AccessibilityWindowInfo w : ws) {
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (p == null || !AMAP.contentEquals(p)) continue;
                String t = dump(root).replace("|", " ");
                if (hudLooksLikeNav(t)) return t;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private String dump(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        collect(n, sb, 0);
        return sb.toString();
    }

    /**
     * 迪友卡片不在前台也要能读：遍历 getWindows() 找 com.dy.launcher 的窗口拿 root，
     * 不依赖事件回调，也不依赖它有焦点。
     */
    private void parseDiYou() {
        AccessibilityNodeInfo root = null;
        try {
            java.util.List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p != null && DY.contentEquals(p) && r.getChildCount() > 0) { root = r; break; }
                }
            }
        } catch (Throwable ignored) {}
        if (root == null) root = getRootInActiveWindow();
        if (root == null) return;
        parseDiYouText(dump(root));
    }

    /** 文本形如： … 7.0 | kWh | 最近能耗 | … 3.2 | L | 最近能耗 …（值/单位/标签 三元组） */
    private void parseDiYouText(String raw) {
        String[] tk = raw.split("\\s*\\|\\s*");
        double elec = -1, fuel = -1;
        for (int i = 2; i < tk.length; i++) {
            if (!"最近能耗".equals(tk[i].trim())) continue;
            String unit = tk[i - 1].trim();
            String v = tk[i - 2].trim();
            if ("kWh".equals(unit) && elec < 0) elec = num(v);
            else if ("L".equals(unit) && fuel < 0) fuel = num(v);
        }
        if (elec >= 0 && elec < 100 && fuel >= 0 && fuel < 100) {
            boolean changed = elec != VehicleData.dyElec || fuel != VehicleData.dyFuel;
            VehicleData.dyElec = elec;
            VehicleData.dyFuel = fuel;
            VehicleData.dyAt = android.os.SystemClock.elapsedRealtime();
            VehicleData.saveDyCache(elec, fuel);
            if (changed) {
                Dbg.e("CLUSTERLAB-A11Y", "迪友 最近能耗: 电耗 " + elec + " 油耗 " + fuel);
            }
        }
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 24) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(t).append(" | ");
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private double num(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Throwable t) { return -1; }
    }

    @Override public void onInterrupt() {}
}