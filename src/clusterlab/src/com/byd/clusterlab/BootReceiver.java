package com.byd.clusterlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * v4.0：不再等 Shizuku。提权链改成
 *   BootReceiver → YBAdb.connect（自连 127.0.0.1:5555，App 内自己做 ADB AUTH）
 *   → YBPrivilege.applyOnce（pm grant / appops / 通知监听）
 *   → display1 上拉起 CardActivity。
 */
public class BootReceiver extends BroadcastReceiver {
    /** 查 display 1 上是否已有我们的界面。 */
    private static final String CHECK =
            // 只看「有没有 ActivityRecord」不够：v4.9.1 现场遇到过 record 在、窗口 isVisible=false（GONE），
            // 于是自愈以为卡片活着、再也不拉，界面就一直空着。改成直接问窗口管理器「可见吗」。
            "dumpsys window windows | awk '/Window #/ {w=$0} /isVisible=true/ && w ~ /clusterlab/ {c++} END{print c+0}'"
            ;

    private static final String LAUNCH =
            "am start-activity --display 1 -n com.byd.clusterlab/.CardActivity";

    private static final String TAG = "CLUSTERLAB-BOOT";

    @Override public void onReceive(final Context ctx, Intent intent) {
        String action = intent.getAction();
        Dbg.e(TAG, "received " + action);
        // 这台车机熄火后是「休眠」不是断电（实测 uptime 一直累加、BOOT_COMPLETED 根本不来），
        // 所以点火实际上只是亮屏。亮屏/解锁广播也要当成启动信号，但卡片活着就别重复起。
        // 闹钟每次都会来：先把下一次排上，进程再被杀也还有下一次机会
        AlarmKeeper.schedule(ctx, KeepAlive.beatAge() < 60000L);
        boolean wake = "android.intent.action.SCREEN_ON".equals(action)
                || "android.intent.action.USER_PRESENT".equals(action)
                || "com.byd.clusterlab.TICK".equals(action);
        if (wake && KeepAlive.beatAge() < 30000L) {
            Dbg.e(TAG, "卡片活着，忽略 " + action);
            return;
        }
        // 熄火停车时屏幕是黑的，这时候拉界面纯属白烧电（一晚上几百次）。
        // 黑屏就只留着闹钟，等亮屏广播或下一次 tick 再拉。
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                Dbg.e(TAG, "屏幕没亮，跳过拉起（等亮屏/tick）");
                return;
            }
        } catch (Throwable ignored) {}
        // 助理的「停止卡片」走这条广播：只停服务 + 关界面，
        // 绝不用 am force-stop —— 那会把包装进 stopped 态，之后收不到 BOOT_COMPLETED，开机就不自启了。
        // 自检用：只关界面、不碰服务，用来验证 :keep 能不能把卡片重新拉出来
        if ("com.byd.clusterlab.KILLCARD".equals(action)) {
            try { CardActivity.shutdown(); } catch (Throwable ignored) {}
            Dbg.e(TAG, "自检：已关掉卡片，等 :keep 自愈");
            return;
        }
        if ("com.byd.clusterlab.STOP".equals(action)) {
            try { ctx.stopService(new Intent(ctx, KeepAlive.class)); } catch (Throwable ignored) {}
            try { CardActivity.shutdown(); } catch (Throwable ignored) {}
            return;
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(new Intent(ctx, KeepAlive.class));
            } else {
                ctx.startService(new Intent(ctx, KeepAlive.class));
            }
        } catch (Throwable t) {
            Dbg.e(TAG, "keepalive start failed: " + t);
        }

        final Handler h = new Handler(Looper.getMainLooper());
        final Context app = ctx.getApplicationContext();

        // 先不看 adb：本进程直接往 display 1 拉一次（详情见 CardActivity.launchLocal）。
        CardActivity.launchLocal(app);

        // 连接是异步的：YBAdb.connect 立刻返回，内部线程完成 AUTH 握手。
        // 这条路径不再受 Shizuku 服务是否被系统杀掉影响。
        try { YBAdb.connect(app); } catch (Throwable t) {
            Dbg.e(TAG, "adb connect fail: " + t);
        }

        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            @Override public void run() {
                tries[0]++;
                if (!YBAdb.ready()) {
                    if (tries[0] < 90) { YBAdb.ping(); h.postDelayed(poll[0], 1000); return; }
                    Dbg.e(TAG, "adb never ready: " + YBAdb.status());
                    return;
                }
                if (tries[0] == 1) {
                    // 第一次拿到 shell 立刻补授权（pm grant / appops / 通知监听），在后台线程
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { YBPrivilege.applyOnce(app); } catch (Throwable t) {
                                Dbg.e(TAG, "priv fail: " + t);
                            }
                        }
                    }).start();
                }
                // 关键：先查是否已经在 display 1 上，避免重复 am start 让界面重建（用户看到"闪一下"）
                String alive = YBAdb.exec(CHECK, 8000);
                boolean running = alive != null && alive.trim().length() > 0
                        && !alive.trim().equals("0");
                if (running) {
                    Dbg.e(TAG, "界面已在 display 1，跳过重复启动");
                } else {
                    String out = YBAdb.exec(LAUNCH, 12000);
                    Dbg.e(TAG, "adb 启动 -> " + out);
                }
                YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
                YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
            }
        };
        h.postDelayed(poll[0], 1200);   // ADB 本机握手通常 200ms 内完成，1.2 秒后开始探
    }
}
