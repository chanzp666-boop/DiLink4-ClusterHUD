package com.byd.clusterlab;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用 ADB shell 给自己补权限：WRITE_SECURE_SETTINGS 是 signature 级，
 * 只有 pm grant 能让它变成 GRANTED，所以必须等 adb 通道就绪后再跑一遍。
 */
public final class YBPrivilege {
    private static final String TAG = "YB_ADB";
    private static final String WSS = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String REX = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String WEX = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final String LOC = "android.permission.ACCESS_FINE_LOCATION";
    private static final String OVL = "android.permission.SYSTEM_ALERT_WINDOW";
    private static final String NLS = "com.byd.clusterlab.MediaListener";

    private static final String[] PERMS = {WSS, REX, WEX};   // 定位/录音我们不用，不申请也不授予
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    /** 后台线程跑一遍，幂等：已授权的跳过，重复调用不会重跑。 */
    public static void applyOnce(final Context ctx) {
        if (ctx == null) return;
        if (!busy.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    apply(ctx.getApplicationContext());
                } finally {
                    busy.set(false);
                }
            }
        }, "yb-priv");
        t.setDaemon(true);
        t.start();
    }

    private static void apply(Context ctx) {
        final String pkg = ctx.getPackageName();
        // 等 adb 就绪，最多 30 秒；等不到就退出，下次调用还能再来
        for (int i = 0; i < 30 && !YBAdb.ready(); i++) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        if (!YBAdb.ready()) {
            Dbg.w(TAG, "提权跳过：adb 未就绪");
            return;
        }
        for (String p : PERMS) {
            if (granted(ctx, p)) continue;
            String o = YBAdb.exec("pm grant " + pkg + " " + p, 8000);
            Dbg.i(TAG, "pm grant " + p.substring(p.lastIndexOf('.') + 1)
                    + " -> " + (o == null ? "null" : o.trim()));
        }
        // 面板（clusterassist）要靠读 /sdcard/clusterlab/adbkey.pk8 才能拿 shell；
        // 每次 install -r 都会把它这两条运行时权限清掉，清了面板就整个失联。这里顺手补回去。
        grantOther("com.byd.clusterassist", REX);
        grantOther("com.byd.clusterassist", WEX);
        if (!granted(ctx, OVL)) {
            YBAdb.exec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow", 8000);
        }
        YBAdb.exec("cmd notification allow_listener " + pkg + "/" + NLS, 8000);
        Dbg.i(TAG, "提权完成: " + grantedList(ctx));
    }

    /** signature 级权限只有 pm grant 之后才会返回 GRANTED；悬浮窗走 appops，单独判断。 */
    /** 给另一个包补一条运行时权限（只对 pm grant 允许的类型有效）。 */
    private static void grantOther(String pkg, String perm) {
        try {
            String o = YBAdb.exec("pm grant " + pkg + " " + perm, 8000);
            if (o != null && o.trim().length() > 0) {
                Dbg.i(TAG, "pm grant " + pkg + " "
                        + perm.substring(perm.lastIndexOf('.') + 1) + " -> " + o.trim());
            }
        } catch (Throwable ignored) {}
    }

    public static boolean granted(Context ctx, String perm) {
        if (ctx == null || perm == null) return false;
        try {
            if (OVL.equals(perm)) {
                AppOpsManager am = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
                if (am == null) return false;
                int mode = am.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                        ctx.getApplicationInfo().uid, ctx.getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            }
            return ctx.checkPermission(perm, android.os.Process.myPid(),
                    android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Dbg.e(TAG, "granted 失败 " + perm + ": " + t);
            return false;
        }
    }

    /** 调试用：一行看完所有关键授权。 */
    public static String grantedList(Context ctx) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("WSS=").append(granted(ctx, WSS) ? 1 : 0);
        sb.append(" REX=").append(granted(ctx, REX) ? 1 : 0);
        sb.append(" WEX=").append(granted(ctx, WEX) ? 1 : 0);
        sb.append(" LOC=").append(granted(ctx, LOC) ? 1 : 0);
        sb.append(" OVL=").append(granted(ctx, OVL) ? 1 : 0);
        sb.append(" NLS=").append(listener(ctx) ? 1 : 0);
        sb.append(" ADB=").append(YBAdb.ready() ? 1 : 0);
        return sb.toString();
    }

    private static boolean listener(Context ctx) {
        try {
            String s = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            return s != null && s.contains(ctx.getPackageName() + "/" + NLS);
        } catch (Throwable t) {
            return false;
        }
    }
}
