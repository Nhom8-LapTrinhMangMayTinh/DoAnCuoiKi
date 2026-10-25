package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;
import com.chatapp.security.PasswordHasher;
import com.chatapp.security.PasswordPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Xử lý đăng ký, đăng nhập, khóa tài khoản tạm thời và đổi mật khẩu.
 * Mọi kiểm tra bảo mật quan trọng được thực hiện ở server, không dựa riêng vào UI.
 */
public final class AuthService {
    private static final Object AUTH_MONITOR = new Object();
    private static final Object SCHEMA_MONITOR = new Object();
    private static volatile boolean authSchemaReady;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_MILLIS = 15L * 60L * 1000L;

    private final ClientHandler client;

    public AuthService(ClientHandler client) {
        this.client = client;
    }

    public void handleLogin(String[] parts) {
        if (parts == null || parts.length != 3) {
            sendAuthFail("Thông tin đăng nhập không hợp lệ.");
            return;
        }

        String requestedUsername = parts[1] == null ? "" : parts[1].trim();
        String password = parts[2] == null ? "" : parts[2];
        if (requestedUsername.isEmpty() || password.isEmpty()) {
            sendAuthFail("Vui lòng nhập tên đăng nhập và mật khẩu.");
            return;
        }
        if (requestedUsername.length() > 64 || requestedUsername.contains("|")) {
            sendAuthFail("Thông tin đăng nhập không hợp lệ.");
            return;
        }
        if (client.getCurrentUsername() != null) {
            sendAuthFail("Kết nối này đã đăng nhập rồi.");
            return;
        }

        synchronized (AUTH_MONITOR) {
            try {
                ensureAuthSchema();
                Account account = findAccount(requestedUsername);
                if (account == null) {
                    // Không phân biệt rõ tài khoản không tồn tại và mật khẩu sai.
                    sendAuthFail("Tên đăng nhập hoặc mật khẩu không chính xác.");
                    return;
                }

                long now = System.currentTimeMillis();
                int failedAttempts = account.failedAttempts;
                long lockedUntil = account.lockedUntil;

                if (account.adminLocked) {
                    sendAuthFail("Tài khoản đã bị quản trị viên khóa.");
                    return;
                }

                if (lockedUntil > now) {
                    sendAuthFail("Tài khoản đang bị khóa tạm thời. Vui lòng thử lại sau "
                            + formatRemaining(lockedUntil - now) + ".");
                    return;
                }

                // Nếu thời gian khóa đã hết, bắt đầu lại bộ đếm từ 0.
                if (lockedUntil != 0L || failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    resetLoginFailures(account.username);
                    failedAttempts = 0;
                }

                if (!PasswordHasher.verify(password, account.passwordHash)) {
                    int nextAttempts = failedAttempts + 1;
                    if (nextAttempts >= MAX_FAILED_ATTEMPTS) {
                        long newLockedUntil = now + LOCK_MILLIS;
                        saveLoginFailure(account.username, nextAttempts, newLockedUntil);
                        sendAuthFail("Bạn đã nhập sai mật khẩu " + MAX_FAILED_ATTEMPTS
                                + " lần. Tài khoản bị khóa trong 15 phút.");
                    } else {
                        saveLoginFailure(account.username, nextAttempts, 0L);
                        sendAuthFail("Tên đăng nhập hoặc mật khẩu không chính xác. Còn "
                                + (MAX_FAILED_ATTEMPTS - nextAttempts) + " lần thử trước khi bị khóa.");
                    }
                    return;
                }

                // Nâng cấp hash SHA-256 cũ sang PBKDF2 ngay sau lần đăng nhập hợp lệ.
                String replacementHash = account.passwordHash;
                if (!PasswordHasher.isModernHash(account.passwordHash)) {
                    replacementHash = PasswordHasher.hash(password);
                }
                updatePasswordAndResetFailures(account.username, replacementHash);

                // Cho phép phiên đăng nhập mới thay thế phiên cũ một cách rõ ràng.
                // CallService được gọi trong quá trình ngắt phiên cũ để kết thúc cuộc gọi đang chạy.
                synchronized (ChatServer.onlineClients) {
                    ClientHandler existing = ChatServer.onlineClients.get(account.username);
                    if (existing != null && existing != client) {
                        existing.forceDisconnect("Phiên đăng nhập này đã được thay thế bởi một lần đăng nhập mới.");
                    }
                    ChatServer.onlineClients.put(account.username, client);
                }

                client.setCurrentUsername(account.username);
                client.sendMessage(Protocol.join(Protocol.AUTH_OK, account.username));
                client.sendMessage(Protocol.join(Protocol.ADMIN_STATUS,
                        AdminService.isAdmin(account.username) ? "1" : "0"));
                AdminService.recordLoginSuccess(account.username, client);
                System.out.println("[Auth] Đăng nhập thành công: " + account.username);
            } catch (Exception ex) {
                System.err.println("[Auth] Lỗi đăng nhập: " + ex.getMessage());
                sendAuthFail("Không thể xử lý đăng nhập do lỗi cơ sở dữ liệu.");
            }
        }
    }

    public void handleRegister(String[] parts) {
        if (parts == null || parts.length != 4) {
            sendRegisterFail("Vui lòng nhập tên đăng nhập, mật khẩu và xác nhận mật khẩu.");
            return;
        }

        String username = parts[1] == null ? "" : parts[1].trim();
        String password = parts[2] == null ? "" : parts[2];
        String confirmPassword = parts[3] == null ? "" : parts[3];

        String usernameError = PasswordPolicy.usernameError(username);
        if (usernameError != null) {
            sendRegisterFail(usernameError);
            return;
        }
        String passwordError = PasswordPolicy.passwordError(password, username);
        if (passwordError != null) {
            sendRegisterFail(passwordError);
            return;
        }
        if (!password.equals(confirmPassword)) {
            sendRegisterFail("Mật khẩu xác nhận không khớp.");
            return;
        }

        synchronized (AUTH_MONITOR) {
            try {
                ensureAuthSchema();
                if (findAccount(username) != null) {
                    sendRegisterFail("Tên đăng nhập đã tồn tại. Vui lòng chọn tên khác.");
                    return;
                }

                String hash = PasswordHasher.hash(password);
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "INSERT INTO users(username, password, failed_attempts, locked_until) "
                                     + "VALUES(?, ?, 0, 0)")) {
                    ps.setString(1, username);
                    ps.setString(2, hash);
                    ps.executeUpdate();
                }

                client.sendMessage(Protocol.join(
                        Protocol.REGISTER_OK,
                        "Đăng ký thành công. Bạn có thể đăng nhập."));
                System.out.println("[Auth] Đăng ký tài khoản mới: " + username);
            } catch (SQLException ex) {
                if (isConstraintError(ex)) {
                    sendRegisterFail("Tên đăng nhập đã tồn tại. Vui lòng chọn tên khác.");
                } else {
                    System.err.println("[Auth] Lỗi đăng ký: " + ex.getMessage());
                    sendRegisterFail("Không thể đăng ký do lỗi cơ sở dữ liệu.");
                }
            } catch (Exception ex) {
                System.err.println("[Auth] Lỗi đăng ký: " + ex.getMessage());
                sendRegisterFail("Không thể hoàn tất đăng ký.");
            }
        }
    }

    public void handleChangePassword(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }
        if (parts == null || parts.length != 4) {
            sendChangePasswordFail("Yêu cầu đổi mật khẩu không hợp lệ.");
            return;
        }

        String oldPassword = parts[1] == null ? "" : parts[1];
        String newPassword = parts[2] == null ? "" : parts[2];
        String confirmPassword = parts[3] == null ? "" : parts[3];
        String username = client.getCurrentUsername();

        if (oldPassword.isEmpty()) {
            sendChangePasswordFail("Vui lòng nhập mật khẩu hiện tại.");
            return;
        }
        String passwordError = PasswordPolicy.passwordError(newPassword, username);
        if (passwordError != null) {
            sendChangePasswordFail(passwordError);
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            sendChangePasswordFail("Mật khẩu mới và phần xác nhận không khớp.");
            return;
        }
        if (oldPassword.equals(newPassword)) {
            sendChangePasswordFail("Mật khẩu mới phải khác mật khẩu hiện tại.");
            return;
        }

        synchronized (AUTH_MONITOR) {
            try {
                ensureAuthSchema();
                Account account = findAccount(username);
                if (account == null || !PasswordHasher.verify(oldPassword, account.passwordHash)) {
                    sendChangePasswordFail("Mật khẩu hiện tại không chính xác.");
                    return;
                }

                String newHash = PasswordHasher.hash(newPassword);
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "UPDATE users SET password = ?, failed_attempts = 0, locked_until = 0 "
                                     + "WHERE username COLLATE BINARY = ? COLLATE BINARY")) {
                    ps.setString(1, newHash);
                    ps.setString(2, account.username);
                    if (ps.executeUpdate() != 1) {
                        sendChangePasswordFail("Không tìm thấy tài khoản để cập nhật mật khẩu.");
                        return;
                    }
                }

                client.sendMessage(Protocol.join(
                        Protocol.CHANGE_PASSWORD_OK,
                        "Đổi mật khẩu thành công."));
                System.out.println("[Auth] Đổi mật khẩu thành công cho: " + username);
            } catch (Exception ex) {
                System.err.println("[Auth] Lỗi đổi mật khẩu: " + ex.getMessage());
                sendChangePasswordFail("Không thể đổi mật khẩu do lỗi cơ sở dữ liệu.");
            }
        }
    }

    private static void ensureAuthSchema() throws SQLException {
        if (authSchemaReady) {
            return;
        }
        synchronized (SCHEMA_MONITOR) {
            if (authSchemaReady) {
                return;
            }
            try (Connection conn = DatabaseManager.getConnection()) {
                ensureColumn(conn, "failed_attempts", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(conn, "locked_until", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(conn, "is_admin", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(conn, "admin_locked", "INTEGER NOT NULL DEFAULT 0");

                // Ràng buộc username BINARY (trigger + unique index khi có thể) được tạo một lần
                // lúc DatabaseManager.initializeDatabase() khởi động server. Không thử tạo
                // unique index lại ở đây vì DB cũ có thể đang chứa tài khoản trùng chính xác tên đăng nhập.
                authSchemaReady = true;
            }
        }
    }

    private static void ensureColumn(Connection conn, String name, String definition) throws SQLException {
        boolean exists = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(users)")) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN " + name + " " + definition);
            }
        }
    }

    private static Account findAccount(String username) throws SQLException {
        String sql = "SELECT username, password, failed_attempts, locked_until, admin_locked "
                + "FROM users WHERE username COLLATE BINARY = ? COLLATE BINARY LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Account(
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getInt("failed_attempts"),
                        rs.getLong("locked_until"),
                        rs.getInt("admin_locked") == 1);
            }
        }
    }

    private static void saveLoginFailure(String username, int count, long lockedUntil) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET failed_attempts = ?, locked_until = ? "
                             + "WHERE username COLLATE BINARY = ? COLLATE BINARY")) {
            ps.setInt(1, count);
            ps.setLong(2, lockedUntil);
            ps.setString(3, username);
            ps.executeUpdate();
        }
    }

    private static void resetLoginFailures(String username) throws SQLException {
        saveLoginFailure(username, 0, 0L);
    }

    private static void updatePasswordAndResetFailures(String username, String hash) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET password = ?, failed_attempts = 0, locked_until = 0 "
                             + "WHERE username COLLATE BINARY = ? COLLATE BINARY")) {
            ps.setString(1, hash);
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    private static boolean isConstraintError(SQLException ex) {
        String message = ex.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("unique") || lower.contains("constraint") || lower.contains("username already exists");
    }

    private static String formatRemaining(long millis) {
        long seconds = Math.max(1L, (millis + 999L) / 1000L);
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        if (minutes > 0L) {
            return minutes + " phút " + remainder + " giây";
        }
        return seconds + " giây";
    }

    private void sendAuthFail(String message) {
        AdminService.recordLoginFailure(message, client);
        client.sendMessage(Protocol.join(Protocol.AUTH_FAIL, Protocol.clean(message)));
    }

    private void sendRegisterFail(String message) {
        client.sendMessage(Protocol.join(Protocol.REGISTER_FAIL, message));
    }

    private void sendChangePasswordFail(String message) {
        client.sendMessage(Protocol.join(Protocol.CHANGE_PASSWORD_FAIL, message));
    }

    private static final class Account {
        private final String username;
        private final String passwordHash;
        private final int failedAttempts;
        private final long lockedUntil;
        private final boolean adminLocked;

        private Account(String username, String passwordHash, int failedAttempts, long lockedUntil,
                        boolean adminLocked) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.failedAttempts = failedAttempts;
            this.lockedUntil = lockedUntil;
            this.adminLocked = adminLocked;
        }
    }
}
