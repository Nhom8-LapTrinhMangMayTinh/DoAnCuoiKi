package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Xử lý trạng thái đã đọc, người đã xem và trạng thái đang nhập.
 * Phần đọc được lưu bền vững vào SQLite; trạng thái đang nhập chỉ ở bộ nhớ.
 */
public final class ReadStateService {

    private static final int TYPING_TIMEOUT_SECONDS = 5;

    private static final ScheduledExecutorService TYPING_EXPIRER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "Chat-Typing-Expiry");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /** key = conversation signature + username; value = current typing lease. */
    private static final ConcurrentHashMap<String, TypingLease> ACTIVE_TYPING =
            new ConcurrentHashMap<>();

    private final ClientHandler client;

    public ReadStateService(ClientHandler client) {
        this.client = client;
    }

    // =========================================================
    // PUBLIC COMMAND HANDLERS
    // =========================================================

    /**
     * Gửi snapshot số tin chưa đọc cho các phòng và cuộc trò chuyện riêng
     * mà tài khoản hiện tại có quyền xem. Mốc đọc được lấy từ read_states.
     */
    public void handleGetUnreadCounts(String[] parts) {
        if (!client.requireLogin()) return;

        String me = client.getCurrentUsername();
        client.sendMessage(Protocol.UNREAD_BEGIN);
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureReadStatesTable(conn);

            // Các phòng mà tài khoản hiện đang là thành viên.
            String roomsSql =
                    "SELECT rm.room_id, "
                    + "COALESCE(rs.last_read_message_id, 0) AS last_read_id, "
                    + "(SELECT COUNT(*) FROM messages m "
                    + " WHERE m.room_id = rm.room_id AND m.sender COLLATE BINARY <> ? COLLATE BINARY "
                    + " AND m.id > COALESCE(rs.last_read_message_id, 0)) AS unread_count, "
                    + "(SELECT COALESCE(MAX(m2.id), 0) FROM messages m2 "
                    + " WHERE m2.room_id = rm.room_id) AS latest_id "
                    + "FROM room_members rm "
                    + "LEFT JOIN read_states rs ON rs.username COLLATE BINARY = rm.username COLLATE BINARY "
                    + " AND rs.conversation_type = 'G' "
                    + " AND rs.conversation_key = CAST(rm.room_id AS TEXT) "
                    + "WHERE rm.username COLLATE BINARY = ? COLLATE BINARY ORDER BY rm.room_id";
            try (PreparedStatement ps = conn.prepareStatement(roomsSql)) {
                ps.setString(1, me);
                ps.setString(2, me);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        client.sendMessage(Protocol.join(
                                Protocol.UNREAD_COUNT, "G",
                                rs.getInt("room_id"),
                                rs.getInt("unread_count"),
                                rs.getLong("latest_id")));
                    }
                }
            }

            // Những người từng trao đổi tin nhắn riêng với tài khoản này.
            String privateSql =
                    "SELECT peers.peer, "
                    + "COALESCE(rs.last_read_message_id, 0) AS last_read_id, "
                    + "(SELECT COUNT(*) FROM messages m "
                    + " WHERE m.receiver COLLATE BINARY = ? COLLATE BINARY AND m.sender COLLATE BINARY = peers.peer COLLATE BINARY "
                    + " AND m.id > COALESCE(rs.last_read_message_id, 0)) AS unread_count, "
                    + "(SELECT COALESCE(MAX(m2.id), 0) FROM messages m2 "
                    + " WHERE m2.receiver IS NOT NULL AND "
                    + " ((m2.sender COLLATE BINARY = ? COLLATE BINARY AND m2.receiver COLLATE BINARY = peers.peer COLLATE BINARY) "
                    + " OR (m2.sender COLLATE BINARY = peers.peer COLLATE BINARY AND m2.receiver COLLATE BINARY = ? COLLATE BINARY))) AS latest_id "
                    + "FROM (SELECT DISTINCT "
                    + " CASE WHEN sender COLLATE BINARY = ? COLLATE BINARY THEN receiver ELSE sender END AS peer "
                    + " FROM messages WHERE receiver IS NOT NULL "
                    + " AND (sender COLLATE BINARY = ? COLLATE BINARY OR receiver COLLATE BINARY = ? COLLATE BINARY)) peers "
                    + "LEFT JOIN read_states rs ON rs.username COLLATE BINARY = ? COLLATE BINARY "
                    + " AND rs.conversation_type = 'P' "
                    + " AND rs.conversation_key COLLATE BINARY = peers.peer COLLATE BINARY "
                    + "WHERE peers.peer IS NOT NULL AND peers.peer <> ? "
                    + "ORDER BY peers.peer COLLATE BINARY";
            try (PreparedStatement ps = conn.prepareStatement(privateSql)) {
                ps.setString(1, me); // số tin nhận được
                ps.setString(2, me); // MAX: người gửi là tài khoản hiện tại
                ps.setString(3, me); // MAX: người nhận là tài khoản hiện tại
                ps.setString(4, me); // CASE: đầu mối là tài khoản hiện tại
                ps.setString(5, me); // lọc sender
                ps.setString(6, me); // lọc receiver
                ps.setString(7, me); // read_states.username
                ps.setString(8, me); // loại chính tài khoản hiện tại khỏi danh sách peer
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String peer = rs.getString("peer");
                        if (peer == null || peer.trim().isEmpty()) continue;
                        String peerToken = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(peer.getBytes(StandardCharsets.UTF_8));
                        client.sendMessage(Protocol.join(
                                Protocol.UNREAD_COUNT, "P", peerToken,
                                rs.getInt("unread_count"), rs.getLong("latest_id")));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ReadStateService] Không tải được số tin chưa đọc: " + e.getMessage());
            client.sendError("Không thể tải số tin chưa đọc.");
        } finally {
            client.sendMessage(Protocol.UNREAD_END);
        }
    }

    public void handleGetReadState(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts.length < 3) {
            client.sendError("Thiếu thông tin cuộc trò chuyện cần đồng bộ trạng thái đã đọc.");
            return;
        }

        String me = client.getCurrentUsername();
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureReadStatesTable(conn);
            ConversationTarget target = resolveTarget(conn, parts[1], parts[2], me);
            if (target == null) return;

            if ("G".equals(target.kind)) {
                String sql = "SELECT username, last_read_message_id FROM read_states "
                        + "WHERE conversation_type = 'G' AND conversation_key = ? "
                        + "AND username IN (SELECT username FROM room_members WHERE room_id = ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, target.key);
                    ps.setInt(2, target.roomId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            client.sendMessage(Protocol.join(
                                    Protocol.READ_STATE, "G", target.key,
                                    rs.getString("username"),
                                    rs.getLong("last_read_message_id")));
                        }
                    }
                }
            } else {
                String sql = "SELECT username, last_read_message_id FROM read_states "
                        + "WHERE conversation_type = 'P' AND ((username COLLATE BINARY = ? COLLATE BINARY AND conversation_key = ?) "
                        + "OR (username COLLATE BINARY = ? COLLATE BINARY AND conversation_key = ?))";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, me);
                    ps.setString(2, target.peer);
                    ps.setString(3, target.peer);
                    ps.setString(4, me);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            client.sendMessage(Protocol.join(
                                    Protocol.READ_STATE, "P", target.peer,
                                    rs.getString("username"),
                                    rs.getLong("last_read_message_id")));
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            client.sendError(e.getMessage());
        } catch (Exception e) {
            System.err.println("[ReadStateService] Không tải được trạng thái đọc: " + e.getMessage());
            client.sendError("Không thể tải trạng thái đã đọc của cuộc trò chuyện.");
        }
    }

    public void handleMarkRead(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts.length < 4) {
            client.sendError("Lệnh đánh dấu đã đọc thiếu tham số.");
            return;
        }

        final long requestedId;
        try {
            requestedId = Long.parseLong(parts[3]);
            if (requestedId <= 0) return;
        } catch (NumberFormatException e) {
            client.sendError("ID tin nhắn đã đọc không hợp lệ.");
            return;
        }

        String me = client.getCurrentUsername();
        ConversationTarget target;
        long actualId;
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureReadStatesTable(conn);
            target = resolveTarget(conn, parts[1], parts[2], me);
            if (target == null) return;

            actualId = findLatestMessageId(conn, target, me, requestedId);
            if (actualId <= 0) return;
            saveReadMarker(conn, target, me, actualId);
        } catch (IllegalArgumentException e) {
            client.sendError(e.getMessage());
            return;
        } catch (Exception e) {
            System.err.println("[ReadStateService] Không lưu được mốc đã đọc: " + e.getMessage());
            client.sendError("Không thể cập nhật trạng thái đã đọc.");
            return;
        }

        broadcastReadState(target, me, actualId);
    }

    public void handleTyping(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts.length < 4) {
            client.sendError("Lệnh trạng thái đang nhập thiếu tham số.");
            return;
        }

        String status = parts[3];
        if (!"1".equals(status) && !"0".equals(status)) {
            client.sendError("Trạng thái đang nhập không hợp lệ.");
            return;
        }

        String me = client.getCurrentUsername();
        try (Connection conn = DatabaseManager.getConnection()) {
            ConversationTarget target = resolveTarget(conn, parts[1], parts[2], me);
            if (target == null) return;
            updateTypingLease(target, me, "1".equals(status));
        } catch (IllegalArgumentException e) {
            // Lệnh dừng nhập có thể đến đúng lúc client vừa rời phòng/bị kick.
            // Chỉ cho phép dừng lease đã tồn tại của chính người gửi; không tạo lease mới.
            if ("0".equals(status)) {
                clearExistingTyping(parts[1], parts[2], me);
                return;
            }
            client.sendError(e.getMessage());
        } catch (Exception e) {
            System.err.println("[ReadStateService] Không xử lý được trạng thái nhập: " + e.getMessage());
        }
    }

    private void clearExistingTyping(String kind, String rawKey, String username) {
        if (kind == null || rawKey == null || username == null) return;
        String canonicalKey = rawKey.trim();
        if ("G".equals(kind)) {
            try {
                canonicalKey = String.valueOf(Integer.parseInt(canonicalKey));
            } catch (NumberFormatException e) {
                return;
            }
        }
        for (Map.Entry<String, TypingLease> entry : ACTIVE_TYPING.entrySet()) {
            TypingLease lease = entry.getValue();
            if (lease.username.equals(username)
                    && lease.target.kind.equals(kind)
                    && lease.target.key.equals(canonicalKey)
                    && ACTIVE_TYPING.remove(entry.getKey(), lease)) {
                lease.cancel();
                broadcastTyping(lease.target, lease.username, false);
            }
        }
    }

    public void handleGetTypingState(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts.length < 3) {
            client.sendError("Thiếu thông tin để tải trạng thái đang nhập.");
            return;
        }

        String me = client.getCurrentUsername();
        try (Connection conn = DatabaseManager.getConnection()) {
            ConversationTarget target = resolveTarget(conn, parts[1], parts[2], me);
            if (target == null) return;

            String signature = conversationSignature(target, me);
            for (TypingLease lease : ACTIVE_TYPING.values()) {
                if (signature.equals(lease.signature)) {
                    client.sendMessage(Protocol.join(
                            Protocol.TYPING_STATE, target.kind, target.key,
                            lease.username, "1"));
                }
            }
        } catch (IllegalArgumentException e) {
            client.sendError(e.getMessage());
        } catch (Exception e) {
            System.err.println("[ReadStateService] Không tải được trạng thái đang nhập: " + e.getMessage());
        }
    }

    /** Gọi khi client đóng kết nối để các trạng thái đang nhập được gỡ ngay. */
    public void onDisconnect() {
        String username = client.getCurrentUsername();
        if (username == null || username.trim().isEmpty()) return;

        for (Map.Entry<String, TypingLease> entry : ACTIVE_TYPING.entrySet()) {
            TypingLease lease = entry.getValue();
            if (lease.username.equals(username)
                    && ACTIVE_TYPING.remove(entry.getKey(), lease)) {
                lease.cancel();
                broadcastTyping(lease.target, lease.username, false);
            }
        }
    }

    // =========================================================
    // DATABASE
    // =========================================================

    private void ensureReadStatesTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS read_states ("
                    + "username TEXT NOT NULL, "
                    + "conversation_type TEXT NOT NULL, "
                    + "conversation_key TEXT NOT NULL, "
                    + "last_read_message_id INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY(username, conversation_type, conversation_key)"
                    + ")");
        }
    }

    private ConversationTarget resolveTarget(
            Connection conn, String kind, String rawKey, String username)
            throws SQLException {

        if (kind == null || rawKey == null) {
            throw new IllegalArgumentException("Cuộc trò chuyện không hợp lệ.");
        }

        if ("G".equals(kind)) {
            final int roomId;
            try {
                roomId = Integer.parseInt(rawKey.trim());
                if (roomId <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ID phòng chat không hợp lệ.");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM room_members WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {
                ps.setInt(1, roomId);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException(
                                "Bạn không phải thành viên của phòng này.");
                    }
                }
            }
            return new ConversationTarget("G", String.valueOf(roomId), roomId, null);
        }

        if ("P".equals(kind)) {
            String peerInput = rawKey.trim();
            if (peerInput.isEmpty() || peerInput.equals(username)) {
                throw new IllegalArgumentException("Người trò chuyện riêng không hợp lệ.");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT username FROM users WHERE username COLLATE BINARY = ? COLLATE BINARY")) {
                ps.setString(1, peerInput);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Không tìm thấy người dùng này.");
                    }
                    String peer = rs.getString("username");
                    return new ConversationTarget("P", peer, -1, peer);
                }
            }
        }

        throw new IllegalArgumentException("Loại cuộc trò chuyện không hợp lệ.");
    }

    private long findLatestMessageId(
            Connection conn, ConversationTarget target, String me, long requestedId)
            throws SQLException {
        String sql;
        if ("G".equals(target.kind)) {
            sql = "SELECT COALESCE(MAX(id),0) FROM messages WHERE room_id = ? AND id <= ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, target.roomId);
                ps.setLong(2, requestedId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            }
        }

        sql = "SELECT COALESCE(MAX(id),0) FROM messages WHERE receiver IS NOT NULL "
                + "AND ((sender COLLATE BINARY = ? COLLATE BINARY AND receiver COLLATE BINARY = ? COLLATE BINARY) OR (sender COLLATE BINARY = ? COLLATE BINARY AND receiver COLLATE BINARY = ? COLLATE BINARY)) "
                + "AND id <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, me);
            ps.setString(2, target.peer);
            ps.setString(3, target.peer);
            ps.setString(4, me);
            ps.setLong(5, requestedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private void saveReadMarker(
            Connection conn, ConversationTarget target, String username, long messageId)
            throws SQLException {
        String sql = "INSERT INTO read_states "
                + "(username, conversation_type, conversation_key, last_read_message_id, updated_at) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT(username, conversation_type, conversation_key) DO UPDATE SET "
                + "last_read_message_id = MAX(read_states.last_read_message_id, excluded.last_read_message_id), "
                + "updated_at = CURRENT_TIMESTAMP";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, target.kind);
            ps.setString(3, target.key);
            ps.setLong(4, messageId);
            ps.executeUpdate();
        }
    }

    // =========================================================
    // BROADCAST READ STATE
    // =========================================================

    private void broadcastReadState(
            ConversationTarget target, String reader, long messageId) {
        if ("G".equals(target.kind)) {
            String frame = Protocol.join(Protocol.READ_STATE, "G", target.key,
                    reader, messageId);
            broadcastToRoomMembers(target.roomId, frame);
            return;
        }

        ClientHandler readerClient = ChatServer.onlineClients.get(reader);
        if (readerClient != null) {
            readerClient.sendMessage(Protocol.join(Protocol.READ_STATE, "P",
                    target.peer, reader, messageId));
        }
        ClientHandler peerClient = ChatServer.onlineClients.get(target.peer);
        if (peerClient != null) {
            peerClient.sendMessage(Protocol.join(Protocol.READ_STATE, "P",
                    reader, reader, messageId));
        }
    }

    // =========================================================
    // TYPING STATE + TIMEOUT
    // =========================================================

    private void updateTypingLease(
            ConversationTarget target, String username, boolean typing) {
        String signature = conversationSignature(target, username);
        String leaseKey = signature + "|" + username;

        if (!typing) {
            TypingLease old = ACTIVE_TYPING.remove(leaseKey);
            if (old != null) old.cancel();
            broadcastTyping(target, username, false);
            return;
        }

        TypingLease next = new TypingLease(target, username, signature);
        TypingLease previous = ACTIVE_TYPING.put(leaseKey, next);
        if (previous != null) previous.cancel();

        next.future = TYPING_EXPIRER.schedule(() -> {
            if (ACTIVE_TYPING.remove(leaseKey, next)) {
                broadcastTyping(next.target, next.username, false);
            }
        }, TYPING_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Chỉ broadcast 1 khi trạng thái chuyển từ tắt sang bật.
        if (previous == null) broadcastTyping(target, username, true);
    }

    private static String conversationSignature(ConversationTarget target, String currentUser) {
        if ("G".equals(target.kind)) return "G|" + target.key;
        String a = currentUser;
        String b = target.peer;
        return a.compareTo(b) <= 0 ? "P|" + a + "|" + b : "P|" + b + "|" + a;
    }

    private void broadcastTyping(ConversationTarget target, String user, boolean typing) {
        String flag = typing ? "1" : "0";
        if ("G".equals(target.kind)) {
            broadcastToRoomMembers(target.roomId, Protocol.join(
                    Protocol.TYPING_STATE, "G", target.key, user, flag));
            return;
        }

        ClientHandler userClient = ChatServer.onlineClients.get(user);
        if (userClient != null) {
            userClient.sendMessage(Protocol.join(Protocol.TYPING_STATE,
                    "P", target.peer, user, flag));
        }
        ClientHandler peerClient = ChatServer.onlineClients.get(target.peer);
        if (peerClient != null) {
            peerClient.sendMessage(Protocol.join(Protocol.TYPING_STATE,
                    "P", user, user, flag));
        }
    }

    private void broadcastToRoomMembers(int roomId, String frame) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT username FROM room_members WHERE room_id = ?")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ClientHandler member = ChatServer.onlineClients.get(rs.getString("username"));
                    if (member != null) member.sendMessage(frame);
                }
            }
        } catch (Exception e) {
            // Dự phòng nếu truy vấn thành viên thất bại: phát tới các client đang nằm trong phòng.
            CopyOnWriteRoomFallback.broadcast(roomId, frame);
        }
    }

    private static final class CopyOnWriteRoomFallback {
        private static void broadcast(int roomId, String frame) {
            java.util.concurrent.CopyOnWriteArrayList<ClientHandler> members =
                    ChatServer.roomMembers.get(roomId);
            if (members == null) return;
            for (ClientHandler member : members) member.sendMessage(frame);
        }
    }

    private static final class ConversationTarget {
        final String kind;
        final String key;
        final int roomId;
        final String peer;

        ConversationTarget(String kind, String key, int roomId, String peer) {
            this.kind = kind;
            this.key = key;
            this.roomId = roomId;
            this.peer = peer;
        }
    }

    private static final class TypingLease {
        final ConversationTarget target;
        final String username;
        final String signature;
        volatile ScheduledFuture<?> future;

        TypingLease(ConversationTarget target, String username, String signature) {
            this.target = target;
            this.username = username;
            this.signature = signature;
        }

        void cancel() {
            ScheduledFuture<?> f = future;
            if (f != null) f.cancel(false);
        }
    }
}
