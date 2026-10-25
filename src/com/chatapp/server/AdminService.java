package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;
import com.chatapp.security.PasswordHasher;
import com.chatapp.security.PasswordPolicy;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Server-side authorization, administration, metrics, audit logging and anti-spam. */
public final class AdminService {
    private static final AtomicLong loginSuccess = new AtomicLong();
    private static final AtomicLong loginFailure = new AtomicLong();
    private static final AtomicLong blockedSpam = new AtomicLong();
    private static final AtomicLong serverErrors = new AtomicLong();
    private static final AtomicLong bytesIn = new AtomicLong();
    private static final AtomicLong bytesOut = new AtomicLong();
    private static final Set<ClientHandler> metricSubscribers = ConcurrentHashMap.newKeySet();
    private static final Map<String, SpamTracker> spamTrackers = new ConcurrentHashMap<>();
    private static ScheduledExecutorService metricsExecutor;

    private AdminService() { }

    /**
     * Khởi tạo tài khoản admin một lần. Nếu tài khoản admin đã có từ DB cũ,
     * lần triển khai Dashboard đầu tiên sẽ đặt mật khẩu ban đầu là 123; những
     * lần khởi động sau giữ nguyên mật khẩu do quản trị viên chủ động đổi.
     */
    public static void bootstrapAdmin() {
        final String marker = "admin_credentials_bootstrapped_v1";
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureColumn(conn, "is_admin", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "admin_locked", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "failed_attempts", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "locked_until", "INTEGER NOT NULL DEFAULT 0");
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS server_settings ("
                        + "setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
            }

            boolean bootstrapped = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT setting_value FROM server_settings WHERE setting_key=?")) {
                ps.setString(1, marker);
                try (ResultSet rs = ps.executeQuery()) {
                    bootstrapped = rs.next() && "1".equals(rs.getString(1));
                }
            }

            boolean exists = false;
            boolean alreadyAdmin = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT is_admin FROM users WHERE username COLLATE BINARY='admin' COLLATE BINARY LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    alreadyAdmin = rs.getInt(1) == 1;
                }
            }

            if (!exists) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users(username,password,is_admin,admin_locked,failed_attempts,locked_until) "
                        + "VALUES('admin', ?, 1, 0, 0, 0)")) {
                    ps.setString(1, PasswordHasher.hash("123"));
                    ps.executeUpdate();
                }
                audit((ClientHandler) null, "ADMIN_BOOTSTRAP", "USER", "admin",
                        "Tài khoản quản trị mặc định được tạo.", "");
                System.out.println("[Admin] Đã tạo tài khoản mặc định: admin / 123");
            } else if (!alreadyAdmin || !bootstrapped) {
                // Chỉ đặt mật khẩu khởi tạo ở lần nâng cấp đầu tiên; không reset mỗi lần server chạy.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET password=?, is_admin=1, admin_locked=0, failed_attempts=0, locked_until=0 "
                        + "WHERE username COLLATE BINARY='admin' COLLATE BINARY")) {
                    ps.setString(1, PasswordHasher.hash("123"));
                    ps.executeUpdate();
                }
                audit((ClientHandler) null, "ADMIN_BOOTSTRAP", "USER", "admin",
                        !alreadyAdmin ? "Tài khoản admin hiện có được nâng quyền." : "Thiết lập mật khẩu khởi tạo cho admin.", "");
                System.out.println("[Admin] Tài khoản admin đã được khởi tạo/nâng quyền với mật khẩu ban đầu.");
            } else {
                System.out.println("[Admin] Tài khoản quản trị admin đã sẵn sàng.");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO server_settings(setting_key,setting_value) VALUES(?, '1')")) {
                ps.setString(1, marker);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[Admin] Không thể khởi tạo tài khoản admin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void startMetricsPublisher() {
        if (metricsExecutor != null) return;
        metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChatAdminMetrics");
            t.setDaemon(true);
            return t;
        });
        metricsExecutor.scheduleAtFixedRate(() -> {
            try {
                if (metricSubscribers.isEmpty()) return;
                String packet = createMetricsPacket();
                for (ClientHandler client : metricSubscribers) {
                    if (client == null || client.getCurrentUsername() == null || !isAdmin(client.getCurrentUsername())) {
                        metricSubscribers.remove(client);
                    } else {
                        client.sendMessage(packet);
                    }
                }
            } catch (Throwable e) {
                recordError();
                System.err.println("[Admin] Lỗi đẩy metrics: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public static void stopMetricsPublisher() {
        ScheduledExecutorService executor = metricsExecutor;
        metricsExecutor = null;
        if (executor != null) executor.shutdownNow();
        metricSubscribers.clear();
    }

    public static void handle(ClientHandler client, String[] p) {
        if (p == null || p.length == 0) return;
        String command = p[0];
        if (Protocol.ADMIN_UNSUBSCRIBE.equals(command)) {
            metricSubscribers.remove(client);
            return;
        }
        if (!client.requireLogin() || !isAdmin(client.getCurrentUsername())) {
            sendResult(client, false, "Bạn không có quyền quản trị viên.");
            return;
        }
        try {
            switch (command) {
                case Protocol.ADMIN_SUBSCRIBE:
                    metricSubscribers.add(client);
                    client.sendMessage(createMetricsPacket());
                    break;
                case Protocol.ADMIN_LIST_USERS:
                    sendUsers(client);
                    break;
                case Protocol.ADMIN_LIST_ROOMS:
                    sendRooms(client);
                    break;
                case Protocol.ADMIN_GET_ROOM_MEMBERS:
                    if (p.length < 2) { sendResult(client, false, "Thiếu ID phòng."); break; }
                    sendRoomMembers(client, parseRoomId(p[1]));
                    break;
                case Protocol.ADMIN_USER_ACTION:
                    handleUserAction(client, p);
                    break;
                case Protocol.ADMIN_ROOM_ACTION:
                    handleRoomAction(client, p);
                    break;
                case Protocol.ADMIN_LOGS_REQUEST:
                    handleLogs(client, p);
                    break;
                default:
                    sendResult(client, false, "Lệnh quản trị không được hỗ trợ.");
            }
        } catch (Exception e) {
            recordError();
            System.err.println("[Admin] Lỗi lệnh " + command + ": " + e.getMessage());
            sendResult(client, false, "Không thể hoàn tất thao tác quản trị.");
        }
    }

    public static boolean isAdmin(String username) {
        if (username == null || username.isBlank()) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin, admin_locked FROM users WHERE username COLLATE BINARY=? COLLATE BINARY LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("is_admin") == 1 && rs.getInt("admin_locked") == 0;
            }
        } catch (Exception e) {
            recordError();
            return false;
        }
    }

    private static boolean isAdminAccount(String username) {
        if (username == null || username.isBlank()) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE username COLLATE BINARY=? COLLATE BINARY LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) == 1; }
        } catch (Exception e) { recordError(); return false; }
    }

    public static void onClientDisconnected(ClientHandler client) {
        metricSubscribers.remove(client);
    }

    public static void recordInbound(long count) { if (count > 0) bytesIn.addAndGet(count); }
    public static void recordOutbound(long count) { if (count > 0) bytesOut.addAndGet(count); }
    public static void recordError() { serverErrors.incrementAndGet(); }

    public static void recordLoginSuccess(String username, ClientHandler client) {
        loginSuccess.incrementAndGet();
        audit(client, "LOGIN_SUCCESS", "USER", username, "Đăng nhập thành công.", remote(client));
    }

    public static void recordLoginFailure(String details, ClientHandler client) {
        loginFailure.incrementAndGet();
        audit(client, "LOGIN_FAILED", "USER", "", details, remote(client));
    }

    /** Returns true when a text message must be rejected due to rate/duplicate limits. */
    public static boolean allowMessage(ClientHandler client, String encryptedText) {
        if (client == null || client.getCurrentUsername() == null) return false;
        String username = client.getCurrentUsername();
        long now = System.currentTimeMillis();
        SpamTracker tracker = spamTrackers.computeIfAbsent(username, k -> new SpamTracker());
        String plain = AESUtils.decrypt(encryptedText == null ? "" : encryptedText);
        String normalized = (plain == null || plain.isBlank() ? encryptedText : plain)
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > Protocol.MAX_TEXT_CHARS) normalized = normalized.substring(0, Protocol.MAX_TEXT_CHARS);
        synchronized (tracker) {
            if (tracker.restrictedUntil > now) {
                long remaining = Math.max(1, (tracker.restrictedUntil - now + 999) / 1000);
                client.sendError("Bạn đang bị hạn chế gửi tin do spam. Thử lại sau " + remaining + " giây.");
                return false;
            }
            if (tracker.restrictedUntil != 0) tracker.restrictedUntil = 0;
            while (!tracker.recent.isEmpty() && now - tracker.recent.peekFirst().time > 5000L) {
                tracker.recent.removeFirst();
            }
            int duplicateCount = 0;
            for (SpamEntry entry : tracker.recent) {
                if (entry.content.equals(normalized)) duplicateCount++;
            }
            boolean tooMany = tracker.recent.size() >= 10; // Reject the 11th message in a 5-second window.
            boolean duplicates = duplicateCount >= 4;      // Reject the 5th repeat in that window.
            if (tooMany || duplicates) {
                tracker.restrictedUntil = now + 30_000L;
                blockedSpam.incrementAndGet();
                String reason = tooMany
                        ? "Vượt quá 10 tin nhắn trong 5 giây."
                        : "Lặp cùng một nội dung quá 4 lần.";
                audit(client, "SPAM_BLOCKED", "USER", username, reason + " Hạn chế 30 giây.", remote(client));
                client.sendError("Phát hiện spam: " + reason + " Bạn bị hạn chế gửi tin trong 30 giây.");
                return false;
            }
            tracker.recent.addLast(new SpamEntry(now, normalized));
            return true;
        }
    }

    private static String createMetricsPacket() {
        long now = System.currentTimeMillis();
        long online = ChatServer.onlineClients.size();
        long tcp = Math.max(0, ChatServer.tcpConnections.get());
        long rooms = scalarLong("SELECT COUNT(*) FROM rooms");
        double cpu = cpuLoad();
        Runtime runtime = Runtime.getRuntime();
        long ramUsed = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        long ramMax = runtime.maxMemory();
        long threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long messages = scalarLong("SELECT COUNT(*) FROM messages");
        long perMinute = scalarLong("SELECT COUNT(*) FROM messages WHERE datetime(timestamp) >= datetime('now','-1 minute')");
        long files = scalarLong("SELECT COUNT(*) FROM chat_files");
        long fileBytes = scalarLong("SELECT COALESCE(SUM(file_size),0) FROM chat_files");
        long dbBytes = databaseSizeBytes();
        // Count call sessions rather than every join/leave audit event. 1:1 call outcomes
        // are stored as TYPE_CALL chat-history rows; group sessions have one GROUP_CALL_STARTED audit row.
        long oneToOneCalls = tableExists("calls")
                ? scalarLong("SELECT COUNT(*) FROM calls")
                : scalarLong("SELECT COUNT(*) FROM messages WHERE msg_type='CALL'");
        long groupCalls = scalarLong("SELECT COUNT(*) FROM audit_logs WHERE action='GROUP_CALL_STARTED'");
        long calls = oneToOneCalls + groupCalls;
        return Protocol.join(Protocol.ADMIN_METRICS, now, online, tcp, rooms,
                String.format(Locale.ROOT, "%.4f", cpu), ramUsed, ramMax, threads,
                messages, perMinute, files, fileBytes, loginSuccess.get(), loginFailure.get(),
                blockedSpam.get(), serverErrors.get(), dbBytes, calls, bytesIn.get(), bytesOut.get());
    }

    private static double cpuLoad() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad();
                return load < 0 ? -1 : Math.min(1, load);
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    private static long databaseSizeBytes() {
        long size = 0;
        String[] names = {"data/chat.db", "data/chat.db-wal", "data/chat.db-shm"};
        for (String name : names) {
            try { size += Files.exists(Path.of(name)) ? Files.size(Path.of(name)) : 0; }
            catch (Exception ignored) { }
        }
        return size;
    }

    private static long scalarLong(String sql) {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception ignored) { return 0L; }
    }

    private static boolean tableExists(String name) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception ignored) { return false; }
    }

    private static void sendUsers(ClientHandler client) throws SQLException {
        StringBuilder text = new StringBuilder();
        String sql = "SELECT username, COALESCE(public_id,''), is_admin, admin_locked, failed_attempts, locked_until " +
                "FROM users ORDER BY username COLLATE BINARY";
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                if (text.length() > 0) text.append('\n');
                String name = rs.getString(1);
                ClientHandler online = ChatServer.onlineClients.get(name);
                boolean locked = rs.getInt("admin_locked") == 1 || rs.getLong("locked_until") > System.currentTimeMillis();
                text.append(tsv(name)).append('\t').append(tsv(rs.getString(2))).append('\t')
                        .append(online != null ? "1" : "0").append('\t').append(locked ? "1" : "0").append('\t')
                        .append(rs.getInt("is_admin")).append('\t').append(rs.getInt("failed_attempts")).append('\t')
                        .append(rs.getLong("locked_until")).append('\t').append(tsv(spamStatus(name)));
            }
        }
        client.sendMessage(Protocol.join(Protocol.ADMIN_USERS, encode(text.toString())));
    }

    private static String spamStatus(String username) {
        SpamTracker tracker = spamTrackers.get(username == null ? "" : username);
        if (tracker == null) return "Không";
        synchronized (tracker) {
            long left = tracker.restrictedUntil - System.currentTimeMillis();
            return left > 0 ? "Còn " + Math.max(1, (left + 999) / 1000) + " giây" : "Không";
        }
    }

    private static void sendRooms(ClientHandler client) throws SQLException {
        StringBuilder text = new StringBuilder();
        String sql = "SELECT r.id, COALESCE(r.public_id,''), r.room_name, r.owner, " +
                "(SELECT COUNT(*) FROM room_members rm WHERE rm.room_id=r.id) AS member_count, r.created_at " +
                "FROM rooms r ORDER BY r.room_name COLLATE NOCASE";
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                if (text.length() > 0) text.append('\n');
                text.append(rs.getInt(1)).append('\t').append(tsv(rs.getString(2))).append('\t')
                        .append(tsv(rs.getString(3))).append('\t').append(tsv(rs.getString(4))).append('\t')
                        .append(rs.getInt(5)).append('\t').append(tsv(rs.getString(6)));
            }
        }
        client.sendMessage(Protocol.join(Protocol.ADMIN_ROOMS, encode(text.toString())));
    }

    private static void sendRoomMembers(ClientHandler client, int roomId) throws SQLException {
        StringBuilder text = new StringBuilder();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT username, role FROM room_members WHERE room_id=? ORDER BY role, username COLLATE BINARY")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (text.length() > 0) text.append('\n');
                    String username = rs.getString(1);
                    text.append(tsv(username)).append('\t').append(tsv(rs.getString(2))).append('\t')
                            .append(ChatServer.onlineClients.containsKey(username) ? "1" : "0");
                }
            }
        }
        client.sendMessage(Protocol.join(Protocol.ADMIN_ROOM_MEMBERS, roomId, encode(text.toString())));
    }

    private static void handleUserAction(ClientHandler client, String[] p) throws Exception {
        if (p.length < 3) { sendResult(client, false, "Tham số tài khoản không hợp lệ."); return; }
        String action = p[1];
        String target = decode(p[2]).trim();
        String argument = p.length > 3 ? decode(p[3]) : "";
        if (target.isBlank()) { sendResult(client, false, "Chưa chọn tài khoản."); return; }
        if (action.equals("LOCK")) {
            if (target.equals(client.getCurrentUsername())) {
                sendResult(client, false, "Không thể khóa chính tài khoản đang quản trị."); return;
            }
            if (isAdminAccount(target)) {
                sendResult(client, false, "Không được khóa tài khoản Admin khác."); return;
            }
            try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET admin_locked=1 WHERE username COLLATE BINARY=? COLLATE BINARY AND is_admin=0")) {
                ps.setString(1, target);
                if (ps.executeUpdate() == 0) { sendResult(client, false, "Không tìm thấy tài khoản thường để khóa."); return; }
            }
            ClientHandler online = findOnline(target);
            if (online != null) online.forceDisconnect("Tài khoản đã bị quản trị viên khóa.");
            audit(client, "LOCK_USER", "USER", target, "Khóa tài khoản.", remote(client));
            sendResult(client, true, "Đã khóa tài khoản " + target + ".");
        } else if (action.equals("UNLOCK")) {
            try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET admin_locked=0, failed_attempts=0, locked_until=0 WHERE username COLLATE BINARY=? COLLATE BINARY")) {
                ps.setString(1, target);
                if (ps.executeUpdate() == 0) { sendResult(client, false, "Không tìm thấy tài khoản."); return; }
            }
            audit(client, "UNLOCK_USER", "USER", target, "Mở khóa tài khoản.", remote(client));
            sendResult(client, true, "Đã mở khóa tài khoản " + target + ".");
        } else if (action.equals("DISCONNECT")) {
            if (target.equals(client.getCurrentUsername())) { sendResult(client, false, "Không thể tự ngắt kết nối từ Dashboard."); return; }
            if (isAdminAccount(target)) { sendResult(client, false, "Không thể ngắt kết nối tài khoản Admin khác."); return; }
            ClientHandler online = findOnline(target);
            if (online == null) { sendResult(client, false, "Tài khoản hiện không online."); return; }
            online.forceDisconnect("Kết nối đã bị quản trị viên ngắt.");
            audit(client, "DISCONNECT_USER", "USER", target, "Ngắt kết nối đang hoạt động.", remote(client));
            sendResult(client, true, "Đã ngắt kết nối " + target + ".");
        } else if (action.equals("RESET_PASSWORD")) {
            String error = PasswordPolicy.passwordError(argument, target);
            if (error != null) { sendResult(client, false, error); return; }
            if (isAdminAccount(target) && !target.equals(client.getCurrentUsername())) {
                sendResult(client, false, "Không được đặt lại mật khẩu Admin khác."); return;
            }
            try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password=?, failed_attempts=0, locked_until=0 WHERE username COLLATE BINARY=? COLLATE BINARY")) {
                ps.setString(1, PasswordHasher.hash(argument)); ps.setString(2, target);
                if (ps.executeUpdate() == 0) { sendResult(client, false, "Không tìm thấy tài khoản."); return; }
            }
            audit(client, "RESET_PASSWORD", "USER", target, "Đặt lại mật khẩu từ Dashboard.", remote(client));
            sendResult(client, true, "Đã đặt lại mật khẩu tài khoản " + target + ".");
        } else if (action.equals("CLEAR_SPAM")) {
            spamTrackers.remove(target);
            audit(client, "CLEAR_SPAM_RESTRICTION", "USER", target, "Gỡ hạn chế chống spam.", remote(client));
            sendResult(client, true, "Đã gỡ hạn chế spam cho " + target + ".");
        } else {
            sendResult(client, false, "Thao tác tài khoản không hợp lệ.");
            return;
        }
        sendUsers(client);
    }

    private static void handleRoomAction(ClientHandler client, String[] p) throws Exception {
        if (p.length < 3) { sendResult(client, false, "Tham số phòng không hợp lệ."); return; }
        String action = p[1];
        int roomId = parseRoomId(p[2]);
        String target = p.length > 3 ? decode(p[3]).trim() : "";
        String role = p.length > 4 ? decode(p[4]).trim().toUpperCase(Locale.ROOT) : "";
        String roomName;
        String owner;
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT room_name, owner FROM rooms WHERE id=?")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { sendResult(client, false, "Phòng không còn tồn tại."); return; }
                roomName = rs.getString(1); owner = rs.getString(2);
            }
        }

        if (action.equals("SET_ROLE")) {
            if (!role.equals("ADMIN") && !role.equals("MEMBER")) { sendResult(client, false, "Vai trò phải là ADMIN hoặc MEMBER; dùng chuyển chủ phòng để đặt OWNER."); return; }
            if (target.equals(owner)) { sendResult(client, false, "Hãy dùng chức năng Chuyển chủ phòng để đổi vai trò chủ sở hữu."); return; }
            try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE room_members SET role=? WHERE room_id=? AND username COLLATE BINARY=? COLLATE BINARY")) {
                ps.setString(1, role); ps.setInt(2, roomId); ps.setString(3, target);
                if (ps.executeUpdate() == 0) { sendResult(client, false, "Tài khoản không phải thành viên của phòng."); return; }
            }
            audit(client, "SET_ROOM_ROLE", "ROOM", String.valueOf(roomId), target + " => " + role, remote(client));
            sendResult(client, true, "Đã cập nhật vai trò của " + target + ".");
            broadcastRoomMembersChanged(roomId);
            broadcastRoomsChanged();
        } else if (action.equals("TRANSFER_OWNER")) {
            if (target.isBlank()) { sendResult(client, false, "Chưa chọn chủ phòng mới."); return; }
            try (Connection conn = DatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE username COLLATE BINARY=? COLLATE BINARY")) {
                        ps.setString(1, target);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) { conn.rollback(); sendResult(client, false, "Tài khoản chủ mới không tồn tại."); return; }
                        }
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE room_members SET role='MEMBER' WHERE room_id=? AND role='OWNER'")) {
                        ps.setInt(1, roomId); ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT OR IGNORE INTO room_members(room_id,username,role) VALUES(?,?,'OWNER')")) {
                        ps.setInt(1, roomId); ps.setString(2, target); ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE room_members SET role='OWNER' WHERE room_id=? AND username COLLATE BINARY=? COLLATE BINARY")) {
                        ps.setInt(1, roomId); ps.setString(2, target); ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE rooms SET owner=? WHERE id=?")) {
                        ps.setString(1, target); ps.setInt(2, roomId); ps.executeUpdate();
                    }
                    conn.commit();
                } catch (Exception e) { conn.rollback(); throw e; }
            }
            audit(client, "TRANSFER_ROOM_OWNER", "ROOM", String.valueOf(roomId), owner + " => " + target, remote(client));
            sendResult(client, true, "Đã chuyển chủ phòng " + roomName + " cho " + target + ".");
            broadcastRoomMembersChanged(roomId);
            broadcastRoomsChanged();
        } else if (action.equals("KICK_MEMBER")) {
            if (target.equals(owner)) { sendResult(client, false, "Không thể xóa chủ phòng. Hãy chuyển chủ phòng trước."); return; }
            try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM room_members WHERE room_id=? AND username COLLATE BINARY=? COLLATE BINARY")) {
                ps.setInt(1, roomId); ps.setString(2, target);
                if (ps.executeUpdate() == 0) { sendResult(client, false, "Tài khoản không phải thành viên của phòng."); return; }
            }
            java.util.concurrent.CopyOnWriteArrayList<ClientHandler> members = ChatServer.roomMembers.get(roomId);
            if (members != null) {
                for (ClientHandler member : members) {
                    if (member.getCurrentUsername() != null && member.getCurrentUsername().equals(target)) {
                        members.remove(member);
                        member.sendMessage(Protocol.join(Protocol.KICKED, roomId));
                    }
                }
            }
            audit(client, "KICK_ROOM_MEMBER", "ROOM", String.valueOf(roomId), "Đã xóa thành viên " + target, remote(client));
            sendResult(client, true, "Đã xóa " + target + " khỏi phòng.");
            broadcastRoomMembersChanged(roomId);
            broadcastRoomsChanged();
        } else if (action.equals("DELETE_ROOM")) {
            String encodedRoomName = Base64.getEncoder().encodeToString(roomName.getBytes(StandardCharsets.UTF_8));
            List<Path> roomFilesToDelete = new ArrayList<>();
            try (Connection conn = DatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE room_id=?")) { ps.setInt(1, roomId); ps.executeUpdate(); }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM read_states WHERE conversation_type='G' AND conversation_key=?")) { ps.setString(1, String.valueOf(roomId)); ps.executeUpdate(); }
                    if (tableExists(conn, "chat_files")) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT COALESCE(NULLIF(file_path,''), NULLIF(stored_path,''), '') FROM chat_files WHERE room_id=?")) {
                            ps.setInt(1, roomId);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    Path safePath = resolveSafeStoragePath(rs.getString(1));
                                    if (safePath != null) roomFilesToDelete.add(safePath);
                                }
                            }
                        }
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chat_files WHERE room_id=?")) { ps.setInt(1, roomId); ps.executeUpdate(); }
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM room_members WHERE room_id=?")) { ps.setInt(1, roomId); ps.executeUpdate(); }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rooms WHERE id=?")) { ps.setInt(1, roomId); ps.executeUpdate(); }
                    conn.commit();
                } catch (Exception e) { conn.rollback(); throw e; }
            }
            for (Path roomFile : roomFilesToDelete) {
                try { Files.deleteIfExists(roomFile); }
                catch (Exception e) { System.err.println("[Admin] Không xóa được tệp phòng: " + roomFile + " - " + e.getMessage()); }
            }
            GroupCallService.onRoomDissolved(roomId);
            java.util.concurrent.CopyOnWriteArrayList<ClientHandler> activeMembers = ChatServer.roomMembers.remove(roomId);
            if (activeMembers != null) activeMembers.clear();
            for (ClientHandler online : ChatServer.onlineClients.values()) {
                online.sendMessage(Protocol.join(Protocol.ROOM_DISSOLVED, roomId, encodedRoomName));
            }
            audit(client, "DELETE_ROOM", "ROOM", String.valueOf(roomId), "Xóa phòng " + roomName, remote(client));
            sendResult(client, true, "Đã xóa phòng " + roomName + " và lịch sử tin nhắn nhóm.");
        } else {
            sendResult(client, false, "Thao tác phòng không hợp lệ.");
        }
        sendRooms(client);
    }

    private static void handleLogs(ClientHandler client, String[] p) throws SQLException {
        int page = 0;
        try { if (p.length > 1) page = Math.max(0, Math.min(1_000_000, Integer.parseInt(p[1]))); }
        catch (NumberFormatException ignored) { }
        String filter = p.length > 2 ? decode(p[2]).trim() : "";
        String like = "%" + filter.replace("%", "\\%").replace("_", "\\_") + "%";
        long total = 0;
        String where = filter.isEmpty() ? "" : " WHERE COALESCE(actor,'') LIKE ? ESCAPE '\\' OR action LIKE ? ESCAPE '\\' OR COALESCE(target_type,'') LIKE ? ESCAPE '\\' OR COALESCE(target_id,'') LIKE ? ESCAPE '\\' OR COALESCE(details,'') LIKE ? ESCAPE '\\' OR COALESCE(remote_address,'') LIKE ? ESCAPE '\\'";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM audit_logs" + where)) {
                if (!filter.isEmpty()) for (int i = 1; i <= 6; i++) ps.setString(i, like);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) total = rs.getLong(1); }
            }
            String sql = "SELECT created_at, COALESCE(actor,''), action, COALESCE(target_type,''), COALESCE(target_id,''), COALESCE(details,''), COALESCE(remote_address,'') FROM audit_logs" + where + " ORDER BY id DESC LIMIT 50 OFFSET ?";
            StringBuilder text = new StringBuilder();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int index = 1;
                if (!filter.isEmpty()) for (int i = 0; i < 6; i++) ps.setString(index++, like);
                ps.setInt(index, page * 50);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (text.length() > 0) text.append('\n');
                        for (int col = 1; col <= 7; col++) {
                            if (col > 1) text.append('\t');
                            text.append(tsv(rs.getString(col)));
                        }
                    }
                }
            }
            client.sendMessage(Protocol.join(Protocol.ADMIN_LOGS, page, total, encode(text.toString())));
        }
    }

    private static void sendResult(ClientHandler client, boolean ok, String message) {
        client.sendMessage(Protocol.join(Protocol.ADMIN_RESULT, ok ? "1" : "0", encode(message)));
    }

    /** Records call lifecycle/activity in the same audit stream displayed by the Admin Dashboard. */
    public static void recordCallEvent(String action, String targetType, String targetId, String details) {
        audit("system", action == null ? "CALL_EVENT" : action,
                targetType == null ? "CALL" : targetType,
                targetId == null ? "" : targetId,
                details == null ? "" : details, "");
    }

    private static void audit(ClientHandler actorClient, String action, String type, String target, String details, String remote) {
        String actor = actorClient == null ? "system" : actorClient.getCurrentUsername();
        audit(actor, action, type, target, details, remote);
    }

    private static void audit(String actor, String action, String type, String target, String details, String remote) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_logs(actor,action,target_type,target_id,details,remote_address) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, actor); ps.setString(2, action); ps.setString(3, type);
            ps.setString(4, target); ps.setString(5, details); ps.setString(6, remote);
            ps.executeUpdate();
        } catch (Exception e) { System.err.println("[Audit] " + e.getMessage()); }
    }

    private static String remote(ClientHandler client) { return client == null ? "" : client.getRemoteAddress(); }
    private static ClientHandler findOnline(String username) {
        for (Map.Entry<String, ClientHandler> entry : ChatServer.onlineClients.entrySet())
            if (entry.getKey().equals(username)) return entry.getValue();
        return null;
    }

    private static void broadcastRoomsChanged() {
        String packet = Protocol.join(Protocol.ROOMS_CHANGED);
        for (ClientHandler online : ChatServer.onlineClients.values()) online.sendMessage(packet);
    }

    private static void broadcastRoomMembersChanged(int roomId) {
        String packet = Protocol.join(Protocol.MEMBERS_CHANGED, roomId);
        java.util.concurrent.CopyOnWriteArrayList<ClientHandler> members = ChatServer.roomMembers.get(roomId);
        if (members != null) for (ClientHandler member : members) member.sendMessage(packet);
    }

    /** Only remove physical files located under the application's data/files directory. */
    private static Path resolveSafeStoragePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Path base = Path.of("data", "files").toAbsolutePath().normalize();
            Path candidate = Path.of(raw.trim());
            if (!candidate.isAbsolute()) {
                Path relativeToWorkingDir = Path.of("").toAbsolutePath().resolve(candidate).normalize();
                candidate = relativeToWorkingDir.startsWith(base)
                        ? relativeToWorkingDir : base.resolve(candidate).normalize();
            } else {
                candidate = candidate.normalize();
            }
            return candidate.startsWith(base) ? candidate : null;
        } catch (Exception e) { return null; }
    }

    private static int parseRoomId(String value) {
        try { int id = Integer.parseInt(value); if (id > 0) return id; }
        catch (Exception ignored) { }
        throw new IllegalArgumentException("ID phòng không hợp lệ.");
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return ""; }
    }
    private static String tsv(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean tableExists(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private static void ensureColumn(Connection conn, String name, String definition) throws SQLException {
        boolean found = false;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(users)")) {
            while (rs.next()) if (name.equalsIgnoreCase(rs.getString("name"))) { found = true; break; }
        }
        if (!found) try (Statement st = conn.createStatement()) { st.execute("ALTER TABLE users ADD COLUMN " + name + " " + definition); }
    }

    private static final class SpamTracker {
        final Deque<SpamEntry> recent = new ArrayDeque<>();
        long restrictedUntil;
    }
    private static final class SpamEntry {
        final long time; final String content;
        SpamEntry(long time, String content) { this.time = time; this.content = content; }
    }
}
