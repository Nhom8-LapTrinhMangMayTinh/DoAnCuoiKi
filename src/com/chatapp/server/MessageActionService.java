package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class MessageActionService {

    private final ClientHandler client;

    public MessageActionService(ClientHandler client) {
        this.client = client;
    }

    // =====================================================
    // SỬA TIN NHẮN
    // =====================================================

    public void handleEditMessage(String[] parts) {
        String username = client.getCurrentUsername();

        if (username == null) {
            client.sendError("Vui lòng đăng nhập trước.");
            return;
        }

        if (parts.length < 3) {
            client.sendError("Thiếu ID hoặc nội dung tin nhắn.");
            return;
        }

        long messageId;

        try {
            messageId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID tin nhắn không hợp lệ.");
            return;
        }

        String encryptedContent = parts[2];

        if (messageId < 1
                || encryptedContent.isEmpty()
                || encryptedContent.length() > Protocol.MAX_TEXT_CHARS * 4) {
            client.sendError("Nội dung chỉnh sửa không hợp lệ.");
            return;
        }

        try {
            MessageRecord message = getMessageRecord(messageId);

            if (message == null) {
                client.sendError("Không tìm thấy tin nhắn.");
                return;
            }

            if (!username.equals(message.sender)) {
                client.sendError(
                        "Bạn chỉ được sửa tin nhắn của chính mình."
                );
                return;
            }

            if (message.deleted) {
                client.sendError(
                        "Không thể sửa tin nhắn đã thu hồi."
                );
                return;
            }

            if (!Protocol.TYPE_TEXT.equals(message.type)) {
                client.sendError(
                        "Hiện chỉ hỗ trợ sửa tin nhắn văn bản."
                );
                return;
            }

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE messages "
                         + "SET content = ?, edited = 1, "
                         + "edited_at = CURRENT_TIMESTAMP "
                         + "WHERE id = ? AND sender COLLATE BINARY = ? COLLATE BINARY AND deleted = 0")) {

                ps.setString(1, encryptedContent);
                ps.setLong(2, messageId);
                ps.setString(3, username);

                if (ps.executeUpdate() != 1) {
                    client.sendError(
                            "Tin nhắn đã thay đổi hoặc không thể sửa."
                    );
                    return;
                }
            }

            long updatedAt = System.currentTimeMillis();

            String key = message.roomId != null
                    ? String.valueOf(message.roomId)
                    : message.receiver;

            String packet = Protocol.join(
                    Protocol.MSG_EDITED,
                    message.id,
                    message.sender,
                    key,
                    message.roomId != null ? "G" : "P",
                    encryptedContent,
                    updatedAt
            );

            broadcastMessageRecord(message, packet);

            DatabaseManager.recordAudit(
                    username,
                    "EDIT_MESSAGE",
                    "MESSAGE",
                    String.valueOf(messageId),
                    "Đã sửa tin nhắn"
            );

        } catch (Exception e) {
            System.err.println("[Edit message] " + e.getMessage());
            client.sendError("Không thể sửa tin nhắn.");
        }
    }

    // =====================================================
    // THU HỒI TIN NHẮN
    // =====================================================

    public void handleRecallMessage(String[] parts) {
        String username = client.getCurrentUsername();

        if (username == null) {
            client.sendError("Vui lòng đăng nhập trước.");
            return;
        }

        if (parts.length < 2) {
            client.sendError("Thiếu ID tin nhắn.");
            return;
        }

        long messageId;

        try {
            messageId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID tin nhắn không hợp lệ.");
            return;
        }

        try {
            MessageRecord message = getMessageRecord(messageId);

            if (message == null) {
                client.sendError("Không tìm thấy tin nhắn.");
                return;
            }

            if (message.deleted) {
                client.sendError("Tin nhắn đã được thu hồi.");
                return;
            }

            boolean isSender =
                    username.equals(message.sender);

            boolean isRoomModerator = false;

            if (message.roomId != null && !isSender) {
                try (Connection conn = DatabaseManager.getConnection()) {
                    isRoomModerator = client.isOwnerOrAdmin(
                            conn,
                            message.roomId,
                            username
                    );
                }
            }

            if (!isSender && !isRoomModerator) {
                client.sendError(
                        "Bạn không có quyền thu hồi tin nhắn này."
                );
                return;
            }

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE messages "
                         + "SET content = '', deleted = 1, "
                         + "deleted_at = CURRENT_TIMESTAMP "
                         + "WHERE id = ? AND deleted = 0")) {

                ps.setLong(1, messageId);

                if (ps.executeUpdate() != 1) {
                    client.sendError("Không thể thu hồi tin nhắn.");
                    return;
                }
            }

            String key = message.roomId != null
                    ? String.valueOf(message.roomId)
                    : message.receiver;

            String packet = Protocol.join(
                    Protocol.MSG_RECALLED,
                    message.id,
                    message.sender,
                    key,
                    message.roomId != null ? "G" : "P"
            );

            broadcastMessageRecord(message, packet);

            DatabaseManager.recordAudit(
                    username,
                    "RECALL_MESSAGE",
                    "MESSAGE",
                    String.valueOf(messageId),
                    "Đã thu hồi tin nhắn"
            );

        } catch (Exception e) {
            System.err.println("[Recall message] " + e.getMessage());
            client.sendError("Không thể thu hồi tin nhắn.");
        }
    }

    // =====================================================
    // GHIM HOẶC BỎ GHIM TIN NHẮN
    // =====================================================

    public void handlePinMessage(String[] parts) {
        String username = client.getCurrentUsername();

        if (username == null) {
            client.sendError("Vui lòng đăng nhập trước.");
            return;
        }

        if (parts.length < 3) {
            client.sendError(
                    "Thiếu ID tin nhắn hoặc trạng thái ghim."
            );
            return;
        }

        long messageId;
        int pinValue;

        try {
            messageId = Long.parseLong(parts[1]);
            pinValue = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            client.sendError(
                    "ID tin nhắn hoặc trạng thái ghim không hợp lệ."
            );
            return;
        }

        if (messageId < 1 || (pinValue != 0 && pinValue != 1)) {
            client.sendError("Tham số ghim không hợp lệ.");
            return;
        }

        try {
            MessageRecord message = getMessageRecord(messageId);

            if (message == null) {
                client.sendError("Không tìm thấy tin nhắn.");
                return;
            }

            if (message.roomId == null) {
                client.sendError(
                        "Hiện chỉ hỗ trợ ghim tin nhắn trong phòng nhóm."
                );
                return;
            }

            if (message.deleted) {
                client.sendError(
                        "Không thể ghim tin nhắn đã thu hồi."
                );
                return;
            }

            try (Connection conn = DatabaseManager.getConnection()) {
                if (!client.isOwnerOrAdmin(
                        conn, message.roomId, username)) {
                    client.sendError(
                            "Chỉ chủ phòng hoặc quản trị viên "
                            + "được ghim tin nhắn."
                    );
                    return;
                }
            }

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE messages SET pinned = ?, "
                         + "pinned_by = ?, "
                         + "pinned_at = CASE WHEN ? = 1 "
                         + "THEN CURRENT_TIMESTAMP ELSE NULL END "
                         + "WHERE id = ? AND room_id = ? AND deleted = 0")) {

                ps.setInt(1, pinValue);
                ps.setString(
                        2,
                        pinValue == 1 ? username : null
                );
                ps.setInt(3, pinValue);
                ps.setLong(4, messageId);
                ps.setInt(5, message.roomId);

                if (ps.executeUpdate() != 1) {
                    client.sendError(
                            "Không thể cập nhật trạng thái ghim."
                    );
                    return;
                }
            }

            String packet = Protocol.join(
                    Protocol.MSG_PINNED,
                    message.id,
                    message.roomId,
                    username,
                    pinValue
            );

            client.broadcastToRoom(message.roomId, packet);

            DatabaseManager.recordAudit(
                    username,
                    pinValue == 1 ? "PIN_MESSAGE" : "UNPIN_MESSAGE",
                    "MESSAGE",
                    String.valueOf(messageId),
                    "Phòng ID " + message.roomId
            );

        } catch (Exception e) {
            System.err.println("[Pin message] " + e.getMessage());
            client.sendError(
                    "Không thể ghim hoặc bỏ ghim tin nhắn."
            );
        }
    }

    // =====================================================
    // ĐỌC THÔNG TIN TIN NHẮN TỪ DATABASE
    // =====================================================

    private MessageRecord getMessageRecord(long messageId)
            throws java.sql.SQLException {

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, sender, receiver, room_id, "
                     + "msg_type, deleted, pinned "
                     + "FROM messages WHERE id = ?")) {

            ps.setLong(1, messageId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                int rawRoomId = rs.getInt("room_id");

                Integer roomId = rs.wasNull()
                        ? null
                        : rawRoomId;

                return new MessageRecord(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getString("receiver"),
                        roomId,
                        rs.getString("msg_type"),
                        rs.getInt("deleted") != 0,
                        rs.getInt("pinned") != 0
                );
            }
        }
    }

    // =====================================================
    // PHÁT THÔNG BÁO ĐẾN NHỮNG CLIENT LIÊN QUAN
    // =====================================================

    private void broadcastMessageRecord(
            MessageRecord message,
            String packet) {

        if (message.roomId != null) {
            client.broadcastToRoom(message.roomId, packet);
            return;
        }

        ClientHandler senderClient =
                ChatServer.onlineClients.get(message.sender);

        ClientHandler receiverClient =
                ChatServer.onlineClients.get(message.receiver);

        if (senderClient != null) {
            senderClient.sendMessage(packet);
        }

        if (receiverClient != null
                && receiverClient != senderClient) {
            receiverClient.sendMessage(packet);
        }
    }

    // =====================================================
    // DỮ LIỆU TIN NHẮN
    // =====================================================

    private static final class MessageRecord {

        final long id;
        final String sender;
        final String receiver;
        final Integer roomId;
        final String type;
        final boolean deleted;
        final boolean pinned;

        MessageRecord(
                long id,
                String sender,
                String receiver,
                Integer roomId,
                String type,
                boolean deleted,
                boolean pinned) {

            this.id = id;
            this.sender = sender;
            this.receiver = receiver;
            this.roomId = roomId;
            this.type = type;
            this.deleted = deleted;
            this.pinned = pinned;
        }
    }
}