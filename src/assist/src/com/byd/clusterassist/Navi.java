package com.byd.clusterassist;

/** 面板侧通用小工具（ADAS 大小控制已随区块一起删除，只留日志用的短消息）。 */
public final class Navi {
    private Navi() {}

    static String shortMsg(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return t == null ? "null" : (t.getClass().getSimpleName() + (m == null ? "" : (": " + m)));
    }
}
