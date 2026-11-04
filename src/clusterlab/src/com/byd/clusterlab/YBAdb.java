package com.byd.clusterlab;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 车机本机 adbd 的极简 ADB 客户端：不碰 Shizuku，只用 socket + RSA。
 * 一条连接常驻；adb 协议本身就是一条流，并发 exec 会把 WRTE/OKAY 搅乱，所以全程串行。
 */
public final class YBAdb {
    private static final String TAG = "YB_ADB";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    // 协议命令字：都是 4 字节大端 ASCII，这里按 little-endian uint32 传送
    private static final int CNXN = 0x4e584e43; // "CNXN"
    private static final int AUTH = 0x48545541; // "AUTH"
    private static final int OPEN = 0x4e45504f; // "OPEN"
    private static final int OKAY = 0x59414b4f; // "OKAY"
    private static final int CLSE = 0x45534c43; // "CLSE"
    private static final int WRTE = 0x45545257; // "WRTE"

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAXDATA = 256 * 1024;
    private static final int MAX_OUT = 512 * 1024;   // 单条命令输出上限，防 OOM
    private static final byte[] EMPTY = new byte[0];

    private static final Object LOCK = new Object();   // 串行化：同一时刻只跑一条命令
    private static Socket sock;
    private static InputStream in;
    private static OutputStream out;
    private static int nextId = 1;
    private static boolean booted;
    private static volatile boolean readyConn;
    private static volatile boolean pendingAuth;      // 公钥已推，等车机点"允许"
    private static volatile String state = "off";
    private static volatile int ops = 0;         // 正在跑的命令数：>0 时不做探活
    private static final Object WAKE = new Object();  // 探活睡眠用：连接掉了能立刻醒来重连

    /** 幂等；真正连接在后台线程里，立刻返回当前是否已就绪。 */
    public static boolean connect(Context ctx) {
        if (ctx != null) AdbCrypto.attach(ctx);
        boolean start;
        synchronized (LOCK) {
            start = !booted;
            booted = true;
            if (ctx != null && state.equals("off")) state = "connecting";
        }
        if (start) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    // 开机自启的关键：开机早期 adbd / 存储可能还没就绪，
                    // 单次失败就放弃会让卡片永远不出现。这里最多重试 5 分钟。
                    long end = SystemClock.elapsedRealtime() + 300000L;
                    while (SystemClock.elapsedRealtime() < end) {
                        if (ensureOnce() && ready()) return;
                        sleep(3000);
                    }
                }
            }, "yb-adb");
            t.setDaemon(true);
            t.start();
        }
        return readyConn;
    }

    public static boolean ready() { return readyConn || everReady; }

    /** 轮询方（BootReceiver / 守护）调一下：主动推进一次连接，内部有退避，不会连环重连。 */
    public static void ping() {
        try {
            PING.execute(new Runnable() {
                public void run() { try { ensureOnce(); } catch (Throwable ignored) {} }
            });
        } catch (Throwable ignored) {}
    }

    private static final java.util.concurrent.ExecutorService PING =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** "ok" / "connecting" / "auth-needed" / "off" */
    public static String status() {
        if (readyConn) return "ok";
        if (pendingAuth) return "auth-needed";
        if (everReady) return "idle";
        return state;
    }

    public static String exec(String cmd) { return exec(cmd, 8000); }

    public static String exec(String cmd, int timeoutMs) { return exec0(cmd, timeoutMs, false); }

    public static void run(String cmd, int timeoutMs) { exec0(cmd, timeoutMs, true); }

    // ---------- 后台连接/探活 ----------

    /*
     * 兼容性改造（v4.5）：不再常驻 adb 连接、不再 15 秒探活。
     * 这台 ROM 的 adbd 在「允许 USB 调试」点完后不回包，任何长期挂着的连接都会占住待授权槽，
     * 迪加 / 迪友桌面就抢不到授权（真机实测）。改成按需连接：
     *   用的时候连一次 -> 空闲 20 秒由 reaper 主动断开 -> 平时 adbd 上零 socket。
     */
    private static final long IDLE_MS = 45000L;   // 40 秒的复用窗口：导航探测期间不用反复握手
    private static volatile long lastUse;
    private static volatile long lastTry;
    private static long backoffMs = 0L;   // 成功过就是 0：用完即断之后再连不该被退避卡住
    private static volatile boolean everReady;
    private static boolean reaperStarted;

    /** 连接一次；带退避，绝不连环重连（连环重连会把 adbd 待授权槽刷爆）。 */
    private static boolean ensureOnce() {
        permitMainNet();          // BootReceiver 的轮询在主线程，不放行会 NetworkOnMainThreadException
        long now = SystemClock.elapsedRealtime();
        if (readyConn && sock != null) return true;
        if (now - lastTry < backoffMs) return false;
        lastTry = now;
        try {
            synchronized (LOCK) { connectOnce(); }
            backoffMs = 0L;                     // 成功就连，退避只在连续失败时增长
            if (readyConn) {
                everReady = true;
                lastUse = SystemClock.elapsedRealtime();
                startReaper();
            }
            return readyConn;
        } catch (Throwable t) {
            Dbg.w(TAG, "连接失败: " + t);
            backoffMs = Math.min(backoffMs * 2, 60000L);
            return false;
        }
    }

    /** 空闲自动断开：adbd 上常态零连接，不跟迪加 / 迪友抢槽。 */
    private static void startReaper() {
        synchronized (YBAdb.class) {
            if (reaperStarted) return;
            reaperStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    sleep(5000);
                    try {
                        if (!readyConn) continue;
                        long idle = SystemClock.elapsedRealtime() - lastUse;
                        // ops 长时间不归零 = 某条 exec 卡住了（对端不回包），一样强制断开，
                        // 否则那条连接会永远挂在 adbd 上，别人（迪加/迪友）再想连就排队。
                        if (idle > IDLE_MS && (ops == 0 || idle > 60000L)) {
                            // 故意不取 LOCK：卡住的 exec 正抱着 LOCK，取锁会把 reaper 一起拖死
                            closeQuietly();
                            state = everReady ? "idle" : "off";
                            if (Dbg.on()) Dbg.i(TAG, "空闲断开 adb（不占 adbd 槽）");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }, "yb-adb-reaper");
        t.setDaemon(true);
        t.start();
    }

    /** 连 adbd 并走完 AUTH 握手；成功即 readyConn=true。 */
    private static void connectOnce() throws Exception {
        synchronized (LOCK) {
            closeQuietly();
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(HOST, PORT), 4000);
                s.setSoTimeout(10000);
                s.setTcpNoDelay(true);
                sock = s;
                in = s.getInputStream();
                out = s.getOutputStream();
                send(CNXN, VERSION, MAXDATA, "host::\0".getBytes("UTF-8"));
                byte[] hdr = new byte[24];
                boolean sentPub = false;
                int authCount = 0;
                int poke = 0;
                byte[] lastTok = null;
                long authDeadline = SystemClock.elapsedRealtime() + 300000L;
                while (true) {
                    try {
                        readFully(hdr);
                    } catch (SocketTimeoutException ste) {
                        // adbd 在"待授权"状态不会主动说话，poke 一次签名它会回一个新 token
                        if (lastTok != null && SystemClock.elapsedRealtime() < authDeadline) {
                            if (sentPub && ++poke > 3) throw new IOException("授权未完成（弹窗未授权）");
                            byte[] ps = AdbCrypto.signToken(lastTok);
                            if (ps != null) {
                                send(AUTH, AUTH_SIGNATURE, 0, ps);
                                continue;
                            }
                        }
                        if (SystemClock.elapsedRealtime() >= authDeadline) {
                            throw new IOException("授权超时");
                        }
                        continue;
                    }
                    int c = le32(hdr, 0);
                    int a0 = le32(hdr, 4);
                    int a1 = le32(hdr, 8);
                    int len = le32(hdr, 12);
                    byte[] data = len > 0 ? readBytes(len) : EMPTY;
                    if (c == CNXN) {
                        readyConn = true;
                        pendingAuth = false;
                        state = "ok";
                        s.setSoTimeout(0);
                        Dbg.i(TAG, "握手成功，shell 通道就绪 fp=" + AdbCrypto.fingerprint());
                        return;
                    }
                    if (c != AUTH) continue;
                    if (a0 != AUTH_TOKEN) continue;
                    lastTok = data;
                    authCount++;
                    if (authCount == 2 && !sentPub) {
                        // 第二次被拒：主机不认识这把 key，把公钥推过去，车机会弹"允许USB调试"
                        if (!AdbCrypto.canPush()) throw new IOException("刚刚推过公钥，等车机确认");
                        String pub = AdbCrypto.publicKeyAdbBase64();
                        if (pub == null) throw new IOException("公钥不可用");
                        send(AUTH, AUTH_RSAPUBLICKEY, 0,
                                (pub + " clusterlab@byd ").getBytes("UTF-8"));
                        sentPub = true;
                        pendingAuth = true;
                        state = "auth-needed";
                        // 只等 15 秒：这台 ROM 的 adbd 在弹窗点允许后不回包，
                        // 长等会把 adbd 的待授权槽占死，别的客户端（迪加/迪友）就授权不上。
                        authDeadline = SystemClock.elapsedRealtime() + 15000L;
                        s.setSoTimeout(5000);
                        Dbg.i(TAG, "已推送公钥，等车机点允许");
                        continue;
                    }
                    byte[] sig = AdbCrypto.signToken(data);
                    if (sig == null) throw new IOException("签名失败");
                    send(AUTH, AUTH_SIGNATURE, 0, sig);
                }
            } catch (Exception e) {
                closeQuietly();
                throw e;
            }
        }
    }

    // ---------- 命令执行 ----------

    private static String exec0(String cmd, int timeoutMs, boolean discard) {
        if (cmd == null) return null;
        synchronized (LOCK) {
            if (!readyConn || sock == null) {
                if (!ensureOnce()) return null;
            }
            lastUse = SystemClock.elapsedRealtime();
            permitMainNet();
            ops++;
            final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            final int localId = allocId();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
            boolean opened = false;
            try {
                // OPEN payload 是 C 字符串，必须带 \0
                send(OPEN, localId, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
                byte[] hdr = new byte[24];
                while (true) {
                    long left = deadline - SystemClock.elapsedRealtime();
                    if (left <= 0) throw new SocketTimeoutException("exec 超时 " + timeoutMs);
                    sock.setSoTimeout((int) left);
                    readFully(hdr);
                    int c = le32(hdr, 0);
                    int a0 = le32(hdr, 4);
                    int a1 = le32(hdr, 8);
                    int len = le32(hdr, 12);
                    byte[] data = len > 0 ? readBytes(len) : EMPTY;
                    // 只认自己这条流的报文：旧的超时流/别的流漏出来的 WRTE 混进来会把输出拼脏
                    // （实测 TOP 这条命令收到过 settings get secure 的返回值）。
                    if (c == WRTE) {
                        if (a1 != localId) { send(OKAY, localId, a0, EMPTY); continue; }
                        if (opened && !discard) {
                            int room = MAX_OUT - buf.size();
                            if (room > 0) buf.write(data, 0, Math.min(data.length, room));
                        }
                        send(OKAY, localId, a0, EMPTY);   // 回 OKAY 对端才继续发
                    } else if (c == OKAY) {
                        // adb 协议：对端的 arg0 是它自己的 local id，arg1 才是我们这条流的 id
                        if (a1 != localId) continue;
                        opened = true;                     // OPEN 被接受，arg1 是远端 id
                    } else if (c == CLSE) {
                        if (a1 != localId) continue;
                        if (!opened) return null;          // OPEN 被拒：服务不存在
                        return discard ? "" : new String(buf.toByteArray(), "UTF-8");
                    }
                    // 其它命令（CNXN 等）忽略，payload 已经读掉了
                }
            } catch (Throwable t) {
                Dbg.w(TAG, "exec 失败 [" + cmd + "]: " + t);
                drop();
                return null;
            } finally {
                if (--ops == 0) {
                    // v4.7.1：不再「用完立刻断」。导航探测 700ms 一次 screencap，
                    // 立刻断 = 每次都要重新握手（约 400ms），实测单次采样要 1 秒以上：
                    // 切换慢、CPU 高、logcat 每 0.5 秒一条「握手成功」。
                    // 改成保留连接 + 空闲 45 秒由 reaper 关掉：活跃期只握手一次，停手就归零。
                    state = "idle";
                }
            }
        }
    }

    /** 主线程也会直接调 exec（overlay 刷新回调里），这里放行本线程的网络，避免 NetworkOnMainThreadException。 */
    private static void permitMainNet() {
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
            if (mainNetOk) return;
            mainNetOk = true;
            android.os.StrictMode.setThreadPolicy(
                    new android.os.StrictMode.ThreadPolicy.Builder().permitNetwork().build());
        } catch (Throwable ignored) {}
    }

    private static boolean mainNetOk = false;

    // ---------- 报文编解码 ----------

    private static void send(int cmd, int a0, int a1, byte[] payload) throws IOException {
        int len = payload == null ? 0 : payload.length;
        byte[] h = new byte[24];
        put32(h, 0, cmd);
        put32(h, 4, a0);
        put32(h, 8, a1);
        put32(h, 12, len);
        put32(h, 16, 0);              // data_crc32：adbd 侧不校验，填 0
        put32(h, 20, cmd ^ 0xFFFFFFFF); // magic = command 按位取反
        out.write(h);
        if (len > 0) out.write(payload);
        out.flush();
    }

    private static void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("adbd 断开");
            off += n;
        }
    }

    private static byte[] readBytes(int len) throws IOException {
        if (len <= 0 || len > (1 << 20)) throw new IOException("坏包长度 " + len);
        byte[] b = new byte[len];
        readFully(b);
        return b;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static int allocId() {
        int id = nextId++;
        if (nextId <= 0) nextId = 1;
        return id;
    }

    // ---------- 收尾 ----------

    private static void drop() {
        synchronized (LOCK) {
            readyConn = false;
            if (!pendingAuth) state = "off";
            closeQuietly();
            synchronized (WAKE) { WAKE.notifyAll(); }   // 打断 15 秒探活，马上重连
        }
    }

    private static void closeQuietly() {
        try { if (in != null) in.close(); } catch (Throwable ignored) {}
        try { if (out != null) out.close(); } catch (Throwable ignored) {}
        try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        in = null;
        out = null;
        sock = null;
        readyConn = false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
