package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Locale;

/**
 * Xử lý nghiệp vụ thả và gỡ cảm xúc cho tin nhắn.
 */
public final class ReactionService {

    private final ClientHandler client;

    public ReactionService(ClientHandler client) {
        this.client = client;
    }

    public void handleReactMessage(String[] parts) {
        String username = client.getCurrentUsername();

        if (username == null) {
            client.sendError("Vui lòng đăng nhập trước.");
            return;
        }

        // REACT_MSG|messageID|reaction
        if (parts.length < 3) {
            client.sendError("Thiếu ID tin nhắn hoặc loại cảm xúc.");
            return;
        }

        long messageId;

        try {
            messageId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID tin nhắn không hợp lệ.");
            return;
        }

        if (messageId < 1) {
            client.sendError("ID tin nhắn không hợp lệ.");
            return;
        }

        String reaction = parts[2].trim().toUpperCase(Locale.ROOT);

        if (!Arrays.asList(Protocol.REACTIONS).contains(reaction)) {
            client.sendError("Loại cảm xúc không được hỗ trợ.");
            return;
        }

        try {
            MessageRecord message = getMessageRecord(messageId);

            if (message == null) {
                client.sendError("Không tìm thấy tin nhắn.");
                return;
            }

            if (message.deleted) {
                client.sendError(
                        "Không thể thả cảm xúc vào tin nhắn đã thu hồi."
                );
                return;
            }

            // Kiểm tra quyền tương tác với tin nhắn.
            if (message.roomId != null) {
                if (!client.isCurrentRoomMember(message.roomId)) {
                    client.sendError(
                            "Bạn phải tham gia phòng mới được thả cảm xúc."
                    );
                    return;
                }
            } else {
                boolean isSender = message.sender != null
                        && message.sender.equals(username);

                boolean isReceiver = message.receiver != null
                        && message.receiver.equals(username);

                if (!isSender && !isReceiver) {
                    client.sendError(
                            "Bạn không có quyền tương tác với tin nhắn này."
                    );
                    return;
                }
            }

            boolean active;

            // Nếu cảm xúc đã tồn tại thì xóa; nếu chưa có thì thêm.
            try (Connection conn = DatabaseManager.getConnection()) {
                conn.setAutoCommit(false);

                try {
                    boolean exists;

                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT 1 FROM message_reactions "
                            + "WHERE message_id = ? "
                            + "AND username COLLATE BINARY = ? COLLATE BINARY AND reaction = ?")) {

                        ps.setLong(1, messageId);
                        ps.setString(2, username);
                        ps.setString(3, reaction);

                        try (ResultSet rs = ps.executeQuery()) {
                            exists = rs.next();
                        }
                    }

                    if (exists) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "DELETE FROM message_reactions "
                                + "WHERE message_id = ? "
                                + "AND username COLLATE BINARY = ? COLLATE BINARY AND reaction = ?")) {

                            ps.setLong(1, messageId);
                            ps.setString(2, username);
                            ps.setString(3, reaction);
                            ps.executeUpdate();
                        }

                        active = false;

                    } else {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO message_reactions "
                                + "(message_id, username, reaction) "
                                + "VALUES (?, ?, ?)")) {

                            ps.setLong(1, messageId);
                            ps.setString(2, username);
                            ps.setString(3, reaction);
                            ps.executeUpdate();
                        }

                        active = true;
                    }

                    conn.commit();

                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            }

            // Phát thông báo cập nhật cảm xúc.
            if (message.roomId != null) {
                String packet = Protocol.join(
                        Protocol.REACTIONS_UPDATED,
                        message.id,
                        "G",
                        String.valueOf(message.roomId),
                        username,
                        reaction,
                        active ? 1 : 0
                );

                client.broadcastToRoom(message.roomId, packet);

            } else {
                /*
                 * Với chat riêng, mỗi người có key là username
                 * của người đối thoại. Vì vậy gửi packet riêng
                 * cho từng phía để cả hai giao diện đều cập nhật.
                 */
                ClientHandler senderClient =
                        ChatServer.onlineClients.get(message.sender);

                ClientHandler receiverClient =
                        ChatServer.onlineClients.get(message.receiver);

                if (senderClient != null) {
                    senderClient.sendMessage(
                            privateReactionPacket(
                                    message,
                                    message.receiver,
                                    username,
                                    reaction,
                                    active
                            )
                    );
                }

                if (receiverClient != null
                        && receiverClient != senderClient) {

                    receiverClient.sendMessage(
                            privateReactionPacket(
                                    message,
                                    message.sender,
                                    username,
                                    reaction,
                                    active
                            )
                    );
                }
            }

            DatabaseManager.recordAudit(
                    username,
                    "REACT_MESSAGE",
                    "MESSAGE",
                    String.valueOf(messageId),
                    reaction + (active ? " added" : " removed")
            );

        } catch (Exception e) {
            System.err.println("[Reaction] " + e.getMessage());
            client.sendError(
                    "Không thể cập nhật cảm xúc tin nhắn."
            );
        }
    }

    private String privateReactionPacket(
            MessageRecord message,
            String peer,
            String username,
            String reaction,
            boolean active) {

        return Protocol.join(
                Protocol.REACTIONS_UPDATED,
                message.id,
                "P",
                peer,
                username,
                reaction,
                active ? 1 : 0
        );
    }

    private MessageRecord getMessageRecord(long messageId)
            throws java.sql.SQLException {

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, sender, receiver, room_id, deleted "
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
                        rs.getInt("deleted") != 0
                );
            }
        }
    }

    private static final class MessageRecord {

        final long id;
        final String sender;
        final String receiver;
        final Integer roomId;
        final boolean deleted;

        MessageRecord(
                long id,
                String sender,
                String receiver,
                Integer roomId,
                boolean deleted) {

            this.id = id;
            this.sender = sender;
            this.receiver = receiver;
            this.roomId = roomId;
            this.deleted = deleted;
        }
    }
}