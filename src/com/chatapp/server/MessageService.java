package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Xử lý gửi tin nhắn và tải lịch sử trò chuyện.
 */
public final class MessageService {

    private final ClientHandler client;

    public MessageService(ClientHandler client) {
        this.client = client;
    }

    // =====================================================
    // GỬI TIN NHẮN NHÓM
    // =====================================================

    public void handleSendGroupMessage(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts.length < 3) {
            client.sendError("Thiếu dữ liệu tin nhắn nhóm.");
            return;
        }

        int roomId;

        try {
            roomId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        String encryptedContent = parts[2];

        if (encryptedContent.isEmpty()
                || encryptedContent.length() > Protocol.MAX_TEXT_CHARS * 4) {
            client.sendError("Nội dung tin nhắn không hợp lệ hoặc quá dài.");
            return;
        }

        if (!client.isCurrentRoomMember(roomId)) {
            client.sendError("Bạn chưa tham gia phòng này.");
            return;
        }

        long timestamp = System.currentTimeMillis();
        long messageId = -1;
        String roomName;

        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement roomQuery = conn.prepareStatement(
                    "SELECT room_name FROM rooms WHERE id = ?")) {

                roomQuery.setInt(1, roomId);

                try (ResultSet rs = roomQuery.executeQuery()) {
                    if (!rs.next()) {
                        client.sendError("Phòng không tồn tại.");
                        return;
                    }

                    roomName = rs.getString("room_name");
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO messages "
                    + "(sender, room_id, room_name, content, msg_type) "
                    + "VALUES(?, ?, ?, ?, ?)")) {

                ps.setString(1, client.getCurrentUsername());
                ps.setInt(2, roomId);
                ps.setString(3, roomName);
                ps.setString(4, encryptedContent);
                ps.setString(5, Protocol.TYPE_TEXT);
                ps.executeUpdate();
            }

            // Lấy ID tin nhắn vừa lưu trên chính kết nối này.
            try (Statement idStmt = conn.createStatement();
                 ResultSet idRs = idStmt.executeQuery(
                         "SELECT last_insert_rowid()")) {

                if (idRs.next()) {
                    messageId = idRs.getLong(1);
                }
            }

        } catch (Exception e) {
            System.err.println("[Group message] " + e.getMessage());
            client.sendError("Không thể lưu tin nhắn nhóm.");
            return;
        }

        if (messageId < 1) {
            client.sendError("Không lấy được ID tin nhắn.");
            return;
        }

        String packet = Protocol.join(
                Protocol.MSG_GROUP,
                roomId,
                messageId,
                client.getCurrentUsername(),
                timestamp,
                Protocol.TYPE_TEXT,
                encryptedContent
        );

        client.broadcastToRoom(roomId, packet);
    }

    // =====================================================
    // GỬI TIN NHẮN RIÊNG
    // =====================================================

    public void handleSendPrivateMessage(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts.length < 3) {
            client.sendError("Thiếu dữ liệu tin nhắn riêng.");
            return;
        }

        String sender = client.getCurrentUsername();
        String receiver = parts[1].trim();
        String encryptedContent = parts[2];

        if (receiver.isEmpty() || receiver.equals(sender)) {
            client.sendError("Người nhận không hợp lệ.");
            return;
        }

        if (encryptedContent.isEmpty()
                || encryptedContent.length() > Protocol.MAX_TEXT_CHARS * 4) {
            client.sendError("Nội dung tin nhắn không hợp lệ hoặc quá dài.");
            return;
        }

        long timestamp = System.currentTimeMillis();
        long messageId = -1;

        try (Connection conn = DatabaseManager.getConnection()) {
            // Chỉ gửi tin nhắn tới tài khoản đã tồn tại.
            try (PreparedStatement userQuery = conn.prepareStatement(
                    "SELECT 1 FROM users WHERE username COLLATE BINARY = ? COLLATE BINARY")) {

                userQuery.setString(1, receiver);

                try (ResultSet rs = userQuery.executeQuery()) {
                    if (!rs.next()) {
                        client.sendError("Tài khoản người nhận không tồn tại.");
                        return;
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO messages "
                    + "(sender, receiver, content, msg_type) "
                    + "VALUES(?, ?, ?, ?)")) {

                ps.setString(1, sender);
                ps.setString(2, receiver);
                ps.setString(3, encryptedContent);
                ps.setString(4, Protocol.TYPE_TEXT);
                ps.executeUpdate();
            }

            try (Statement idStmt = conn.createStatement();
                 ResultSet idRs = idStmt.executeQuery(
                         "SELECT last_insert_rowid()")) {

                if (idRs.next()) {
                    messageId = idRs.getLong(1);
                }
            }

        } catch (Exception e) {
            System.err.println("[Private message] " + e.getMessage());
            client.sendError("Không thể lưu tin nhắn riêng.");
            return;
        }

        if (messageId < 1) {
            client.sendError("Không lấy được ID tin nhắn.");
            return;
        }

        String packet = Protocol.join(
                Protocol.MSG_PRIVATE,
                messageId,
                sender,
                receiver,
                timestamp,
                Protocol.TYPE_TEXT,
                encryptedContent
        );

        ClientHandler target = ChatServer.onlineClients.get(receiver);

        if (target != null) {
            target.sendMessage(packet);
        }

        // Gửi lại cho người gửi để giao diện hiển thị tin vừa gửi.
        client.sendMessage(packet);
    }

    // =====================================================
    // TẢI LỊCH SỬ CHAT NHÓM THEO TRANG
    // =====================================================

    public void handleLoadHistoryPaged(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts.length < 3) {
            client.sendError("Thiếu ID phòng hoặc số trang.");
            return;
        }

        int roomId;
        int page;

        try {
            roomId = Integer.parseInt(parts[1]);
            page = Integer.parseInt(parts[2]);

            if (roomId < 1
                    || page < 0
                    || page > Integer.MAX_VALUE / Protocol.PAGE_SIZE) {
                client.sendError("ID phòng hoặc số trang không hợp lệ.");
                return;
            }

        } catch (NumberFormatException e) {
            client.sendError("ID phòng hoặc số trang không hợp lệ.");
            return;
        }

        if (!client.isCurrentRoomMember(roomId)) {
            client.sendError("Bạn phải tham gia phòng mới được xem lịch sử.");
            return;
        }

        int offset = page * Protocol.PAGE_SIZE;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, sender, content, timestamp, msg_type, "
                     + "edited, deleted, pinned "
                     + "FROM messages "
                     + "WHERE room_id = ? "
                     + "ORDER BY id DESC LIMIT ? OFFSET ?")) {

            ps.setInt(1, roomId);
            ps.setInt(2, Protocol.PAGE_SIZE + 1);
            ps.setInt(3, offset);

            try (ResultSet rs = ps.executeQuery()) {
                List<String[]> rows = new ArrayList<>();

                while (rs.next()) {
                    rows.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("sender"),
                        rs.getString("timestamp"),
                        rs.getString("msg_type"),
                        rs.getString("content"),
                        String.valueOf(rs.getInt("edited")),
                        String.valueOf(rs.getInt("deleted")),
                        String.valueOf(rs.getInt("pinned"))
                    });
                }

                boolean hasMore = rows.size() > Protocol.PAGE_SIZE;
                int count = Math.min(rows.size(), Protocol.PAGE_SIZE);

                client.sendMessage(
                        Protocol.join(
                                Protocol.HISTORY_BEGIN,
                                "G",
                                roomId,
                                page
                        )
                );

                // Gửi lịch sử theo thứ tự từ cũ đến mới.
                for (int i = count - 1; i >= 0; i--) {
                    String[] row = rows.get(i);

                    client.sendMessage(
                            Protocol.join(
                                    Protocol.HISTORY_ITEM,
                                    "G",
                                    roomId,
                                    row[0],
                                    Protocol.clean(row[1]),
                                    Protocol.clean(row[2]),
                                    Protocol.clean(row[3]),
                                    row[4],
                                    row[5],
                                    row[6],
                                    row[7]
                            )
                    );
                }

                client.sendMessage(
                        Protocol.join(
                                Protocol.HISTORY_END,
                                "G",
                                roomId,
                                page,
                                hasMore ? "1" : "0"
                        )
                );
            }

        } catch (Exception e) {
            System.err.println("[Group history] " + e.getMessage());
            client.sendError("Không thể tải lịch sử trò chuyện.");
        }
    }

    // =====================================================
    // TẢI LỊCH SỬ CHAT RIÊNG THEO TRANG
    // =====================================================

    public void handleLoadPrivateHistory(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts.length < 3) {
            client.sendError("Thiếu username người đối thoại hoặc số trang.");
            return;
        }

        String username = client.getCurrentUsername();
        String peer = parts[1].trim();

        if (peer.isEmpty() || peer.equals(username)) {
            client.sendError("Username người đối thoại không hợp lệ.");
            return;
        }

        int page;

        try {
            page = Integer.parseInt(parts[2]);

            if (page < 0
                    || page > Integer.MAX_VALUE / Protocol.PAGE_SIZE) {
                client.sendError("Số trang không hợp lệ.");
                return;
            }

        } catch (NumberFormatException e) {
            client.sendError("Số trang không hợp lệ.");
            return;
        }

        int offset = page * Protocol.PAGE_SIZE;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, sender, receiver, content, timestamp, "
                     + "msg_type, edited, deleted, pinned "
                     + "FROM messages "
                     + "WHERE receiver IS NOT NULL AND "
                     + "((sender COLLATE BINARY = ? COLLATE BINARY AND receiver COLLATE BINARY = ? COLLATE BINARY) "
                     + "OR (sender COLLATE BINARY = ? COLLATE BINARY AND receiver COLLATE BINARY = ? COLLATE BINARY)) "
                     + "ORDER BY id DESC LIMIT ? OFFSET ?")) {

            ps.setString(1, username);
            ps.setString(2, peer);
            ps.setString(3, peer);
            ps.setString(4, username);
            ps.setInt(5, Protocol.PAGE_SIZE + 1);
            ps.setInt(6, offset);

            try (ResultSet rs = ps.executeQuery()) {
                List<String[]> rows = new ArrayList<>();

                while (rs.next()) {
                    rows.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("sender"),
                        rs.getString("receiver"),
                        rs.getString("timestamp"),
                        rs.getString("msg_type"),
                        rs.getString("content"),
                        String.valueOf(rs.getInt("edited")),
                        String.valueOf(rs.getInt("deleted")),
                        String.valueOf(rs.getInt("pinned"))
                    });
                }

                boolean hasMore = rows.size() > Protocol.PAGE_SIZE;
                int count = Math.min(rows.size(), Protocol.PAGE_SIZE);

                client.sendMessage(
                        Protocol.join(
                                Protocol.HISTORY_BEGIN,
                                "P",
                                peer,
                                page
                        )
                );

                for (int i = count - 1; i >= 0; i--) {
                    String[] row = rows.get(i);

                    client.sendMessage(
                            Protocol.join(
                                    Protocol.HISTORY_ITEM,
                                    "P",
                                    peer,
                                    row[0],
                                    Protocol.clean(row[1]),
                                    Protocol.clean(row[3]),
                                    Protocol.clean(row[4]),
                                    row[5],
                                    row[6],
                                    row[7],
                                    row[8]
                            )
                    );
                }

                client.sendMessage(
                        Protocol.join(
                                Protocol.HISTORY_END,
                                "P",
                                peer,
                                page,
                                hasMore ? "1" : "0"
                        )
                );
            }

        } catch (Exception e) {
            System.err.println("[Private history] " + e.getMessage());
            client.sendError("Không thể tải lịch sử tin nhắn riêng.");
        }
    }
}