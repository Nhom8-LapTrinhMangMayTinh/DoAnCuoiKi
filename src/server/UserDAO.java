package server;

import common.CryptoUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserDAO {

    public enum Auth { OK, BAD, DISABLED }

    /** Ket qua xac thuc: kem Models.User khi OK / DISABLED. */
    public static class AuthResult {
        public Auth status = Auth.BAD;
        public Models.User user;
    }

    private static final String USER_COLS =
        "userID,username,COALESCE(display_name,''),COALESCE(server_role,'USER'),COALESCE(disabled,0),COALESCE(avatar_ver,0),COALESCE(last_seen,0)";

    private static Models.User map(ResultSet rs) throws SQLException {
        Models.User u = new Models.User();
        u.id = rs.getInt(1);
        u.username = rs.getString(2);
        u.displayName = rs.getString(3);
        u.serverRole = rs.getString(4);
        u.disabled = rs.getInt(5) != 0;
        u.avatarVer = rs.getInt(6);
        u.lastSeen = rs.getLong(7);
        return u;
    }

    public boolean exists(String username) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM Users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public int register(String username, String plainPassword) throws SQLException {
        return register(username, plainPassword, "USER");
    }

    /** Dang ky. Mat khau duoc bam PBKDF2 + salt ngau nhien. Tra ve userID, hoac -1 neu da ton tai. */
    public int register(String username, String plainPassword, String serverRole) throws SQLException {
        if (exists(username)) return -1;
        String hash = CryptoUtil.hashPassword(plainPassword); // tinh truoc khi mo ket noi DB
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO Users(username,password_hash,status,created_at,server_role) VALUES(?,?, 'OFFLINE', ?, ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, serverRole);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getInt(1) : -1; }
        } catch (SQLException e) {
            if (exists(username)) return -1; // dua nhau dang ky cung luc
            throw e;
        }
    }

    // Hash gia dung de dang nhap voi user khong ton tai van ton thoi gian nhu user that (chong do ten tai khoan qua thoi gian phan hoi).
    private static final String DUMMY_HASH = CryptoUtil.hashPassword("dummy-password-for-timing");

    public AuthResult authenticate(String username, String plainPassword) throws SQLException {
        AuthResult res = new AuthResult();
        String stored = null;
        Models.User u = null;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + ",password_hash FROM Users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { u = map(rs); stored = rs.getString(8); }
            }
        }
        if (u == null) { CryptoUtil.verifyPassword(plainPassword, DUMMY_HASH); return res; }
        if (!CryptoUtil.verifyPassword(plainPassword, stored)) return res;
        if (CryptoUtil.needsRehash(stored)) setPassword(u.id, plainPassword); // nang cap hash cu (SHA-256 khong salt) len PBKDF2
        res.user = u;
        res.status = u.disabled ? Auth.DISABLED : Auth.OK;
        return res;
    }

    public Models.User find(String username) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM Users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public Models.User findById(int id) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM Users WHERE userID=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public boolean checkPassword(int userId, String plain) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM Users WHERE userID=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && CryptoUtil.verifyPassword(plain, rs.getString(1)); }
        }
    }

    public void setPassword(int userId, String plain) throws SQLException {
        String hash = CryptoUtil.hashPassword(plain);
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Users SET password_hash=? WHERE userID=?")) {
            ps.setString(1, hash);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public void setStatus(int userId, boolean online) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Users SET status=?, last_seen=? WHERE userID=?")) {
            ps.setString(1, online ? "ONLINE" : "OFFLINE");
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    public void resetAllOffline() throws SQLException {
        try (Connection c = DatabaseManager.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE Users SET status='OFFLINE'");
        }
    }

    public void setDisabled(int userId, boolean disabled) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Users SET disabled=? WHERE userID=?")) {
            ps.setInt(1, disabled ? 1 : 0);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public void setServerRole(int userId, String role) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Users SET server_role=? WHERE userID=?")) {
            ps.setString(1, role);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    public void setDisplayName(int userId, String name) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Users SET display_name=? WHERE userID=?")) {
            ps.setString(1, name);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    /** Luu anh dai dien (base64) va tang so phien ban de client biet khi nao phai tai lai. Tra ve phien ban moi. */
    public int setAvatar(int userId, String b64) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Users SET avatar=?, avatar_ver=COALESCE(avatar_ver,0)+1 WHERE userID=?")) {
            ps.setString(1, b64);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
        Models.User u = findById(userId);
        return u == null ? 0 : u.avatarVer;
    }

    /** {version, base64} hoac null. */
    public String[] getAvatar(String username) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(avatar_ver,0), avatar FROM Users WHERE username=?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString(2) == null) return null;
                return new String[]{String.valueOf(rs.getInt(1)), rs.getString(2)};
            }
        }
    }

    public List<Models.User> listUsers(int limit) throws SQLException {
        List<Models.User> list = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + USER_COLS + " FROM Users ORDER BY username LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        }
        return list;
    }

    public List<String> listUsernames(int limit) throws SQLException {
        List<String> list = new ArrayList<>();
        for (Models.User u : listUsers(limit)) list.add(u.username);
        return list;
    }

    /** Danh sach cho trang quan tri: {username, serverRole, disabled, lastSeen, messageCount}. */
    public List<long[]> adminCounts(List<String> namesOut) throws SQLException {
        List<long[]> rows = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.username, COALESCE(u.disabled,0), COALESCE(u.last_seen,0), " +
                 "(SELECT COUNT(*) FROM Messages m WHERE m.senderID=u.userID) FROM Users u ORDER BY u.username LIMIT 2000");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                namesOut.add(rs.getString(1));
                rows.add(new long[]{rs.getInt(2), rs.getLong(3), rs.getLong(4)});
            }
        }
        return rows;
    }

    public int countUsers() throws SQLException {
        try (Connection c = DatabaseManager.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM Users")) { return rs.next() ? rs.getInt(1) : 0; }
    }
}
