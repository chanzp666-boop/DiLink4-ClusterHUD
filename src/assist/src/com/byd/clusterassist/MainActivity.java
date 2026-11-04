package com.byd.clusterassist;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 4096;

    // 调色板从 Ui 取，onCreate / 系统主题变化时刷新
    private int BG, CARD, CARD2, TXT, DIM, OK, BAD, WARN, SEL, PRIMARY, STYLE;
    private boolean builtNight = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView stCard, stMedia, stShizuku, stDiplus, stNote, logView, autoBtn, logBtn;
    private ScrollView logScroll;
    private volatile Boolean diplusOnline = null;
    private int tickCount = 0;
    /** 车机横屏时整体压缩 12%，一屏能看到更多控件。 */
    private float compact = 1f;

    /** 取当前系统深浅色，并把窗口底色/状态栏一起刷成对应配色。 */
    private void loadPalette() {
        Ui.refresh(this);
        builtNight = Ui.night;
        BG = Ui.bg(); CARD = Ui.card(); CARD2 = Ui.card2();
        TXT = Ui.txt(); DIM = Ui.dim();
        OK = Ui.ok(); BAD = Ui.bad(); WARN = Ui.warn();
        SEL = Ui.sel(); PRIMARY = Ui.primary(); STYLE = Ui.styleBtn();
        try {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        } catch (Throwable ignored) {}
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadPalette();
        buildUi();
        Journal.setSink(new Journal.Sink() {
            @Override public void onLine(final String line) {
                ui.post(new Runnable() {
                    @Override public void run() { appendLog(line); }
                });
            }
        });
        logView.setText(Journal.dump());
        ensureAdb();
        startWatchdog();
        worker.execute(new Runnable() {
            @Override public void run() {
                Journal.add("自检: " + Shell.diagnose());
            }
        });
        ui.post(refresh);
    }

    @Override protected void onResume() {
        super.onResume();
        ensureAdb();
    }

    @Override protected void onDestroy() {
        Journal.setSink(null);
        ui.removeCallbacks(refresh);
        super.onDestroy();
    }

    // ---------------- ADB（自研客户端，不再依赖 Shizuku） ----------------

    private void ensureAdb() {
        try {
            Shell.init(this);
            if (!Shell.ready()) Journal.add("ADB 连接中…");
        } catch (Throwable t) {
            Journal.add("ADB 初始化失败: " + Navi.shortMsg(t));
        }
    }

    private void startWatchdog() {
        try {
            startForegroundService(new Intent(this, Watchdog.class));
        } catch (Throwable t) {
            Journal.add("watchdog start: " + Navi.shortMsg(t));
        }
    }

    // ---------------- UI 基件 ----------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * compact);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private void header(LinearLayout parent, String s) {
        TextView t = text(s, 12f, DIM, true);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp((wide() ? 10 : 16) * compact), 0, dp(6 * compact));
        parent.addView(t);
    }

    private LinearLayout cardBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, 16));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private LinearLayout rowBox() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    /** 横屏（车机）3 个一行，竖屏（手机）2 个一行。 */
    private int perRow() {
        return getResources().getDisplayMetrics().widthPixels
                > getResources().getDisplayMetrics().heightPixels ? 3 : 2;
    }

    private void grid(LinearLayout parent, TextView... cells) {
        int per = perRow();
        LinearLayout row = null;
        for (int i = 0; i < cells.length; i++) {
            if (i % per == 0) {
                row = rowBox();
                parent.addView(row);
            }
            row.addView(cells[i]);
        }
        int rest = cells.length % per;
        if (row != null && rest != 0) {
            for (int i = rest; i < per; i++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, 1, 1f);
                lp.rightMargin = dp(8);
                spacer.setLayoutParams(lp);
                row.addView(spacer);
            }
        }
    }

    /** 按钮文字按底色亮度自动取黑/白，深浅两套都能看清。 */
    private int fgFor(int bg) {
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum > 150 ? 0xFF0F1622 : 0xFFFFFFFF;
    }

    private TextView action(String label, final int bg, int fg, final Runnable r) {
        final TextView t = text(label, 14f, fgFor(bg), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, 12));
        t.setPadding(dp(6), dp(12 * compact), dp(6), dp(12 * compact));
        t.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                t.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                t.animate().scaleX(1f).scaleY(1f).setDuration(90);
                            }
                        });
                worker.execute(new Runnable() {
                    @Override public void run() {
                        try { r.run(); } catch (Throwable e) {
                            Journal.add("[" + label + "] 异常: " + Navi.shortMsg(e));
                        }
                        ui.post(refresh);
                    }
                });
            }
        });
        return t;
    }

    private TextView statusRow(String name) {
        TextView t = text("● " + name, 13.5f, DIM, false);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(3 * compact), 0, dp(3 * compact));
        return t;
    }

    private void setStatus(TextView t, String name, Boolean ok, String extra) {
        int color = ok == null ? DIM : (ok.booleanValue() ? OK : BAD);
        t.setText("● " + name + (extra == null ? "" : "   " + extra));
        t.setTextColor(color);
    }

    /** 车机横屏：左边控制区可滚，右边日志常驻；竖屏：单列滚动。 */
    private void buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(BG);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        boolean wide = w > h;
        compact = wide ? 0.9f : 1f;

        if (wide) {
            LinearLayout hRow = new LinearLayout(this);
            hRow.setOrientation(LinearLayout.HORIZONTAL);
            hRow.setPadding(dp(16), dp(12), dp(10), dp(10));
            shell.addView(hRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ScrollView leftScroll = new ScrollView(this);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setPadding(0, 0, dp(10), dp(10));
            leftScroll.addView(left, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hRow.addView(leftScroll, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.18f));

            ScrollView rightScroll = new ScrollView(this);
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setPadding(dp(4), 0, 0, dp(6));
            rightScroll.addView(right, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hRow.addView(rightScroll, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            titles(left);
            sections(left);
            statusCard(right);
            logPane(right, false);
        } else {
            ScrollView scroll = new ScrollView(this);
            shell.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(18), dp(20), dp(10), dp(26));
            float wDp = w / getResources().getDisplayMetrics().density;
            int colW = (int) Math.min(dp(560), dp(Math.max(280f, wDp - 36f)));
            scroll.addView(root, new FrameLayout.LayoutParams(
                    colW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            titles(root);
            statusCard(root);
            sections(root);
            logPane(root, false);
        }

        setContentView(shell);
    }

    private boolean wide() {
        return getResources().getDisplayMetrics().widthPixels
                > getResources().getDisplayMetrics().heightPixels;
    }

    private void titles(LinearLayout root) {
        root.addView(text("仪表助理", wide() ? 20f : 26f, TXT, true));
        TextView sub = text("ClusterAssist " + Ver.TAG + " · 卡片控制 / 自愈 / ADB", 11f, DIM, false);
        sub.setPadding(0, dp(3), 0, dp(4));
        root.addView(sub);

    }

    private void statusCard(LinearLayout parent) {
        header(parent, "状 态");
        LinearLayout st = cardBox();
        stCard = statusRow("仪表卡片 display 1");
        stMedia = statusRow("媒体通知监听");
        stShizuku = statusRow("Shizuku shell");
        stDiplus = statusRow("迪加数据 API");
        stNote = text("—", 11.5f, DIM, false);
        stNote.setIncludeFontPadding(false);
        stNote.setPadding(0, dp(7 * compact), 0, 0);
        if (wide()) {
            LinearLayout r1 = new LinearLayout(this);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            addWeighted(r1, stCard);
            addWeighted(r1, stMedia);
            LinearLayout r2 = new LinearLayout(this);
            r2.setOrientation(LinearLayout.HORIZONTAL);
            addWeighted(r2, stShizuku);
            addWeighted(r2, stDiplus);
            st.addView(r1);
            st.addView(r2);
        } else {
            st.addView(stCard);
            st.addView(stMedia);
            st.addView(stShizuku);
            st.addView(stDiplus);
        }
        st.addView(stNote);
        parent.addView(st);
    }

    private void addWeighted(LinearLayout row, View v) {
        row.addView(v, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void sections(LinearLayout root) {
        header(root, "仪 表 卡 片");
        grid(root,
                action("启动卡片", PRIMARY, TXT, new Runnable() {
                    @Override public void run() {
                        Journal.add("启动卡片");
                        Journal.add("→ " + Card.start().trim());
                        Card.sleep(1100);
                        Journal.add("存活: " + Card.alive());
                        Card.rebindMedia();
                    }
                }),
                action("重启卡片", PRIMARY, TXT, new Runnable() {
                    @Override public void run() {
                        Journal.add("重启卡片");
                        Card.stop();
                        Card.sleep(700);
                        Journal.add("→ " + Card.start().trim());
                        Card.sleep(1200);
                        Journal.add("存活: " + Card.alive());
                        Card.rebindMedia();
                    }
                }),
                action("停止卡片", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Journal.add("停止卡片: " + Card.stop());
                    }
                }),
                action("重绑媒体", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Card.rebindMedia();
                        Card.sleep(600);
                        Journal.add("媒体监听已绑定: " + Card.mediaBound());
                    }
                }),
                autoBtn = action("自愈：…", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        SharedPreferences sp = getSharedPreferences(Watchdog.PREF, MODE_PRIVATE);
                        boolean now = !sp.getBoolean(Watchdog.KEY_AUTO, true);
                        sp.edit().putBoolean(Watchdog.KEY_AUTO, now).apply();
                        Journal.add("自愈 = " + now);
                    }
                }),
                action("修复存储权限", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Card.grantStorage();
                        Journal.add("已给 overlay 授予存储权限");
                    }
                }),
                logBtn = action("日志：关", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        boolean now = !Dbg.on();
                        Dbg.set(now);
                        logBtn.setText(now ? "日志：开" : "日志：关");
                        if (now) Journal.add("调试日志已开启");
                    }
                }),
                action("ADB 自检", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Journal.add("自检: " + Shell.diagnose());
                    }
                }),
                action("诊断媒体", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Journal.add("媒体: " + Card.mediaDiagnose());
                    }
                }));

        header(root, "主 题 / 歌 词");
        grid(root,
                action("浅色模式", CARD2, TXT, new Runnable() {
                    @Override public void run() { writeFile("clusterlab_theme.txt", "light"); }
                }),
                action("深色模式", CARD2, TXT, new Runnable() {
                    @Override public void run() { writeFile("clusterlab_theme.txt", "night"); }
                }),
                action("跟随系统", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Shell.exec("rm -f /sdcard/clusterlab_theme.txt");
                        Journal.add("主题跟随系统");
                    }
                }),
                action("偏移 -50ms", CARD2, TXT, new Runnable() {
                    @Override public void run() { nudgeOffset(-50); }
                }),
                action("偏移 +50ms", CARD2, TXT, new Runnable() {
                    @Override public void run() { nudgeOffset(50); }
                }),
                action("读偏移", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        String v = Shell.exec("cat /sdcard/clusterlab_lyric_offset.txt 2>/dev/null");
                        Journal.add("当前歌词偏移: "
                                + (v == null || v.trim().length() == 0 ? "默认 80ms" : v.trim()));
                    }
                }),
                action("元素样式", STYLE, TXT, new Runnable() {
                    @Override public void run() {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                new StyleEditor(MainActivity.this).show();
                            }
                        });
                    }
                }),
                action("恢复默认样式", CARD2, WARN, new Runnable() {
                    @Override public void run() {
                        Style.reset();
                        Journal.add("元素样式已恢复默认（/sdcard/clusterlab_style.txt 已删除）");
                    }
                }));

    }

    private void logPane(LinearLayout parent, boolean fillHeight) {
        if (!fillHeight) header(parent, "日 志");
        LinearLayout logCard = cardBox();
        if (fillHeight) {
            TextView lab = text("日 志", 11f, DIM, true);
            lab.setIncludeFontPadding(false);
            lab.setPadding(0, 0, 0, dp(6));
            logCard.addView(lab);
        }
        logView = text("", 11f, 0xFFB8C7DA, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams slp;
        if (fillHeight) {
            slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            clp.bottomMargin = dp(10);
            logCard.setLayoutParams(clp);
        } else {
            int logH = (int) Math.min(dp(wide() ? 150 : 240),
                    getResources().getDisplayMetrics().heightPixels * 0.3f);
            slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, logH);
        }
        logScroll.setLayoutParams(slp);
        logCard.addView(logScroll);
        parent.addView(logCard);

        grid(parent,
                action("清空日志", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Journal.clear();
                        ui.post(new Runnable() { @Override public void run() { logView.setText(""); } });
                    }
                }),
                action("清 adb 白名单", CARD2, TXT, new Runnable() {
                    @Override public void run() {
                        Shell.exec("cmd deviceidle whitelist +com.byd.clusterlab");
                        Shell.exec("cmd deviceidle whitelist +com.byd.clusterassist");
                        Journal.add("已加入 doze 白名单");
                    }
                }));
    }

    private void appendLog(String line) {
        logView.append(line + "\n");
        CharSequence cs = logView.getText();
        if (cs.length() > 12000) {
            logView.setText(cs.subSequence(cs.length() - 10000, cs.length()));
        }
        logScroll.post(new Runnable() {
            @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void writeFile(String name, String content) {
        String r = Shell.exec("echo " + content + " > /sdcard/" + name);
        Journal.add("写入 " + name + " = " + content + (r == null ? "（失败）" : ""));
    }

    private void nudgeOffset(int delta) {
        String cur = Shell.exec("cat /sdcard/clusterlab_lyric_offset.txt 2>/dev/null");
        long base = 80;
        try {
            if (cur != null && cur.trim().length() > 0) base = Long.parseLong(cur.trim());
        } catch (Throwable ignored) {}
        long next = base + delta;
        if (next > 900) next = 900;
        if (next < -900) next = -900;
        writeFile("clusterlab_lyric_offset.txt", String.valueOf(next));
        Journal.add("歌词偏移 " + base + " → " + next + " ms");
    }

    // ---------------- 定时刷新 ----------------

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            Ui.refresh(MainActivity.this);
            if (Ui.night != builtNight) {
                // 系统切了深浅色：整个界面按新配色重建（守护服务不受影响）
                recreate();
                return;
            }
            boolean sh = Watchdog.shell;
            boolean running = Watchdog.running;
            setStatus(stShizuku, "ADB shell",
                    running ? Boolean.valueOf(sh) : null,
                    sh ? "shell 可用" : "未就绪");
            setStatus(stCard, "仪表卡片 display 1",
                    (running && sh) ? Boolean.valueOf(Watchdog.cardOn) : null,
                    !running ? "守护未运行" : (!sh ? "无法判定" : (Watchdog.cardOn ? "运行中" : "不在 display 1")));
            setStatus(stMedia, "媒体通知监听",
                    (running && sh) ? Boolean.valueOf(Watchdog.mediaOn) : null,
                    !sh ? "无法判定" : (Watchdog.mediaOn ? "已绑定" : "未绑定"));
            setStatus(stDiplus, "迪加数据 API", diplusOnline,
                    diplusOnline == null ? "探测中" : (diplusOnline.booleanValue() ? "8988 可用" : "无响应"));
            stNote.setText("守护：" + (running ? Watchdog.note : "未运行")
                    + "　·　自愈 " + Watchdog.healCount + " 次"
                    + (Watchdog.lastHealAt > 0 ? ("　·　最近 " + ago(Watchdog.lastHealAt)) : ""));

            SharedPreferences sp = getSharedPreferences(Watchdog.PREF, MODE_PRIVATE);
            boolean auto = sp.getBoolean(Watchdog.KEY_AUTO, true);
            if (logBtn != null) logBtn.setText(Dbg.on() ? "日志：开" : "日志：关");
            autoBtn.setText(auto ? "自愈：开" : "自愈：关");
            autoBtn.setTextColor(auto ? OK : WARN);

            tickCount++;
            if (tickCount % 5 == 1) {
                worker.execute(new Runnable() {
                    @Override public void run() {
                        diplusOnline = Boolean.valueOf(Diplus.online());
                    }
                });
            }
            ui.postDelayed(this, 1000);
        }
    };

    private String ago(long ts) {
        long d = (System.currentTimeMillis() - ts) / 1000;
        if (d < 60) return d + " 秒前";
        if (d < 3600) return (d / 60) + " 分钟前";
        return (d / 3600) + " 小时前";
    }
}
