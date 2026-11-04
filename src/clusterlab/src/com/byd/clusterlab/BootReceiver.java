package com.byd.clusterlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * 启动链（v5.0）：
 *   TICK/BOOT → 起 :keep → 装 shell 看门狗 → 自连 127.0.0.1:5555 握手 → 提权 → display 1 拉起卡片
 *
 * onReceive 里绝不做重活：前台服务 5 秒内必须 startForeground，同步 exec/sleep 就是 ANR。
 * 所以真正的工作全部丢到后台线程，这里只做「排闹钟 + 起服务 + 起线程」。
 */
public class BootReceiver extends BroadcastReceiver {
    /**
     * 卡片是否真的可见。只看「有没有 ActivityRecord」不够：v4.9.1 现场遇到过 record 在、
     * 窗口 isVisible=false（GONE），自愈以为卡片活着就再也不拉，界面一直空着。
     */
    static final String CHECK =
            "dumpsys window windows | awk '/Window #/ {w=$0} /isVisible=true/ && w ~ /clusterlab/ {c++} END{print c+0}'";

    static final String LAUNCH =
            "am start-activity --display 1 -n com.byd.clusterlab/.CardActivity";

    private static final String TAG = "CLUSTERLAB-BOOT";
    /** 面板「停止卡片」留下的闸：停止期间看门狗不拉起，点「启动卡片」才清掉。 */
    static final String HALT = "/sdcard/clusterlab_halt";

    @Override public void onReceive(final Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Dbg.e(TAG, "received " + action);
        final Context app = ctx.getApplicationContext();

        // 用户的显式指令优先，不参与后面的「亮屏/tick」判断
        if ("com.byd.clusterlab.STOP".equals(action)) {
            markHalt(true);
            try { ctx.stopService(new Intent(ctx, KeepAlive.class)); } catch (Throwable ignored) {}
            try { CardActivity.shutdown(); } catch (Throwable ignored) {}
            return;
        }
        if ("com.byd.clusterlab.RESUME".equals(action)) {
            markHalt(false);
            startKeep(ctx);
            new Thread(new Runnable() { @Override public void run() {
                YBAdb.connect(app);
                for (int i = 0; i < 30 && !YBAdb.ready(); i++) KeepAlive.pause(300);
                YBAdb.exec(LAUNCH, 12000);
            } }, "resume").start();
            return;
        }

        // 这台车机熄火后是「休眠」不是断电（实测 uptime 一直累加、BOOT_COMPLETED 根本不来），
        // 点火实际上只是亮屏，靠闹钟 TICK / 解锁广播叫醒我们。
        // 闹钟每次都先排下一次：进程随后被杀也还有下一次机会。
        AlarmKeeper.schedule(ctx, KeepAlive.beatAge() < 60000L);

        // 自检用：只关界面、不碰服务，验证 :keep 与看门狗能不能把卡片重新拉出来
        if ("com.byd.clusterlab.KILLCARD".equals(action)) {
            try { CardActivity.shutdown(); } catch (Throwable ignored) {}
            Dbg.e(TAG, "自检：已关掉卡片，等自愈");
            return;
        }

        boolean wake = "com.byd.clusterlab.TICK".equals(action)
                || "android.intent.action.USER_PRESENT".equals(action);
        if (wake && KeepAlive.beatAge() < 30000L) {
            Dbg.e(TAG, "卡片活着，忽略 " + action);
            return;
        }

        // 熄火停车时屏幕是黑的，这时候拉界面纯属白烧电
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                Dbg.e(TAG, "屏幕没亮，跳过拉起（等亮屏/tick）");
                return;
            }
        } catch (Throwable ignored) {}

        startKeep(ctx);

        final Handler h = new Handler(Looper.getMainLooper());
        // 本进程直接往 display 1 拉一次（详情见 CardActivity.launchLocal）
        CardActivity.launchLocal(app);

        // 连接是异步的：connect 立刻返回，内部线程完成 AUTH 握手
        try { YBAdb.connect(app); } catch (Throwable t) {
            Dbg.e(TAG, "adb connect fail: " + t);
        }

        final int[] tries = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            @Override public void run() {
                tries[0]++;
                if (!YBAdb.ready()) {
                    if (tries[0] < 60) { YBAdb.ping(); h.postDelayed(poll[0], 1000); return; }
                    Dbg.e(TAG, "adb never ready: " + YBAdb.status());
                    return;
                }
                if (tries[0] == 1) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { YBPrivilege.applyOnce(app); } catch (Throwable t) {
                                Dbg.e(TAG, "priv fail: " + t);
                            }
                            // 拿到 shell 第一件事：把 UID 2000 看门狗装上（熄火唤醒后唯一能救命的层）
                            ShellDog.ensure(app, "boot");
                        }
                    }).start();
                }
                String alive = YBAdb.exec(CHECK, 8000);
                boolean running = alive != null && alive.trim().length() > 0
                        && !alive.trim().equals("0");
                if (running) {
                    Dbg.e(TAG, "界面已在 display 1，跳过重复启动");
                } else {
                    Dbg.e(TAG, "adb 启动 -> " + YBAdb.exec(LAUNCH, 12000));
                }
                YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
                YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
            }
        };
        h.postDelayed(poll[0], 1200);   // 本机握手通常 200ms 内完成，1.2 秒后开始探
    }

    private static void startKeep(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, KeepAlive.class));
        } catch (Throwable t) {
            Dbg.e(TAG, "keepalive start failed: " + t);
        }
    }

    private static void markHalt(boolean on) {
        try {
            java.io.File f = new java.io.File(HALT);
            if (on) { if (!f.exists()) f.createNewFile(); }
            else f.delete();
        } catch (Throwable ignored) {}
    }
}
