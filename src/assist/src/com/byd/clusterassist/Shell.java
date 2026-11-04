package com.byd.clusterassist;

import android.content.Context;

/**
 * shell 执行器（v4.1 起自研 ADB 客户端，彻底不依赖 Shizuku）。
 *
 * 车机本机 adbd 监听 127.0.0.1:5555，YBAdb 用导入的主机密钥（/sdcard/clusterlab/adbkey.pk8）
 * 走完 AUTH 握手就是 shell uid，免弹窗、免 Shizuku、免 root。
 * 对外接口保持和旧 Shizuku 版一致，MainActivity / Watchdog / Card 不改调用方式。
 */
public final class Shell {
    public static volatile String lastPath = "";
    private static Context app;

    /** 绑定 ApplicationContext 并开始后台连接（幂等）。 */
    public static void init(Context ctx) {
        if (ctx == null) return;
        app = ctx.getApplicationContext();
        try {
            YBAdb.connect(app);
            lastPath = "adb";
        } catch (Throwable t) {
            lastPath = "none";
        }
    }

    public static boolean ready() {
        if (app == null) return false;
        try {
            return YBAdb.ready();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给界面看的状态串：ok / connecting / auth-needed / off。 */
    public static String state() {
        try {
            return YBAdb.status();
        } catch (Throwable t) {
            return "off";
        }
    }

    public static int uid() {
        String out = exec("id -u", 4000);
        if (out == null) return -1;
        try { return Integer.parseInt(out.trim()); } catch (Throwable t) { return -1; }
    }

    public static String exec(String cmd) { return exec(cmd, 8000); }

    public static String exec(String cmd, int timeoutMs) {
        // 没有 ADB 就返回 null：本地 exec 是 unprivileged 的，拿 dumpsys/grep
        // 只会得到恒为 0 的假结果，比"未知"更糟。
        if (!ready()) { lastPath = "none"; return null; }
        try {
            String out = YBAdb.exec(cmd + " 2>&1", timeoutMs);
            lastPath = out == null ? "adb-fail" : "adb";
            return out;
        } catch (Throwable t) {
            lastPath = "adb-ex";
            return null;
        }
    }

    /** 一页诊断，直接进日志。 */
    public static String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("adb=").append(state());
        sb.append(" ready=").append(ready() ? 1 : 0);
        sb.append(" key=").append(AdbCrypto.fingerprint());
        String id = exec("id", 4000);
        sb.append(" | id=").append(id == null ? "null" : id.trim());
        String wss = exec("dumpsys package com.byd.clusterassist | grep -c WRITE_SECURE_SETTINGS", 4000);
        sb.append(" | wss=").append(wss == null ? "?" : wss.trim());
        sb.append(" | path=").append(lastPath);
        return sb.toString();
    }
}
