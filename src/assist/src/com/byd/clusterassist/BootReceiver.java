package com.byd.clusterassist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        Journal.add("boot 收到 " + a);
        try {
            Intent svc = new Intent(ctx, Watchdog.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Throwable t) {
            Journal.add("watchdog 启动失败: " + Navi.shortMsg(t));
        }
        // 立即尝试一次；失败由 Watchdog 的 binder 回调 / 10s 轮询兜住
        new Thread(new Runnable() {
            @Override public void run() {
                Card.sleep(4000);
                if (!Shell.ready()) {
                    Journal.add("boot: ADB 未就绪，交给守护轮询");
                    return;
                }
                Journal.add("boot: 尝试启动卡片");
                Card.start();
                Card.sleep(1200);
                Card.rebindMedia();
                Journal.add("boot: 卡片存活 = " + Card.alive());
            }
        }).start();
    }
}
