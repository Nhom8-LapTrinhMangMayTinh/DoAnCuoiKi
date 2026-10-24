package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Quản lý trạng thái online/offline và thời điểm hoạt động gần nhất.
 * Online được lấy từ ChatServer.onlineClients; last_seen được lưu trong SQLite.
 */
public final class PresenceService {

    private static volatile boolean schemaReady;
    private static final Object SCHEMA_LOCK = new Object();
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PresenceService() { }

    /** Bổ sung cột last_seen cho database cũ mà không xóa dữ liệu hiện có. */
    public static void ensureSchema() throws SQLException {
        if (schemaReady) return;
        synchronized (SCHEMA_LOCK) {
            if (schemaReady) return;
            try (Connection conn = DatabaseManager.getConnection()) {
                boolean exists = false;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("PRAGMA table_info(users)")) {
                    while (rs.next()) {
                        if ("last_seen".equalsIgnoreCase(rs.getString("name"))) {
                            exists = true;
                            break;
                        }
                    }
                }
                if (!exists) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE users ADD COLUMN last_seen TEXT");
                    }
                }
                schemaReady = true;
            }
        }
    }

    /** Gọi sau khi đăng nhập thành công và đã thêm tài khoản vào onlineClients. */
    public static void userLoggedIn(String username) {
        if (username == null || username.trim().isEmpty()) return;
        String lastSeen = "";
        try {
            ensureSchema();
            lastSeen = getLastSeen(username);
        } catch (Exception ex) {
            System.err.println("[Presence] Không đọc được last_seen: " + ex.getMessage());
        }
        broadcast(username, true, lastSeen);
    }

    /** Cập nhật thời gian offline rồi phát thông báo đến các client còn kết nối. */
    public static void userLoggedOut(String username) {
        if (username == null || username.trim().isEmpty()) return;
        String lastSeen = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        try {
            ensureSchema();
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE users SET last_seen = strftime('%Y-%m-%d %H:%M:%S','now','localtime') "
                                 + "WHERE username COLLATE BINARY = ? COLLATE BINARY")) {
                ps.setString(1, username);
                ps.executeUpdate();
            }
            String stored = getLastSeen(username);
            if (stored != null && !stored.trim().isEmpty()) lastSeen = stored;
        } catch (Exception ex) {
            System.err.println("[Presence] Không lưu được last_seen cho "
                    + username + ": " + ex.getMessage());
        }
        broadcast(username, false, lastSeen);
    }

    public static String getLastSeen(String username) throws SQLException {
        ensureSchema();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_seen FROM users WHERE username COLLATE BINARY = ? COLLATE BINARY LIMIT 1")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString("last_seen");
                    return value == null ? "" : value;
                }
            }
        }
        return "";
    }

    private static void broadcast(String username, boolean online, String lastSeen) {
        String packet = Protocol.join(
                Protocol.PRESENCE_UPDATE,
                Protocol.clean(username),
                online ? "1" : "0",
                Protocol.clean(lastSeen));
        int recipients = 0;
        for (ClientHandler client : ChatServer.onlineClients.values()) {
            if (client != null && client.getCurrentUsername() != null) {
                client.sendMessage(packet);
                recipients++;
            }
        }
        System.out.println("[Presence] PRESENCE_UPDATE user=" + username
                + " status=" + (online ? "ONLINE" : "OFFLINE")
                + " recipients=" + recipients
                + (lastSeen == null || lastSeen.isEmpty() ? "" : " lastSeen=" + lastSeen));
    }
}
