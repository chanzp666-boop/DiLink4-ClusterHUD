package com.byd.clusterlab;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 存活兜底：这台车机熄火只是休眠、不重启（实测 uptime 连续累加，BOOT_COMPLETED 永远不来），
 * 熄火后系统会把我们进程杀掉，点火时也没有任何广播叫醒我们。
 * 所以自己排单次精确闹钟，每次 TICK 起来先排下一次：
 *   卡片在 → 120 秒一次（省电）；
 *   卡片没了 → 15 秒一次（点火后 30 秒内一定收敛）。
 * Android 的 setRepeating 最小周期是 60 秒且不精确，所以这里用「单次链式」。
 */
public final class AlarmKeeper {
    public static final long PERIOD_ALIVE = 120000L;
    public static final long PERIOD_LOST = 15000L;
    private static final int REQ = 1001;

    private AlarmKeeper() {}

    public static void schedule(Context ctx, boolean cardAlive) {
        schedule(ctx, cardAlive ? PERIOD_ALIVE : PERIOD_LOST);
    }

    public static void schedule(Context ctx) {
        schedule(ctx, PERIOD_ALIVE);
    }

    public static void schedule(Context ctx, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent it = new Intent(ctx, BootReceiver.class);
            it.setAction("com.byd.clusterlab.TICK");
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-BOOT", "排闹钟失败: " + t);
        }
    }
}
