package com.byd.clusterassist;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 迪加本地 API（127.0.0.1:8988）——车辆数据的白名单通道。 */
public final class Diplus {
    public static final String HOST = "http://127.0.0.1:8988";

    private Diplus() {}

    public static String get(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean online() {
        String body = get(HOST + "/api/getVal?name=" + url("车速"));
        return body != null && body.indexOf("success") >= 0;
    }

    public static String val(String name) {
        String body = get(HOST + "/api/getVal?name=" + url(name));
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("success", false)) return null;
            return o.optString("val", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 提交一条小迪 / Lua 指令，success 只代表入口接受。 */
    public static boolean sendCmd(String cmd) {
        String body = get(HOST + "/api/sendCmd?cmd=" + url(cmd));
        if (body == null) return false;
        return body.indexOf("true") >= 0;
    }

    public static boolean lua(String script) {
        String b64 = android.util.Base64.encodeToString(
                script.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                android.util.Base64.NO_WRAP);
        return sendCmd("lua:" + b64);
    }

    private static String url(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }
}
