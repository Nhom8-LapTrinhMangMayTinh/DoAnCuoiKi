package server;

import common.CryptoUtil;
import common.Protocol;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RoomDAO {

    /** Tao phong, nguoi tao tro thanh OWNER. Tra ve roomID hoac -1 neu trung ten. */
    public int create(String name, String plainPassword, int ownerId) throws SQLException {
        final String hashed = (plainPassword == null || plainPassword.isEmpty()) ? null : CryptoUtil.hashPassword(plainPassword);
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement chk = c.prepareStatement("SELECT 1 FROM Rooms WHERE roomName=?")) {
                    chk.setString(1, name);
                    try (ResultSet rs = chk.executeQuery()) { if (rs.next()) { c.rollback(); return -1; } }
                }
                int roomId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO Rooms(roomName,password_hash,ownerID,created_at) VALUES(?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    if (plainPassword == null || plainPassword.isEmpty()) ps.setNull(2, Types.VARCHAR);
                    else ps.setString(2, hashed);
                    ps.setInt(3, ownerId);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); roomId = rs.getInt(1); }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO RoomMembers(roomID,userID,role,joined_at) VALUES(?,?,?,?)")) {
                    ps.setInt(1, roomId);
                    ps.setInt(2, ownerId);
                    ps.setString(3, Protocol.ROLE_OWNER);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
                return roomId;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static final String ROOM_SELECT =
        "SELECT r.roomID, r.roomName, r.password_hash IS NOT NULL, r.ownerID, u.username, " +
        "(SELECT COUNT(*) FROM RoomMembers m WHERE m.roomID=r.roomID), " +
        "COALESCE((SELECT role FROM RoomMembers m WHERE m.roomID=r.roomID AND m.userID=?), '') " +
        "FROM Rooms r JOIN Users u ON u.userID=r.ownerID ";

    private Models.RoomInfo map(ResultSet rs) throws SQLException {
        Models.RoomInfo r = new Models.RoomInfo();
        r.id = rs.getInt(1);
        r.name = rs.getString(2);
        r.locked = rs.getInt(3) != 0;
        r.ownerId = rs.getInt(4);
        r.ownerName = rs.getString(5);
        r.members = rs.getInt(6);
        r.myRole = rs.getString(7);
        return r;
    }

    public List<Models.RoomInfo> listAll(int viewerId, int limit) throws SQLException {
        List<Models.RoomInfo> list = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(ROOM_SELECT + "ORDER BY r.roomID LIMIT ?")) {
            ps.setInt(1, viewerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        }
        return list;
    }

    public Models.RoomInfo get(int roomId, int viewerId) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(ROOM_SELECT + "WHERE r.roomID=?")) {
            ps.setInt(1, viewerId);
            ps.setInt(2, roomId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public boolean checkPassword(int roomId, String plain) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM Rooms WHERE roomID=?")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String h = rs.getString(1);
                return h == null || CryptoUtil.verifyPassword(plain == null ? "" : plain, h);
            }
        }
    }

    public boolean addMember(int roomId, int userId, String role) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT OR IGNORE INTO RoomMembers(roomID,userID,role,joined_at) VALUES(?,?,?,?)")) {
            ps.setInt(1, roomId);
            ps.setInt(2, userId);
            ps.setString(3, role);
            ps.setLong(4, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        }
    }

    /** Tra ve vai tro (OWNER/ADMIN/MEMBER) hoac null neu khong phai thanh vien. */
    public String getRole(int roomId, int userId) throws SQLException {
        try (Connection c = DatabaseManager.getConnection()) { return roleOf(c, roomId, userId); }
    }

    private String roleOf(Connection c, int roomId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT role FROM RoomMembers WHERE roomID=? AND userID=?")) {
            ps.setInt(1, roomId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    public List<Integer> roomIdsOf(int userId) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT roomID FROM RoomMembers WHERE userID=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        }
        return ids;
    }

    public List<Models.MemberInfo> members(int roomId) throws SQLException {
        List<Models.MemberInfo> list = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.username, m.role FROM RoomMembers m JOIN Users u ON u.userID=m.userID " +
                 "WHERE m.roomID=? ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, u.username")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Models.MemberInfo m = new Models.MemberInfo();
                    m.username = rs.getString(1);
                    m.role = rs.getString(2);
                    list.add(m);
                }
            }
        }
        return list;
    }

    public void setRole(int roomId, int userId, String role) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE RoomMembers SET role=? WHERE roomID=? AND userID=?")) {
            ps.setString(1, role);
            ps.setInt(2, roomId);
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
    }

    public void removeMember(int roomId, int userId) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM RoomMembers WHERE roomID=? AND userID=?")) {
            ps.setInt(1, roomId);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
    }

    /** Roi phong. Neu la OWNER thi chuyen quyen cho Admin/Member lau nhat; phong trong thi bi xoa. */
    public Models.LeaveResult leave(int roomId, int userId) throws SQLException {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                String role = roleOf(c, roomId, userId);
                if (role == null) { c.rollback(); return null; }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM RoomMembers WHERE roomID=? AND userID=?")) {
                    ps.setInt(1, roomId);
                    ps.setInt(2, userId);
                    ps.executeUpdate();
                }
                Models.LeaveResult res = new Models.LeaveResult();
                if (Protocol.ROLE_OWNER.equals(role)) {
                    int next = -1;
                    String nextName = null;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT m.userID, u.username FROM RoomMembers m JOIN Users u ON u.userID=m.userID " +
                            "WHERE m.roomID=? ORDER BY CASE m.role WHEN 'ADMIN' THEN 0 ELSE 1 END, m.joined_at, m.userID LIMIT 1")) {
                        ps.setInt(1, roomId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) { next = rs.getInt(1); nextName = rs.getString(2); }
                        }
                    }
                    if (next < 0) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM Rooms WHERE roomID=?")) {
                            ps.setInt(1, roomId);
                            ps.executeUpdate();
                        }
                        res.roomDeleted = true;
                    } else {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE RoomMembers SET role='OWNER' WHERE roomID=? AND userID=?")) {
                            ps.setInt(1, roomId);
                            ps.setInt(2, next);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = c.prepareStatement("UPDATE Rooms SET ownerID=? WHERE roomID=?")) {
                            ps.setInt(1, next);
                            ps.setInt(2, roomId);
                            ps.executeUpdate();
                        }
                        res.newOwner = nextName;
                    }
                }
                c.commit();
                return res;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void delete(int roomId) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM Rooms WHERE roomID=?")) {
            ps.setInt(1, roomId);
            ps.executeUpdate();
        }
    }

    public int countRooms() throws SQLException {
        try (Connection c = DatabaseManager.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM Rooms")) { return rs.next() ? rs.getInt(1) : 0; }
    }

    /** Danh sach phong cho trang quan tri: {id, name, locked, owner, members, messages} dang chuoi. */
    public List<String[]> adminRooms() throws SQLException {
        List<String[]> list = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT r.roomID, r.roomName, r.password_hash IS NOT NULL, u.username, " +
                 "(SELECT COUNT(*) FROM RoomMembers m WHERE m.roomID=r.roomID), " +
                 "(SELECT COUNT(*) FROM Messages g WHERE g.roomID=r.roomID) " +
                 "FROM Rooms r JOIN Users u ON u.userID=r.ownerID ORDER BY r.roomID LIMIT 1000")) {
            while (rs.next()) list.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
        }
        return list;
    }

    /** Dat vai tro bat ky (ke ca OWNER - dong thoi ha chu cu xuong ADMIN va cap nhat Rooms.ownerID). */
    public void adminSetRole(int roomId, int userId, String role) throws SQLException {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (Protocol.ROLE_OWNER.equals(role)) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE RoomMembers SET role='ADMIN' WHERE roomID=? AND role='OWNER'")) {
                        ps.setInt(1, roomId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE Rooms SET ownerID=? WHERE roomID=?")) {
                        ps.setInt(1, userId);
                        ps.setInt(2, roomId);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE RoomMembers SET role=? WHERE roomID=? AND userID=?")) {
                    ps.setString(1, role);
                    ps.setInt(2, roomId);
                    ps.setInt(3, userId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
