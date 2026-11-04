package com.byd.clusterlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * overlay 侧自愈（后备）。主守护在「仪表助理」com.byd.clusterassist 里，
 * 这里保留一份，保证只装 overlay 时也能自愈。
 */
public class KeepAlive extends Service {
    private static final String CH = "cluster_keepalive";
    private static final int NOTI_ID = 1001;
    private static final String LAUNCH =
            "am start-activity --display 1 -n com.byd.clusterlab/.CardActivity";
    /**
     * display 1 最上层可见窗口属于哪个包。
     * 用途：判断「我们是被人盖住了，还是用户自己选择了投屏导航」。用户选全屏/小屏导航时，
     * 车机会把导航 App 顶在最上层，这时候绝不能抢回来 —— 那是用户要的界面。
     */
    static final String CHECK =
            // 只看「有没有 ActivityRecord」不够：v4.9.1 现场遇到过 record 在、窗口 isVisible=false（GONE），
            // 于是自愈以为卡片活着、再也不拉，界面就一直空着。改成直接问窗口管理器「可见吗」。
            "dumpsys window windows | awk '/Window #/ {w=$0} /isVisible=true/ && w ~ /clusterlab/ {c++} END{print c+0}'"
            ;

    /** 卡片界面活着就每 10 秒戳一下这个时间戳：健康时完全不走 adb，adbd 上零连接。 */
    /**
     * 卡片心跳：从 v4.9 起用**文件**而不是静态变量 —— KeepAlive 已经被拆到 :keep 独立进程，
     * 静态字段不跨进程，主进程写的 cardBeat 在 :keep 里永远读不到。
     */
    public static final String HB = "/sdcard/clusterlab_card_beat.txt";

    /** 卡片每次心跳调一次（主进程 -> 文件）。 */
    public static void beat() {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(HB, false);
            try { o.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8")); }
            finally { try { o.close(); } catch (Throwable ignored) {} }
        } catch (Throwable ignored) {}
    }

    /** 心跳距今多少毫秒；没文件返回 Long.MAX_VALUE。 */
    public static long beatAge() {
        java.io.File f = new java.io.File(HB);
        if (!f.exists()) return Long.MAX_VALUE;
        return System.currentTimeMillis() - f.lastModified();
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean hooked = false;
    private long lastMediaFix = 0L;
    private long lastA11yFix = 0L;

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(CH, "ClusterLab",
                        NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("仪表助理")
                    .setContentText("运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .build();
            startForeground(NOTI_ID, n);
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-KEEP", "startForeground failed: " + t);
        }
        hook();
        h.postDelayed(tick, 15000);
    }

    private void hook() {
        if (hooked) return;
        hooked = true;
        // v4.0：不再依赖 Shizuku binder。ADB 连接是自建的，连上就立刻 heal 一次。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    YBAdb.connect(KeepAlive.this);
                    for (int i = 0; i < 30 && !YBAdb.ready(); i++) Thread.sleep(500);
                } catch (Throwable ignored) {}
                h.removeCallbacks(tick);
                h.post(tick);
            }
        }, "keep-adb-connect").start();
    }

    /**
     * tick 跑在主线程。这里绝不能做 YBAdb.exec / sleep：
     * 本进程同时承载无障碍服务 CarAccess，它的绑定回调也在主线程，
     * 主线程一被占住，系统就判定绑定超时，服务永远卡在 Binding services 里。
     * 所以所有 heal 都丢到后台线程。
     */
    private volatile boolean tickBusy = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!tickBusy) {
                tickBusy = true;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { heal(); } catch (Throwable ignored) {}
                        try { healMedia(); } catch (Throwable ignored) {}
                        try { healAccessibility(); } catch (Throwable ignored) {}
                        tickBusy = false;
                    }
                }, "keep-heal");
                t.setDaemon(true);
                t.start();
            }
            h.postDelayed(this, 3000);
        }
    };

    /**
     * 音乐不显示的根因几乎都是这个：force-stop / quickboot 之后通知监听被解绑，
     * 而 secure 里的 enabled_notification_listeners 条目还在，光看设置永远判"已绑定"。
     * 这里直接看 MediaListener 自己上报的真实连接状态，掉了就 disallow → allow 重绑。
     */
    /** 连续几次检测到"没界面"才 relaunch，避免和 BootReceiver 抢跑。 */
    private int goneCount = 0;
    /** 上次「心跳新鲜时」的可见性检查时刻（20 秒节流）。 */
    private long lastVisCheck = 0L;
    /** 上次无动画抬升的时刻，抬升无效时别连着重试。 */
    private long lastRaise = 0L;
    /** 本进程启动时刻。 */
    private final long bornAt = android.os.SystemClock.elapsedRealtime();

    /**
     * 保证 display 1 上有我们的界面。
     * 只做"真的没有才拉"，绝不主动杀进程（那会和助理 watchdog 形成死循环）。
     */
    /**
     * 自愈：只做一件事 —— 检测到仪表助理没显示，就重拉一次。
     * 判据只看窗口管理器（dumpsys window windows 里我们那扇窗 isVisible=true），
     * 心跳新鲜说明进程活着，但被别的窗口压住时心跳也是新鲜的，所以不用心跳当判据。
     * 每 3 秒查一次，连续两次（约 6 秒）都看不见才动手，避免和 BootReceiver / 面板抢跑。
     */
    private void heal() {
        if (!YBAdb.ready()) return;
        // 开机前 25 秒不检查：BootReceiver 正在启动，窗口创建要几百毫秒。
        if (android.os.SystemClock.elapsedRealtime() - bornAt < 25000) return;

        String out = YBAdb.exec(CHECK, 6000);
        boolean alive = out != null && out.trim().length() > 0 && !out.trim().equals("0");
        if (alive) { goneCount = 0; return; }

        goneCount++;
        if (goneCount < 2) {
            Dbg.e("CLUSTERLAB-KEEP", "仪表助理没显示，待确认 " + goneCount);
            return;
        }
        goneCount = 0;
        Dbg.e("CLUSTERLAB-KEEP", "仪表助理没显示，自愈重拉 display 1");
        YBAdb.exec(LAUNCH, 10000);
        try { Thread.sleep(900); } catch (Throwable ignored) {}
        YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
        YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
    }


    private void healMedia() {
        if (MediaListener.connected) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastMediaFix < 60000) return;
        lastMediaFix = now;
        Dbg.e("CLUSTERLAB-KEEP", "media listener 未连接，重绑通知监听");
        YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
        try { Thread.sleep(1200); } catch (Throwable ignored) {}
        YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
        try { Thread.sleep(600); } catch (Throwable ignored) {}
        Dbg.e("CLUSTERLAB-KEEP", "media rebind 后 connected=" + MediaListener.connected);
    }

    /**
     * 迪+方控(com.byd.ls) 会不定期改写 enabled_accessibility_services，
     * 把我们的 CarAccess 挤掉 → 抓不到迪友的实时油耗，只能回落到累计值。
     * 这里每 20s 校验一次，缺了就补回去。
     */
    /**
     * 上电后最常见的故障：无障碍服务卡在 Binding（settings 列表里有，但系统没绑上），
     * 于是 CarAccess 一行代码都跑不到 → 迪友抓不到 → 油耗回落成错的值。
     * 所以这里必须查真实的 Bound 列表，而不是看 settings。
     */
    /**
     * 只补自己，绝不碰别人。
     *
     * 历史教训（用户真机实测）：旧版每 20 秒 get -> 拼接 -> settings put，
     * 为了触发重绑还会先把本服务摘掉再写回。迪+方控(com.byd.ls) 也在改同一个全局列表，
     * 两边一抢，我们写回的旧列表就把迪加 KDService / 迪友 AutoService 顶掉了 ——
     * 用户看到的就是「仪表助理一跑，迪加和迪友桌面就掉无障碍」。
     *
     * 现在的规则：
     *   1. 只读一次当前列表，只在「列表里没有我们」时把 self 追加到末尾（不删不改别人的条目）；
     *   2. 绝不做「先摘掉再写回」这种重绑动作，也不碰 accessibility_enabled；
     *   3. 6 小时内最多补一次（SharedPreferences 记时间戳），杜绝循环写全局设置。
     */
    private void healAccessibility() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastA11yFix < 60000) return;
        lastA11yFix = now;
        if (!YBAdb.ready()) return;

        final String me = "com.byd.clusterlab/com.byd.clusterlab.CarAccess";
        String cur = YBAdb.exec("settings get secure enabled_accessibility_services", 5000);
        if (cur == null) return;
        cur = cur.trim();
        if (cur.contains(me)) return;                 // 已经在列表里，什么都不做

        SharedPreferences sp = getSharedPreferences("clusterlab", MODE_PRIVATE);
        long last = sp.getLong("a11yTryAt", 0L);
        if (System.currentTimeMillis() - last < 6L * 3600L * 1000L) return;
        sp.edit().putLong("a11yTryAt", System.currentTimeMillis()).apply();

        String fixed = (cur.isEmpty() || cur.equals("null")) ? me : (cur + ":" + me);
        Dbg.e("CLUSTERLAB-KEEP", "补自己的无障碍条目（只追加）: " + fixed);
        YBAdb.exec("settings put secure enabled_accessibility_services '" + fixed + "'", 5000);
        String after = YBAdb.exec("settings get secure enabled_accessibility_services", 5000);
        if (after != null) Dbg.e("CLUSTERLAB-KEEP", "补后列表: " + after.trim());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        hook();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
