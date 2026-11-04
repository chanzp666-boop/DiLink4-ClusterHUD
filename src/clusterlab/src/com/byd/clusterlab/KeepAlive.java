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
 * :keep 独立进程的自愈层。
 *
 * 为什么不跟 CardActivity 同进程：迪加的 MainService 跑在 :remote、开机后 22 小时没被杀，
 * 而我们原来同进程时，熄火清理把界面和守护一起带走。拆开后界面死了服务还在。
 *
 * 三层职责：
 *   heal()            —— 卡片不可见就 am start --display 1（3 秒一轮，连续两次即动作）
 *   healMedia()       —— 通知监听掉了就 disallow → allow 重绑（60 秒一次）
 *   healAccessibility()—— 只往全局列表末尾追加自己，绝不改别人的条目
 */
public class KeepAlive extends Service {
    private static final String CH = "cluster_keepalive";
    private static final int NOTI_ID = 1001;
    private static final String LAUNCH = BootReceiver.LAUNCH;
    private static final String CHECK = BootReceiver.CHECK;
    private static final String HALT = BootReceiver.HALT;

    /**
     * 卡片心跳：v4.9 起用**文件**而不是静态变量 —— KeepAlive 已被拆到 :keep 独立进程，
     * 静态字段不跨进程，主进程写的 cardBeat 在 :keep 里永远读不到。
     */
    public static final String HB = "/sdcard/clusterlab_card_beat.txt";

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

    static void pause(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean hooked = false;
    private long lastMediaFix = 0L;
    private long lastA11yFix = 0L;
    private long lastDogTry = 0L;

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
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    YBAdb.connect(KeepAlive.this);
                    for (int i = 0; i < 30 && !YBAdb.ready(); i++) pause(500);
                } catch (Throwable ignored) {}
                try { ShellDog.ensure(KeepAlive.this, "keep"); } catch (Throwable ignored) {}
                h.removeCallbacks(tick);
                h.post(tick);
            }
        }, "keep-adb-connect").start();
    }

    /**
     * tick 跑在主线程。这里绝不能做 YBAdb.exec / sleep：
     * 本进程同时承载无障碍服务 CarAccess，它的绑定回调也在主线程，
     * 主线程一被占住，系统就判定绑定超时，服务永远卡在 Binding services 里。
     */
    private volatile boolean tickBusy = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!tickBusy) {
                tickBusy = true;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { keepDog(); } catch (Throwable ignored) {}
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

    /** 看门狗自己也可能被清（ROM 重启、用户误杀），10 分钟补装一次，已跑则幂等跳过。 */
    private void keepDog() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastDogTry < 600000L) return;
        lastDogTry = now;
        if (YBAdb.ready()) ShellDog.ensure(this, "keep-tick");
    }

    /** 连续几次检测到「没界面」才 relaunch，避免和 BootReceiver / 看门狗抢跑。 */
    private int goneCount = 0;
    private final long bornAt = android.os.SystemClock.elapsedRealtime();

    /**
     * 自愈：检测到仪表卡片没显示，就重拉一次。
     * 判据只看窗口管理器（dumpsys window windows 里我们那扇窗 isVisible=true）：
     * 心跳新鲜只说明进程活着，被别的窗口压住时心跳同样新鲜，不能当判据。
     */
    private void heal() {
        if (new java.io.File(HALT).exists()) { goneCount = 0; return; }
        if (!YBAdb.ready()) return;
        adjustDog();
        // 开机前 25 秒不检查：BootReceiver 正在启动，窗口创建要几百毫秒
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
        pause(900);
        YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
        YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
    }

    /** 自愈成功一次就把看门狗退避归零：它证明链路是通的，别再休眠。 */
    private void adjustDog() { lastDogTry = 0L; }

    /**
     * 音乐不显示的根因几乎都是这个：force-stop / quickboot 之后通知监听被解绑，
     * 而 secure 里的 enabled_notification_listeners 条目还在，光看设置永远判「已绑定」。
     * 这里看 MediaListener 自己上报的真实连接状态，掉了就 disallow → allow 重绑。
     */
    private void healMedia() {
        if (MediaListener.connected) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastMediaFix < 60000) return;
        lastMediaFix = now;
        Dbg.e("CLUSTERLAB-KEEP", "media listener 未连接，重绑通知监听");
        YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
        pause(1200);
        YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
        pause(600);
        Dbg.e("CLUSTERLAB-KEEP", "media rebind 后 connected=" + MediaListener.connected);
    }

    /**
     * 只补自己，绝不碰别人。
     *
     * 历史教训（用户真机实测）：旧版每 20 秒 get → 拼接 → settings put，为了触发重绑还会先把自己
     * 摘掉再写回。迪+方控(com.byd.ls) 也在改同一个全局列表，两边一抢，我们写回的旧列表就把
     * 迪加 KDService / 迪友 AutoService 顶掉了 —— 用户看到的是「仪表助理一跑，迪加和迪友掉无障碍」。
     *
     * 规则：只在「列表里没有我们」时追加一次，6 小时最多一次，不删不改别人的条目。
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
