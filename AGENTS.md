# 仪表助理（ClusterHUD / ClusterAssist）· AI 交接总纲

> 这是给**下一个 AI** 看的入口文件。读完这一页，你就知道：这是什么、代码在哪、怎么编译、怎么装车、哪些坑已经踩过。
> 详细内容按需跳转：`docs/01-环境与工具.md` → `docs/02-代码结构.md` → `docs/03-迭代日志.md` → `docs/04-问题台账.md` → `docs/05-原始分析摘录.md`

**版本**：v4.9.8（2026-11-03 15:52 · 自愈「没显示就重拉」轮询 3s；两个包 Ver.TAG 已对齐 v4.9.8）
**目标车机**：2023 款比亚迪汉 DM-i · DiLink 4.0 · 仪表屏 display 1（1920×720）
**语言**：Java 8 / Android SDK 29（编译用 android-34 jar）· 纯手写 View 自绘，无 Gradle

---

## 一、这是什么

两个 APK，一主一辅，把**仪表屏（display 1）**变成一个自绘的信息层：

| APK | 包名 | 角色 |
|---|---|---|
| `ClusterHUD-1.0-v4.9.8.apk` | `com.byd.clusterlab` | 仪表屏透明自绘卡片：油耗/电耗/胎压 + 音乐封面歌词进度条 + 导航卡片识别 |
| `ClusterAssist-1.0-v4.9.8.apk` | `com.byd.clusterassist` | 手机/车机上的控制面板：元素样式编辑器（颜色/字号/字重/描边/透明度）+ 守护进程 |

关键事实：
- **不改车机系统 App**，不替换 framework，不 hook 系统进程；纯第三方 APK。
- **提权链（v4.0 起自研，不依赖 Shizuku）**：`YBAdb` 自己连 `127.0.0.1:5555` 走 ADB AUTH 拿 shell（uid 2000）
  → 用 shell 权限 `pm grant` / `appops` / `cmd notification` 补权限，`am start-activity --display 1` 拉起卡片。
  **v4.9.1 已删掉 d_service 那条线**（`YBDaemon`/`YBCore`/`YBByd`/`YBFeed`/`Stat`）：实测 uid 2000 同样没有
  `BYDAUTO_*_GET`，读不到车况，纯属白烧电。
- 数据来源：**迪加 HTTP `127.0.0.1:8988`**（`/api/getVal` 取四轮胎压、总油耗/总电耗/里程，`/api/currentTrip` 取本次行程）、
  迪加遥测 SQLite。**迪友无障碍抓卡与 BYDAuto 直读都已废弃**（见 `04` #6 / #20）。
- **可复用资产**：`AdbCrypto` + `YBAdb` + `YBPrivilege`（844 行、零依赖、免 root）能拿到 uid 2000 shell，
  可 `pm grant` / `appops` / `am start --display N` / `settings put secure` / `input tap`。
  「灵手」手势与 ADAS 测试工具都用它。移植说明见 `docs/08-ADB能力复用包.md`。
- 界面全部用 `Canvas` 手绘在一个 1920×720 的 View 上，不用 XML 布局。
- 屏幕坐标硬编码（`SL/ST/SB` 等常量），改布局就是改常量。

---

## 二、30 秒上手

```bash
# 1) 编译（overlay=主程序 / assist=面板 / all=两个都编）
cd ~/Desktop/DSH/仪表助理
./tools/build.sh overlay

# 2) 装车（车机与 Mac 同一 WiFi，车机 IP 见 docs/01）
adb connect 192.168.46.1:5555
adb install -r apk/ClusterHUD-1.0-v4.9.8.apk

# 3) 看日志（所有关键路径都打了 TAG）
adb logcat -s CLUSTERLAB:* CLUSTERLAB-NAV:* CLUSTERLAB-LYRIC:* CLUSTERLAB-BOOT:* CLUSTERLAB-KEEP:*
# 自研 ADB 那条线
adb logcat -s YB_ADB:*
```

> `tools/build.sh` 里写死了 SDK 路径与 JDK 路径，换机器先改文件头部变量（见 `docs/01-环境与工具.md`）。

---

## 三、当前状态（2026-11-03 15:52 · v4.9.8，两个包版本号已对齐）

| 功能 | 状态 | 备注 |
|---|---|---|
| 仪表屏透明卡片（油耗/胎压/歌词） | ✅ | display 1 自绘；`CardActivity` 单文件 1920×720 |
| 综合油耗（L/100km） | ✅ | 只认「一段里程的平均」：最近 50km 分段 → `/api/currentTrip`；已剔除迪加「百公里电耗」瞬时值（它给 49.3，50km 给 15.9，来回切换就是跳动） |
| 胎压四轮 | ✅ | 迪加 `getVal` 四个中文名，4 线程并发、节流 2s |
| 音乐封面 + 歌词（四级回退） | ✅ | `MediaListener` 通知监听 → `LyricLoader`（网易云→QQ→酷狗→酷我） |
| 导航大卡/小卡自适应 | ✅ | `NavProbe` 像素判据 + 高德广播；进 3 退 4、切完锁 1.5s |
| ADB 自授权（软件内） | 🟡 半自动 | 握手在 App 内完成；**首次仍需点一次系统「允许」**，或导入主机密钥（`/sdcard/clusterlab/adbkey.pk8` 三行） |
| 程序自愈 | ✅ | `:keep` 独立前台进程 + adb 重拉；判据只认窗口 isVisible（心跳新鲜 ≠ 看得见）；实测关卡片 ~28s、被导航盖住 ~9s |
| 车机切投屏模式 | 🟡 | 只做一件事：窗口不可见连续两次（约 6 秒）→ `am start-activity --display 1` 重拉。不加布局判据、不搬任务栈 |
| 开机自启 | ❌ | 车机熄火是休眠、`BOOT_COMPLETED` 永不到来；熄火后主进程被杀。现靠亮屏广播 + 15s 精确闹钟链，**点火即现未达成** |
| CPU 占用 | ✅ | 静止 3%、播放 21% |

## 四、下一步该干什么（按优先级）

1. **验熄火后 `:keep` 是否存活**（当前唯一未知，决定"点火即现"能不能做到）。
```bash
   # 熄火锁车 1 分钟以上，回车上点火，然后：
   adb shell "ps -A | grep clusterlab"          # 只剩 :keep → 白名单不是必需的
   adb shell "ls -l /sdcard/clusterlab_card_beat.txt"
```
   两个都没了 → 车机整包清理，只剩「极速模式白名单」这一条（迪加也是靠用户手动加）。
2. **数据源现在是迪加单点**：迪加被停/被卸，卡片就只剩空值。要自给自足得先解决
   `BYDAUTO_*_GET` 的签名级权限（`04` #6，三条路已否决，别再重走）。
3. **别恢复已删的东西**：`GetValProbe`/`TestActivity`/`Stat`/`YBDaemon`/`YBCore`/`YBByd`/`YBFeed`
   都是按用户口径（只做油耗/胎压/歌词 + 自启 + 自愈）删掉的死代码，需要时查 `docs/03` 的 v4.9.1 那节。
4. **大窗进度条**已随死方法删除；用户嫌突兀，**别自作主张恢复**。

## 五、铁律（违反会被用户纠正）

1. **不自己加需求、不改需求**。用户明确说过：「不要自己增加需求或改变需求」。
2. **不加装饰符号**。用户不要 `≈`、不要多余空格，格式按他给的原文（如 `综合油耗: xxL/100km`）。
3. **改布局就改常量**，改完在 Mac 上出 PIL 预演图给他看，再装车。
4. **先回滚再重做**。用户说「回滚」就 `cp` 备份回去，别解释。
5. **别写"约等于/估算"标记**，估算状态只在日志里说。
6. 每个改动都要能 `./tools/build.sh overlay` 一次过，不留编译警告为错误。
7. **自绘里不许每帧拼字符串**。缓存 key 一律用引用/数值比较，否则 GC 会把 CPU 顶到 40%（见 `04` #17）。
8. **主线程只做毫秒级操作**。`YBAdb.exec` / `Thread.sleep` 一律丢后台线程，否则无障碍绑定超时（见 `04` #5）。
9. **两个包都不许再引 Shizuku**。`Shz.java` 已删，assist 的 `Shell` 也走 `YBAdb`；
   要 shell 就用 `YBAdb`（用完即断，别常驻占 adbd 槽，见 `04` #22）。

---

## 六、目录

```
仪表助理/
├── AGENTS.md                  ← 你在读
├── docs/
│   ├── 01-环境与工具.md        工具链版本、SDK 路径、adb、签名、构建流程
│   ├── 02-代码结构.md          逐文件职责 + 关键常量 + 数据流
│   ├── 03-迭代日志.md          v0→v2 全部需求与改动（含日期与触发点）
│   ├── 04-问题台账.md          踩过的坑、根因、结论、别再试的路径
│   ├── 05-原始分析摘录.md      仪表投屏技术总结 / 哨兵项目的相关结论
│   └── 06-交付清单.md          文件哈希、APK 校验、可复现步骤
├── src/
│   ├── clusterlab/            主程序模块（com.byd.clusterlab，18 文件）
│   │   ├── src/               ← 改这里
│   │   ├── res/               styles.xml、xml/car_access.xml
│   │   ├── assets/            car_top.png
│   │   ├── AndroidManifest.xml
│   │   └── build/             javac/aapt2 中间产物 + 最终 apk（不要手改）
│   ├── assist/                面板模块（com.byd.clusterassist，15 文件，同构）
│   ├── shizuku/               Shizuku SDK 源码（已无引用，可删）
│   └── clusterlab_style.txt   用户当前生效的样式配置（从车机导出）
├── apk/                       可直接安装的产物
├── preview/                   Mac 端 PIL 布局预演图
└── tools/build.sh, *.jks      构建脚本与 debug 签名
```

---

## 七、给下一个 AI 的三句话

1. 这是**真车真屏**上的东西，坐标靠实测，不要凭想象改数字；改完必须给预演图。
2. 大部分疑难都是**权限/通道问题**，不是代码逻辑问题——先看 `docs/04` 再动手。
3. 用户是车主本人，改的是自己车的仪表显示；所有验证都在他自己车上做，离线、本地、可回滚。

## v4.2 关键结论（11-01 车机实测，务必先读）

- ADB 自授权能跑通，但签名算法是坑：adbd 把 20 字节 token 当已算好的 SHA-1 摘要，
  直接签 DigestInfo(3021…0414 || token)，不要用 SHA1withRSA。见 AdbCrypto.signToken。
- 免弹窗做法：把电脑上已授权的 ~/.android/adbkey 导成 /sdcard/clusterlab/adbkey.pk8（三行）。
- d_service + BYDAuto 直读这台 ROM 上不通（uid 2000 也缺 BYDAUTO_*_GET），车况走迪加 getVal。
- 日志默认关，/sdcard/clusterlab_debug.txt 存在才写；助理界面有「日志」开关。
- 助理已彻底去 Shizuku（Shell 走 YBAdb），ADAS 大小区块已删。
