package com.byd.clusterlab;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 把 assets/app-watchdog.sh 装到 /data/local/tmp 并以 UID 2000 常驻。
 *
 * 为什么宿主是 shell 而不是 App 自己：
 *   DiLink 4.0 熄火是整机 suspend，唤醒没有广播；ROM 又按 UID 整组清理（闹钟一起端掉）。
 *   应用内四层守护全在 com.byd.clusterlab 这个 UID 下，一起死；只有 shell 进程躲得过去。
 *
 * 安装方式（三条真车踩过的坑，别改）：
 *   1) 一步式 sh -c "setsid ... &" 在 shell: 服务里会超时并把子进程带走 —— 必须落脚本文件，由新起的 sh 解释后自己 exit 0；
 *   2) 脚本 2.5KB base64 后接近 adbd 单条 OPEN 的上限 —— 分块写，写完 wc -c 复核；
 *   3) 判活不能 ps | grep 脚本名（COMM 是 sh），要看 ARGS / /proc/<pid>/cmdline。
 */
public final class ShellDog {
    private static final String REMOTE = "/data/local/tmp/clusterlab-watchdog.sh";
    private static final String BOOT_SH = "/data/local/tmp/clusterlab-watchdog-boot.sh";
    private static final String PIDFILE = "/data/local/tmp/clusterlab-watchdog.pid";
    private static final String TAG = "CLUSTERLAB-DOG";

    /** 安装防抖：30 秒内只装一次；脚本内容变了会绕过它。 */
    private static final long GAP_MS = 30000L;
    private static volatile long lastEnsureAt = 0L;
    private static volatile boolean busy = false;
    private static volatile String lastState = "未安装";

    private ShellDog() {}

    public static String state() { return lastState; }

    /** 幂等。慢操作（写文件 + 起进程）在后台线程里做，绝不阻塞调用方主线程。 */
    public static void ensure(final Context ctx, final String reason) {
        if (busy) return;
        final long now = SystemClock.elapsedRealtime();
        // 初值 0 必须当成「从未执行」：开机 9 秒时 9 - 0 < 30 会命中防抖，永远装不上
        if (lastEnsureAt != 0L && now - lastEnsureAt < GAP_MS) return;
        lastEnsureAt = now;
        busy = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    lastState = install(ctx);
                    Dbg.e(TAG, "install(" + reason + ") -> " + lastState);
                } catch (Throwable e) {
                    lastEnsureAt = 0L;   // 失败让出防抖，下一轮立刻重试
                    lastState = "安装失败: " + e;
                    Dbg.e(TAG, lastState);
                } finally {
                    busy = false;
                }
            }
        }, "shell-dog");
        t.setDaemon(true);
        t.start();
    }

    private static String install(Context ctx) throws Exception {
        if (!YBAdb.ready()) return "ADB 未就绪";
        String body = readAsset(ctx);
        if (body.length() == 0) return "assets 缺脚本";

        // 已经在跑且脚本长度一致 = 同一份逻辑，不重启（重启会丢一次巡检窗口）
        if (running() && remoteLen().equals(String.valueOf(bytes(body).length))) return "运行中";
        if (running()) {
            YBAdb.exec("kill $(cat " + PIDFILE + ") 2>/dev/null; rm -f " + PIDFILE, 5000);
            sleep(600);
        }

        writeChunked(REMOTE, body);
        writeChunked(BOOT_SH, bootScript());

        // 落盘「成功」不等于文件存在，写完量一次
        String sz = YBAdb.exec("wc -c < " + REMOTE + " 2>/dev/null", 5000);
        if (sz == null || sz.trim().length() == 0 || "0".equals(sz.trim())) return "写入失败";

        for (int i = 0; i < 2; i++) {
            YBAdb.exec("/system/bin/sh " + BOOT_SH, 8000);
            sleep(1500);   // 给内核把子进程挂到 init 下
            if (running()) return "运行中";
        }
        return "未运行";
    }

    /** 双 fork 守护化：子 shell 立刻 exit，常驻进程被 init 收养，逃出 adbd 的会话进程树。 */
    private static String bootScript() {
        return "#!/system/bin/sh\n"
                + "LOG=/data/local/tmp/clusterlab-watchdog-boot.log\n"
                + "echo \"boot $(date +%s) pid=$$\" >> $LOG 2>/dev/null\n"
                + "( /system/bin/setsid /system/bin/sh " + REMOTE + " loop </dev/null >/dev/null 2>&1 & echo \"spawned p=$!\" >> $LOG 2>/dev/null )\n"
                // 不能删这 1 秒：adbd 在会话结束时清理进程树，子进程要在这期间完成 setsid + exec
                + "sleep 1\n"
                + "exit 0\n";
    }

    /** 写盘走 base64 分块：一条 OPEN 塞不下 2.5KB 脚本，超了会被 adbd 截断。 */
    private static void writeChunked(String remote, String content) {
        byte[] all = bytes(content);
        YBAdb.exec(": > " + remote + ".b64", 5000);
        int step = 1024;
        for (int off = 0; off < all.length; off += step) {
            int n = Math.min(step, all.length - off);
            byte[] part = new byte[n];
            System.arraycopy(all, off, part, 0, n);
            String b64 = android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP);
            YBAdb.exec("echo '" + b64 + "' >> " + remote + ".b64", 5000);
        }
        YBAdb.exec("base64 -d " + remote + ".b64 > " + remote + " && rm -f " + remote + ".b64", 8000);
        // adbd 的 shell 里 umask 可能是 022，补一次可执行位（虽然我们是 sh 解释执行）
        YBAdb.exec("chmod 700 " + remote, 5000);
    }

    private static boolean running() {
        String pid = YBAdb.exec("cat " + PIDFILE + " 2>/dev/null", 5000);
        if (pid != null) {
            pid = pid.trim();
            boolean digits = pid.length() > 0;
            for (int i = 0; i < pid.length(); i++) if (!Character.isDigit(pid.charAt(i))) digits = false;
            if (digits) {
                // kill -0 只证明 pid 存在，可能已被复用，必须核对 cmdline
                String r = YBAdb.exec("kill -0 " + pid + " 2>/dev/null && grep -q clusterlab-watchdog /proc/"
                        + pid + "/cmdline && echo ALIVE", 5000);
                if (r != null && r.contains("ALIVE")) return true;
            }
        }
        String ps = YBAdb.exec("ps -A -o PID,ARGS 2>/dev/null | grep '[c]lusterlab-watchdog.sh'", 6000);
        return ps != null && ps.contains("clusterlab-watchdog.sh");
    }

    private static String remoteLen() {
        String s = YBAdb.exec("wc -c < " + REMOTE + " 2>/dev/null", 5000);
        return s == null ? "" : s.trim();
    }

    private static String readAsset(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open("app-watchdog.sh");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            Dbg.e(TAG, "读 assets 失败: " + t);
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] bytes(String s) {
        try { return s.getBytes("UTF-8"); } catch (Throwable t) { return s.getBytes(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }
}
