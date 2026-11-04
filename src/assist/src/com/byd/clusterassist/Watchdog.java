package com.byd.clusterassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 自愈守护：10s 一轮，检查 display 1 上是否还有 CardActivity，掉了就用 ADB 拉起来并重绑通知监听。
 * 相比 overlay 自带的 KeepAlive，这里不依赖 overlay 存活，overlay 被 quickboot 干掉也能复活。
 */
public class Watchdog extends Service {
    public static final String CH = "assist_watchdog";
    public static final int NOTI = 2001;
    public static final String PREF = "assist";
    public static final String KEY_AUTO = "auto_heal";

    public static volatile boolean shell = false;
    public static volatile boolean cardOn = false;
    public static volatile boolean mediaOn = false;
    public static volatile int healCount = 0;
    public static volatile long lastHealAt = 0L;
    public static volatile long lastMediaFixAt = 0L;
    public static volatile String note = "启动中";
    public static volatile boolean running = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean binderHooked = false;
    private int failedHeals = 0;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(NOTI, buildNoti("守护中"));
        hookBinder();
        h.postDelayed(tick, 3000);
        Journal.add("watchdog 启动");
    }

    private void hookBinder() {
        if (binderHooked) return;
        binderHooked = true;
        try {
            Shell.init(getApplicationContext());
        } catch (Throwable t) {
            Journal.add("ADB 初始化失败: " + Navi.shortMsg(t));
        }
    }

    private Notification buildNoti(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel c = new NotificationChannel(CH, "仪表守护",
                    NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle("仪表助理")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
    }

    private boolean autoHeal() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO, true);
    }

    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // step() 里有 Shell.exec（adb 往返），必须挪出主线程，否则每 10s 卡一次 UI
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            worker.execute(new Runnable() {
                @Override public void run() {
                    try { step(); } catch (Throwable t) {
                        Journal.add("tick 异常: " + Navi.shortMsg(t));
                    }
                }
            });
            h.postDelayed(this, 30000);
        }
    };

    @Override public void onDestroy() {
        running = false;
        try { worker.shutdownNow(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void step() {
        Shell.init(getApplicationContext());
        shell = Shell.ready();
        if (!shell) {
            cardOn = false;
            mediaOn = false;
            note = "ok".equals(Shell.state()) ? "状态查询超时" : ("ADB 未就绪（" + Shell.state() + "）");
            updateNoti();
            return;
        }
        Integer c = Card.count();
        if (c == null) {
            note = "ok".equals(Shell.state()) ? "状态查询超时" : ("ADB 未就绪（" + Shell.state() + "）");
            updateNoti();
            return;
        }
        cardOn = c.intValue() > 0;
        Boolean realMedia = Card.mediaConnected();
        mediaOn = realMedia != null ? realMedia.booleanValue() : Card.mediaBound();

        if (!cardOn) {
            if (!autoHeal()) {
                note = "卡片不在 display 1（自愈已关闭）";
                updateNoti();
                return;
            }
            // 连续自愈失败要退避：main 进程自己也会 relaunch，
            // 两边同时动手会把界面反复重建（用户看到不停闪退重启）。
            if (healCount >= 5 && lastHealAt > 0
                    && System.currentTimeMillis() - lastHealAt < 120000) {
                note = "自愈已退避（连续 " + healCount + " 次失败，等 2 分钟）";
                updateNoti();
                return;
            }
            Journal.add("卡片不在 display 1，执行自愈（第 " + (healCount + 1) + " 次）");
            Card.start();
            Card.sleep(1500);
            Card.rebindMedia();
            Card.sleep(400);
            Integer c2 = Card.count();
            cardOn = c2 != null && c2.intValue() > 0;
            healCount++;
            lastHealAt = System.currentTimeMillis();
            lastMediaFixAt = lastHealAt;
            if (cardOn) { healCount = 0; note = "已自愈"; }
            else { note = "自愈失败，下一轮重试"; }
            Journal.add(note + "  mode=" + Card.mode);
            if (!cardOn) {
                failedHeals++;
                if (failedHeals >= 3 && Card.mode == 0) {
                    Card.mode = 1;
                    failedHeals = 0;
                    Journal.add("连续 3 次自愈失败，切到 window 服务判据（疑似 dumpsys 分组不兼容）");
                }
            } else {
                failedHeals = 0;
            }
        } else if (!mediaOn && System.currentTimeMillis() - lastMediaFixAt > 60000) {
            lastMediaFixAt = System.currentTimeMillis();
            Journal.add("媒体监听未连接，重绑通知监听");
            Card.rebindMedia();
            Boolean after = Card.mediaConnected();
            mediaOn = after != null && after.booleanValue();
            note = mediaOn ? "媒体监听已重绑" : "重绑后仍未连接（看 logcat -s CLUSTERLAB-MEDIA）";
        } else {
            note = "正常";
        }
        updateNoti();
    }

    private void updateNoti() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTI, buildNoti(note));
        } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        hookBinder();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
