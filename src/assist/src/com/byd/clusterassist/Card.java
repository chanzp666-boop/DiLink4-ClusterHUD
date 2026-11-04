package com.byd.clusterassist;

/** 仪表卡片（com.byd.clusterlab）的启动 / 停止 / 存活判定。 */
public final class Card {
    public static final String PKG = "com.byd.clusterlab";
    public static final String CMP = "com.byd.clusterlab/.CardActivity";

    /** 只统计 Display #1 段里的 CardActivity，避免主屏残留误判。 */
    /** 按唯一 ActivityRecord 计数：同一个 activity 在 dumpsys 里会出现 5~9 行，grep -c 会虚高。 */
    static final String CHECK_CMD =
            "dumpsys activity activities | awk '/^ *Display #/ {f = ($0 ~ /Display #1/)} f'"
            + " | grep -oE 'ActivityRecord\\{[0-9a-f]+ [^}]*clusterlab' | sort -u | wc -l";

    /** 某些固件 dumpsys activity 不按 Display 分组时的兜底判据。 */
    static final String CHECK_WIN =
            "dumpsys window displays | grep -c 'clusterlab/.CardActivity'";

    /** 0 = 按 Display 分组判定；1 = window 服务兜底（连续自愈失败后自动切换）。 */
    public static volatile int mode = 0;

    static final String LISTENER_CMD =
            "settings get secure enabled_notification_listeners | grep -c com.byd.clusterlab";

    private Card() {}

    /** null = ADB 未就绪，无法判定。 */
    public static Integer count() {
        String out = Shell.exec(mode == 0 ? CHECK_CMD : CHECK_WIN, 8000);
        if (out == null) return null;
        try { return Integer.valueOf(Integer.parseInt(out.trim())); } catch (Throwable t) { return null; }
    }

    public static boolean alive() {
        Integer c = count();
        return c != null && c.intValue() > 0;
    }

    /** secure 设置里的条目，只能证明"挂过"，force-stop 之后它不会消失。 */
    public static boolean mediaBound() {
        String out = Shell.exec(LISTENER_CMD, 6000);
        if (out == null) return false;
        try { return Integer.parseInt(out.trim()) > 0; } catch (Throwable t) { return false; }
    }

    /** overlay 自己写的真实连接心跳；null = 心跳文件不可用（没存储权限）。 */
    public static Boolean mediaConnected() {
        String s = mediaDump();
        if (s == null || s.trim().length() == 0) return null;
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.startsWith("connected=")) return Boolean.valueOf(l.endsWith("1"));
        }
        return null;
    }

    public static String mediaDump() {
        return Shell.exec("cat /sdcard/clusterlab_media.txt 2>/dev/null", 5000);
    }

    /** 媒体链路诊断：真实连接、session 数、当前曲目、设置条目、系统侧计数。 */
    public static String mediaDiagnose() {
        StringBuilder sb = new StringBuilder();
        String dump = mediaDump();
        String flat = dump == null ? "" : dump.trim().replace("\n", "  ");
        sb.append("心跳: ").append(flat.length() == 0 ? "无（存储权限没给？）" : flat);
        String setting = Shell.exec(LISTENER_CMD, 5000);
        sb.append(" | 设置条目=").append(setting == null ? "?" : setting.trim());
        String sys = Shell.exec("dumpsys notification --noredact | grep -c 'com.byd.clusterlab'", 8000);
        sb.append(" | dumpsys命中=").append(sys == null ? "?" : sys.trim());
        return sb.toString();
    }

    public static String start() {
        String r = Shell.exec("am start-activity --display 1 -n " + CMP, 12000);
        if (r == null) return "ADB 未就绪：未启动";
        String low = r.trim();
        if (low.length() == 0) return "已提交启动";
        return low;
    }

    public static String stop() {
        // 不用 am force-stop：那会把包置为 stopped 态，之后系统不再投递 BOOT_COMPLETED，开机就不自启了
        String r = Shell.exec("am broadcast -a com.byd.clusterlab.STOP -n " + PKG + "/.BootReceiver", 8000);
        return r == null ? "ADB 未就绪：未停止" : "已停止";
    }

    public static void rebindMedia() {
        Shell.exec("cmd notification disallow_listener " + PKG + "/" + PKG + ".MediaListener", 8000);
        sleep(1200);
        Shell.exec("cmd notification allow_listener " + PKG + "/" + PKG + ".MediaListener", 8000);
        sleep(800);
    }

    /** 权限修复：给 overlay 读 /sdcard/clusterlab_*.txt 的存储权限。 */
    public static void grantStorage() {
        Shell.exec("pm grant " + PKG + " android.permission.READ_EXTERNAL_STORAGE", 8000);
        Shell.exec("pm grant " + PKG + " android.permission.WRITE_EXTERNAL_STORAGE", 8000);
        Shell.exec("cmd appops set " + PKG + " READ_EXTERNAL_STORAGE allow", 8000);
        Shell.exec("cmd appops set " + PKG + " WRITE_EXTERNAL_STORAGE allow", 8000);
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }
}
