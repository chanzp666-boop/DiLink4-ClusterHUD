package com.byd.clusterlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class LyricLoader {
    public static volatile List<Long> times = new ArrayList<>();
    public static volatile List<String> lines = new ArrayList<>();
    public static volatile String loadedKey = "";
    public static volatile boolean loading = false;
    /** 当前这首歌的曲名/歌手，用于源里缺头部信息时自动补上。 */
    private static String curTitle = "";
    private static String curArtist = "";
    public static volatile long lrcDuration = 0L;

    public static void reset() {
        times = new ArrayList<>();
        lines = new ArrayList<>();
        loadedKey = "";
    }

    public static void load(final String title, final String artist) { load(title, artist, 0); }

    public static void load(final String title, final String artist, final long durationMs) {
        if (title == null || title.length() == 0) return;
        final String key = title + "|" + artist;
        if (key.equals(loadedKey) || loading) return;
        loadedKey = key;
        loading = true;
        curTitle = title;
        curArtist = artist == null ? "" : artist;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String clean = title.replaceAll("\\s*[\\(\\[（【].*?[\\)\\]）】]", "").trim();

                    // 1) 网易云
                    if (loadFromNetease(clean, artist, durationMs)) {
                        Dbg.e("CLUSTERLAB", "歌词来源: 网易云 " + clean);
                        return;
                    }
                    // 2) QQ 音乐
                    Dbg.e("CLUSTERLAB", "网易云没拿到，切 QQ 音乐: " + clean);
                    if (loadFromQQ(clean, artist, durationMs)) {
                        Dbg.e("CLUSTERLAB", "歌词来源: QQ音乐 " + clean);
                        return;
                    }
                    // 3) 酷狗（与迪加同源）
                    Dbg.e("CLUSTERLAB", "QQ 没拿到，切酷狗: " + clean);
                    if (loadFromKugou(clean, artist, durationMs)) {
                        Dbg.e("CLUSTERLAB", "歌词来源: 酷狗 " + clean);
                        return;
                    }
                    // 4) 酷我
                    Dbg.e("CLUSTERLAB", "酷狗没拿到，切酷我: " + clean);
                    if (loadFromKuwo(clean, artist, durationMs)) {
                        Dbg.e("CLUSTERLAB", "歌词来源: 酷我 " + clean);
                        return;
                    }
                    Dbg.e("CLUSTERLAB", "四个来源都没拿到歌词: " + clean);
                } catch (Throwable t) {
                    Dbg.e("CLUSTERLAB", "歌词加载异常: " + t);
                    loadedKey = "";
                } finally {
                    loading = false;
                }
            }
        }).start();
    }

    /** 网易云：搜索接口 + 歌词接口。成功返回 true。 */
    private static boolean loadFromNetease(String clean, String artist, long durationMs) {
        try {
            String q = URLEncoder.encode(clean + " " + artist, "UTF-8");
            String search = http("https://music.163.com/api/search/get/web?csrf_token=&s="
                    + q + "&type=1&offset=0&total=true&limit=6");
            JSONObject o = new JSONObject(search);
            JSONArray songs = o.getJSONObject("result").getJSONArray("songs");
            if (songs.length() == 0) return false;
            long id = songs.getJSONObject(0).getLong("id");
            lrcDuration = songs.getJSONObject(0).optLong("duration", 0);
            long bestDiff0 = Long.MAX_VALUE;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject s = songs.getJSONObject(i);
                String nm = s.optString("name", "");
                if (nm.equals(clean)) { id = s.getLong("id"); bestDiff0 = 0; break; }
                if (nm.contains(clean) || clean.contains(nm)) {
                    long d = s.optLong("duration", 0);
                    long diff = durationMs > 20000 && d > 0 ? Math.abs(d - durationMs) : 1;
                    if (diff < bestDiff0) { bestDiff0 = diff; id = s.getLong("id"); }
                }
            }
            if (durationMs > 20000) {
                long bestDiff = Long.MAX_VALUE;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject s = songs.getJSONObject(i);
                    long d = s.optLong("duration", 0);
                    if (d <= 0) continue;
                    long diff = Math.abs(d - durationMs);
                    if (diff < bestDiff) { bestDiff = diff; id = s.getLong("id"); lrcDuration = d; }
                }
            }
            String lyric = http("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1");
            JSONObject lo = new JSONObject(lyric);
            String text = lo.getJSONObject("lrc").getString("lyric");
            if (text == null || text.trim().length() == 0) return false;
            parse(text, curTitle, curArtist);
            return lines.size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * QQ 音乐：搜索 → 拿 songmid → 取歌词。
     * 走 c.y.qq.com 的公开接口，Referer 必须带上，否则 403。
     */
    private static boolean loadFromQQ(String clean, String artist, long durationMs) {
        try {
            String kw = URLEncoder.encode((clean + " " + artist).trim(), "UTF-8");
            String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?cr=1&t=0&aggr=1&flag=1&lossless=0&curnum=8&curpage=1&format=json"
                    + "&w=" + kw;
            String r = httpQQ(searchUrl);
            if (r == null) { Dbg.e("CLUSTERLAB", "QQ搜索无响应"); return false; }
            // format=json 返回纯 JSON；个别情况是 callback({...})，只在这种形态才去壳
            r = stripJsonp(r);
            JSONObject o = new JSONObject(r);
            JSONObject songObj = o.getJSONObject("data").getJSONObject("song");
            JSONArray list = songObj.getJSONArray("list");
            if (list.length() == 0) { Dbg.e("CLUSTERLAB", "QQ搜索无结果"); return false; }

            String mid = null;
            String bestName = null;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.getJSONObject(i);
                String nm = s.optString("songname", "");
                String mid2 = s.optString("songmid", "");
                if (mid2.length() == 0) continue;
                long interval = s.optLong("interval", 0) * 1000L;   // 秒
                long diff = durationMs > 20000 && interval > 0 ? Math.abs(interval - durationMs) : i;
                if (nm.equals(clean) && mid == null) { mid = mid2; bestName = nm; bestDiff = diff; }
                if (diff < bestDiff) { bestDiff = diff; mid = mid2; bestName = nm; }
            }
            if (mid == null) return false;

            String lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                    + "?songmid=" + mid + "&g_tk=5381&format=json&nobase64=0";
            String lr = httpQQ(lyricUrl);
            if (lr == null) return false;
            lr = stripJsonp(lr);
            JSONObject lo = new JSONObject(lr);
            String b64 = lo.optString("lyric", "");
            if (b64.length() == 0) {
                Dbg.e("CLUSTERLAB", "QQ歌词空 retcode=" + lo.optInt("retcode") + " mid=" + mid);
                return false;
            }
            String text = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
            // QQ 的歌词是 HTML 转义的
            text = text.replace("&apos;", "'").replace("&quot;", "\"")
                       .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
            parse(text, curTitle, curArtist);
            Dbg.e("CLUSTERLAB", "QQ 歌词命中: " + bestName);
            return lines.size() > 0;
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB", "QQ 歌词失败: " + t);
            return false;
        }
    }

    /**
     * 酷狗：搜索拿 hash → krcs 取歌词下载地址 → 下载 base64 的歌词。
     * 迪加的 kugou-online 来源就是这套。
     */
    private static boolean loadFromKugou(String clean, String artist, long durationMs) {
        try {
            String kw = URLEncoder.encode((clean + " " + artist).trim(), "UTF-8");
            String r = httpPlain("http://mobilecdn.kugou.com/api/v3/search/song"
                    + "?format=json&keyword=" + kw + "&page=1&pagesize=8", "http://m.kugou.com/");
            if (r == null) return false;
            JSONObject o = new JSONObject(r);
            JSONArray info = o.getJSONObject("data").optJSONArray("info");
            if (info == null || info.length() == 0) return false;

            String hash = null;
            for (int i = 0; i < info.length(); i++) {
                JSONObject it = info.getJSONObject(i);
                String h = it.optString("hash", "");
                if (h.length() > 0) { hash = h; break; }
            }
            if (hash == null) return false;

            String krcs = httpPlain("http://krcs.kugou.com/search"
                    + "?ver=1&man=yes&client=mobi&keyword="
                    + URLEncoder.encode(clean, "UTF-8") + "&duration=&hash=" + hash,
                    "http://m.kugou.com/");
            if (krcs == null) return false;
            JSONObject ko = new JSONObject(krcs);
            JSONArray cand = ko.optJSONArray("candidates");
            if (cand == null || cand.length() == 0) return false;
            String id = cand.getJSONObject(0).optString("id", "");
            String accesskey = cand.getJSONObject(0).optString("accesskey", "");
            if (id.length() == 0 || accesskey.length() == 0) return false;

            String dl = httpPlain("http://lyrics.kugou.com/download"
                    + "?ver=1&client=pc&id=" + id + "&accesskey=" + accesskey
                    + "&fmt=lrc&charset=utf8", "http://m.kugou.com/");
            if (dl == null) return false;
            JSONObject lo = new JSONObject(dl);
            String content = lo.optString("content", "");
            if (content.length() == 0) return false;
            String text = new String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), "UTF-8");
            parse(text, curTitle, curArtist);
            return lines.size() > 0;
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB", "酷狗歌词失败: " + t);
            return false;
        }
    }

    /** 酷我：搜索拿 RID → 取歌词。 */
    private static boolean loadFromKuwo(String clean, String artist, long durationMs) {
        try {
            String kw = URLEncoder.encode((clean + " " + artist).trim(), "UTF-8");
            String r = httpPlain("http://search.kuwo.cn/r.s?all=" + kw
                    + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=8&rformat=json&encoding=utf8",
                    "http://www.kuwo.cn/");
            if (r == null) return false;
            // 酷我返回单引号 JSON，先规整成标准 JSON
            String j = r.replace('\'', '"');
            JSONObject o = new JSONObject(j);
            JSONArray list = o.optJSONArray("abslist");
            if (list == null || list.length() == 0) return false;
            String rid = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.getJSONObject(i);
                String id = it.optString("DC_TARGETID", "");
                if (id.length() > 0) { rid = id; break; }
            }
            if (rid == null) return false;

            String lrc = httpPlain("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + rid,
                    "http://m.kuwo.cn/");
            if (lrc == null) return false;
            JSONObject lo = new JSONObject(lrc);
            JSONObject data = lo.optJSONObject("data");
            if (data == null) return false;
            JSONArray lrcList = data.optJSONArray("lrclist");
            if (lrcList == null || lrcList.length() == 0) return false;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lrcList.length(); i++) {
                JSONObject it = lrcList.getJSONObject(i);
                String tm = it.optString("time", "");
                String tx = it.optString("lineLyric", "");
                if (tm.length() == 0 || tx.length() == 0) continue;
                try {
                    double sec = Double.parseDouble(tm);
                    long min = (long) (sec / 60);
                    double s2 = sec - min * 60;
                    sb.append(String.format(java.util.Locale.US, "[%02d:%05.2f]", min, s2))
                      .append(tx).append('\n');
                } catch (Throwable ignored) {}
            }
            if (sb.length() == 0) return false;
            parse(sb.toString(), curTitle, curArtist);
            return lines.size() > 0;
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB", "酷我歌词失败: " + t);
            return false;
        }
    }

    /** 通用 HTTP GET（酷狗/酷我都要带 Referer）。 */
    private static String httpPlain(String url, String referer) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只在 callback(...) / jQuery(...) 这种形态下去壳，纯 JSON 原样返回。 */
    private static String stripJsonp(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("{")) return t;
        int lb = t.indexOf('(');
        int rb = t.lastIndexOf(')');
        if (lb > 0 && rb > lb) return t.substring(lb + 1, rb).trim();
        return t;
    }

    /** QQ 接口要 Referer，否则返回 403。 */
    private static String httpQQ(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Referer", "https://y.qq.com/");
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void parse(String lrc) {
        parse(lrc, null, null);
    }

    private static void parse(String lrc, String title, String artist) {
        List<Long> t = new ArrayList<>();
        List<String> s = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.length() < 6 || line.charAt(0) != '[') continue;
            int close = line.indexOf(']');
            if (close < 0) continue;
            String stamp = line.substring(1, close);
            String body = line.substring(close + 1).trim();
            if (body.length() == 0) continue;
            String[] mmss = stamp.split(":");
            if (mmss.length != 2) continue;
            try {
                long min = Long.parseLong(mmss[0].trim());
                double sec = Double.parseDouble(mmss[1].trim());
                t.add((long) (min * 60000 + sec * 1000));
                s.add(body);
            } catch (Throwable ignored) {
            }
        }
        // 源里没有曲名/歌手等头部信息时（很多日韩歌源只有正文），
        // 自己补上，保证歌词结构和以前一致：歌名 → 歌手 → 词曲 → 正文
        if (s.size() > 0 && title != null && title.length() > 0 && !hasHeaderInfo(s)) {
            s.add(0, artist != null && artist.length() > 0 ? (title + " - " + artist) : title);
            t.add(0, 0L);
        }

        if (s.size() > 0) {
            StringBuilder sb = new StringBuilder("歌词前3行: ");
            for (int i = 0; i < Math.min(3, s.size()); i++) {
                sb.append("[").append(s.get(i)).append("] ");
            }
            Dbg.e("CLUSTERLAB", sb.toString());
        }

        // 整体替换、绝不原地改：绘制线程每帧都在读这两个 list，
        // 原来直接清空再逐条 add，读到"清空后、加回前"就是 IndexOutOfBounds。
        times = java.util.Collections.unmodifiableList(t);
        lines = java.util.Collections.unmodifiableList(s);
    }

    /**
     * LRC 头部元数据（作词/作曲/编曲/制作…）。
     * 它们通常带 [00:00.00] 时间戳，不过滤的话会被当成第一句歌词，
     * 表现为"前奏还没唱就高亮了当前歌词"。
     */
    /** 前 4 行里是否已经有曲名/歌手/词曲这类头部信息。 */
    private static boolean hasHeaderInfo(java.util.List<String> s) {
        int n = Math.min(4, s.size());
        for (int i = 0; i < n; i++) {
            String b = s.get(i).trim();
            if (b.length() == 0) continue;
            if (b.contains("作词") || b.contains("作曲") || b.contains("词：") || b.contains("曲：")
                    || b.contains("编曲") || b.contains("Lyrics") || b.contains("Composer")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMetaLine(String body) {
        String b = body.trim();
        if (b.length() == 0) return true;
        // 必须带冒号（「词：李焯雄」），纯歌词很少有
        int c1 = b.indexOf('：'), c2 = b.indexOf(':');
        int c = c1 >= 0 ? c1 : c2;
        if (c <= 0 || c > 6) return false;          // 冒号太靠后，不是标签
        String tag = b.substring(0, c).trim();
        String[] META = {"词", "曲", "编曲", "作词", "作曲", "制作", "监制", "出品",
                "混音", "录音", "和声", "吉他", "贝斯", "鼓", "弦乐", "母带",
                "op", "sp", "出品人", "统筹", "发行", "企划"};
        for (String m : META) {
            if (tag.equalsIgnoreCase(m)) return true;
        }
        return false;
    }

    /** 供绘制线程安全快照：两个 list 一定同时非空或同时为空。 */
    public static List<String> snapshotLines() { return lines; }

    public static int indexAt(long positionMs) {
        List<Long> t = times;
        int lo = 0, hi = t.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (t.get(mid) <= positionMs) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Referer", "https://music.163.com");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
