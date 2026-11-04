# ADB 自授权 + 去 Shizuku 实战（给灵手）

> 来源：仪表助理 v4.0 → v4.5，比亚迪 DiLink 4.0（Android 10，ro.adb.secure=1，ro.build.type=user，无 root）真车实测。
> 场景：App 自己连 `127.0.0.1:5555` 拿 shell 权限，**彻底不依赖 Shizuku / root / 电脑**。
> 一句话总结：**签名算法、密钥导入、绝不常驻连接、绝不写全局设置** —— 这四件事做对，就一直稳。

---

## 0. 十条结论（先看这段，能省一整晚）

1. adbd 把 20 字节 token 当成**已经算好的 SHA-1 摘要**，要直接签 `DigestInfo || token`；用 `SHA1withRSA` 一律被拒。
2. 点完"允许 USB 调试"后 adbd **不回包**；收到 token 就签，收不到就 poke，别长等也别短超时狂重连。
3. 免弹窗唯一稳的路径：把电脑上**已被授权**的 `~/.android/adbkey` 导成三行推到 `/sdcard/<dir>/adbkey.pk8`。
4. 推公钥必须**限流**（30 分钟一次，落一个共享时间戳文件），否则两个 App 会把 adbd 的待授权槽刷爆。
5. **用完即断**：一条命令执行完就 close，空闲 20 秒再由 reaper 兜底 —— adbd 上常态零连接。
6. 退避只在连续失败时增长，**成功即归零**；否则顺序命令会被自己的退避卡死。
7. **绝不写全局设置**（`enabled_accessibility_services`、通知监听列表、`policy_control`）；要补自己的条目只能"追加自己"且限频。
8. 别用 `pkill -f` 去匹配一个别人也在用的进程名；自己的进程起个专属名字。
9. 用 uid 统计 `/proc/net/tcp*` 里 5555（0x15B3）的连接，量化"我到底占了多少 adbd"。
10. 主线程不许碰 `exec` / `Thread.sleep`：无障碍服务的绑定回调就在主线程，一卡就绑不上。

---

## 1. 最关键的一条：签名算法

**adbd 不是"对 token 做 SHA-1 再签"，而是把 token 当成已经算好的 SHA-1 摘要值，直接签 DigestInfo。**

```
待签数据 = 3021300906052b0e03021a05000414 || token      // 15 + 20 = 35 字节
签名     = RSA_PKCS1_v1_5_private_op(待签数据)            // 不再做任何哈希
```

用 `Signature.getInstance("SHA1withRSA")` 去签，adbd 一律判无效 → 再发一个新的 AUTH TOKEN → 又签错 → 循环。
表现就是"手机上明明点了允许，连接还是过不去"。这个坑花了一整晚。

**判定方法（决定性证据）**：电脑上用真 adb 抓包，拿 adbd 的 token 和客户端回的 256 字节签名，用公钥做模幂：

```python
em = pow(int.from_bytes(sig, 'big'), e, n).to_bytes(256, 'big')
# em 尾部 20 字节 == 原始 token（不是它的 SHA-1）
```

### Java 实现（Android 可用）

```java
private static final byte[] SHA1_DI = {
        0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14 };

public static byte[] signToken(byte[] token, PrivateKey key) throws Exception {
    byte[] di = new byte[SHA1_DI.length + token.length];
    System.arraycopy(SHA1_DI, 0, di, 0, SHA1_DI.length);
    System.arraycopy(token, 0, di, SHA1_DI.length, token.length);
    Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    c.init(Cipher.ENCRYPT_MODE, key);      // 私钥 + ENCRYPT_MODE = 裸私钥运算
    return c.doFinal(di);
}
```

回退方案：`Signature.getInstance("NONEwithRSA")`，`update(di)` 一次再 `sign()`。

---

## 2. 握手流程（含 v4.5 的时间参数）

```
C → adbd : CNXN  ver=0x01000000 maxdata=256K "host::\0"
D → C    : AUTH arg0=1 (TOKEN)   payload=20B token1
C → D    : AUTH arg0=2 (SIGNATURE) payload=256B sign(token1)
D → C    : 验签过 → CNXN；不过 → 再发 AUTH TOKEN2
C → D    : 第 2 次被拒才推公钥 AUTH arg0=3
           payload = base64(legacy RSAPublicKey) + " 你的标识\0"
           → 车机弹「允许 USB 调试吗」
C → D    : 【v4.5】最多等 15 秒、poke 3 次；拿到 CNXN 就完事，拿不到就断开走退避
```

要点（前两条是 v4.2 的，第三条是 v4.5 修出来的）：

- **不要长等在原地**：确认后 adbd 不会主动回包，必须自己 poke 签名。
- **也不要短超时狂重连**：每次重连都在 adbd 里留一个待授权 transport。
- **更不要"等 5 分钟"**：这是 v4.2 的做法，实测会把 adbd 的待授权槽**长期占死**，同机上别的客户端（迪加 / 迪友）就再也授权不上。
  正确做法：`authDeadline = now + 15000`，`sentPub` 之后 poke 超过 3 次直接抛异常断开，退回普通退避重连。

### 报文编码

```
24 字节头：uint32 command, arg0, arg1, data_length, data_crc32(填0), magic
magic = command ^ 0xFFFFFFFF；全部小端。
CNXN=0x4e584e43 AUTH=0x48545541 OPEN=0x4e45504f OKAY=0x59414b4f
WRTE=0x45545257 CLSE=0x45534c43
执行命令：OPEN arg0=本地id arg1=0 payload="shell:<cmd>\0"，之后收 WRTE / OKAY / CLSE。
收 WRTE 必须回 OKAY，否则对端不发下一步。
```

---

## 3. 密钥

- 车机弹窗显示的是**客户端公钥指纹**（对 524 字节 blob 取 md5，形如 `40:7D:A2:BF:…`）。看到陌生指纹就说明这个 App 现场生成了新钥匙。
- 私钥存 PKCS8。**Android 10 取不出模数**：`OpenSSLRSAPrivateKey` 不 implements `RSAPrivateCrtKey`，强转必抛
  `ClassCastException: com.android.org.conscrypt.OpenSSLRSAPrivateKey cannot be cast to java.security.interfaces.RSAPrivateCrtKey`。
  所以自己存三行：`PKCS8 base64` / `模数 n base64` / `指数 e base64`；解析用 `new BigInteger(1, bytes)`（**必须带符号位 1**，否则模数变负，后面直接 `m.signum() <= 0`）。
- 公钥编码是旧版结构（全小端）：

```
struct { uint32 len; uint32 n0inv; uint32 n[len]; uint32 rr[len]; uint32 exponent; }
n0inv = -1 / n[0] mod 2^32;   rr = (2^(32*len))^2 mod n
```

---

## 4. 免弹窗：导入「已被授权的主机密钥」

电脑既然能 `adb shell` 进这台车机，说明**那把主机私钥已经在 adbd 白名单里**（`/data/misc/adb/adb_keys`，shell 读不到）。
把它导成三行推给车机，App 直接用，**零弹窗、长期有效**：

```bash
openssl pkcs8 -topk8 -nocrypt -in ~/.android/adbkey -outform DER | base64 -w0   # 第 1 行
# 第 2 行：模数的 base64（大端）；第 3 行：65537 的 base64 = AQAB
adb push adbkey3 /sdcard/<你的目录>/adbkey.pk8
```

App 侧优先级：**导入密钥 > 内部生成的密钥**。

---

## 5. 连接生命周期：别常驻，用完即断（v4.5 核心）

### 为什么放弃"一条常驻连接 + 15 秒探活"

车机是**多客户端抢 adbd** 的环境：迪加（com.van.diplus）和迪友桌面（com.dy.launcher）各自也有一条常驻连接。
我们早期是"一条常驻 + 15 秒 echo 探活"，两个 App 就是两条常驻。结果：
这台 ROM 的 adbd 在**待授权状态不回包**，我们的连接一旦进入待授权就长期挂着，把槽位占死 ——
别人再想授权（走同一个弹窗通道）就排队、超时、失败。用户原话：*"仪表助理会导致迪友桌面跟迪加掉 adb 授权"*。

### 现在的做法

```java
// 1) 每次 exec 结束就断开（--ops == 0 表示队列空了）
} finally {
    if (--ops == 0) {
        closeQuietly();
        state = "idle";
    }
}

// 2) 空闲 reaper：20 秒没人用就断，60 秒还没断说明有 exec 卡住，强制断
while (true) {
    sleep(5000);
    if (!readyConn) continue;
    long idle = elapsedRealtime() - lastUse;
    if (idle > IDLE_MS && (ops == 0 || idle > 60000L)) {
        closeQuietly();           // 故意不取 LOCK：卡死的 exec 正抱着 LOCK，取锁会把 reaper 一起拖死
        state = everReady ? "idle" : "off";
    }
}

// 3) 退避只在失败时长，成功归零
try {
    synchronized (LOCK) { connectOnce(); }
    backoffMs = 0L;
} catch (Throwable t) {
    backoffMs = Math.min(backoffMs == 0 ? 5000L : backoffMs * 2, 60000L);
}

// 4) 按需连接入口（带退避，绝不连环重连）
static boolean ensureOnce() {
    long now = elapsedRealtime();
    if (readyConn && sock != null) return true;
    if (now - lastTry < backoffMs) return false;
    lastTry = now;
    ...
}
```

**代价与收益**：一次握手约 100ms；换来的是 adbd 上**常态零连接**，别人随时抢得到授权。
配合"卡片活着就不去 adb 里 dumpsys"（心跳文件/静态变量），实测我们两个进程在 adbd 上的占用从"长期 2 条"降到"每几十秒闪现一两秒"。

### 推公钥限流

```java
// 两个 App 共用同一个时间戳文件，任何一方刚推过就都不许再推
if (!AdbCrypto.canPush()) throw new IOException("刚刚推过公钥，等车机确认");
```
文件：`/sdcard/<dir>/adbpush.stamp`，间隔 30 分钟。

---

## 6. 兼容性铁律：别把别人的东西搞坏

### 6.1 全局设置只读不写（最惨的一课）

`settings put secure enabled_accessibility_services` 是**整串覆盖**。我们过去的"无障碍自愈"每 20 秒读一次、拼上自己、写回去，
还为了让系统重绑**先把自己摘掉再写回**。可是迪+方控（com.byd.ls）也在改同一个字符串，
两边一抢，我们写回去的是**读到的旧列表** → 迪加的 `KDService`、迪友的 `AutoService` 直接被顶掉。

正确姿势（要补只补自己，且限频）：

```java
String cur = exec("settings get secure enabled_accessibility_services");
if (cur.contains(me)) return;                    // 已经在，什么都不做
if (now - lastTryAt < 6 * 3600_000L) return;     // 6 小时最多一次（SharedPreferences 记时间戳）
exec("settings put secure enabled_accessibility_services '" + cur + ":" + me + "'");
```

**绝不**：删别人的条目、`replace` 自己的条目、写 `accessibility_enabled`、写通知监听列表、写 `policy_control`。

### 6.2 别杀别人的进程

`pkill -f 'd_service'` 看着只杀"自己的"，实际迪友的常驻进程**也叫 d_service**，直接被误杀。
规矩：自己的进程起专属名字（我们叫 `yb_core`），pkill/pgrep 只匹配**自己的类名**：

```bash
pkill -f 'com.byd.clusterlab.YBCore'
ps -A | grep -E 'yb_core|YBCore'
```

### 6.3 装自己的包会牵动别人的注册项

`install -r` 之后系统会把**我们自己**的无障碍服务从列表里摘掉（这是系统行为）。
所以"启动后补一次自己的条目"是必要的，但必须按 6.1 的方式：只在缺的时候追加，且限频。

---

## 7. 怎么自证"我没占 adbd"

```bash
# 5555 = 0x15B3；$8 是 uid，$3 是远端地址
adb shell "cat /proc/net/tcp /proc/net/tcp6 | awk 'NR>1 && \$3 ~ /15B3/ {print \$8}' | sort | uniq -c"
```

uid 对照：`10083=你的主 App`、`10084=你的辅助 App`、`2000=系统 shell`、其余是别人的 App。
**判定标准**：连续采样 10 次，自己的 uid 应该大部分为 0，只在执行命令那一两秒出现。

归属不确定时用 force-stop 验证：

```bash
adb shell am force-stop <你的包名>
adb shell "cat /proc/net/tcp* | awk 'NR>1 && \$3 ~ /15B3/ {print \$8}' | sort | uniq -c"
# 消失的那条就是你的
```

---

## 8. 我们踩过但不成立的猜测（别再试）

| 猜测 | 结论 |
|---|---|
| 点允许后长等就行（5 分钟） | 错，adbd 根本回包；反而占死待授权槽，害别人授权不上 |
| 短超时反复重连能碰运气过 | 错，制造一串僵尸 transport，确认会落到最早的死连接 |
| 「一律允许使用这台计算机」勾了会持久化 | 这台 ROM 上没写进 adb_keys，新连接照样要 AUTH |
| `ro.adb.secure=0` 就能免签 | 这台是 1；adbd 以 shell uid 跑，改不了 |
| 从 `/system/etc/adb_keys` 找现成私钥 | 只有厂内工程师公钥，私钥不在车机上 |
| 用 `settings put` 修无障碍是"自愈" | 这是把别人服务顶掉的头号杀手 |
| `pkill -f d_service` 只杀自己的 | 迪友的常驻进程也叫这个名 |

---

## 9. 验证清单

```bash
adb shell getprop ro.adb.secure              # 1 = 需要授权
adb shell ps -A -o USER,PID,NAME | grep adbd # 一般 shell 2000
adb logcat -s adbd:*                         # 看 "Loading keys from /data/misc/adb/adb_keys"
                                             # 和 "adb client authorized"（成对出现才正常）
```

App 侧（自己的 TAG）：
- 握手成功打一条 `握手成功，shell 通道就绪 fp=<模数前 16 位 hex>`；
- 失败按 token 序号打印，能一眼分清"没等到 token"还是"签了被拒"；
- 断开打 `空闲断开 adb（不占 adbd 槽）`，常态应远多于握手日志。

---

## 10. 参考实现

`~/Desktop/DSH/仪表助理/src/clusterlab/src/com/byd/clusterlab/`：

| 文件 | 职责 |
|---|---|
| `AdbCrypto.java` | 三行密钥格式、`signToken`、legacy 公钥编码、指纹、`canPush()` 限流 |
| `YBAdb.java` | CNXN/AUTH 握手、`ensureOnce()` 按需连接、`exec/run`、空闲 reaper、状态机 |
| `YBPrivilege.java` | 连上后补 `pm grant` / `appops` / 通知监听 |
| `YBDaemon.java` | 用 shell 权限 `app_process` 起常驻进程（进程名 `yb_core`） |
| `YBCore.java` / `YBByd.java` | 特权进程本体：系统上下文 + 反射 BYDAuto |

辅助 App 里 `com.byd.clusterassist` 有一份同构的 `AdbCrypto/YBAdb`，两包各自独立连，互不共享连接（共享连接需要额外 IPC，收益不值）。
