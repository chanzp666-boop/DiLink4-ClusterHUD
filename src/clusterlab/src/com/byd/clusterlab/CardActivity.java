package com.byd.clusterlab;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

public class CardActivity extends Activity {
    /** 不走 adb、本进程直接往 display 1 拉一次。成功返回 true。
     *  开机阶段 adb 还没授权，以前 BootReceiver 卡在等 YBAdb.ready()，
     *  卡片就一直不出现 —— 用户看到的是「开机无自启、手动打开后状态全绿才冒出卡片」。 */
    public static boolean launchLocal(android.content.Context ctx) {
        try {
            android.content.Intent it = new android.content.Intent(ctx, CardActivity.class);
            it.putExtra("local_launch", true);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setLaunchDisplayId(1);
                ctx.startActivity(it, o.toBundle());
            } else {
                ctx.startActivity(it);
            }
            Dbg.e("CLUSTERLAB-BOOT", "本地拉起 display1 卡片（不等 adb）");
            return true;
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-BOOT", "本地拉起失败: " + t);
            return false;
        }
    }

    private static boolean probed = false;
    private static boolean askedAdb = false;

    /** v4.0：自建 ADB 连接，不再走 Shizuku。连上后自动补授权 + 拉起 d_service。 */
    private void requestAdb() {
        if (askedAdb) return;
        askedAdb = true;
        try { YBAdb.connect(this); } catch (Throwable t) {
            Dbg.e("CLUSTERLAB", "adb connect failed: " + t);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 40 && !YBAdb.ready(); i++) {
                    try { Thread.sleep(500); } catch (Throwable ignored) {}
                }
                Dbg.e("CLUSTERLAB", "adb " + YBAdb.status());
                if (!YBAdb.ready()) return;
                try { YBPrivilege.applyOnce(CardActivity.this); } catch (Throwable ignored) {}
            }
        }, "adb-boot").start();
    }

    private static CardActivity current;

    // 试过改用 SYSTEM_ALERT_WINDOW 悬浮窗直接压住导航，这条路在这台车机上不通：
    // 仪表屏是 com.xdja.containerservice 的私有虚拟屏，普通 App 的 DisplayManager.getDisplays()
    // 只返回 1 个（默认屏），getDisplay(1) 返回 null，拿不到 display 1 的 Context 就 addView 不了。
    // 实测日志：探针 actDisplay=0 dmCount=1。所以只能继续用 Activity + adb 换栈抬升。

    @Override protected void onResume() { super.onResume(); current = this; }
    @Override protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    /** 助理点「停止卡片」时用：只关界面，不做 force-stop（force-stop 会让我们下次开机收不到 BOOT_COMPLETED）。 */
    public static void shutdown() {
        CardActivity a = current;
        if (a == null) return;
        try { a.finish(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 本地直拉只是「开机那一刻 adb 还没授权」的抢跑手段：
        // 万一系统没允许跨屏启动、界面落到了 0 号屏，立刻自己退出，别污染主屏。
        if (getIntent() != null && getIntent().getBooleanExtra("local_launch", false)) {
            android.view.Display d = getDisplay();
            int dId = d == null ? -1 : d.getDisplayId();
            if (dId != 1) {
                Dbg.e("CLUSTERLAB-BOOT", "local_launch 落到 display " + dId + "，退出");
                finish();
                return;
            }
            Dbg.e("CLUSTERLAB-BOOT", "local_launch 命中 display 1");
        }
        requestAdb();
        NavReceiver.start(this);   // 高德导航状态广播
        AlarmKeeper.schedule(this);
        try {
            startForegroundService(new android.content.Intent(this, KeepAlive.class));
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-KEEP", "start failed: " + t);
        }
        // 冷启动时通知监听常常没绑上（force-stop 解绑后系统不会自动恢复），
        // 界面起来 2.5s 后自查一次，没连上就 disallow → allow 重绑。
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(2500); } catch (Throwable ignored) {}
                if (MediaListener.connected || !YBAdb.ready()) {
                    Dbg.e("CLUSTERLAB-MEDIA", "skip rebind connected=" + MediaListener.connected);
                    return;
                }
                Dbg.e("CLUSTERLAB-MEDIA", "rebind on activity start");
                YBAdb.exec("cmd notification disallow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
                try { Thread.sleep(1200); } catch (Throwable ignored) {}
                YBAdb.exec("cmd notification allow_listener com.byd.clusterlab/com.byd.clusterlab.MediaListener", 6000);
            }
        }).start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        getWindow().setDimAmount(0f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.alpha = 1.0f;
        lp.format = android.graphics.PixelFormat.TRANSLUCENT;
        getWindow().setAttributes(lp);
        setContentView(new CardView(this));

    }

    static class CardView extends View {
        static final int SL = 120, ST = 165, SR = 1800, SB = 555;
        static final long LYRIC_LEAD_MS = 80L;
        private static long lastLoggedLd = -1;

        static final int BG_TOP = 0xFF232E40, BG_BOT = 0xFF161E2B;
        static final int FG = 0xFFE9EFF7, FG_DIM = 0xFF8496AE, FG_DIM2 = 0xFF6C7C94;
        static final int ACCENT = 0xFF4DA3FF, GREEN = 0xFF2FD79B;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint txt = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        private final Typeface tfRegular = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfThin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
        private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface tfBold = Typeface.create("sans-serif-black", Typeface.NORMAL);

        private final Typeface light = tfLight;
        private final Typeface med = tfMed;
        private final Typeface bold = tfBold;

        /** 按元素设置取字重：0=细 1=基本 2=加粗 3=超粗 */
        /**
         * 中文字体系统里只有 NotoSansCJK-Regular 一个字重，
         * sans-serif-thin/medium 落到汉字上全部回退成同一个字形。
         * 所以：细 = light 字体族、基本 = regular、加粗/超粗 = 伪粗（fakeBoldText 才真正对中文生效）。
         */
        private Typeface tfFor(String el) {
            int w = Style.weight(el);
            if (w == 0) return tfLight;      // 细：light 字体族
            if (w >= 2) return tfRegular;    // 加粗/超粗：基础字形 + fakeBold
            return tfRegular;                // 基本
        }

        /** 该字重是否需要伪粗（中文只有靠 fakeBoldText 才能变粗）。 */
        private boolean fakeBoldFor(String el) {
            return Style.weight(el) >= 2;
        }

        /** 超粗的额外加粗半径（像素）：用多次偏移描字模拟，0 表示不加。 */
        private float extraBoldFor(String el) {
            int w = Style.weight(el);
            if (w >= 3) {
                Dbg.e("CLUSTERLAB-W", "extraBold " + el + " w=" + w);
                return 1.2f;
            }
            return 0f;
        }

        private boolean nightMode = true;
        private long themeStamp = 0L;

        private boolean resolveNight() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - themeStamp < 1500) return nightMode;
            themeStamp = now;
            try {
                java.io.File f = new java.io.File("/sdcard/clusterlab_theme.txt");
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                    String line = br.readLine();
                    br.close();
                    if (line != null) {
                        String v = line.trim().toLowerCase();
                        if (v.equals("light")) { nightMode = false; return nightMode; }
                        if (v.equals("night") || v.equals("dark")) { nightMode = true; return nightMode; }
                    }
                }
            } catch (Throwable ignored) {}
            try {
                // 以系统 Configuration 为准：ui_night_mode 可能是 2（自动），
                // 但 framework 的 uiMode 一定反映真实深浅色（本车实测 cmd uimode night = yes）。
                boolean n = (getContext().getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                int mode = android.provider.Settings.Secure.getInt(
                        getContext().getContentResolver(), "ui_night_mode", 0);
                if (n != nightMode) {
                    Dbg.e("CLUSTERLAB-THEME",
                            "uiMode=" + (n ? "night" : "notnight") + " ui_night_mode=" + mode + " -> night=" + n);
                }
                nightMode = n;
                return nightMode;
            } catch (Throwable ignored) {}
            return nightMode;
        }

        /** 元素最终颜色 = 配置色 + 该元素透明度。 */
        private int el(String el) {
            return Style.withAlpha(Style.color(el, nightMode), Style.alpha(el));
        }

        /** 样式版本 + 模式进缓存 key：改任一元素或切主题都重画静态层。 */
        private String colorKey() {
            return (nightMode ? "N" : "L") + "v" + Style.version
                    + ":" + el("cap") + "," + el("num") + "," + el("unit");
        }

        private int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | ((a & 0xFF) << 24); }

        /* ---------- 音乐两种形态 ---------- */
        private boolean bigMusic() { return !NavState.isNavigating(); }
        private float artSize() { return bigMusic() ? 116f : 64f; }
        // 大窗封面：156（v2 由上版 146 右移 10px），小窗不动
        private float artX() { return bigMusic() ? 156f : 180f; }
        /**
         * 小窗位置跟着导航卡片走：
         * 大卡（三行信息）蓝条在 y≈330，底边约 390 → 音乐从 400 开始
         * 小卡（只有距离+箭头）蓝条在 y≈270，底边约 330 → 音乐从 340 开始
         * blueTop 由 NavProbe 实时测量，测不到就退回 400。
         */
        /** 小窗两种固定位置（实测），只在两值间切换。 */
        // 实测确认：
        //   大卡（170~390，含蓝条箭头区）→ 音乐 402
        //   小卡（170~325，纯文字）      → 音乐 320
        private static final float SMALL_Y_BIG_CARD = 402f;
        private static final float SMALL_Y_SMALL_CARD = 340f;

        /** 大窗歌词块：固定 6 行，第一行顶边从这里开始。 */
        private static final int BIG_LYRIC_ROWS = 6;
        private static final float BIG_LYRIC_TOP = 186f;
        /** 大窗歌词块左边界（封面右缘 272，留 24px 呼吸位）。 */
        private static final float BIG_LYRIC_X = 296f;
        /** 大窗歌词块的文字宽度：进度条比它宽 10px，两边各留 5px。 */
        private static final float LYRIC_MAX_W = 316f;
        /** 大窗进度条宽度（比歌词宽 10px）。 */
        private static final float PROG_W = LYRIC_MAX_W + 10f;
        /** 进度条粗细。 */
        private static final float PROG_H = 6f;

        private float artY() {
            if (bigMusic()) {
                // 封面对齐歌词块中间：歌词块高 = 6 行，封面在其中垂直居中
                float blockH = lineH() * BIG_LYRIC_ROWS;
                return BIG_LYRIC_TOP + (blockH - artSize()) / 2f;
            }
            return NavProbe.hasArrowBar ? SMALL_Y_BIG_CARD : SMALL_Y_SMALL_CARD;
        }
        private float titleSizePx() { return Style.size("title"); }
        private float artistSizePx() { return Style.size("artist"); }
        // 大窗：QQ 音乐式卡片，碟在左、曲名作者垂直居中于碟；小窗：堆在封面正上方
        private float titleX() { return bigMusic() ? (artX() + artSize() + 22f) : artX(); }
        private float titleY() { return bigMusic() ? (artY() + artSize() / 2f - 4f) : artY() - 26f; }
        // 歌手与曲名的间距：曲名基线 + 曲名字号 × 0.55 + 24（34 → 24，收 10px）
        private float artistY() { return bigMusic()
                ? (titleY() + titleSizePx() * 0.55f + 24f) : artY() - 8f; }
        // 歌词横向：封面右侧（不盖住封面）
        private float lyricX() { return bigMusic() ? BIG_LYRIC_X : (artX() + artSize() + 20f); }

        // 行距：大窗宽松(+18)，小窗收紧(+8)
        private float lineH() {
            float extra = bigMusic() ? 18f : 8f;
            return Math.max(22f, Style.size("lcur") + extra);
        }

        // 纵向：让"最上面那一行"的文字顶边与封面顶边平齐。
        // baseY 是当前行(k=0)的基线；上面还有 aboveLines 行，
        // 所以第一行的基线 = baseY - aboveLines*lineH，它的顶边 = 基线 - 字号。
        // 令 顶边 == artY：baseY = artY + 字号 + aboveLines*lineH
        // 大窗上面留 2 行（第 1 行播放时淡出）；小窗不留，当前行直接从封面顶边开始
        // 上方只留一行（已唱过、正在淡出的那一行）；小窗不留
        // 大窗上面留 1 行（已唱过的、正在滚出的）；小窗不留
        /** 当前歌词索引的快照（绘制时更新）。 */
        private int drawIdx = 0;

        /**
         * 上方预留几行（决定"当前行显示在第几行"）。
         * 大窗：歌词刚出现时当前行在第 1 行，往下走到第 3 行才开始滚动；
         * 小窗：一直不留，当前行就在第 1 行。
         */
        private int aboveLines() {
            if (!bigMusic()) return 0;
            if (drawIdx <= 0) return 0;
            return drawIdx >= 3 ? 3 : drawIdx;
        }

        /** 上方实际能显示几行（歌曲开头时可能不足 aboveLines）。 */
        private int availAbove(int idx, int size) {
            int n = 0;
            for (int k = -aboveLines(); k <= -1; k++) {
                if (idx + k >= 0 && idx + k < size) n++;
            }
            return n;
        }

        /**
         * 两种形态布局规则不同：
         *   大窗：上面留一行给"已唱过的歌词"，当前行在封面顶部下方一行
         *   小窗：不留上方的行，当前行首行直接与封面顶部平齐
         */
        private float lyricBaseY(int aboveNow) {
            if (bigMusic()) {
                // 第一行(k=-aboveLines)顶边 = BIG_LYRIC_TOP，
                // 当前行(0) 的基线 = 顶边 + aboveLines*lineH + 字号
                return BIG_LYRIC_TOP + aboveLines() * lineH() + Style.size("lcur");
            }
            // 小窗：当前行直接从封面顶部开始（-1 让视觉更居中，再往上会贴到卡片）
            return artY() + Style.size("lcur") - 1f;
        }

        /* 大窗背后的半透明卡片，让文字从地图上“浮”起来 */
        private static final float CARD_L = 158f, CARD_T = 162f, CARD_R = 700f, CARD_B = 470f;

        /* 油耗：一行居中，放在顶栏（P 档/车速/时间）下面 */
        private static final float FUEL_LINE_Y = 188f;   // 基线（再下移 4px）
        private static final float FUEL_LINE_CX = 960f;  // 屏幕水平中心

        private Bitmap staticCache;
        private String cacheKey = "";
        private long lastKeyAt = 0L;
        /** 歌词是否处于动画中（滚动/淡入），供 tick 决定帧率。 */
        private volatile boolean lyricAnim = false;
        private boolean lyricLogged = false;
        private long lyricLogAt = 0L;

        /* 歌词排版缓存：每帧都新建 StaticLayout 会拖慢绘制，按"文本+宽度+字号"缓存 */
        /* 每行歌词的 StaticLayout 缓存：4 个槽位。原来每帧建 4 个，换行时更密集，
           这是歌词动画卡的根因。key = 文本 + 宽度 + 字号档位。 */
        private final String[] slKey = new String[12];
        private final String[] slText = new String[12];
        private final int[] slWidth = new int[12];
        private final float[] slSize = new float[12];
        private final android.text.StaticLayout[] slCache = new android.text.StaticLayout[12];

        private boolean inCache(int slot, String kk) {
            return slot >= 0 && slot < slCache.length && slCache[slot] != null && kk.equals(slKey[slot]);
        }

        private String probeCacheKey = "";
        private android.text.StaticLayout probeCache = null;
        private float probeExtraCache = 0f;

        /**
         * 绘制节拍。
         * 唱片旋转、歌词滚动需要 60fps；其余时间没有必要一直满帧重绘
         * （车机 CPU 弱，每帧还要重算 ensureStatic 的缓存 key）。
         */
        private long beatAt = 0L;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                // 10 秒一次心跳：守护进程据此判断界面在，不再去 adb 里查 dumpsys
                long now = SystemClock.elapsedRealtime();
                if (now - beatAt > 10000L) { beatAt = now; KeepAlive.beat(); }
                // 没有音乐、也没有歌词动画时，界面是静止的：完全不重绘，1 秒轮一次看状态就够。
                // 以前固定 250ms 重绘一次，白烧了一个核的 4~5%。
                // 歌词滚动刷整条歌词带；只转唱片时只刷唱片那一小块（面积差 6 倍，30fps 也不费）
                boolean playing = MediaListener.playing;
                if (lyricAnim) {
                    invalidate((int) SL - 8, (int) (ST - 10), (int) (SL + 566), (int) SB);
                    postDelayed(this, 33);
                } else if (playing && discRect.width() > 0f) {
                    invalidate((int) discRect.left, (int) discRect.top,
                            (int) Math.ceil(discRect.right), (int) Math.ceil(discRect.bottom));
                    postDelayed(this, 33);   // 30fps：旋转要顺滑
                } else {
                    // 静止（没音乐 / 没动画）也必须周期性取数并重绘：
                    // refresh() 只在 onDraw 里调用，而这里以前只 postDelayed(1000) 不重绘，
                    // 于是「没音乐 = 不重绘 = 永远不取数」，界面就冻在第一次画的 "--" 上
                    // （真机实测 Total frames rendered: 2，油耗/胎压/歌曲全是空白）。
                    VehicleData.refresh(getContext());
                    invalidate();
                    postDelayed(this, 700);
                }
            }
        };

        CardView(Context c) {
            super(c);
            setBackgroundColor(0x00000000);
            setLayerType(View.LAYER_TYPE_NONE, null);   // 用局部刷新省 CPU；硬件层会强制整层重绘，反而更贵
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(tick, 16); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        private void T(Canvas cv, String s, float x, float y, float size, int color, Typeface tf, Paint.Align al) {
            if (s == null || s.length() == 0) return;
            txt.setTypeface(tf); txt.setTextSize(size); txt.setColor(color); txt.setTextAlign(al);
            cv.drawText(s, x, y, txt);
        }

        private void ensureStatic() {
            // 静态层只跟数据有关，而数据最快 400ms 才更新一次；
            // 每帧（60fps）都拼接十几个字符串算 key 纯属浪费 CPU
            long nowKey = System.currentTimeMillis();
            if (staticCache != null && nowKey - lastKeyAt < 150) return;
            lastKeyAt = nowKey;
            String key = MediaListener.title + "|" + MediaListener.artist + "|" + MediaListener.album
                    + "|" + (MediaListener.art == null ? 0 : System.identityHashCode(MediaListener.art))
                    + "|" + VehicleData.pressure[0] + "," + VehicleData.pressure[1]
                    + "," + VehicleData.pressure[2] + "," + VehicleData.pressure[3]

                    + "|" + Math.round(VehicleData.fuelPHM * 10) + "," + Math.round(VehicleData.elecPHM * 10) + "," + Math.round(VehicleData.totalPHM * 10)
                    + "|" + getWidth() + "x" + getHeight()
                    + "|nav:" + NavState.isNavigating()
                    + "|ly:" + LyricLoader.lines.size() + ":" + LyricLoader.indexAt(MediaListener.position)
                    + "|" + colorKey();
            if (staticCache != null && key.equals(cacheKey)) return;
            cacheKey = key;
            if (staticCache == null || staticCache.getWidth() != Math.max(1, getWidth())
                    || staticCache.getHeight() != Math.max(1, getHeight())) {
                staticCache = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
            }
            Canvas c = new Canvas(staticCache);
            c.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR);
            drawStatic(c);
            postInvalidate();   // 缓存重建后整屏重绘一次，别只刷局部区
        }

        private boolean lastNav = NavState.isNavigating();

        {
            NavState.onChange = new Runnable() {
                @Override public void run() {
                    cacheKey = "";
                    discSrc = null;
                    postInvalidate();
                }
            };
        }

        @Override protected void onDraw(Canvas cv) {
            boolean nav = NavState.isNavigating();
            if (nav != lastNav) {
                lastNav = nav;
                cacheKey = "";          // 强制下一行 ensureStatic() 重建静态层
                discSrc = null;          // 唱片尺寸变了，重建位图
                Dbg.e("CLUSTERLAB-NAV", "布局切换: " + (nav ? "小窗(导航中)" : "大窗(未导航)"));
            }
            VehicleData.refresh(getContext());
            resolveNight();
            ensureStatic();
            if (staticCache != null) {
                android.graphics.Rect cb = cv.getClipBounds();
                if (cb != null && cb.width() < getWidth() - 1) {
                    cv.drawBitmap(staticCache, cb, cb, null);
                } else {
                    cv.drawBitmap(staticCache, 0, 0, null);
                }
            }
            if (bigMusic()) drawDisc(cv);
            drawLyrics(cv);
            drawVersion(cv);
        }

        /** 右上角版本号：出问题时报这个号，一眼知道装的是哪版。 */
        private void drawVersion(Canvas cv) {
            try {
                if (verPaint == null) {
                    verPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    verPaint.setColor(0x99FFFFFF);
                    verPaint.setTextSize(20f);
                    verPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    verPaint.setTextAlign(Paint.Align.RIGHT);
                }
                cv.drawText(Ver.TAG, getWidth() - 14, 28, verPaint);
            } catch (Throwable ignored) {}
        }

        private Paint verPaint;

        private void drawStatic(Canvas cv) {

            // 音乐卡片不要背景：保持全透明，直接叠在地图上

            float l1 = SL, r1 = SL + 560;
            float l2 = r1, r2 = r1 + 560;

            /* ---------- music ---------- */
            float ax = artX(), ay = artY(), art = artSize();
            Bitmap cover = MediaListener.art;
            r.set(ax, ay, ax + art, ay + art);
            if (!bigMusic()) {
            if (cover != null) {
                cv.save();
                Path clip = new Path();
                clip.addRoundRect(r, 8f, 8f, Path.Direction.CW);
                cv.clipPath(clip);
                bmp.setAlpha(255);
                cv.drawBitmap(cover, null, r, bmp);
                cv.restore();
            } else {
                fill.setShader(new LinearGradient(ax, ay, ax + art, ay + art, 0xFF3A5A88, 0xFF16223A, Shader.TileMode.CLAMP));
                cv.drawRoundRect(r, 8f, 8f, fill);
                fill.setShader(null);
                T(cv, "\u266a", ax + art / 2f, ay + art * 0.70f, 52f, 0x555C7FA8, light, Paint.Align.CENTER);
            }
            line.setColor(0x337FB4FF); line.setStrokeWidth(1.2f);
            cv.drawRoundRect(r, 8f, 8f, line);
            }

            // 曲名 / 歌手：大窗小窗都不显示（用户要求），只保留封面 + 歌词

            /* ---------- 油耗：一行居中，放在顶栏（P 档/车速/时间）下面 ---------- */
            drawFuelLine(cv);

            // ---- tyre 2x2, value over unit, hugging the ADAS car ----
            float ty0 = ST + 274, ty1 = ST + 350;
            float tx0 = 1409f, tx1 = 1664f;      // 左 +8，右 -2
            drawTyre2(cv, tx0 + 18f, ty0, tyreStr(0), Paint.Align.RIGHT);
            drawTyre2(cv, tx1 - 18f, ty0, tyreStr(1), Paint.Align.LEFT);
            drawTyre2(cv, tx0, ty1, tyreStr(2), Paint.Align.RIGHT);
            drawTyre2(cv, tx1, ty1, tyreStr(3), Paint.Align.LEFT);
        }

        /**
         * 油耗一行：本次电耗：11.0 kW·h，本次油耗 2.3 L，综合油耗 5.5 L/100km
         * 标签用 cap 样式、数值用 num、单位用 unit，整行按实测宽度水平居中。
         */
        private void drawFuelLine(Canvas cv) {
            // 只显示综合油耗
            String[] lab = {"综合油耗: "};
            String[] val = {totalPHMStr()};
            String[] unit = {"L/100km"};
            String[] sign = {""};
            // 全部按面板设置直出：标题/数值/单位 三个元素的字号、颜色、透明度、描边各管各的
            float lSize = Style.size("cap");
            float vSize = Style.size("num");
            float uSize = Style.size("unit");

            // 间距：标签后 8px、数字与单位间 4px、组与组之间 14px
            float gapL = 3f, gapU = 2f, gapG = 6f;
            float total = 0f;
            for (int i = 0; i < lab.length; i++) {
                txt.setTypeface(med);  txt.setTextSize(lSize); total += txt.measureText(lab[i]);
                txt.setTypeface(bold); txt.setTextSize(vSize); total += txt.measureText(val[i]);
                txt.setTypeface(med);  txt.setTextSize(uSize); total += txt.measureText(unit[i]);
                txt.setTextSize(vSize); total += txt.measureText(sign[i]);
                total += gapL + gapU;
            }
            float x = FUEL_LINE_CX - total / 2f;
            for (int i = 0; i < lab.length; i++) {
                styleText(cv, lab[i], x, FUEL_LINE_Y, lSize, el("cap"), Paint.Align.LEFT, tfFor("cap"), "cap");
                txt.setTypeface(med); txt.setTextSize(lSize); x += txt.measureText(lab[i]) + gapL;
                styleText(cv, val[i], x, FUEL_LINE_Y, vSize, el("num"), Paint.Align.LEFT, tfFor("num"), "num");
                txt.setTypeface(bold); txt.setTextSize(vSize); x += txt.measureText(val[i]) + gapU;
                styleText(cv, unit[i], x, FUEL_LINE_Y, uSize, el("unit"), Paint.Align.LEFT, tfFor("unit"), "unit");
                txt.setTypeface(med); txt.setTextSize(uSize); x += txt.measureText(unit[i]);
                // + / = 用数值字号但常规字重（不加粗）
                txt.setTypeface(med); txt.setTextSize(vSize);
                styleText(cv, sign[i], x, FUEL_LINE_Y, vSize, el("unit"), Paint.Align.LEFT, med, "unit");
                x += txt.measureText(sign[i]);
            }
        }

        private void drawTyre2(Canvas cv, float x, float y, String v, Paint.Align al) {
            float num = Style.size("tnum");
            float unit = Style.size("tunit");
            outlined(cv, v, x, y, num, el("tnum"), al, "tnum", Math.max(3f, num * 0.12f));
            // kPa 紧贴数字下沿（原来是 unit*1.9，太远）
            outlined(cv, "kPa", x, y + unit * 1.15f, unit, el("tunit"), al,
                    "tunit", Math.max(2.5f, unit * 0.20f));
        }

        /** 描边也吃该元素的透明度，避免"文字半透、描边死黑"。 */
        /** 描边颜色：固定不透明，不再乘文字的 alpha（否则调透明度会看着像调描边）。 */
        private int strokePaint(String el) {
            return Style.strokeColor(el, nightMode);
        }

        /** 白色（高亮度）字不再描边：描边是为了压花底，白字本身已经够跳。 */
        private boolean noStrokeNeeded(int fill) {
            int r = (fill >> 16) & 0xFF, g = (fill >> 8) & 0xFF, b = fill & 0xFF;
            int a = (fill >>> 24) & 0xFF;
            if (a < 40) return true;
            return (r * 299 + g * 587 + b * 114) / 1000 > 220;
        }
        /** 按元素设置绘制：描边开关/粗细/颜色 + 透明度 + 字重都由面板决定。 */
        private void styleText(Canvas cv, String s, float x, float y, float size, int color,
                               Paint.Align al, Typeface tf, String el) {
            txt.setTypeface(tf);
            txt.setFakeBoldText(fakeBoldFor(el));
            txt.setTextSize(size);
            txt.setTextAlign(al);
            if (Style.strokeOn(el)) {
                // 外部描边：Android 的 STROKE 是"居中"的（一半在内一半在外），
                // 想只往外扩，就把线宽设为 2 倍目标值，外侧自然就是目标宽度，
                // 内侧那一半随后被填充色盖住。
                txt.setStyle(Paint.Style.STROKE);
                float sw = Math.max(1.5f, Style.strokeW(el, size * 0.11f));
                txt.setStrokeWidth(Math.min(sw, size * 0.30f) * 2f);
                txt.setColor(Style.strokeColor(el, nightMode));
                cv.drawText(s, x, y, txt);
            }
            txt.setStyle(Paint.Style.FILL);
            txt.setColor(color);
            float eb = extraBoldFor(el);
            if (eb > 0f) {
                // 超粗：四方向偏移叠画，笔画明显加厚
                cv.drawText(s, x - eb, y, txt);
                cv.drawText(s, x + eb, y, txt);
                cv.drawText(s, x, y - eb, txt);
                cv.drawText(s, x, y + eb, txt);
            }
            cv.drawText(s, x, y, txt);
        }
        /** 不描边的纯填充绘制，字重按传入的 Typeface 走（不强制加粗）。 */
        private void plain(Canvas cv, String s, float x, float y, float size, int color,
                           Paint.Align al, android.graphics.Typeface tf) {
            txt.setTypeface(tf);
            txt.setTextSize(size);
            txt.setTextAlign(al);
            txt.setStyle(Paint.Style.FILL);
            txt.setColor(color);
            cv.drawText(s, x, y, txt);
        }

        private void outlined(Canvas cv, String s, float x, float y, float size, int fillColor,
                              Paint.Align al, String el, float autoStroke) {
            txt.setTypeface(tfFor(el));
            txt.setFakeBoldText(fakeBoldFor(el));   // 中文靠伪粗区分粗细
            txt.setTextSize(size);
            txt.setTextAlign(al);
            if (Style.strokeOn(el)) {
                float w = Style.strokeW(el, autoStroke);
                if (w > 0f) {
                    txt.setStyle(Paint.Style.STROKE);
                    txt.setStrokeWidth(w);
                    txt.setStrokeJoin(Paint.Join.ROUND);
                    txt.setColor(strokePaint(el));
                    cv.drawText(s, x, y, txt);
                }
            }
            txt.setStyle(Paint.Style.FILL);
            txt.setColor(fillColor);
            float eb = extraBoldFor(el);
            if (eb > 0f) {
                cv.drawText(s, x - eb, y, txt);
                cv.drawText(s, x + eb, y, txt);
                cv.drawText(s, x, y - eb, txt);
                cv.drawText(s, x, y + eb, txt);
            }
            cv.drawText(s, x, y, txt);
        }


        private long tuningLead = 80L;
        private long tuningStamp = 0L;
        private long readTuningLead() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - tuningStamp < 2000) return tuningLead;
            tuningStamp = now;
            try {
                java.io.File f = new java.io.File("/sdcard/clusterlab_lyric_offset.txt");
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                    String line = br.readLine();
                    br.close();
                    if (line != null) {
                        long v = Long.parseLong(line.trim());
                        if (v > -2000 && v < 2000) tuningLead = v;
                    }
                }
            } catch (Throwable ignored) {}
            return tuningLead;
        }

        private void drawLyrics(Canvas cv) {
            float l1 = SL;
            long nowPos = MediaListener.playing
                    ? MediaListener.basePosition + (long) ((android.os.SystemClock.elapsedRealtime() - MediaListener.baseTime) * MediaListener.playSpeed)
                    : MediaListener.basePosition;
            if (MediaListener.title.length() > 0) {
                LyricLoader.load(MediaListener.title, MediaListener.artist, MediaListener.duration);
            }
            long autoLead = 0L;
            try {
                long ld = LyricLoader.lrcDuration;
                long md = MediaListener.duration;
                if (ld > 60000 && md > 60000) {
                    autoLead = 0; // duration 上报精度不足，停用              // 前奏差的一半
                    if (autoLead > 1500) autoLead = 1500;
                    if (autoLead < -1500) autoLead = -1500;
                }
            } catch (Throwable ignored) {}
            if (lastLoggedLd != LyricLoader.lrcDuration) {
                lastLoggedLd = LyricLoader.lrcDuration;
                Dbg.e("CLUSTERLAB-SYNC", "mediaDur=" + MediaListener.duration + " lrcDur=" + LyricLoader.lrcDuration + " auto=" + autoLead);
            }
            long fileLead = readTuningLead();
            long effPos = nowPos + LYRIC_LEAD_MS + fileLead;
            int idx = LyricLoader.indexAt(effPos);
            drawIdx = Math.max(idx, 0);    // aboveLines() 要靠它算"当前行在第几行"
            java.util.List<String> ls = LyricLoader.lines;
            float lx = lyricX();
            if (!lyricLogged || System.currentTimeMillis() - lyricLogAt > 5000) {
                lyricLogged = true; lyricLogAt = System.currentTimeMillis();
                Dbg.e("CLUSTERLAB-LYRIC", "lines=" + ls.size()
                        + " idx=" + idx + " pos=" + MediaListener.position
                        + " dur=" + MediaListener.duration);
            }
            if (ls.size() > 0) {           // 前奏阶段(idx<0)也先把歌词排出来，只是不高亮
                float curS = Style.size("lcur");
                float prevS = Style.size("lprev");
                float farS = Style.size("lfar");
                float lineH = lineH();      // 统一用成员方法（大窗 +18 / 小窗 +12）

                // 行数与"末尾钳制"先行算好，后面 progress / baseY / 循环都要用
                int maxK = bigMusic() ? (BIG_LYRIC_ROWS - aboveLines() - 1) : 3;
                int minK = -aboveLines();
                final int lastIdx0 = ls.size() - 1;
                final int maxBase0 = lastIdx0 - maxK;
                int locIdx = Math.max(idx, 0);   // 定位用：前奏阶段从第 0 句起排
                int baseIdx = locIdx;
                if (maxBase0 > 0 && locIdx > maxBase0) baseIdx = maxBase0;
                final boolean atTail = baseIdx != locIdx;  // 已贴末尾，停止滚动

                java.util.List<String> lsz = LyricLoader.lines;
                float baseY = lyricBaseY(availAbove(baseIdx, lsz.size()));

                // 当前行也强制单行：行高必须与其余 5 行完全一致，
                // 换行会把下面几行整体推下去，且末尾那行不会跟着走，看起来就是"空一块"。
                float curExtra = 0f;
                // progress 用"固定时长的滚动窗口"，不能用整行时长：
                // 长句（比如 30 秒）用整行时长做分母，一分钟才滚一行 —— 每帧位移 0.01px，
                // 量化到屏幕上就是"一卡一卡往上走"。改成换行前 ROLL_MS 内匀速滚完。
                final long ROLL_MS = 620L;
                float progress = 0f;
                java.util.List<Long> ts = LyricLoader.times;
                // 贴到末尾就不滚动，6 行固定住
                if (!atTail && idx >= 0 && idx + 1 < ts.size()) {
                    long t0 = ts.get(idx), t1 = ts.get(idx + 1);
                    if (t1 > t0) {
                        long remain = t1 - effPos;
                        if (remain < ROLL_MS) {
                            progress = 1f - Math.max(0L, remain) / (float) ROLL_MS;
                            if (progress < 0f) progress = 0f;
                            if (progress > 1f) progress = 1f;
                            // smoothstep 缓动：起步和收尾都放慢，中段快，
                            // 比线性滚动自然得多（线性像"啪"地平移一格）
                            progress = progress * progress * (3f - 2f * progress);
                        }
                    }
                }
                // 只在换行瞬间做 200ms 过渡，其余时间字号颜色固定
                long t0now = (idx >= 0 && idx < ts.size()) ? ts.get(idx) : 0;
                float since = (effPos - t0now) / 200f;
                if (since < 0f) since = 0f;
                if (since > 1f) since = 1f;
                // 有滚动或淡入进行中 → 下一帧提到 32ms
                lyricAnim = (progress > 0.001f && progress < 0.999f) || since < 1f;

                // 大窗位置空，多画几行；小窗贴着导航卡片，保持紧凑
                // 当前行放第二行，第一行是正在淡出的上一行
                // 大窗 5 行(-1..3)，小窗 4 行(-1..3)：小窗底部多显示一行
                // 大窗 6 行（k = -1..4），小窗 5 行（k = -1..3）
                float slotShift = 0f;

                // 实际存在的第一行/最后一行（歌曲开头/结尾时 k 可能越界）
                int firstK = minK, lastK = maxK;
                for (int k = minK; k <= maxK; k++) {
                    int ii = baseIdx + k;
                    if (ii >= 0 && ii < ls.size()) { firstK = k; break; }
                }
                for (int k = maxK; k >= minK; k--) {
                    int ii = baseIdx + k;
                    if (ii >= 0 && ii < ls.size()) { lastK = k; break; }
                }
                // ===== 先排版：长行自动换行，按实际高度累加，放不下的行直接不画 =====
                final float BOT = SB - 6;
                final float TOP = bigMusic() ? (BIG_LYRIC_TOP - 6f) : (artY() - 12f);
                final float maxW = bigMusic() ? LYRIC_MAX_W : ((SL + 556) - lx);

                // 偏移 8：支持 k = -8..7
                final int OFF = 8, SPAN = 16;
                float[] yOf = new float[SPAN];
                float[] szOf = new float[SPAN];
                String[] elOf = new String[SPAN];
                int[] colOf = new int[SPAN];
                android.text.StaticLayout[] layOf = new android.text.StaticLayout[SPAN];
                boolean[] hasOf = new boolean[SPAN];

                // 1) 为每个 k 建 layout（自动换行，不截断）
                for (int k = minK - 2; k <= maxK + 2; k++) {
                    int i = baseIdx + k;
                    if (i < 0 || i >= ls.size()) continue;
                    int si = k + OFF;
                    if (si < 0 || si >= SPAN) continue;
                    int rel = i - idx;
                    float size = (rel == 0) ? curS : (rel == -1 ? prevS : farS);
                    String el = (rel == 0) ? "lcur" : (rel < 0 ? "lprev" : "lfar");
                    Typeface tf = tfFor(el);
                    txt.setTypeface(tf);
                    txt.setFakeBoldText(fakeBoldFor(el));
                    txt.setTextSize(size);
                    txt.setTextAlign(Paint.Align.LEFT);
                    String text = ls.get(i);
                    int slot = k + 4;
                    android.text.StaticLayout sl = null;
                    if (slot >= 0 && slot < slCache.length) {
                        sl = slCache[slot];
                        // 只比较"文本引用 + 字号 + 宽度"，不再拼字符串：
                        // 每帧拼十几个字符串会疯狂触发 GC（实测 HeapTaskDaemon 吃掉 10 秒 CPU）
                        boolean stale = sl == null
                                || slText[slot] != text
                                || slWidth[slot] != (int) maxW
                                || Math.abs(slSize[slot] - size) > 0.5f;
                        if (stale) {
                            sl = android.text.StaticLayout.Builder
                                    .obtain(text, 0, text.length(), txt, Math.max(24, (int) maxW))
                                    .setMaxLines(3)
                                    .setIncludePad(false)
                                    .setLineSpacing(2f, 1.05f)    // 换行句内部行距 ≈34，小于句间 44
                                    .build();
                            slCache[slot] = sl;
                            slText[slot] = text;
                            slWidth[slot] = (int) maxW;
                            slSize[slot] = size;
                        }
                    }
                    if (sl == null) continue;
                    layOf[si] = sl;
                    szOf[si] = size;
                    elOf[si] = el;
                    colOf[si] = el(el);
                    hasOf[si] = true;
                }

                // 2) 以当前行为锚点，向下再向上累加高度
                int si0 = OFF;                       // k = 0
                if (!hasOf[si0]) {
                    // 当前行不存在（极少见），退化为按 k 顺序画
                } else {
                    // 句间行距(lineH) > 换行句内部行距(INNER)，排版才好看
                    final float INNER = bigMusic() ? 34f : 30f;
                    final float GAPV  = bigMusic() ? 18f : 8f;

                    float curBox = szOf[si0]
                            + (layOf[si0].getLineCount() - 1) * INNER + GAPV;
                    float curTop = baseY + slotShift - szOf[si0] - progress * lineH;
                    yOf[si0] = curTop + szOf[si0];
                    float bottom = curTop + curBox;               // 下一句的盒顶

                    for (int k = 1; k <= maxK + 2; k++) {
                        int si = k + OFF;
                        if (si >= SPAN || !hasOf[si]) break;
                        float box = szOf[si]
                                + (layOf[si].getLineCount() - 1) * INNER + GAPV;
                        if (bottom + box > BOT + lineH * 0.5f) break;   // 放不下就不画
                        yOf[si] = bottom + szOf[si];
                        bottom += box;
                    }
                    float top = curTop;                           // 上一句的盒底
                    for (int k = -1; k >= minK - 2; k--) {
                        int si = k + OFF;
                        if (si < 0 || !hasOf[si]) break;
                        float box = szOf[si]
                                + (layOf[si].getLineCount() - 1) * INNER + GAPV;
                        if (top - box < TOP) break;
                        top -= box;
                        yOf[si] = top + szOf[si];
                    }
                }

                // 3) 绘制
                for (int k = minK - 2; k <= maxK + 2; k++) {
                    int si = k + OFF;
                    if (si < 0 || si >= SPAN || !hasOf[si]) continue;
                    android.text.StaticLayout sl = layOf[si];
                    float size = szOf[si];
                    String elLyric = elOf[si];
                    int color = colOf[si];
                    float y = yOf[si];
                    if (y == 0f) continue;                      // 没排到位置 = 放不下
                    final float INNER2 = bigMusic() ? 34f : 30f;
                    final float GAPV2  = bigMusic() ? 18f : 8f;
                    float box = size + (sl.getLineCount() - 1) * INNER2 + GAPV2;
                    float glyphTop = y - size;
                    if (glyphTop < TOP - 2f || y - size + box > BOT + 4f) continue;

                    Typeface tf = tfFor(elLyric);
                    txt.setTypeface(tf);
                    txt.setFakeBoldText(fakeBoldFor(elLyric));
                    txt.setTextSize(size);

                    cv.save();
                    cv.translate(lx, y - size);
                    if (Style.strokeOn(elLyric)) {
                        float sw = Style.strokeW(elLyric, size * 0.10f) * (size / Math.max(1f, curS));
                        if (sw > 0f) {
                            txt.setStyle(Paint.Style.STROKE);
                            txt.setStrokeWidth(sw);
                            txt.setStrokeJoin(Paint.Join.ROUND);
                            txt.setColor(strokePaint(elLyric));
                            sl.draw(cv);
                        }
                    }
                    txt.setStyle(Paint.Style.FILL);
                    txt.setColor(color);
                    float eb = extraBoldFor(elLyric);
                    if (eb > 0f) {
                        cv.save(); cv.translate(-eb, 0f); sl.draw(cv); cv.restore();
                        cv.save(); cv.translate(eb, 0f); sl.draw(cv); cv.restore();
                        cv.save(); cv.translate(0f, -eb); sl.draw(cv); cv.restore();
                        cv.save(); cv.translate(0f, eb); sl.draw(cv); cv.restore();
                    }
                    sl.draw(cv);
                    cv.restore();
                }
            } else {
                // 没有歌词时给一句明确的状态，免得在车上看不出是"没歌"还是"监听没绑上"
                String hint;
                int hintColor = FG_DIM2;
                if (MediaListener.album.length() > 0) {
                    hint = MediaListener.album;
                } else if (!MediaListener.connected) {
                    hint = "媒体未连接";
                    hintColor = nightMode ? 0xFF7A6A58 : 0xFF8A7358;
                } else {
                    hint = MediaListener.title.length() > 0 ? "\u2014\u2014" : "等待播放";
                }
                T(cv, hint, lx, lyricBaseY(aboveLines()) - 4f, 21f, hintColor, med, Paint.Align.LEFT);
            }
            // 进度条已移除
        }
        private final Paint progPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        /* ---------- 转动的唱片 ---------- */
        private Bitmap discBmp;
        private Bitmap discSrc;
        private float discPx;
        /** 唱片当前所在矩形：只刷这一块就能让旋转平滑，不用重画整条歌词带。 */
        final RectF discRect = new RectF();

        private void drawDisc(Canvas cv) {
            float size = artSize();
            Bitmap src = MediaListener.art;
            if (discBmp == null || discSrc != src || discPx != size) {
                discSrc = src;
                discPx = size;
                int s = Math.max(16, (int) size);
                discBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(discBmp);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                p.setColor(0xFF101A2B);
                c.drawCircle(s / 2f, s / 2f, s / 2f, p);
                if (src != null) {
                    // 用 BitmapShader 画圆：比 clipPath 稳，不会有方角残留
                    BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    Matrix m = new Matrix();
                    float k = Math.max(s / (float) src.getWidth(), s / (float) src.getHeight());
                    m.setScale(k, k);
                    m.postTranslate((s - src.getWidth() * k) / 2f, (s - src.getHeight() * k) / 2f);
                    sh.setLocalMatrix(m);
                    Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    pp.setShader(sh);
                    c.drawCircle(s / 2f, s / 2f, s / 2f, pp);
                } else {
                    txt.setTypeface(light);
                    txt.setTextSize(s * 0.45f);
                    txt.setColor(0x555C7FA8);
                    txt.setTextAlign(Paint.Align.CENTER);
                    txt.setStyle(Paint.Style.FILL);
                    c.drawText("\u266a", s / 2f, s * 0.68f, txt);
                }
                // 中心孔 + 内圈
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(0xFF0A1220);
                c.drawCircle(s / 2f, s / 2f, s * 0.085f, hole);
                hole.setStyle(Paint.Style.STROKE);
                hole.setStrokeWidth(1.5f);
                hole.setColor(0x55FFFFFF);
                c.drawCircle(s / 2f, s / 2f, s * 0.085f, hole);
            }

            float cx = artX() + size / 2f, cy = artY() + size / 2f;
            discRect.set(cx - size / 2f - 2f, cy - size / 2f - 2f, cx + size / 2f + 2f, cy + size / 2f + 2f);
            float deg = (android.os.SystemClock.elapsedRealtime() % 20000L) / 20000f * 360f;
            cv.save();
            cv.rotate(deg, cx, cy);
            bmp.setFilterBitmap(false);   // 旋转时关掉双线性：视觉差别几乎看不出来，每帧省一半光栅化成本
            bmp.setAlpha(255);
            cv.drawBitmap(discBmp, null, new RectF(cx - size / 2f, cy - size / 2f,
                    cx + size / 2f, cy + size / 2f), bmp);
            cv.restore();
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x337FB4FF);
            line.setStrokeWidth(1.2f);
            cv.drawCircle(cx, cy, size / 2f, line);
        }

        private Bitmap carCache;
        private Bitmap getCar() {
            if (carCache != null) return carCache;
            try {
                java.io.InputStream in = getContext().getAssets().open("car_top.png");
                carCache = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
            } catch (Throwable ignored) {}
            return carCache;
        }

        private int mix(int c1, int c2, float t) {
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
            int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
            int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
            int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private void drawTyre(Canvas cv, float x, float y, String v, String temp, Paint.Align al) {
            // always left-aligned from x: "255 kPa"
            T(cv, v, x, y, 26f, FG, light, Paint.Align.LEFT);
            T(cv, "kPa", x + 50f, y - 2f, 12f, FG_DIM2, light, Paint.Align.LEFT);
        }

        private String tyreStr(int i) {
            int v = VehicleData.pressure[i];
            if (v <= 0) return "--";
            return String.valueOf(v);
        }

        private String tempStr(int i) { return ""; }

        private String fmt(double v) {
            return v > 0 ? String.format(java.util.Locale.US, "%.1f", v) : "--";
        }

        private String elecPHMStr() { return fmt(VehicleData.elecPHM); }

        private String fuelPHMStr() { return fmt(VehicleData.fuelPHM); }

        private String totalPHMStr() { return fmt(VehicleData.totalPHM); }
    }
}
