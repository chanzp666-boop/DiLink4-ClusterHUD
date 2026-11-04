package com.byd.clusterassist;

/** 仪表卡片（com.byd.clusterlab）的启动 / 停止 / 存活判定。 */
public final class Card {
    public static final String PKG = "com.byd.clusterlab";
    public static final String CMP = "com.byd.clusterlab/.CardActivity";

    /**
     * 卡片是否真的可见。旧判据数 ActivityRecord，遇到过 record 在、窗口 isVisible=false（GONE）
     * 的情况，于是面板判「活着」、自愈永不动作，仪表一直空着。
     */
    static final String CHECK_CMD =
            "dumpsys window windows 2>/dev/null | awk '/Window #/ {w=$0} /isVisible=true/ && w ~ /clusterlab/ {c++} END{print c+0}'";

    /** 兼容旧调用：Only 一套判据（窗口可见性），保留字段只为日志兼容。 */
    public static volatile int mode = 0;

    static final String LISTENER_CMD =
            "settings get secure enabled_notification_listeners | grep -c com.byd.clusterlab";

    /** 面板「停止卡片」留下的闸：主程序侧看门狗据此不再拉起。 */
    static final String HALT = "/sdcard/clusterlab_halt";

    private Card() {}

    /** null = ADB 未就绪，无法判定。 */
    public static Integer count() {
        String out = Shell.exec(CHECK_CMD, 8000);
        if (out == null) return null;
        try { return Integer.valueOf(Integer.parseInt(out.trim())); } catch (Throwable t) { return null; }
    }

    public static boolean alive() {
        Integer c = count();
        return c != null && c.intValue() > 0;
    }

    /** secure 设置里的条目，只能证明「挂过」，force-stop 之后它不会消失。 */
    public static boolean mediaBound() {
        String out = Shell.exec(LISTENER_CMD, 6000);
        if (out == null) return false;
        try { return Integer.parseInt(out.trim()) > 0; } catch (Throwable t) { return false; }
    }

    /** 真实连接心跳（由主程序的 MediaListener 落盘）；null = 心跳文件读不到。 */
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

    /** 启动 = 清掉停止闸 + 让主程序拉起卡片（主程序自己会走 am start --display 1）。 */
    public static String start() {
        Shell.exec("rm -f " + HALT, 5000);
        String r = Shell.exec("am broadcast -a com.byd.clusterlab.RESUME -n " + PKG + "/.BootReceiver -f 0x20", 8000);
        if (r == null) return "ADB 未就绪：未启动";
        String low = r.trim();
        return low.length() == 0 ? "已提交启动" : low;
    }

    /**
     * 停止：落闸 + 只关界面、停 :keep。
     * 绝不用 am force-stop —— 那会把包装进 stopped 态，之后系统不再投递 BOOT_COMPLETED，
     * 开机就再不自启了。
     */
    public static String stop() {
        Shell.exec("touch " + HALT, 5000);
        String r = Shell.exec("am broadcast -a com.byd.clusterlab.STOP -n " + PKG + "/.BootReceiver -f 0x20", 8000);
        return r == null ? "ADB 未就绪：未停止" : "已停止";
    }

    public static void rebindMedia() {
        Shell.exec("cmd notification disallow_listener " + PKG + "/" + PKG + ".MediaListener", 8000);
        sleep(1200);
        Shell.exec("cmd notification allow_listener " + PKG + "/" + PKG + ".MediaListener", 8000);
        sleep(800);
    }

    /** 权限修复：给主程序读 /sdcard/clusterlab_*.txt 的存储权限。 */
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
