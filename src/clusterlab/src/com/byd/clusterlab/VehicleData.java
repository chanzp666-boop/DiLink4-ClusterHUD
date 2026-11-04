package com.byd.clusterlab;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 车辆数据。
 *
 * 油耗口径：不再用「本次行程」，改成**累计寄存器 + 50km 滚动窗口**。
 * 原因：tripStatistics 的 fuelDelta 只有 0.1L 刻度，分母 1.5km 时误差就有 ±6.7 L/100km；
 * 而累计寄存器（总电耗/总燃油消耗/里程值，都是 0.1 刻度）用 50km 窗口时误差只有 ±0.2。
 *
 * 采样：每 60s 往 /sdcard/clusterlab_fuel.csv 追加一行 km,kwh,L，只保留最近 120km。
 * 窗口不足 5km（刚上车 / 刚装车）时退回全生命周期均值，并置 estimated=true 让界面加 ≈。
 */
public class VehicleData {
    public static volatile int[] pressure = new int[]{-1, -1, -1, -1};
    public static volatile double elecPHM = 0;     // kWh/100km
    public static volatile double fuelPHM = 0;     // L/100km
    public static volatile double totalPHM = 0;    // L/100km（综合）
    public static volatile boolean ok = false;
    public static volatile boolean estimated = true;
    public static volatile double winKm = 0;       // 实际参与计算的里程跨度
    public static volatile String lastError = "";

    /** 从迪友桌面卡片抓到的「最近能耗」（每百公里），2 分钟内有效。 */
    public static volatile double dyElec = -1;
    public static volatile double dyFuel = -1;
    public static volatile long dyAt = 0L;
    /** 迪友数据超过 2 分钟没更新（只用于日志提示，不再回落）。 */
    public static volatile boolean dyStale = false;

    /** 迪友值的持久化文件：迪友崩溃/重启期间用它顶住，避免开机先显示错的迪加口径。 */
    private static final String DY_FILE = "/sdcard/clusterlab_dy.txt";
    /** 胎压上次读取时刻（单独节流）。 */
    private static long lastPressureAt = 0L;
    private static boolean dyLoaded = false;

    /** 遥测库最近行驶窗口（100km 内），迪友卡片失效后的替代口径。 */
    public static volatile double winElec = 0;
    public static volatile double winFuel = 0;
    public static volatile long winAt = 0L;

    private static final String HOST = "http://127.0.0.1:8988";
    private static final String HIST = "/sdcard/clusterlab_fuel.csv";
    /** 迪加写的遥测库（全球可读），用来给窗口做"历史预热"，装好就有 50km 数据。 */
    private static final String TELEM_DB = "/sdcard/vandiplus/db/vehicle_telemetry.db";
    private static final double WINDOW_KM = 50.0;
    /** 只要有 0.3km 的跨度就开始出数（原来 5km 的门槛会让人以为没在算）。 */
    private static final double MIN_KM = 0.3;
    /**
     * 先验权重（等效公里数）：窗口值按跨度加权，和生命周期均值做收缩。
     * 0.1L 的刻度在 0.3km 上等于 33L/100km，不收缩就没法看；
     * 跨度越大越接近窗口真实值（50km 时窗口占 91%）。
     */
    private static final double PRIOR_KM = 5.0;
    private static final long STATS_MS = 3000L;    // 计数器轮询
    private static final long SAMPLE_MS = 60000L;   // 锚点落盘
    private static final double KWH_PER_L = 3.5;

    /** 每行 [里程 km, 总电耗 kWh, 总燃油 L]，按里程升序。 */
    private static final java.util.ArrayList<double[]> SAMPLES = new java.util.ArrayList<double[]>();
    private static boolean loaded = false;
    private static long lastSampleAt = 0L;
    private static boolean computed = false;
    private static long lastFuelDbAt = 0L;
    private static boolean fuelDbOk = false;
    private static double fuelDbValue = -1;
    private static double elecDbValue = 0;
    private static double curKm = 0, curKwh = 0, curL = 0;
    private static long lastTry = 0L;
    private static volatile boolean busy = false;
    private static double lifeE = 0, lifeF = 0;
    private static long lastTripAt = 0;
    private static boolean tripOk = false;
    private static long bydLogAt = 0L;

    private static volatile long busySince = 0L;

    public static void refresh(final android.content.Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        // 看门狗：真机上遇到过刷新线程卡住（迪加 8988 或 sqlite 打开被占），
        // busy 卡在 true 就再也不刷新，界面整片空白。卡超过 15 秒就强制解锁。
        if (busy && now - busySince > 15000L) {
            Dbg.e("CLUSTERLAB", "刷新卡死 " + (now - busySince) + "ms，强制解锁");
            busy = false;
        }
        // 600ms：和迪友抓取周期对齐，减少无谓的 Shizuku/HTTP 往返
        if (busy || now - lastTry < 800) return;
        lastTry = now;
        busy = true;
        busySince = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    {
                        // 胎压：迪加 HTTP（4 个值并发，节流 2 秒）
                        long nowP = android.os.SystemClock.elapsedRealtime();
                        if (nowP - lastPressureAt >= 2000) {
                            lastPressureAt = nowP;
                            // 4 个值并发取：串行 4 次 HTTP 在冷启动时能拖到 1 秒以上
                            final int[] pp = new int[4];
                            Thread[] ts = new Thread[4];
                            final String[] names = {"左前轮气压", "右前轮气压", "左后轮气压", "右后轮气压"};
                            for (int i = 0; i < 4; i++) {
                                final int idx = i;
                                ts[i] = new Thread(new Runnable() {
                                    @Override public void run() { pp[idx] = val(names[idx]); }
                                });
                                ts[i].setDaemon(true);
                                ts[i].start();
                            }
                            for (int i = 0; i < 4; i++) {
                                try { ts[i].join(1200); } catch (Throwable ignored) {}
                            }
                            int p0 = pp[0], p1 = pp[1], p2 = pp[2], p3 = pp[3];
                            if (p0 > 0) pressure[0] = p0;
                            if (p1 > 0) pressure[1] = p1;
                            if (p2 > 0) pressure[2] = p2;
                            if (p3 > 0) pressure[3] = p3;
                        }
                    }

                    pollStats(ctx);
                    // 电耗也走累计口径，不再混用迪加的行程值
                    ok = true;
                    Dbg.e("CLUSTERLAB", "api p=" + pressure[0] + "/" + pressure[1]
                            + "/" + pressure[2] + "/" + pressure[3]
                            + " e=" + String.format(java.util.Locale.US, "%.1f", elecPHM)
                            + " f=" + String.format(java.util.Locale.US, "%.1f", fuelPHM)
                            + " t=" + String.format(java.util.Locale.US, "%.1f", totalPHM)
                            + (estimated ? " (life)" : (" (win " + String.format(java.util.Locale.US, "%.1f", winKm) + "km)")));
                } catch (Throwable t) {
                    lastError = String.valueOf(t);
                    Dbg.e("CLUSTERLAB", "api FAIL " + t);
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    /** 累计寄存器：3s 轮询一次，界面每 1.5s 就能拿到最新值。 */
    /**
     * 迪友的算法：本次行程累计 ÷ 本次里程。
     * 数据来自迪加 /api/tripStatistics 的开放行程（closed=false）。
     */
    private static boolean pollTrip() {
        try {
            String body = http(HOST + "/api/tripStatistics");
            if (body == null || !body.startsWith("[")) return false;
            org.json.JSONArray arr = new org.json.JSONArray(body);
            org.json.JSONObject trip = null;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if (!o.optBoolean("closed", true)) { trip = o; break; }
            }
            if (trip == null && arr.length() > 0) trip = arr.getJSONObject(0);
            if (trip == null) return false;

            double dist = trip.optDouble("distanceKm", 0);
            double fuel = trip.optDouble("fuelDelta", 0);
            double elec = trip.optDouble("electricNetKwh", 0);
            if (dist < 0.1) return false;          // 刚起步/停车：分母不可用，保持上次值

            double f = Math.max(0, fuel) / dist * 100.0;
            double e = Math.max(0, elec) / dist * 100.0;
            // 只记录，不采用：实测与迪友/仪表差距大（见日志对比）
            Dbg.e("CLUSTERLAB-TRIP", "本次行程口径 dist=" + dist + " fuel=" + fuel
                    + " elec=" + elec + " -> e=" + String.format(java.util.Locale.US, "%.1f", e)
                    + " f=" + String.format(java.util.Locale.US, "%.1f", f));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void pollStats(android.content.Context ctx) {
        // 综合油耗 = 油耗(L/100km) + 电耗(kWh/100km) / 3.5，两个量都必须是「一段里程上的平均」。
        // 只用迪加自己记下来的真实数据：vehicle_telemetry.db 的 CAN 分段、/api/currentTrip。
        // v4.9.3：口径统一成「一段里程上的平均」，并把迪加「百公里电耗」彻底踢出主路径。
        // 实测同一次刷新里它会给到 49.3 kWh/100km，而最近 50km 的 CAN 分段是 15.9，
        // 两者来回切换就是用户看到的「综合油耗一直跳」；而且它只是电耗，压根没算油耗。
        // 顺序：1) 最近 50km 分段（稳定、覆盖够）→ 2) 本次行程（里程短时）→ 3) 都取不到就保留上次显示值。
        if (pollFuelDb(ctx)) return;
        if (pollCurrentTrip()) return;
        if (totalPHM < 0) totalPHM = elecPHM / KWH_PER_L + fuelPHM;
        estimated = false;
    }

    /** 本次行程口径：直接读迪加 /api/currentTrip 的真实 delta。 */
    private static boolean pollCurrentTrip() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTripAt < 3000) return tripOk;
        lastTripAt = now;
        try {
            String body = http(HOST + "/api/currentTrip");
            if (body == null) { tripOk = false; return false; }
            org.json.JSONObject o = new org.json.JSONObject(body);
            double dist = o.optDouble("distanceKm", 0);
            if (dist < 0.5) { tripOk = false; return false; }   // 刚起步，分母太小
            double fuel = o.optDouble("fuelDelta", 0);
            double elec = o.optDouble("electricNetKwh", 0);
            fuelPHM = Math.max(0, fuel) / dist * 100.0;
            elecPHM = Math.max(0, elec) / dist * 100.0;
            totalPHM = elecPHM / KWH_PER_L + fuelPHM;
            winKm = dist;
            estimated = false;
            computed = true;
            tripOk = true;
            Dbg.e("CLUSTERLAB-TRIP", "本次行程 " + winKm + "km -> e=" + elecPHM + " f=" + fuelPHM);
            return true;
        } catch (Throwable t) {
            tripOk = false;
            return false;
        }
    }

    /** 开机时把上次的迪友值读回来（迪友本身要十几秒才出数据，这段空窗期用它顶）。 */
    public static synchronized void loadDyCache() {
        if (dyLoaded) return;
        dyLoaded = true;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(DY_FILE));
            try {
                String line = r.readLine();
                if (line != null) {
                    String[] p = line.trim().split(",");
                    if (p.length >= 2) {
                        double e = Double.parseDouble(p[0].trim());
                        double f = Double.parseDouble(p[1].trim());
                        if (e >= 0 && e < 100 && f >= 0 && f < 100) {
                            dyElec = e;
                            dyFuel = f;
                            dyAt = android.os.SystemClock.elapsedRealtime();
                            Dbg.e("CLUSTERLAB", "读回上次迪友值: " + e + "/" + f);
                        }
                    }
                }
            } finally {
                try { r.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** 抓到新值时落盘。 */
    public static void saveDyCache(double e, double f) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(DY_FILE, false);
            try {
                w.write(e + "," + f);
            } finally {
                try { w.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void pollWindow(android.content.Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastSampleAt != 0 && now - lastSampleAt < STATS_MS) return;
        lastSampleAt = now;

        double kwh = valD("总电耗");
        double lit = valD("总燃油消耗");
        double km = valD("里程值");
        if (km <= 0 || kwh < 0 || lit < 0) return;

        curKm = km;
        curKwh = kwh;
        curL = lit;
        lifeE = kwh / km * 100.0;
        lifeF = lit / km * 100.0;

        if (!loaded) {
            load();
            loaded = true;
            if (SAMPLES.isEmpty()) seedFromTelemetry(ctx);
        }

        // 锚点：每走 0.2km 落一条；原地不动时只刷新最后一条，
        // 否则停车吹空调/怠速的耗电会全部算进"每百公里"里，数字直接起飞。
        if (SAMPLES.isEmpty()) {
            SAMPLES.add(new double[]{km, kwh, lit});
            save();
        } else {
            double[] last = SAMPLES.get(SAMPLES.size() - 1);
            double moved = km - last[0];
            if (moved >= 0.2) {
                SAMPLES.add(new double[]{km, kwh, lit});
                prune();
                save();
            } else if (moved < 0.05) {
                last[1] = kwh;
                last[2] = lit;
            }
        }
        recompute();
    }

    private static android.database.sqlite.SQLiteDatabase openTelem(android.content.Context ctx) {
        try {
            return android.database.sqlite.SQLiteDatabase.openDatabase(TELEM_DB, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-TELEM", "直开失败，改用副本: " + t);
        }
        // WAL 库只读打开失败时，把主库+wal+shm 拷到 cache 再开
        try {
            java.io.File cache = ctx.getCacheDir();
            for (String suf : new String[]{"", "-wal", "-shm"}) {
                java.io.File src = new java.io.File(TELEM_DB + suf);
                if (!src.exists()) continue;
                java.io.File dst = new java.io.File(cache, "telem.db" + suf);
                java.io.InputStream in = new java.io.FileInputStream(src);
                java.io.OutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close(); out.close();
            }
            return android.database.sqlite.SQLiteDatabase.openDatabase(
                    new java.io.File(ctx.getCacheDir(), "telem.db").getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-TELEM", "副本也失败: " + t);
            return null;
        }
    }

    /** 用遥测库最近 120km 的分段，把累计寄存器锚点补出来。 */
    /**
     * 油耗：直接用遥测库最近 50km 的分段算，**只算 distance>0 的分段**，
     * 把停车怠速那段（有燃油、没里程）剔掉，否则停车吹空调会把每百公里顶到 28 L。
     */
    private static boolean pollFuelDb(android.content.Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastFuelDbAt < 5000) return fuelDbOk;
        lastFuelDbAt = now;
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            db = openTelem(ctx);
            if (db == null) { fuelDbOk = false; return false; }
            // 先取最近 50km 的行驶分段；不足 20km（历史被怠速大分段切成碎片）
            // 就退到整库——否则只是拿最近 10km 的瞬时快照当"最近油耗"，和车机差一个量级
            double[] r = telemWindow(db, 500);
            String tag = "最近50km";
            if (r == null || r[0] < 200) {
                r = telemWindow(db, -1);
                tag = "整库";
            }
            if (r != null && r[0] >= 10) {
                double dist = r[0];                 // 0.1km
                fuelDbValue = r[1] * 1000.0 / dist;
                elecDbValue = r[2] * 1000.0 / dist;
                // 同步给显示口径（迪友卡片失效后的唯一来源）
                winFuel = fuelDbValue;
                winElec = elecDbValue;
                winAt = android.os.SystemClock.elapsedRealtime();
                fuelPHM = fuelDbValue;
                elecPHM = elecDbValue;
                totalPHM = elecPHM / KWH_PER_L + fuelPHM;
                winKm = dist / 10.0;
                fuelDbOk = true;
                Dbg.e("CLUSTERLAB-TELEM", tag + " 覆盖" + String.format(java.util.Locale.US, "%.1f", winKm)
                        + "km -> 电耗 " + String.format(java.util.Locale.US, "%.1f", elecPHM)
                        + " 油耗 " + String.format(java.util.Locale.US, "%.1f", fuelPHM));
                return true;
            }
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-TELEM", "油耗查询失败: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
            try { if (db != null) db.close(); } catch (Throwable ignored) {}
        }
        fuelDbOk = false;
        return false;
    }

    /** limit>0：最近 limit*0.1km；limit<0：整库。只统计 distance>0 的分段。 */
    private static double[] telemWindow(android.database.sqlite.SQLiteDatabase db, int limit) {
        android.database.Cursor c = null;
        try {
            String where = limit > 0
                    ? "startMileage >= (select max(endMileage) from VehicleSegment) - " + limit + " and distance > 0"
                    : "distance > 0";
            c = db.rawQuery("select sum(distance), sum(fuelDelta), sum(electricNetDelta) "
                    + "from VehicleSegment where " + where, null);
            if (c.moveToFirst() && !c.isNull(0)) {
                return new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2)};
            }
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-TELEM", "窗口查询失败: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean seedFromTelemetry(android.content.Context ctx) {
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            db = openTelem(ctx);
            if (db == null) return false;
            c = db.rawQuery(
                    "select endMileage, endTotalElecCon, endTotalFuelCon from VehicleSegment "
                            + "where endMileage > (select max(endMileage) from VehicleSegment) - 1200 "
                            + "order by endMileage", null);
            int n = 0;
            while (c.moveToNext()) {
                SAMPLES.add(new double[]{c.getDouble(0) / 10.0, c.getDouble(1), c.getDouble(2)});
                n++;
            }
            if (n > 1) {
                prune();
                save();
                recompute();
                Dbg.e("CLUSTERLAB-TELEM", "预热 " + n + " 个锚点，跨度 "
                        + String.format(java.util.Locale.US, "%.1f", winKm) + "km");
                return true;
            }
        } catch (Throwable t) {
            Dbg.e("CLUSTERLAB-TELEM", "预热失败: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
            try { if (db != null) db.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void recompute() {
        if (SAMPLES.isEmpty() || curKm <= 0) return;
        // 锚点里找窗口起点，差值用**当前读数**算 —— 读数一变数字就跟着变
        double[] base = null;
        for (int i = SAMPLES.size() - 1; i >= 0; i--) {
            double[] s = SAMPLES.get(i);
            if (curKm - s[0] <= WINDOW_KM) base = s;
            else break;
        }
        if (base == null) base = SAMPLES.get(0);
        double dk = curKm - base[0];
        winKm = dk;
        if (dk >= MIN_KM) {
            double de = curKwh - base[1];
            double df = curL - base[2];
            if (de < 0) de = 0;      // 净回收
            if (df < 0) df = 0;
            double rawE = de / dk * 100.0;
            double rawF = df / dk * 100.0;
            double w = dk / (dk + PRIOR_KM);      // 窗口权重，随里程从 0 收敛到 1
            elecPHM = rawE * w + lifeE * (1 - w);
            fuelPHM = rawF * w + lifeF * (1 - w);
            estimated = false;
            computed = true;
        } else if (!computed) {
            // 还没跑起来（刚装/刚清历史）：先用生命周期均值顶一下
            elecPHM = lifeE;
            fuelPHM = lifeF;
            estimated = true;
        }
        // dk 不足 0.3km 且已经算过：保持上一次的值，不用停车时的漂移去污染它
        totalPHM = elecPHM / KWH_PER_L + fuelPHM;
    }

    private static void prune() {
        if (SAMPLES.isEmpty()) return;
        double newest = SAMPLES.get(SAMPLES.size() - 1)[0];
        while (SAMPLES.size() > 2 && SAMPLES.get(0)[0] < newest - 120.0) SAMPLES.remove(0);
        while (SAMPLES.size() > 400) SAMPLES.remove(0);
    }

    private static void load() {
        File f = new File(HIST);
        if (!f.exists()) return;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 3) continue;
                SAMPLES.add(new double[]{
                        Double.parseDouble(p[0].trim()),
                        Double.parseDouble(p[1].trim()),
                        Double.parseDouble(p[2].trim())
                });
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        recompute();
    }

    private static void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (double[] s : SAMPLES) {
                sb.append(String.format(java.util.Locale.US, "%.1f,%.1f,%.1f", s[0], s[1], s[2])).append('\n');
            }
            FileWriter w = new FileWriter(HIST);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 直接读迪加参数；∞ / 负数 / 失败都返回 -1。 */
    private static double diplusPHM(String name) {
        try {
            String body = http(HOST + "/api/getVal?name=" + URLEncoder.encode(name, "UTF-8"));
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("success", false)) return -1;
            double v = Double.parseDouble(o.optString("val", "-1"));
            return (Double.isFinite(v) && v >= 0) ? v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int val(String name) {
        try {
            String body = http(HOST + "/api/getVal?name=" + URLEncoder.encode(name, "UTF-8"));
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("success", false)) return -1;
            return (int) Double.parseDouble(o.optString("val", "-1"));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static double valD(String name) {
        try {
            String body = http(HOST + "/api/getVal?name=" + URLEncoder.encode(name, "UTF-8"));
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("success", false)) return -1;
            return Double.parseDouble(o.optString("val", "-1"));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String http(String url) throws Exception {
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
    }
}
