package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Quản lý hồ sơ người dùng và ảnh đại diện. Hồ sơ luôn gắn với session đã
 * đăng nhập; client không được chỉ định username để cập nhật hồ sơ người khác.
 */
public final class UserProfileService {
    public static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final int AVATAR_CHUNK_BYTES = 8 * 1024;
    private static final Object SCHEMA_LOCK = new Object();
    private static volatile boolean schemaReady;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+() .-]{0,32}$");

    private final ClientHandler client;
    private ByteArrayOutputStream pendingAvatar;
    private long expectedAvatarSize;
    private String expectedAvatarHash;
    private int expectedChunkIndex;

    public UserProfileService(ClientHandler client) {
        this.client = client;
    }

    public void handleGetProfile(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts == null || parts.length != 2 || parts[1].trim().isEmpty()) {
            failProfile("Tên tài khoản cần xem không hợp lệ.");
            return;
        }
        String target = parts[1].trim();
        try {
            ensureSchema();
            String sql = "SELECT username, display_name, email, phone, birthday, gender, bio, "
                    + "length(avatar_data) AS avatar_size, avatar_sha256, public_id "
                    + "FROM users WHERE username COLLATE BINARY = ? COLLATE BINARY LIMIT 1";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        failProfile("Không tìm thấy tài khoản này.");
                        return;
                    }
                    String username = safe(rs.getString("username"));
                    long avatarSize = Math.max(0L, rs.getLong("avatar_size"));
                    String avatarHash = safe(rs.getString("avatar_sha256"));
                    boolean ownProfile = username.equals(client.getCurrentUsername());
                    // Email, số điện thoại, ngày sinh và giới tính chỉ được trả về cho chính chủ.
                    client.sendMessage(Protocol.join(
                            Protocol.PROFILE_DATA,
                            encode(username),
                            encode(safe(rs.getString("display_name"))),
                            encode(ownProfile ? safe(rs.getString("email")) : ""),
                            encode(ownProfile ? safe(rs.getString("phone")) : ""),
                            encode(ownProfile ? safe(rs.getString("birthday")) : ""),
                            encode(ownProfile ? safe(rs.getString("gender")) : ""),
                            encode(safe(rs.getString("bio"))),
                            avatarSize,
                            avatarHash,
                            encode(safe(rs.getString("public_id")))));
                }
            }
        } catch (Exception ex) {
            System.err.println("[UserProfileService] Không tải được hồ sơ: " + ex.getMessage());
            failProfile("Không thể tải hồ sơ người dùng.");
        }
    }

    public void handleUpdateProfile(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts == null || parts.length != 7) {
            failProfile("Dữ liệu hồ sơ không hợp lệ.");
            return;
        }

        final String displayName;
        final String email;
        final String phone;
        final String birthday;
        final String gender;
        final String bio;
        try {
            displayName = decode(parts[1]).trim();
            email = decode(parts[2]).trim();
            phone = decode(parts[3]).trim();
            birthday = decode(parts[4]).trim();
            gender = decode(parts[5]).trim();
            bio = decode(parts[6]).trim();
        } catch (Exception ex) {
            failProfile("Không giải mã được dữ liệu hồ sơ.");
            return;
        }

        String validation = validateProfile(displayName, email, phone, birthday, gender, bio);
        if (validation != null) {
            failProfile(validation);
            return;
        }

        try {
            ensureSchema();
            String sql = "UPDATE users SET display_name = ?, email = ?, phone = ?, birthday = ?, "
                    + "gender = ?, bio = ? WHERE username COLLATE BINARY = ? COLLATE BINARY";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, displayName);
                ps.setString(2, email);
                ps.setString(3, phone);
                ps.setString(4, birthday);
                ps.setString(5, gender);
                ps.setString(6, bio);
                ps.setString(7, client.getCurrentUsername());
                if (ps.executeUpdate() != 1) {
                    failProfile("Không tìm thấy tài khoản để cập nhật.");
                    return;
                }
            }
            client.sendMessage(Protocol.join(Protocol.PROFILE_UPDATE_OK,
                    encode(client.getCurrentUsername())));
            broadcastProfileChanged(client.getCurrentUsername());
            System.out.println("[UserProfile] Đã cập nhật hồ sơ: " + client.getCurrentUsername());
        } catch (Exception ex) {
            System.err.println("[UserProfileService] Lỗi cập nhật hồ sơ: " + ex.getMessage());
            failProfile("Không thể lưu hồ sơ vào cơ sở dữ liệu.");
        }
    }

    public void handleGetAvatar(String[] parts) {
        if (!client.requireLogin()) return;
        if (parts == null || parts.length != 2 || parts[1].trim().isEmpty()) {
            failAvatar("Tài khoản yêu cầu ảnh không hợp lệ.");
            return;
        }
        String target = parts[1].trim();
        try {
            ensureSchema();
            byte[] bytes = null;
            String hash = "";
            String actualUsername;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT username, avatar_data, avatar_sha256 FROM users "
                                 + "WHERE username COLLATE BINARY = ? COLLATE BINARY LIMIT 1")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        failAvatar("Không tìm thấy người dùng.");
                        return;
                    }
                    actualUsername = rs.getString("username");
                    bytes = rs.getBytes("avatar_data");
                    hash = safe(rs.getString("avatar_sha256"));
                }
            }
            int size = bytes == null ? 0 : bytes.length;
            if (size > MAX_AVATAR_BYTES) {
                failAvatar("Ảnh đại diện trong dữ liệu vượt giới hạn cho phép.");
                return;
            }
            String usernameToken = encode(actualUsername);
            client.sendMessage(Protocol.join(Protocol.USER_AVATAR_BEGIN,
                    usernameToken, size, hash));
            if (bytes != null) {
                int sequence = 0;
                for (int offset = 0; offset < bytes.length; offset += AVATAR_CHUNK_BYTES) {
                    int length = Math.min(AVATAR_CHUNK_BYTES, bytes.length - offset);
                    byte[] chunk = new byte[length];
                    System.arraycopy(bytes, offset, chunk, 0, length);
                    client.sendMessage(Protocol.join(Protocol.USER_AVATAR_CHUNK,
                            usernameToken, sequence++, Base64.getEncoder().encodeToString(chunk)));
                }
            }
            client.sendMessage(Protocol.join(Protocol.USER_AVATAR_END, usernameToken));
        } catch (Exception ex) {
            System.err.println("[UserProfileService] Không tải được avatar: " + ex.getMessage());
            failAvatar("Không thể tải ảnh đại diện.");
        }
    }

    public synchronized void handleAvatarUploadBegin(String[] parts) {
        if (!client.requireLogin()) return;
        resetPendingAvatar();
        if (parts == null || parts.length != 3) {
            failAvatarUpload("Thông tin bắt đầu tải ảnh không hợp lệ.");
            return;
        }
        try {
            long size = Long.parseLong(parts[1]);
            String hash = parts[2].trim().toLowerCase(Locale.ROOT);
            if (size <= 0 || size > MAX_AVATAR_BYTES) {
                failAvatarUpload("Ảnh đại diện phải lớn hơn 0 và không vượt quá 512 KB.");
                return;
            }
            if (!hash.matches("[0-9a-f]{64}")) {
                failAvatarUpload("Mã SHA-256 của ảnh không hợp lệ.");
                return;
            }
            expectedAvatarSize = size;
            expectedAvatarHash = hash;
            expectedChunkIndex = 0;
            pendingAvatar = new ByteArrayOutputStream((int) size);
        } catch (NumberFormatException ex) {
            failAvatarUpload("Kích thước ảnh không hợp lệ.");
        }
    }

    public synchronized void handleAvatarUploadChunk(String[] parts) {
        if (!client.requireLogin()) return;
        if (pendingAvatar == null || parts == null || parts.length != 3) {
            resetPendingAvatar();
            failAvatarUpload("Không có phiên tải ảnh hợp lệ.");
            return;
        }
        try {
            int index = Integer.parseInt(parts[1]);
            if (index != expectedChunkIndex) {
                resetPendingAvatar();
                failAvatarUpload("Thứ tự dữ liệu ảnh không hợp lệ.");
                return;
            }
            byte[] chunk = Base64.getDecoder().decode(parts[2]);
            if ((long) pendingAvatar.size() + chunk.length > expectedAvatarSize
                    || (long) pendingAvatar.size() + chunk.length > MAX_AVATAR_BYTES) {
                resetPendingAvatar();
                failAvatarUpload("Dữ liệu ảnh vượt kích thước đã khai báo.");
                return;
            }
            pendingAvatar.write(chunk);
            expectedChunkIndex++;
        } catch (Exception ex) {
            resetPendingAvatar();
            failAvatarUpload("Dữ liệu ảnh bị lỗi hoặc không đúng Base64.");
        }
    }

    public synchronized void handleAvatarUploadEnd() {
        if (!client.requireLogin()) return;
        if (pendingAvatar == null) {
            failAvatarUpload("Không có ảnh để lưu.");
            return;
        }
        byte[] bytes = pendingAvatar.toByteArray();
        long expectedSize = expectedAvatarSize;
        String expectedHash = expectedAvatarHash;
        resetPendingAvatar();

        if (bytes.length != expectedSize) {
            failAvatarUpload("Kích thước ảnh nhận được không khớp.");
            return;
        }
        try {
            String actualHash = sha256(bytes);
            if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                    actualHash.getBytes(StandardCharsets.US_ASCII))) {
                failAvatarUpload("Ảnh bị thay đổi khi truyền (SHA-256 không khớp).");
                return;
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
                    || image.getWidth() > 1024 || image.getHeight() > 1024
                    || (long) image.getWidth() * image.getHeight() > 1_000_000L) {
                failAvatarUpload("Ảnh không hợp lệ hoặc kích thước ảnh quá lớn.");
                return;
            }

            ensureSchema();
            String sql = "UPDATE users SET avatar_data = ?, avatar_sha256 = ? "
                    + "WHERE username COLLATE BINARY = ? COLLATE BINARY";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, bytes);
                ps.setString(2, actualHash);
                ps.setString(3, client.getCurrentUsername());
                if (ps.executeUpdate() != 1) {
                    failAvatarUpload("Không tìm thấy tài khoản để lưu ảnh.");
                    return;
                }
            }
            client.sendMessage(Protocol.join(Protocol.USER_AVATAR_UPLOAD_OK,
                    encode(client.getCurrentUsername())));
            broadcastProfileChanged(client.getCurrentUsername());
            System.out.println("[UserProfile] Đã cập nhật avatar: " + client.getCurrentUsername()
                    + " bytes=" + bytes.length);
        } catch (Exception ex) {
            System.err.println("[UserProfileService] Lỗi lưu avatar: " + ex.getMessage());
            failAvatarUpload("Không thể lưu ảnh đại diện.");
        }
    }

    private static String validateProfile(String displayName, String email, String phone,
                                          String birthday, String gender, String bio) {
        if (displayName.length() > 80) return "Tên hiển thị tối đa 80 ký tự.";
        if (email.length() > 254 || (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches())) {
            return "Email không hợp lệ.";
        }
        if (!PHONE_PATTERN.matcher(phone).matches()) return "Số điện thoại không hợp lệ.";
        if (!birthday.isEmpty()) {
            try { LocalDate.parse(birthday); }
            catch (DateTimeParseException ex) { return "Ngày sinh phải có định dạng yyyy-MM-dd."; }
        }
        if (!(gender.isEmpty() || gender.equals("Nam") || gender.equals("Nữ")
                || gender.equals("Khác") || gender.equals("Không muốn nêu"))) {
            return "Giá trị giới tính không hợp lệ.";
        }
        if (bio.length() > 500) return "Giới thiệu bản thân tối đa 500 ký tự.";
        return null;
    }

    private static void ensureSchema() throws SQLException {
        if (schemaReady) return;
        synchronized (SCHEMA_LOCK) {
            if (schemaReady) return;
            try (Connection conn = DatabaseManager.getConnection()) {
                ensureColumn(conn, "display_name", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(conn, "email", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(conn, "phone", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(conn, "birthday", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(conn, "gender", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(conn, "bio", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(conn, "avatar_data", "BLOB");
                ensureColumn(conn, "avatar_sha256", "TEXT NOT NULL DEFAULT ''");
            }
            schemaReady = true;
        }
    }

    private static void ensureColumn(Connection conn, String name, String definition) throws SQLException {
        boolean exists = false;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA table_info(users)")) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
            }
        }
        if (!exists) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN " + name + " " + definition);
            }
        }
    }

    private void broadcastProfileChanged(String username) {
        String packet = Protocol.join(Protocol.USER_PROFILE_CHANGED, encode(username));
        for (ClientHandler recipient : ChatServer.onlineClients.values()) {
            if (recipient == null) continue;
            String recipientName = recipient.getCurrentUsername();
            if (recipientName != null && !recipientName.equals(username)) {
                recipient.sendMessage(packet);
            }
        }
    }

    private void failProfile(String message) {
        client.sendMessage(Protocol.join(Protocol.PROFILE_UPDATE_FAIL, encode(message)));
    }

    private void failAvatar(String message) {
        client.sendMessage(Protocol.join(Protocol.USER_AVATAR_FAIL, encode(message)));
    }

    private void failAvatarUpload(String message) {
        client.sendMessage(Protocol.join(Protocol.USER_AVATAR_UPLOAD_FAIL, encode(message)));
    }

    private synchronized void resetPendingAvatar() {
        if (pendingAvatar != null) {
            try { pendingAvatar.close(); } catch (Exception ignored) { }
        }
        pendingAvatar = null;
        expectedAvatarSize = 0;
        expectedAvatarHash = null;
        expectedChunkIndex = 0;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(safe(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }
}
