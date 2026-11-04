# DiLink 4.0 车辆信息投屏（ClusterHUD / ClusterAssist）

> **这是一个「用 AI 许愿式开发」出来的项目。** 一句一句把需求说出来，AI 一行一行写出来，车主本人在真车上反复试出来的。
> 目标很简单：**满足不能刷机、不想刷机的朋友 —— 不刷机就能在全屏导航时看到基本的车辆信息。**

DiLink 4.0（比亚迪汉 DM-i / 1920×720 仪表屏）上，全屏导航会占满整个仪表，油耗、胎压、歌词全都看不到。
本项目在仪表屏（`display 1`）上盖一层透明自绘卡片，把这几个信息一直显示在导航之上。

![仪表效果](preview/mock-mid-v2.png)

**下载**：[Releases](https://github.com/chanzp666-boop/DiLink4-ClusterHUD/releases) 里直接拿 APK（`ClusterHUD` 装车机、`ClusterAssist` 装车机或手机）。

## 它做什么

| 能力 | 说明 |
|---|---|
| 综合油耗 | `油耗 L/100km + 电耗 kWh/100km ÷ 3.5`，取「最近 50km 分段 / 本次行程」；不用瞬时值（会来回跳） |
| 四轮胎压 | 左前 / 右前 / 左后 / 右后，实时 kPa |
| 歌词 | 通知监听 → MediaSession → 四级歌词源（网易云 / QQ / 酷狗 / 酷我），封面旋转 + 逐行高亮 |
| 导航适配 | 识别仪表上的导航卡片，自动让位 / 切大卡小卡 |
| 程序自愈 | 三层：`:keep` 进程 3 秒查窗口可见性 / shell 看门狗 5 秒查进程与卡片 / 精确闹钟链兜底 |
| 免 Shizuku 提权 | 自研 ADB 客户端在 App 内完成 AUTH 握手，拿 uid 2000 shell 后自己补权限 |

## 不刷机是怎么做到的

1. 车机的 adbd 监听 `127.0.0.1:5555`，App 内手写 ADB 协议（CNXN / AUTH / OPEN / WRTE）自己连上去；
2. 首次需要在车机弹窗里点一次「允许 USB 调试」；也可以用「电脑上已授权的 `~/.android/adbkey`」导成三行推到
   `/sdcard/clusterlab/adbkey.pk8`（PKCS8 / 模数 n / 指数 e），之后免弹窗；
3. 拿到 shell（uid 2000）后 `pm grant` / `appops` 补齐权限，再用 `am start-activity --display 1` 把卡片送上仪表屏。

**不改系统 App、不换 framework、不 hook 系统进程**，纯第三方 APK，随时可卸载。

> 三次踩坑换来的关键一行：adbd 把 20 字节 token 当成**已算好的 SHA-1 摘要**，
> 要签 `3021300906052b0e03021a05000414 || token` 的裸 RSA 私钥运算，不能直接 `SHA1withRSA`。
> 细节见 [docs/07-ADB自授权速查.md](docs/07-ADB自授权速查.md) 与 [docs/08-ADB能力复用包.md](docs/08-ADB能力复用包.md)。

## 编译

无 Gradle，纯 `javac` + `aapt2` + `d8`：

```bash
cd 仪表助理
./tools/build.sh all        # 主程序 + 面板，产物在 src/*/build/*.apk
```

`tools/build.sh` 头部写死了 SDK / JDK 路径，换机器先改那几个变量。需要 Android SDK（build-tools 33+、
platform android-33/34）与 JDK 8。

## 安装

```bash
adb connect <车机IP>:5555
adb install -r apk/ClusterHUD-1.0-v5.0.apk        # 仪表卡片
adb install -r apk/ClusterAssist-1.0-v4.9.8.apk     # 控制面板（可选）
```

首次启动会在车机弹一次「允许 USB 调试」，点允许即可。日志默认关：在车机建 `/sdcard/clusterlab_debug.txt` 打开，
面板里也有「日志」开关。

## 目录

```
仪表助理/
├── src/clusterlab/     主程序 com.byd.clusterlab（19 个 java 文件 + assets/app-watchdog.sh）
├── src/assist/         面板   com.byd.clusterassist（15 个 java 文件）
├── docs/               01 环境 / 02 代码结构 / 03 迭代日志 / 04 问题台账 / 06 交付清单 / 07 ADB 速查 / 08 ADB 复用
├── apk/                可直接安装的产物
├── preview/            布局预演图
└── tools/build.sh      构建脚本 + debug 签名
```

给 AI 看的入口是 [AGENTS.md](AGENTS.md)：总纲、铁律、下一步、别再重走的死路，都写在那里。

## 已知边界（先说清楚，省你时间）

- **开机自启靠五层，最后一层是 shell 看门狗**。DiLink 4.0 熄火是整机 suspend，`BOOT_COMPLETED` 不会来，
  熄火后应用进程和被 ROM 按 UID 一起清掉的闹钟都没了。所以真正的兜底是装进 `/data/local/tmp` 的
  UID 2000 看门狗脚本（5 秒一轮），它只被 suspend 冻结、不被按 UID 清理。再配合车机「极速模式白名单」更稳。
  这一步需要 App 里已经拿到 shell —— 也就是下面那条 ADB 自授权。
- **数据依赖迪加（com.van.diplus）**。第三方包拿不到 `BYDAUTO_*_GET` 签名级权限（实测 `pm grant` 报
  “not a changeable permission type”），只能走迪加本地 HTTP `127.0.0.1:8988`。迪加被停用，卡片就只剩空值。
- 坐标按 1920×720 硬编码，换车型要改 `CardActivity` 里的常量。
- 只在 **2023 款比亚迪汉 DM-i / DiLink 4.0 / Android 10** 上真车验证过。

## 许可

**MIT License** —— 可自由使用、修改、分发，**允许商用**。详见 [LICENSE](LICENSE)。

> 行车中请勿操作界面。本项目只是把车机已有的信息搬到仪表屏上显示，不干预任何车辆控制。
