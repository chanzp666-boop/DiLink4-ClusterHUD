# 08 · ADB 能力复用包（免 root 拿 shell）

> 来源：`clusterlab` 里跑通的实现，2026-11-02 车机 DiLink 4.0 / Android 10 实测。
> 「灵手」手势注入、ADAS 测试工具都依赖这套能力，需要时**整包拷过去**即可，不用重写协议。

## 一、它到底能干什么

不需要 root、不需要 Shizuku，App 自己连 `127.0.0.1:5555`（车机 adbd 本地监听）拿 uid 2000 shell，于是可以：

| 动作 | 命令 | 实测结果 |
|---|---|---|
| 拿 signature 级权限 | `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` | GRANTED |
| 悬浮窗 | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` | 生效 |
| 通知监听 | `cmd notification allow_listener <pkg>/<Service>` | 绑定成功 |
| 写系统设置 | `settings put secure enabled_accessibility_services '...'` | 生效（**只追加自己，别改别人的**） |
| 跨屏启动 | `am start-activity --display 1 -n <pkg>/.<Activity>` | 成功（第三方 App 本地 startActivity 无法跨屏，只能走这条） |
| 注入手势 | `input tap X Y` / `input swipe x1 y1 x2 y2 ms` | 可用 |

拿不到 root：`ro.build.type=user`、`ro.debuggable=0`、`/data/misc/adb/adb_keys` 对 uid 2000 不可读。

## 二、三个文件，844 行，零依赖

| 文件 | 行数 | 职责 |
|---|---|---|
| `src/clusterlab/src/com/byd/clusterlab/AdbCrypto.java` | 317 | 密钥生成/加载（三行格式）、签名、公钥 blob、指纹 |
| `src/clusterlab/src/com/byd/clusterlab/YBAdb.java` | 404 | 完整 ADB 协议：CNXN/AUTH/OPEN/OKAY/WRTE/CLSE + shell 通道 + 生命周期 |
| `src/clusterlab/src/com/byd/clusterlab/YBPrivilege.java` | 123 | 用 shell 给自己补权限（幂等） |

assist 侧有同构副本（package 换成 `com.byd.clusterassist`），说明移植成本 = 改包名。

## 三、对外 API（照抄即可）

```java
// 1) 握手（幂等，内部丢后台线程，5 分钟内每 3 秒重试一次）
boolean ok = YBAdb.connect(context);

// 2) 状态
boolean ready = YBAdb.ready();     // 一旦成功过就为 true，掉线后自动重连
String  stat  = YBAdb.status();    // "ready fp=xxxx" / "idle" / "connecting"

// 3) 执行命令（用完即断，不会常驻占 adbd 槽）
String out = YBAdb.exec("pm grant com.foo android.permission.WRITE_SECURE_SETTINGS");
String out2 = YBAdb.exec("dumpsys window windows | grep isVisible", 6000);  // 带超时

// 4) 一次性补权限（后台线程，幂等）
YBPrivilege.applyOnce(context);

// 5) 密钥指纹（排障用）
String fp = AdbCrypto.fingerprint();
```

## 四、移植到别的 App 的步骤

1. 拷三个 `.java`，改 `package`；
2. `AdbCrypto.NAME` 那个字符串改成自己的（`" clusterlab@byd"` → `" lingou@byd"` 之类，只是给人看的标识）；
3. `AndroidManifest.xml` 加 `INTERNET`、`READ/WRITE_EXTERNAL_STORAGE`、`WRITE_SECURE_SETTINGS`，
   application 上加 `android:requestLegacyExternalStorage="true"`（Android 10 读 `/sdcard` 必需）；
4. 首次运行前把电脑上已授权的密钥导进去（否则车机会弹一次「允许 USB 调试」）：
   ```bash
   # Mac 侧生成三行格式：第1行 PKCS8 base64，第2行模数 n，第3行指数 e
   adb push adbkey.pk8 /sdcard/<你的目录>/adbkey.pk8
   ```
5. 在 `Application.onCreate` 或首个界面里 `YBAdb.connect(this)`，之后随时 `exec`。

## 五、七个必须知道的坑（都是踩过的）

1. **签名算法不是 SHA1withRSA**。adbd 把 20 字节 token 当成**已算好的 SHA-1 摘要**，要签
   `3021300906052b0e03021a05000414 || token`（35 字节）的裸 RSA 私钥运算：
   `Cipher.getInstance("RSA/ECB/PKCS1Padding")`。用 `SHA1withRSA` 必然一直要求授权。
2. **确认后 adbd 不主动回包**。点了「允许」之后 agent 侧收不到 CNXN，必须每 5 秒 poke 一次，别长等也别短超时狂重连（会留一串待授权 transport 把槽占满）。
3. **密钥存三行**：第 1 行 PKCS8 base64、第 2 行模数 n、第 3 行指数 e。
   Android 10 上 `PKCS8EncodedKeySpec` 生成的是 `OpenSSLRSAPrivateKey`，强转 `RSAPrivateCrtKey` 会 `ClassCastException`。
4. **用完即断**。常驻连接 + 15 秒探活会让车机 adbd 的待授权槽被占，迪加/迪友会掉授权（v4.5 的真事故）。
5. **主线程禁网**。`exec` 前调 `permitMainNet()`（StrictMode），或者干脆全部丢后台线程。
6. **别 pkill 别人的进程**。车机上 `d_service` 是迪友的常驻进程，早先按名字 pkill 把人家干掉了。
7. **全局设置只追加自己**。`enabled_accessibility_services` 迪+ 方控也在写，删改任何一条都会把别人的无障碍顶掉。

## 六、拿什么验证它真的拿到了 shell

```bash
adb shell "cat /proc/net/tcp /proc/net/tcp6 | awk 'NR>1 && $3 ~ /15B3/ {print $8}' | sort | uniq -c"
```
`15B3` = 5555 十六进制，输出里的 uid 列：`2000` 是 shell，`10083` 是本 App。
正常状态应该是「执行命令的那一两秒有，其余时间 0」——有常驻就是第 4 条坑。
