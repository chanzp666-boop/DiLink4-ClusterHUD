package com.byd.clusterlab;

import android.app.Notification;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * 媒体源。
 * 主通道：MediaSessionManager.getActiveSessions —— 必须通知监听已绑定，否则 SecurityException。
 * 备通道：监听到的媒体通知（EXTRA_MEDIA_SESSION 存在）取标题/艺术家/大图标，
 *         在 session 拿不到数据时顶上，至少封面和歌名不会空着。
 * 另外每 2s 往 /sdcard/clusterlab_media.txt 写心跳，供「仪表助理」诊断。
 */
public class MediaListener extends NotificationListenerService {
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String album = "";
    public static volatile Bitmap art = null;
    public static volatile long position = 0;
    public static volatile long basePosition = 0;
    public static volatile long baseTime = 0;
    public static volatile float playSpeed = 1f;
    public static volatile long duration = 0;
    public static volatile boolean playing = false;
    public static volatile int revision = 0;

    /** 监听器是否真的绑定着（不是看 secure 设置里的条目，那条 force-stop 后不会消失）。 */
    public static volatile boolean connected = false;
    public static volatile int sessions = 0;
    public static volatile long lastMetaAt = 0L;
    public static volatile long lastLoopAt = 0L;
    public static volatile String lastErr = "";
    public static volatile boolean fromNotification = false;

    private static volatile String fbTitle = "";
    private static volatile String fbArtist = "";
    private static volatile String fbAlbum = "";
    private static volatile Bitmap fbArt = null;
    private static volatile long fbAt = 0L;

    private MediaSessionManager msm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastDumpAt = 0L;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            try { refresh(); } catch (Throwable t) { lastErr = "loop:" + t; }
            try { heartbeat(); } catch (Throwable ignored) {}
            h.postDelayed(this, 1500);
        }
    };

    @Override public void onListenerConnected() {
        connected = true;
        lastErr = "";
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        h.removeCallbacks(loop);
        h.post(loop);
        Dbg.e("CLUSTERLAB-MEDIA", "onListenerConnected");
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        h.removeCallbacks(loop);
        Dbg.e("CLUSTERLAB-MEDIA", "onListenerDisconnected");
    }

    @Override public void onDestroy() {
        connected = false;
        h.removeCallbacks(loop);
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;
            Bundle ex = n.extras;
            if (ex == null) return;
            if (!ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
                    && !Notification.CATEGORY_TRANSPORT.equals(n.category)) return;

            String t = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String s = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
            Bitmap bmp = null;
            Object big = ex.getParcelable(Notification.EXTRA_LARGE_ICON_BIG);
            if (big instanceof Bitmap) bmp = (Bitmap) big;
            if (bmp == null) {
                Object lg = ex.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (lg instanceof Bitmap) bmp = (Bitmap) lg;
            }
            if (t.length() == 0) return;
            fbTitle = t;
            fbArtist = s.length() > 0 ? s : sub;
            fbAlbum = sub.length() > 0 && !sub.equals(s) ? sub : "";
            if (bmp != null) fbArt = bmp;
            fbAt = android.os.SystemClock.elapsedRealtime();
            Dbg.e("CLUSTERLAB-MEDIA", "notif fallback pkg=" + sbn.getPackageName()
                    + " t=" + t + " s=" + s + " art=" + (bmp != null));
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-MEDIA", "onNotificationPosted err " + t);
        }
    }

    private void refresh() {
        lastLoopAt = android.os.SystemClock.elapsedRealtime();
        if (msm == null) msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (msm == null) { lastErr = "msm null"; return; }

        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            lastErr = "";
        } catch (Throwable t) {
            // 最常见：监听器没绑定 / 权限被撤销
            lastErr = "getActiveSessions:" + t.getClass().getSimpleName();
            connected = false;
            useFallback();
            return;
        }
        sessions = list.size();

        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            MediaMetadata md = c.getMetadata();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING && md != null
                    && md.getText(MediaMetadata.METADATA_KEY_TITLE) != null) {
                best = c;
                break;
            }
        }
        if (best == null) {
            for (MediaController c : list) {
                MediaMetadata md = c.getMetadata();
                if (md != null && md.getText(MediaMetadata.METADATA_KEY_TITLE) != null) { best = c; break; }
            }
        }
        if (best == null) { useFallback(); return; }

        MediaMetadata md = best.getMetadata();
        if (md == null) { useFallback(); return; }
        String t = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String a = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        if (a.length() == 0) a = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String al = str(md, MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (t.length() == 0) { useFallback(); return; }

        PlaybackState st = best.getPlaybackState();
        boolean pl = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (st != null) {
            playSpeed = st.getPlaybackSpeed();
            long nowRt = android.os.SystemClock.elapsedRealtime();
            long raw = st.getPosition();
            // 先把上报值补齐到"当前时刻"，再当作基准；否则每次上报都会落后一个上报周期
            long adjusted = raw;
            if (pl && playSpeed > 0f) {
                long d = nowRt - st.getLastPositionUpdateTime();
                if (d > 0) adjusted = raw + (long) (d * playSpeed);
            }
            long predicted = basePosition + (long) ((nowRt - baseTime) * playSpeed);
            if (baseTime == 0 || Math.abs(adjusted - predicted) > 500) {
                basePosition = adjusted;
                baseTime = nowRt;
            }
            position = adjusted;
        }
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

        lastMetaAt = android.os.SystemClock.elapsedRealtime();
        if (!t.equals(title)) LyricLoader.reset();
        boolean changed = !t.equals(title) || !a.equals(artist) || !al.equals(album)
                || bmp != art || pl != playing || fromNotification;
        fromNotification = false;
        title = t; artist = a; album = al; art = bmp; playing = pl;
        duration = dur;
        if (changed) revision++;
    }

    /** session 没数据时用通知里的信息顶上（10 秒内有效）。 */
    private void useFallback() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (fbTitle.length() == 0 || now - fbAt > 10000) {
            if (title.length() > 0 && now - lastMetaAt > 15000) {
                // 长时间没有任何数据：清空，避免显示上一首歌
                title = ""; artist = ""; album = ""; art = null; playing = false;
                fromNotification = false;
            }
            return;
        }
        boolean changed = !fbTitle.equals(title) || !fbArtist.equals(artist) || fbArt != art || !fromNotification;
        title = fbTitle;
        artist = fbArtist;
        album = fbAlbum;
        art = fbArt;
        playing = true;
        fromNotification = true;
        lastMetaAt = now;
        if (changed) revision++;
    }

    /** 2 秒一次写心跳文件，助理侧 cat 一下就知道到底绑没绑、有没有数据。 */
    private void heartbeat() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastDumpAt < 2000) return;
        lastDumpAt = now;
        String dump = "ts=" + System.currentTimeMillis() + "\n"
                + "connected=" + (connected ? 1 : 0) + "\n"
                + "sessions=" + sessions + "\n"
                + "title=" + title + "\n"
                + "artist=" + artist + "\n"
                + "playing=" + (playing ? 1 : 0) + "\n"
                + "art=" + (art == null ? 0 : 1) + "\n"
                + "posMs=" + position + "\n"
                + "lyricLines=" + LyricLoader.lines.size() + "\n"
                + "fallback=" + (fromNotification ? 1 : 0) + "\n"
                + "ageMs=" + (lastMetaAt == 0 ? -1 : (now - lastMetaAt)) + "\n"
                + "err=" + lastErr + "\n";
        try {
            java.io.FileWriter w = new java.io.FileWriter("/sdcard/clusterlab_media.txt");
            w.write(dump);
            w.close();
        } catch (Throwable t) {
            // 没有存储权限就跳过，不影响主流程
            if (now - lastDumpAt > 60000) Dbg.e("CLUSTERLAB-MEDIA", "heartbeat fail " + t);
        }
    }

    private String str(MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        return cs == null ? "" : cs.toString();
    }

    private String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
