package server;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * So lieu giam sat Server. Cac bo dem la LongAdder/Atomic nen cap nhat tu moi luong xu ly khong khoa nhau;
 * mot luong lay mau rieng (ChatServer) tong hop moi giay roi day cho cac Admin dang dang ky -> khong lam nghen Server.
 */
@SuppressWarnings("deprecation")
public final class Stats {
    private Stats() {}

    public static final long START = System.currentTimeMillis();
    public static final LongAdder messages = new LongAdder();        // tin nhan tu luc server chay
    public static final LongAdder loginsOk = new LongAdder();
    public static final LongAdder loginsFail = new LongAdder();
    public static final LongAdder spamBlocked = new LongAdder();
    public static final LongAdder errors = new LongAdder();
    public static final LongAdder callsTotal = new LongAdder();
    public static final LongAdder callBytes = new LongAdder();
    public static final AtomicLong callsActive = new AtomicLong();
    public static final LongAdder filesUploaded = new LongAdder();
    public static final LongAdder filesDownloaded = new LongAdder();
    public static final LongAdder bytesUp = new LongAdder();         // byte file nhan tu client
    public static final LongAdder bytesDown = new LongAdder();       // byte file gui cho client
    public static final AtomicLong tcpConnections = new AtomicLong();

    // so lieu do luong lay mau dinh ky ghi vao day
    public static volatile long dbMessages, dbFiles, dbFileBytes, dbRooms, dbUsers;
    public static volatile double msgPerMin;
    public static volatile long lastSampleNanos;   // thoi gian 1 lan lay mau (de chung minh khong nghen)
    private static long lastMsgCount;
    private static long lastTick = System.currentTimeMillis();

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /** Tinh msg/phut theo trung binh truot (goi moi giay boi luong lay mau). */
    public static synchronized void tick() {
        long now = System.currentTimeMillis();
        long cur = messages.sum();
        double perSec = (cur - lastMsgCount) * 1000.0 / Math.max(1, now - lastTick);
        msgPerMin = msgPerMin * 0.7 + perSec * 60 * 0.3;
        lastMsgCount = cur;
        lastTick = now;
    }

    public static double cpuProcess() {
        double d = OS.getProcessCpuLoad();
        return d < 0 ? 0 : d * 100;
    }

    public static double cpuSystem() {
        double d = OS.getSystemCpuLoad();
        return d < 0 ? 0 : d * 100;
    }

    /** Chuoi k=v;k=v;... gui cho dashboard. */
    public static String snapshot() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int rooms = 0;
        try { rooms = ChatServer.rooms.activeRoomCount(); } catch (Exception ignored) { }
        StringBuilder sb = new StringBuilder();
        kv(sb, "uptime", (System.currentTimeMillis() - START) / 1000);
        kv(sb, "online", ChatServer.online.size());
        kv(sb, "tcp", tcpConnections.get());
        kv(sb, "rooms", dbRooms);
        kv(sb, "roomsActive", rooms);
        kv(sb, "users", dbUsers);
        kv(sb, "cpuProc", String.format(java.util.Locale.ROOT, "%.1f", cpuProcess()));
        kv(sb, "cpuSys", String.format(java.util.Locale.ROOT, "%.1f", cpuSystem()));
        kv(sb, "heapUsedMB", used >> 20);
        kv(sb, "heapMaxMB", rt.maxMemory() >> 20);
        kv(sb, "threads", Thread.activeCount());
        kv(sb, "msgTotal", dbMessages);
        kv(sb, "msgSession", messages.sum());
        kv(sb, "msgPerMin", String.format(java.util.Locale.ROOT, "%.1f", msgPerMin));
        kv(sb, "files", dbFiles);
        kv(sb, "fileBytes", dbFileBytes);
        kv(sb, "bytesUp", bytesUp.sum());
        kv(sb, "bytesDown", bytesDown.sum());
        kv(sb, "loginOk", loginsOk.sum());
        kv(sb, "loginFail", loginsFail.sum());
        kv(sb, "spam", spamBlocked.sum());
        kv(sb, "errors", errors.sum());
        kv(sb, "callsActive", callsActive.get());
        kv(sb, "callsTotal", callsTotal.sum());
        kv(sb, "callBytes", callBytes.sum());
        kv(sb, "dbMB", String.format(java.util.Locale.ROOT, "%.2f", DatabaseManager.dbSizeBytes() / 1048576.0));
        kv(sb, "sampleMs", String.format(java.util.Locale.ROOT, "%.2f", lastSampleNanos / 1e6));
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, Object v) {
        if (sb.length() > 0) sb.append(';');
        sb.append(k).append('=').append(v);
    }
}
