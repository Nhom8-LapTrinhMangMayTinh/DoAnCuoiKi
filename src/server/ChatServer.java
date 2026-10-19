package server;

import common.Config;
import common.CryptoUtil;
import common.Protocol;
import common.TlsUtil;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Diem khoi dong Server: java -jar server.jar [port]. Co the khoi dong nhung (embedded) de kiem thu: ChatServer.start(cfg). */
public class ChatServer {
    public static final ConcurrentHashMap<String, ClientHandler> online = new ConcurrentHashMap<>();
    public static final RoomManager rooms = new RoomManager();
    public static final Guards.LoginGuard loginGuard = new Guards.LoginGuard();
    public static final Guards.LoginGuard ipGuard = new Guards.LoginGuard();
    public static final Guards.SpamGuard spam = new Guards.SpamGuard();
    public static final Guards.SessionStore sessions = new Guards.SessionStore();

    public static volatile File filesDir = new File("data/files");
    public static volatile long maxFileBytes = 1024L * 1024 * 1024; // 1 GB
    public static volatile int readTimeoutMs = 60_000;
    public static volatile long idleTimeoutMs = 30 * 60_000L;       // tu dang xuat neu khong thao tac
    public static volatile long staleSessionMs = 25_000;            // phien khong co du lieu qua lau coi nhu da chet
    public static volatile int minPasswordLen = 6;
    public static volatile long callRingMs = common.Protocol.CALL_RING_SECONDS * 1000L;   // thoi gian do chuong toi da
    public static volatile Set<String> blockedExt = new LinkedHashSet<>(Arrays.asList(
            "exe", "bat", "cmd", "com", "scr", "msi", "vbs", "ps1", "dll", "jar", "js", "sh"));
    public static volatile Set<String> allowedExt = new LinkedHashSet<>();

    private static final UserDAO userDAO = new UserDAO();
    private static final MessageDAO messageDAO = new MessageDAO();
    private static final RoomDAO roomDAO = new RoomDAO();

    private static final ConcurrentHashMap<String, Long> recentClientIds = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Guards.RateLimiter> registerLimiters = new ConcurrentHashMap<>();
    private static final Set<ClientHandler> adminSubs = ConcurrentHashMap.newKeySet();

    private static volatile ServerSocket serverSocket;
    private static volatile ScheduledExecutorService scheduler;
    private static volatile ExecutorService pool;
    private static final AtomicBoolean userListDirty = new AtomicBoolean(false);
    private static volatile int actualPort;
    private static volatile String certFingerprint = "";
    private static volatile boolean tlsOn;

    public static int port() { return actualPort; }
    public static String fingerprint() { return certFingerprint; }
    public static boolean tlsEnabled() { return tlsOn; }

    public static void main(String[] args) throws Exception {
        Properties cfg = Config.load("config/server.properties");
        if (args.length > 0) cfg.setProperty("port", args[0]);
        start(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { userDAO.resetAllOffline(); } catch (Exception ignored) { }
            Log.info("Server dung.");
        }));
        Thread.currentThread().join();
    }

    private static Set<String> csv(String s) {
        Set<String> set = new LinkedHashSet<>();
        for (String x : s.split(",")) { x = x.trim().toLowerCase(); if (!x.isEmpty()) set.add(x); }
        return set;
    }

    /** Khoi dong server theo cau hinh (port=0 -> cong ngau nhien, lay bang port()). Tra ve khi da san sang nhan ket noi. */
    public static synchronized void start(Properties cfg) throws Exception {
        if (serverSocket != null) throw new IllegalStateException("Server dang chay");
        int port = Integer.parseInt(cfg.getProperty("port", "5555"));
        CryptoUtil.setSecret(cfg.getProperty("aes.secret", CryptoUtil.DEFAULT_SECRET));
        filesDir = new File(cfg.getProperty("files.dir", "data/files"));
        maxFileBytes = Long.parseLong(cfg.getProperty("max.file.mb", "1024")) * 1024 * 1024;
        readTimeoutMs = Integer.parseInt(cfg.getProperty("read.timeout.sec", "60")) * 1000;
        idleTimeoutMs = (long) (Double.parseDouble(cfg.getProperty("session.idle.min", "30")) * 60_000);
        sessions.ttlMs = (long) (Double.parseDouble(cfg.getProperty("session.token.min", "15")) * 60_000);
        staleSessionMs = Long.parseLong(cfg.getProperty("session.stale.sec", "25")) * 1000;
        minPasswordLen = Integer.parseInt(cfg.getProperty("password.min.length", "6"));
        callRingMs = Long.parseLong(cfg.getProperty("call.ring.sec", "" + common.Protocol.CALL_RING_SECONDS)) * 1000L;
        blockedExt = csv(cfg.getProperty("file.blocked.ext", "exe,bat,cmd,com,scr,msi,vbs,ps1,dll,jar,js,sh"));
        allowedExt = csv(cfg.getProperty("file.allowed.ext", ""));
        loginGuard.maxFail = Integer.parseInt(cfg.getProperty("login.max.fail", "5"));
        loginGuard.lockMs = Long.parseLong(cfg.getProperty("login.lock.sec", "60")) * 1000;
        loginGuard.clear();
        ipGuard.maxFail = Integer.parseInt(cfg.getProperty("login.ip.max.fail", "40"));
        ipGuard.lockMs = loginGuard.lockMs;
        ipGuard.clear();
        spam.maxMsgs = Integer.parseInt(cfg.getProperty("spam.max.msgs", "10"));
        spam.windowMs = Long.parseLong(cfg.getProperty("spam.window.sec", "5")) * 1000;
        spam.muteMs = Long.parseLong(cfg.getProperty("spam.mute.sec", "30")) * 1000;
        spam.maxRepeat = Integer.parseInt(cfg.getProperty("spam.max.repeat", "4"));

        Log.init(cfg.getProperty("log.file", "data/server.log"));
        filesDir.mkdirs();
        DatabaseManager.init(cfg.getProperty("db.url", "jdbc:sqlite:data/chat.db"));
        userDAO.resetAllOffline();
        bootstrapAdmins(cfg);
        refreshDbStats();

        tlsOn = Boolean.parseBoolean(cfg.getProperty("tls.enabled", "true"));
        if (tlsOn) {
            File ks = new File(cfg.getProperty("tls.keystore", "config/server.p12"));
            String pass = cfg.getProperty("tls.password", "groupchat");
            TlsUtil.ensureKeystore(ks, pass);
            SSLContext ctx = TlsUtil.serverContext(ks, pass);
            certFingerprint = TlsUtil.keystoreFingerprint(ks, pass);
            SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(port);
            ss.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            serverSocket = ss;
        } else {
            serverSocket = new ServerSocket(port);
            certFingerprint = "";
            Log.error("CANH BAO: tls.enabled=false - du lieu (ke ca mat khau) di tren mang KHONG duoc ma hoa kenh truyen!");
        }
        actualPort = serverSocket.getLocalPort();

        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "server-bg");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(ChatServer::sampleAndPush, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(ChatServer::sweepIdle, 5, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(ChatServer::housekeeping, 60, 60, TimeUnit.SECONDS);

        Log.info("=== GroupChat Server dang chay tren cong " + actualPort + (tlsOn ? " (TLS)" : " (KHONG ma hoa)") + " ===");
        if (tlsOn) Log.info("Van tay chung chi SHA-256: " + certFingerprint);
        printAddresses(actualPort);

        final ServerSocket ss = serverSocket;
        final ExecutorService p = pool;
        Thread acceptor = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    p.execute(new ClientHandler(s));
                } catch (SocketException e) {
                    if (ss.isClosed()) break;
                    Log.error("accept loi: " + e);
                } catch (Exception e) { // loi khi accept khong duoc lam sap server
                    Log.error("accept loi: " + e);
                }
            }
        }, "acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Dung server: dong cong, ngat moi phien. Dung trong kiem thu. */
    public static synchronized void stop() {
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        serverSocket = null;
        for (ClientHandler h : new ArrayList<>(online.values())) h.sendAndClose(Protocol.ERROR + "|Server dung");
        if (scheduler != null) scheduler.shutdownNow();
        if (pool != null) pool.shutdownNow();
        online.clear();
        adminSubs.clear();
        sessions.clearAll();
        Calls.clearAll();
        Stats.callsActive.set(0);
        try { userDAO.resetAllOffline(); } catch (Exception ignored) { }
    }

    /** Tao tai khoan quan tri dau tien neu chua co (mat khau ngau nhien in ra 1 lan + luu file), nang quyen cac tai khoan trong admin.users. */
    private static void bootstrapAdmins(Properties cfg) throws Exception {
        for (String name : csv(cfg.getProperty("admin.users", "admin"))) {
            Models.User u = userDAO.find(name);
            if (u == null) {
                String pw = cfg.getProperty("admin.password", "");
                boolean generated = pw.isEmpty();
                if (generated) pw = CryptoUtil.randomToken().substring(0, 12);
                if (userDAO.register(name, pw, Protocol.SROLE_ADMIN) > 0) {
                    Log.info("Da tao tai khoan quan tri '" + name + "'" + (generated ? " voi mat khau ngau nhien: " + pw : " voi mat khau trong cau hinh"));
                    if (generated) {
                        File f = new File(cfg.getProperty("admin.credentials.file", "data/admin-credentials.txt"));
                        if (f.getParentFile() != null) f.getParentFile().mkdirs();
                        Files.write(f.toPath(), ("username=" + name + "\npassword=" + pw + "\n(Hay doi mat khau sau khi dang nhap va xoa file nay)\n").getBytes(StandardCharsets.UTF_8));
                        Log.info("Thong tin dang nhap quan tri da luu tai " + f.getPath());
                    }
                }
            } else if (!u.admin()) {
                userDAO.setServerRole(u.id, Protocol.SROLE_ADMIN);
                Log.info("Da nang quyen quan tri cho '" + name + "'");
            }
        }
    }

    private static void printAddresses(int port) {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(nis)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        Log.info("  Client co the ket noi toi: " + a.getHostAddress() + ":" + port + "  (" + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ gui danh sach nguoi dung
    private static String buildUserList() throws Exception {
        List<Models.User> users = userDAO.listUsers(2000);
        users.sort((a, b) -> {
            boolean oa = online.containsKey(a.username), ob = online.containsKey(b.username);
            return oa != ob ? (oa ? -1 : 1) : a.username.compareToIgnoreCase(b.username);
        });
        StringBuilder sb = new StringBuilder(Protocol.USER_LIST);
        int n = 0;
        for (Models.User u : users) {
            if (n++ >= 300) break; // gioi han 1 frame
            sb.append('|').append(u.username).append(',').append(online.containsKey(u.username) ? 1 : 0).append(',')
              .append(u.lastSeen).append(',').append(Protocol.enc(u.displayName)).append(',').append(u.avatarVer);
        }
        return sb.toString();
    }

    public static void sendUserListTo(ClientHandler h) {
        try { h.send(buildUserList()); } catch (Exception e) { Log.error("sendUserListTo: " + e); }
    }

    /**
     * Gui danh sach TAT CA user kem trang thai online/offline cho moi client dang online.
     * Gop nhieu yeu cau lien tiep (vd 50 client dang nhap cung luc) thanh 1 lan gui sau ~150ms de khong bao hoa Server.
     */
    public static void broadcastUserList() {
        if (!userListDirty.compareAndSet(false, true)) return;
        ScheduledExecutorService s = scheduler;
        Runnable job = () -> {
            userListDirty.set(false);
            try {
                String frame = buildUserList();
                for (ClientHandler h : online.values()) h.send(frame);
            } catch (Exception e) {
                Log.error("broadcastUserList: " + e);
            }
        };
        if (s == null || s.isShutdown()) job.run(); else s.schedule(job, 150, TimeUnit.MILLISECONDS);
    }

    /** Hen gio chay 1 viec tren luong nen cua server (vd het gio do chuong). */
    static ScheduledFuture<?> schedule(Runnable r, long delayMs) {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return null;
        return s.schedule(r, delayMs, TimeUnit.MILLISECONDS);
    }

    public static void broadcastAll(String frame) {
        for (ClientHandler h : online.values()) h.send(frame);
    }

    // ------------------------------------------------------------------ nen: thong ke, het phien
    public static void setAdminSubscription(ClientHandler h, boolean on) {
        if (on) adminSubs.add(h); else adminSubs.remove(h);
    }

    private static int tickCount = 0;

    private static void sampleAndPush() {
        long t0 = System.nanoTime();
        try {
            Stats.tick();
            if (++tickCount % 5 == 0) refreshDbStats();       // truy van CSDL moi 5 giay, khong phai moi giay
            if (!adminSubs.isEmpty()) {
                String frame = Protocol.ADMIN_STATS_DATA + "|" + Stats.snapshot();
                for (ClientHandler h : adminSubs) {
                    if (h.isClosed() || !h.isAdmin()) adminSubs.remove(h); else h.send(frame); // send() chi dua vao hang doi
                }
            }
        } catch (Exception e) {
            Log.error("sampleAndPush: " + e);
        } finally {
            Stats.lastSampleNanos = System.nanoTime() - t0;
        }
    }

    private static void refreshDbStats() {
        try {
            Stats.dbMessages = messageDAO.countMessages();
            Stats.dbFiles = messageDAO.countFiles();
            Stats.dbFileBytes = messageDAO.sumFileBytes();
            Stats.dbRooms = roomDAO.countRooms();
            Stats.dbUsers = userDAO.countUsers();
        } catch (Exception e) {
            Log.error("refreshDbStats: " + e);
        }
    }

    /** Tu dong dang xuat phien khong co thao tac nao qua han idleTimeoutMs. */
    private static void sweepIdle() {
        long now = System.currentTimeMillis();
        for (ClientHandler h : online.values()) {
            if (now - h.lastActivity() > idleTimeoutMs) {
                Audit.log(h.username(), "SESSION_IDLE_LOGOUT", h.username(), "khong thao tac " + (idleTimeoutMs / 1000) + "s", h.ip());
                sessions.revokeUser(h.username());
                h.sendAndClose(Protocol.SESSION_EXPIRED + "|Phiên đăng nhập đã hết hạn do không hoạt động, vui lòng đăng nhập lại");
            }
        }
    }

    private static void housekeeping() {
        long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        recentClientIds.values().removeIf(t -> t < cutoff);
        if (registerLimiters.size() > 5000) registerLimiters.clear();
    }

    /** Ghi nho clientId tin nhan. true = chua gap (tin moi); false = trung (client gui lai sau khi mat mang). */
    static boolean rememberClientId(String key) {
        return recentClientIds.putIfAbsent(key, System.currentTimeMillis()) == null;
    }

    static Guards.RateLimiter registerLimiter(String ip) {
        return registerLimiters.computeIfAbsent(ip, k -> new Guards.RateLimiter(Integer.getInteger("gc.register.limit", 30), 60_000));
    }
}
