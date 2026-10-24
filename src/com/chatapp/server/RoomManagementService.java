package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;

/** Quản lý ảnh đại diện phòng và giải tán phòng. */
public final class RoomManagementService {
    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final int CHUNK_BYTES = 8 * 1024;
    private final ClientHandler client;
    private int pendingRoomId = -1;
    private int expectedSize;
    private int expectedSequence;
    private String expectedHash = "";
    private ByteArrayOutputStream pendingBytes;

    RoomManagementService(ClientHandler client) { this.client = client; }

    public synchronized void handleAvatarUploadBegin(String[] p) {
        if (!client.requireLogin()) return;
        if (p == null || p.length != 4) { uploadFail("Dữ liệu ảnh không hợp lệ."); return; }
        try {
            int roomId = Integer.parseInt(p[1]);
            int size = Integer.parseInt(p[2]);
            String hash = p[3];
            if (roomId <= 0 || size <= 0 || size > MAX_AVATAR_BYTES || !isOwner(roomId)) {
                uploadFail("Chỉ chủ phòng mới được đổi ảnh; ảnh tối đa 512 KB."); return;
            }
            pendingRoomId = roomId;
            expectedSize = size;
            expectedHash = hash;
            expectedSequence = 0;
            pendingBytes = new ByteArrayOutputStream(size);
        } catch (Exception ex) { resetUpload(); uploadFail("Không thể bắt đầu tải ảnh lên."); }
    }

    public synchronized void handleAvatarUploadChunk(String[] p) {
        if (pendingBytes == null || p == null || p.length != 3) { uploadFail("Phiên tải ảnh không hợp lệ."); return; }
        try {
            int seq = Integer.parseInt(p[1]);
            byte[] bytes = Base64.getDecoder().decode(p[2]);
            if (seq != expectedSequence || pendingBytes.size() + bytes.length > expectedSize) {
                resetUpload(); uploadFail("Dữ liệu ảnh bị sai thứ tự hoặc quá lớn."); return;
            }
            pendingBytes.write(bytes);
            expectedSequence++;
        } catch (Exception ex) { resetUpload(); uploadFail("Không đọc được dữ liệu ảnh."); }
    }

    public synchronized void handleAvatarUploadEnd() {
        if (pendingBytes == null) { uploadFail("Không có ảnh đang tải lên."); return; }
        int roomId = pendingRoomId;
        byte[] bytes = pendingBytes.toByteArray();
        String hash = expectedHash;
        int size = expectedSize;
        resetUpload();
        try {
            if (bytes.length != size || !sha256(bytes).equalsIgnoreCase(hash)) {
                uploadFail("Ảnh tải lên không đầy đủ hoặc không khớp mã kiểm tra."); return;
            }
            if (!isOwner(roomId)) { uploadFail("Bạn không còn quyền đổi ảnh phòng."); return; }
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE rooms SET avatar_data = ?, avatar_sha256 = ? WHERE id = ? AND owner COLLATE BINARY = ? COLLATE BINARY")) {
                ps.setBytes(1, bytes); ps.setString(2, hash); ps.setInt(3, roomId);
                ps.setString(4, client.getCurrentUsername());
                if (ps.executeUpdate() != 1) { uploadFail("Không tìm thấy phòng hoặc bạn không phải chủ phòng."); return; }
            }
            client.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_UPLOAD_OK, roomId));
            sendAvatarToRoom(roomId, bytes, hash);
        } catch (Exception ex) {
            System.err.println("[RoomManagementService] Lỗi lưu ảnh phòng: " + ex.getMessage());
            uploadFail("Không thể lưu ảnh đại diện phòng.");
        }
    }

    public void handleGetRoomAvatar(String[] p) {
        if (!client.requireLogin()) return;
        if (p == null || p.length != 2) { avatarFail("ID phòng không hợp lệ."); return; }
        try {
            int roomId = Integer.parseInt(p[1]);
            if (!isRoomMember(roomId)) { avatarFail("Bạn chưa tham gia phòng này."); return; }
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT avatar_data, avatar_sha256 FROM rooms WHERE id = ?")) {
                ps.setInt(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { avatarFail("Không tìm thấy phòng."); return; }
                    byte[] bytes = rs.getBytes("avatar_data");
                    String hash = rs.getString("avatar_sha256");
                    if (bytes == null || bytes.length == 0) {
                        client.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_BEGIN, roomId, 0, ""));
                        client.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_END, roomId));
                    } else sendAvatar(client, roomId, bytes, hash == null ? sha256(bytes) : hash);
                }
            }
        } catch (Exception ex) { avatarFail("Không thể tải ảnh đại diện phòng."); }
    }

    public void handleDissolveRoom(String[] p) {
        if (!client.requireLogin()) return;
        if (p == null || p.length != 2) { dissolveFail("ID phòng không hợp lệ."); return; }
        final int roomId;
        try { roomId = Integer.parseInt(p[1]); }
        catch (NumberFormatException ex) { dissolveFail("ID phòng không hợp lệ."); return; }
        if (roomId <= 0) { dissolveFail("ID phòng không hợp lệ."); return; }
        String roomName;
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT room_name, owner FROM rooms WHERE id = ?")) {
                    ps.setInt(1, roomId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); dissolveFail("Phòng không còn tồn tại."); return; }
                        roomName = rs.getString("room_name");
                        if (!client.getCurrentUsername().equals(rs.getString("owner"))) {
                            conn.rollback(); dissolveFail("Chỉ chủ phòng mới được giải tán phòng."); return;
                        }
                    }
                }
                // Dọn dữ liệu phụ thuộc phòng trước khi xóa phòng.
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM messages WHERE room_id = ?")) {
                    ps.setInt(1, roomId); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM read_states WHERE conversation_type = 'G' AND conversation_key = ?")) {
                    ps.setString(1, String.valueOf(roomId)); ps.executeUpdate();
                } catch (Exception ignored) { /* schema cũ có thể không có các cột này */ }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM room_members WHERE room_id = ?")) {
                    ps.setInt(1, roomId); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rooms WHERE id = ?")) {
                    ps.setInt(1, roomId); ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception ex) { conn.rollback(); throw ex; }
        } catch (Exception ex) {
            System.err.println("[RoomManagementService] Lỗi giải tán phòng: " + ex.getMessage());
            dissolveFail("Không thể giải tán phòng."); return;
        }
        GroupCallService.onRoomDissolved(roomId);
        CopyOnWriteArrayList<ClientHandler> members = ChatServer.roomMembers.remove(roomId);
        if (members != null) members.clear();
        // Tất cả client cần cập nhật danh sách phòng, kể cả người chưa tham gia phòng.
        String notice = Protocol.join(Protocol.ROOM_DISSOLVED, roomId, encode(roomName));
        for (ClientHandler online : ChatServer.onlineClients.values()) online.sendMessage(notice);
    }

    private void sendAvatarToRoom(int roomId, byte[] bytes, String hash) {
        CopyOnWriteArrayList<ClientHandler> members = ChatServer.roomMembers.get(roomId);
        if (members != null) for (ClientHandler member : members) sendAvatar(member, roomId, bytes, hash);
    }

    private void sendAvatar(ClientHandler target, int roomId, byte[] bytes, String hash) {
        target.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_BEGIN, roomId, bytes.length, hash));
        int sequence = 0;
        for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, bytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, offset, chunk, 0, length);
            target.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_CHUNK, roomId, sequence++, Base64.getEncoder().encodeToString(chunk)));
        }
        target.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_END, roomId));
    }

    private boolean isOwner(int roomId) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM rooms WHERE id = ? AND owner COLLATE BINARY = ? COLLATE BINARY")) {
            ps.setInt(1, roomId); ps.setString(2, client.getCurrentUsername());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private boolean isRoomMember(int roomId) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM room_members WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {
            ps.setInt(1, roomId); ps.setString(2, client.getCurrentUsername());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
    private String encode(String s) { return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private void resetUpload() { pendingRoomId = -1; expectedSize = 0; expectedSequence = 0; expectedHash = ""; pendingBytes = null; }
    private void uploadFail(String message) { client.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_UPLOAD_FAIL, encode(message))); }
    private void avatarFail(String message) { client.sendMessage(Protocol.join(Protocol.ROOM_AVATAR_FAIL, encode(message))); }
    private void dissolveFail(String message) { client.sendMessage(Protocol.join(Protocol.ROOM_DISSOLVE_FAIL, encode(message))); }
}
