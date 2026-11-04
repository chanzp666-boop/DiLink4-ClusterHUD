package com.byd.clusterlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

/**
 * 高德车机版的公开开放协议：导航状态变化会发 AUTONAVI_STANDARD_BROADCAST_SEND。
 * 关键字段 EXTRA_STATE：0=无导航，非 0=导航中（不同版本取值不同，按非 0 处理）。
 * 这条不依赖无障碍、不依赖副屏，是唯一能拿到"真·导航状态"的通道。
 */
public final class NavReceiver {
    private static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static boolean started = false;

    private NavReceiver() {}

    public static void start(Context c) {
        if (started) return;
        started = true;
        try {
            // 高德是隐式广播（可能带包名/不带），签名级权限也常见 → 用 export 方式放宽
            IntentFilter f = new IntentFilter(ACTION_SEND);
            f.addAction("AUTONAVI_STANDARD_BROADCAST_RECEIVE");
            f.addAction("com.autonavi.amapauto.BROADCAST");
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent it) {
                    try {
                        Bundle b = it.getExtras();
                        StringBuilder sb = new StringBuilder("高德广播: ");
                        if (b != null) {
                            for (String k : b.keySet()) {
                                sb.append(k).append('=').append(b.get(k)).append("; ");
                            }
                        }
                        Dbg.e("CLUSTERLAB-NAV", sb.toString());

                        // 高德一有动静就短时提速探测，别等 2 秒轮询
                        NavState.boost(10000);
                        if (b != null && b.containsKey("EXTRA_STATE")) {
                            int st = b.getInt("EXTRA_STATE", -1);
                            // 只信「开始」。高德在导航过程中也会发 EXTRA_STATE=0（路况/电子眼等），
                            // 拿它当「导航结束」会让大小窗反复横跳 —— 退出交给像素探测（约 2 秒）。
                            NavState.noteBroadcast();
                            if (st != 0) NavState.setWithHold(true, "broadcast", 1500);
                        }
                    } catch (Throwable t) {
                        Dbg.e("CLUSTERLAB-NAV", "广播解析失败: " + t);
                    }
                }
            }, f, Context.RECEIVER_EXPORTED);
            Dbg.e("CLUSTERLAB-NAV", "已注册高德广播监听");
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-NAV", "注册失败: " + t);
        }
    }
}
