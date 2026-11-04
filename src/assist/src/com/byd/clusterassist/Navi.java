package com.byd.clusterassist;

import android.content.Context;

import java.lang.reflect.Method;

/**
 * ADAS 大小由 naviType 决定：device=1007，读事件 1086337074，写事件 1276157976（CanFD）。
 * 写入走迪加 Lua 通道（白名单），读取先试框架反射，失败再退回迪加。
 */
public final class Navi {
    public static final int DEV = 1007;
    public static final int EV_READ = 1086337074;
    public static final int EV_WRITE = 1276157976;

    private Navi() {}

    /** 反射读：普通 app 大概率被 BYD 白名单挡掉，只当参考值。 */
    public static Integer readReflect(Context ctx) {
        try {
            Class<?> c = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method gi = c.getMethod("getInstance", Context.class);
            Object dev = gi.invoke(null, ctx);
            Method get = findInt2(c, "get");
            if (get == null) return null;
            Object v = get.invoke(dev, Integer.valueOf(DEV), Integer.valueOf(EV_READ));
            return (Integer) v;
        } catch (Throwable t) {
            Journal.add("反射读 naviType 失败: " + shortMsg(t));
            return null;
        }
    }

    private static Method findInt2(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Method m = k.getDeclaredMethod(name, int.class, int.class);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            k = k.getSuperclass();
        }
        return null;
    }

    public static String write(int value) {
        String script =
                "local ok, code = pcall(di.byd.setInt, " + DEV + ", " + EV_WRITE + ", " + value + ")\n"
              + "if not ok then di.log('navi set err', code) return end\n"
              + "di.log('naviType', " + value + ", 'code', code)\n"
              + "di.toast('naviType=' .. " + value + " .. ' code=' .. tostring(code))\n";
        boolean sent = Diplus.lua(script);
        return sent ? ("已提交 naviType=" + value) : "迪加未接受指令";
    }

    public static String probe() {
        StringBuilder sb = new StringBuilder();
        sb.append("local vals = {0,1,2,3,4,5,6,7,8}\n");
        sb.append("for i = 1, #vals do\n");
        sb.append("  local v = vals[i]\n");
        sb.append("  local ok, code = pcall(di.byd.setInt, ").append(DEV).append(", ").append(EV_WRITE).append(", v)\n");
        sb.append("  di.log('probe navi', v, 'ok', ok, 'code', tostring(code))\n");
        sb.append("  di.sleep(1500)\n");
        sb.append("end\n");
        sb.append("di.toast('naviType probe 0..8 done')\n");
        return Diplus.lua(sb.toString()) ? "已开始 0..8 探测（约 14s）" : "迪加未接受指令";
    }

    static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : (": " + m));
    }
}
