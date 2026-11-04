package com.byd.clusterlab;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * ADB 身份：RSA-2048 私钥落在私有目录，公钥按 adbd 认的旧版结构编码。
 * 生成密钥和算 rr 都很贵，所以只做一次、全程缓存；对外方法都不带 ctx，
 * 由 YBAdb.connect 先 attach 一次 ApplicationContext。
 *
 * 落盘格式三行（adbd 那份 PKCS8 里取不出模数和指数，实测 Android 10 的
 * OpenSSLRSAPrivateKey 不 implements RSAPrivateCrtKey，强转必抛 ClassCastException，
 * 所以 n / e 自己存一份，永不做接口强转）：
 *   第 1 行 PKCS8 DER 的 base64
 *   第 2 行 模数 n 的 base64
 *   第 3 行 公钥指数 e 的 base64
 */
public final class AdbCrypto {
    private static final String TAG = "YB_ADB";
    private static final String PK8 = "adbkey.pk8";
    private static final String PUB = "adbkey.pub";
    private static final String NAME = " clusterlab@byd";

    /** SHA-1 的 DigestInfo 前缀（19 字节），后面直接跟 20 字节 token。 */
    private static final byte[] SHA1_DI = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static final Object LOCK = new Object();
    private static Context app;
    private static KeyPair pair;
    private static String pubB64;

    /** 绑定上下文，重复调用无副作用。 */
    public static void attach(Context ctx) {
        if (ctx == null) return;
        synchronized (LOCK) {
            if (app == null) app = ctx.getApplicationContext();
        }
    }

    /**
     * 签 20 字节 token，AUTH 握手第二步用。
     *
     * 关键：adbd 不是"对 token 做 SHA1 再签"，而是把 token 直接当成已算好的 SHA-1
     * 摘要值，构造 DigestInfo(3021 3009 0605 2b0e03021a 0500 0414) + token 共 35 字节，
     * 再做一次裸的 RSA 私钥运算（PKCS#1 v1.5 填充）。用 SHA1withRSA 去签会被
     * RSA_verify 判成签名无效，adbd 于是再发一个 token——这就是"点了允许还是过不去"
     * 的真正原因（实测与 Mac 上 adb 抓到的签名逐字节一致）。
     */
    public static byte[] signToken(byte[] token) {
        if (token == null) return null;
        synchronized (LOCK) {
            try {
                KeyPair kp = load();
                if (kp == null) return null;
                byte[] di = new byte[SHA1_DI.length + token.length];
                System.arraycopy(SHA1_DI, 0, di, 0, SHA1_DI.length);
                System.arraycopy(token, 0, di, SHA1_DI.length, token.length);
                try {
                    javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    c.init(javax.crypto.Cipher.ENCRYPT_MODE, kp.getPrivate());
                    return c.doFinal(di);
                } catch (Throwable ce) {
                    Dbg.w(TAG, "Cipher 签名失败，回退 NONEwithRSA: " + ce);
                    Signature s = Signature.getInstance("NONEwithRSA");
                    s.initSign(kp.getPrivate());
                    s.update(di);
                    return s.sign();
                }
            } catch (Throwable t) {
                Dbg.e(TAG, "signToken 失败: " + t);
                return null;
            }
        }
    }

    /** 推公钥限流：同一台车 30 分钟内只推一次，避免两个 App 把 adbd 的待授权槽占死。 */
    public static boolean canPush() {
        try {
            java.io.File d = new java.io.File(Environment.getExternalStorageDirectory(), "clusterlab");
            if (!d.exists()) d.mkdirs();
            java.io.File f = new java.io.File(d, "adbpush.stamp");
            long now = System.currentTimeMillis();
            if (f.exists() && now - f.lastModified() < 1800000L) return false;
            f.createNewFile();
            f.setLastModified(now);
            Dbg.i("YB_ADB", "推公钥许可，记录时间戳");
            return true;
        } catch (Throwable t) {
            Dbg.w("YB_ADB", "推公钥限流文件异常: " + t);
            return true;
        }
    }

    /** 旧版公钥结构 base64（不含 " clusterlab@byd\0" 后缀，AUTH 时再拼）。 */
    public static String publicKeyAdbBase64() {
        synchronized (LOCK) {
            try {
                KeyPair kp = load();
                if (kp == null) return null;
                if (pubB64 == null) pubB64 = encode(kp.getPublic());
                return pubB64;
            } catch (Throwable t) {
                Dbg.e(TAG, "公钥编码失败: " + t);
                return null;
            }
        }
    }

    /** 当前身份的指纹（模数低 64 位十六进制），排障时和 adb 弹窗里的 key 对得上。 */
    public static String fingerprint() {
        synchronized (LOCK) {
            try {
                KeyPair kp = load();
                if (kp == null) return "none";
                RSAPublicKey rp = asRsa(kp.getPublic());
                if (rp == null) return "none";
                String h = rp.getModulus().toString(16);
                return h.length() > 16 ? h.substring(0, 16) : h;
            } catch (Throwable t) {
                return "err:" + t;
            }
        }
    }

    // ---------- 内部 ----------

    private static KeyPair load() {
        if (pair != null) return pair;
        if (app == null) {
            Dbg.e(TAG, "AdbCrypto 未 attach");
            return null;
        }
        try {
            // 优先用导入的主机密钥：这台车机的 USB 调试确认框不落库（点了允许也不写
            // /data/misc/adb/adb_keys），新生成的 key 永远过不了校验。把电脑上已被本车机
            // 授权的 ~/.android/adbkey 导成三行格式推到 /sdcard/clusterlab/adbkey.pk8，
            // 即可零弹窗直连。
            File imp = importFile();
            if (imp != null && imp.exists()) {
                KeyPair ikp = parseKey(read(imp));
                if (ikp != null) {
                    pair = ikp;
                    Dbg.i(TAG, "使用导入密钥 fp=" + fingerprint());
                    return pair;
                }
                Dbg.w(TAG, "导入密钥解析失败，忽略");
            }
            File f = new File(app.getFilesDir(), PK8);
            if (f.exists()) {
                String all = new String(read(f), "UTF-8").trim();
                String[] lines = all.split("\\s+");
                if (lines.length >= 3) {
                    PrivateKey pk = KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(d64(lines[0])));
                    BigInteger n = new BigInteger(1, d64(lines[1]));
                    BigInteger e = new BigInteger(1, d64(lines[2]));
                    PublicKey pub = KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(n, e));
                    pair = new KeyPair(pub, pk);
                    return pair;
                }
                // 旧格式（只有 PKCS8 一行）取不出模数，直接弃用重生成
                Dbg.w(TAG, "发现旧格式 adbkey，重新生成");
                f.delete();
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            RSAPublicKey rp = asRsa(kp.getPublic());
            if (rp == null) {
                Dbg.e(TAG, "生成的公钥不是 RSAPublicKey: " + kp.getPublic().getClass());
                return null;
            }
            StringBuilder sb = new StringBuilder(2000);
            sb.append(e64(kp.getPrivate().getEncoded())).append('\n')
              .append(e64(rp.getModulus().toByteArray())).append('\n')
              .append(e64(rp.getPublicExponent().toByteArray())).append('\n');
            write(f, sb.toString().getBytes("UTF-8"));
            pair = new KeyPair(kp.getPublic(), kp.getPrivate());
            String b64 = encode(pair.getPublic());
            write(new File(app.getFilesDir(), PUB), (b64 + NAME).getBytes("UTF-8"));
            Dbg.i(TAG, "首次运行，已生成 adbkey fp=" + fingerprint());
            return pair;
        } catch (Throwable t) {
            Dbg.e(TAG, "读写 adbkey 失败: " + t);
            return null;
        }
    }

    /** /sdcard/clusterlab/adbkey.pk8：主机导出的三行密钥。 */
    private static File importFile() {
        try {
            return new File(android.os.Environment.getExternalStorageDirectory(),
                    "clusterlab/adbkey.pk8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 三行格式解析；带 PEM 头的先去头。缺 n/e 时返回 null。 */
    private static KeyPair parseKey(byte[] raw) {
        try {
            String all = new String(raw, "UTF-8").trim();
            if (all.contains("BEGIN")) {
                StringBuilder sb = new StringBuilder(all.length());
                for (String ln : all.split("\r?\n")) {
                    if (ln.startsWith("-----")) continue;
                    sb.append(ln.trim());
                }
                all = sb.toString();
            }
            String[] lines = all.split("\\s+");
            if (lines.length < 3) return null;
            PrivateKey pk = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(d64(lines[0])));
            BigInteger n = new BigInteger(1, d64(lines[1]));
            BigInteger e = new BigInteger(1, d64(lines[2]));
            PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(n, e));
            return new KeyPair(pub, pk);
        } catch (Throwable t) {
            Dbg.w(TAG, "parseKey 失败: " + t);
            return null;
        }
    }

    private static RSAPublicKey asRsa(PublicKey k) {
        return (k instanceof RSAPublicKey) ? (RSAPublicKey) k : null;
    }

    /**
     * struct { uint32 len; uint32 n0inv; uint32 n[len]; uint32 rr[len]; uint32 exponent; }
     * 全部小端；n[] 是模数的 32 位字，最低位在前。
     */
    private static String encode(PublicKey pub) throws Exception {
        RSAPublicKey rp = asRsa(pub);
        if (rp == null) throw new IllegalStateException("不是 RSA 公钥: " + pub.getClass());
        BigInteger n = rp.getModulus();
        BigInteger e = rp.getPublicExponent();
        int len = (n.bitLength() + 31) / 32;
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        // n0inv = -1 / n[0] mod 2^32
        long n0 = n.and(BigInteger.valueOf(0xffffffffL)).longValue();
        long n0inv = two32.subtract(BigInteger.valueOf(n0).modInverse(two32)).longValue()
                & 0xffffffffL;
        // rr = (2^(32*len))^2 mod n，蒙哥马利乘法的 R^2
        BigInteger rr = BigInteger.ONE.shiftLeft(64 * len).mod(n);

        ByteArrayOutputStream bo = new ByteArrayOutputStream(8 + len * 8 + 4);
        putLE(bo, len);
        putLE(bo, n0inv);
        for (int i = 0; i < len; i++) putLE(bo, word(n, i));
        for (int i = 0; i < len; i++) putLE(bo, word(rr, i));
        putLE(bo, e.longValue());
        return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static long word(BigInteger v, int i) {
        return v.shiftRight(32 * i).longValue() & 0xffffffffL;
    }

    private static void putLE(ByteArrayOutputStream bo, long v) {
        bo.write((int) (v & 0xff));
        bo.write((int) ((v >> 8) & 0xff));
        bo.write((int) ((v >> 16) & 0xff));
        bo.write((int) ((v >> 24) & 0xff));
    }

    private static String e64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static byte[] d64(String s) {
        return android.util.Base64.decode(s.replaceAll("\\s", ""), android.util.Base64.NO_WRAP);
    }

    private static byte[] read(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length() + 16);
            byte[] buf = new byte[4096];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { fi.close(); } catch (Throwable ignored) {}
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(data);
            fo.flush();
        } finally {
            try { fo.close(); } catch (Throwable ignored) {}
        }
    }
}
