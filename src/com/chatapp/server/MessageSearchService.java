package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Xử lý tìm kiếm tin nhắn trong lịch sử chat.
 * Tách khỏi ClientHandler để ClientHandler chỉ điều phối giao thức.
 * Lớp này chỉ được gọi từ package server và sử dụng ClientHandler
 * hiện tại để xác thực cùng gửi phản hồi về đúng client.
 */
public final class MessageSearchService {

    private static final int SEARCH_MAX_SCAN = 20000;
    private static final int SEARCH_MAX_RESULTS = 500;

    private final ClientHandler client;

    public MessageSearchService(ClientHandler client) {
        if (client == null) {
            throw new IllegalArgumentException("ClientHandler không được null.");
        }
        this.client = client;
    }

    /**
     * Gói yêu cầu:
     * SEARCH_MESSAGES|G/P|roomId/peerUsername|queryBase64Url
     *
     * Phản hồi:
     * SEARCH_BEGIN|G/P|key|queryToken
     * SEARCH_ITEM|G/P|key|queryToken|id|sender|timestamp|type|contentB64|edited|deleted|pinned
     * SEARCH_END|G/P|key|queryToken|count|truncated
     * SEARCH_FAIL|G/P|key|queryToken|reasonBase64Url
     */
    public void handleSearchMessages(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts.length < 4) {
            client.sendError("Lệnh tìm kiếm không đủ tham số.");
            return;
        }

        String kind = parts[1];
        String key = parts[2];
        String queryToken = parts[3];

        if (!("G".equals(kind) || "P".equals(kind))) {
            sendSearchFailure(kind, key, queryToken,
                    "Loại cuộc trò chuyện không hợp lệ.");
            return;
        }

        final String query;
        try {
            query = new String(
                    Base64.getUrlDecoder().decode(queryToken),
                    StandardCharsets.UTF_8
            ).trim();
        } catch (IllegalArgumentException e) {
            sendSearchFailure(kind, key, queryToken,
                    "Từ khóa tìm kiếm không hợp lệ.");
            return;
        }

        if (query.isEmpty() || query.length() > 100) {
            sendSearchFailure(kind, key, queryToken,
                    "Từ khóa phải có từ 1 đến 100 ký tự.");
            return;
        }

        String normalizedQuery = normalizeSearchText(query);
        if (normalizedQuery.isEmpty()) {
            sendSearchFailure(kind, key, queryToken,
                    "Từ khóa không có ký tự có thể tìm kiếm.");
            return;
        }

        int roomId = -1;
        String peer = null;

        if ("G".equals(kind)) {
            try {
                roomId = Integer.parseInt(key);
                if (roomId <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                sendSearchFailure(kind, key, queryToken,
                        "ID phòng không hợp lệ.");
                return;
            }
        } else {
            peer = key == null ? "" : key.trim();
            if (peer.isEmpty()
                    || peer.equals(client.getCurrentUsername())
                    || peer.contains("|")) {
                sendSearchFailure(kind, key, queryToken,
                        "Người trò chuyện không hợp lệ.");
                return;
            }
        }

        List<String[]> hits = new ArrayList<>();
        boolean truncated = false;

        String sql;
        if ("G".equals(kind)) {
            sql = "SELECT id, sender, content, timestamp, msg_type, "
                    + "COALESCE(edited,0) AS edited, "
                    + "COALESCE(deleted,0) AS deleted, "
                    + "COALESCE(pinned,0) AS pinned "
                    + "FROM messages "
                    + "WHERE room_id = ? AND COALESCE(deleted,0) = 0 "
                    + "ORDER BY id DESC LIMIT ?";
        } else {
            sql = "SELECT id, sender, content, timestamp, msg_type, "
                    + "COALESCE(edited,0) AS edited, "
                    + "COALESCE(deleted,0) AS deleted, "
                    + "COALESCE(pinned,0) AS pinned "
                    + "FROM messages "
                    + "WHERE receiver IS NOT NULL "
                    + "AND COALESCE(deleted,0) = 0 "
                    + "AND ((sender COLLATE BINARY = ? COLLATE BINARY AND receiver COLLATE BINARY = ? COLLATE BINARY) "
                    + "OR (sender COLLATE BINARY = ? COLLATE BINARY AND receiver COLLATE BINARY = ? COLLATE BINARY)) "
                    + "ORDER BY id DESC LIMIT ?";
        }

        try (Connection conn = DatabaseManager.getConnection()) {

            // Không cho phép tìm lịch sử nhóm nếu tài khoản không phải
            // thành viên của phòng đó theo cơ sở dữ liệu.
            if ("G".equals(kind)) {
                try (PreparedStatement member = conn.prepareStatement(
                        "SELECT 1 FROM room_members "
                        + "WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {
                    member.setInt(1, roomId);
                    member.setString(2, client.getCurrentUsername());

                    try (ResultSet memberRs = member.executeQuery()) {
                        if (!memberRs.next()) {
                            sendSearchFailure(kind, key, queryToken,
                                    "Bạn không phải thành viên của phòng này nên không thể tìm lịch sử.");
                            return;
                        }
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if ("G".equals(kind)) {
                    ps.setInt(1, roomId);
                    ps.setInt(2, SEARCH_MAX_SCAN + 1);
                } else {
                    ps.setString(1, client.getCurrentUsername());
                    ps.setString(2, peer);
                    ps.setString(3, peer);
                    ps.setString(4, client.getCurrentUsername());
                    ps.setInt(5, SEARCH_MAX_SCAN + 1);
                }

                try (ResultSet rs = ps.executeQuery()) {
                    int scanned = 0;

                    while (rs.next()) {
                        if (scanned >= SEARCH_MAX_SCAN) {
                            truncated = true;
                            break;
                        }
                        scanned++;

                        String type = rs.getString("msg_type");
                        String rawContent = rs.getString("content");
                        String searchable;

                        if (Protocol.TYPE_FILE.equals(type)) {
                            searchable = extractSearchableFileName(rawContent);
                        } else {
                            try {
                                searchable = AESUtils.decrypt(
                                        rawContent == null ? "" : rawContent);
                            } catch (Exception decryptError) {
                                // Không tìm theo ciphertext khi không giải mã được.
                                searchable = "";
                            }
                        }

                        if (!normalizeSearchText(searchable)
                                .contains(normalizedQuery)) {
                            continue;
                        }

                        hits.add(new String[]{
                            String.valueOf(rs.getLong("id")),
                            rs.getString("sender"),
                            rs.getString("timestamp"),
                            type == null ? Protocol.TYPE_TEXT : type,
                            rawContent == null ? "" : rawContent,
                            String.valueOf(rs.getInt("edited")),
                            String.valueOf(rs.getInt("deleted")),
                            String.valueOf(rs.getInt("pinned"))
                        });

                        if (hits.size() >= SEARCH_MAX_RESULTS) {
                            truncated = true;
                            break;
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[MessageSearch] " + e.getMessage());
            sendSearchFailure(kind, key, queryToken,
                    "Không thể tìm trong lịch sử tin nhắn.");
            return;
        }

        client.sendMessage(Protocol.join(
                Protocol.SEARCH_BEGIN, kind, key, queryToken));

        for (String[] hit : hits) {
            String contentBase64 = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hit[4].getBytes(StandardCharsets.UTF_8));

            client.sendMessage(Protocol.join(
                    Protocol.SEARCH_ITEM,
                    kind,
                    key,
                    queryToken,
                    hit[0],
                    safeProtocolField(hit[1]),
                    safeProtocolField(hit[2]),
                    hit[3],
                    contentBase64,
                    hit[5],
                    hit[6],
                    hit[7]
            ));
        }

        client.sendMessage(Protocol.join(
                Protocol.SEARCH_END,
                kind,
                key,
                queryToken,
                hits.size(),
                truncated ? "1" : "0"
        ));
    }

    private void sendSearchFailure(
            String kind,
            String key,
            String queryToken,
            String reason) {

        String encodedReason = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (reason == null ? "Có lỗi tìm kiếm." : reason)
                                .getBytes(StandardCharsets.UTF_8));

        client.sendMessage(Protocol.join(
                Protocol.SEARCH_FAIL,
                safeProtocolField(kind),
                safeProtocolField(key),
                safeProtocolField(queryToken),
                encodedReason
        ));
    }

    /** Chuẩn hóa chữ hoa/thường và bỏ dấu tiếng Việt để tìm kiếm thuận tiện. */
    private String normalizeSearchText(String text) {
        if (text == null) {
            return "";
        }

        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String decomposed = Normalizer.normalize(
                lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "");
    }

    /** Đọc tên file từ nội dung metadata FILE:<id>:<nameBase64>:... */
    private String extractSearchableFileName(String content) {
        if (content == null || !content.startsWith("FILE:")) {
            return "";
        }

        String[] fields = content.split(":", 5);
        if (fields.length < 3) {
            return "";
        }

        try {
            return new String(
                    Base64.getUrlDecoder().decode(fields[2]),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Bảo đảm một trường giao thức không thể phá khung phân tách bằng dấu |. */
    private String safeProtocolField(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('|', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }


}
