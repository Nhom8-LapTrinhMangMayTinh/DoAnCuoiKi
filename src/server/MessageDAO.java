package server;

import common.CryptoUtil;
import common.TextUtil;

import java.sql.*;
import java.util.*;

public class MessageDAO {

    // ============================== luu / doc 1 tin ==============================
    public Models.Msg save(int senderId, String senderName, int roomId, int receiverId, String type, String encrypted)
            throws SQLException {
        return save(senderId, senderName, roomId, receiverId, type, encrypted, 0);
    }

    /** Luu tin nhan (roomId > 0: tin nhom; nguoc lai tin rieng toi receiverId). Noi dung DA duoc ma hoa AES. Tra ve tin day du. */
    public Models.Msg save(int senderId, String senderName, int roomId, int receiverId, String type, String encrypted, long replyTo)
            throws SQLException {
        long ts = System.currentTimeMillis();
        long id;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO Messages(senderID,receiverID,roomID,msgType,content_encrypted,timestamp,reply_to) VALUES(?,?,?,?,?,?,?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, senderId);
            if (receiverId > 0) ps.setInt(2, receiverId); else ps.setNull(2, Types.INTEGER);
            if (roomId > 0) ps.setInt(3, roomId); else ps.setNull(3, Types.INTEGER);
            ps.setString(4, type);
            ps.setString(5, encrypted);
            ps.setLong(6, ts);
            if (replyTo > 0) ps.setLong(7, replyTo); else ps.setNull(7, Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getLong(1); }
        }
        Models.Msg m = get(id);
        if (m == null) { // khong the xay ra, nhung tranh NullPointer
            m = new Models.Msg();
            m.id = id; m.sender = senderName; m.senderId = senderId; m.ts = ts; m.type = type; m.content = encrypted;
            m.roomId = roomId; m.receiverId = receiverId;
        }
        return m;
    }

    private static final String MSG_SELECT =
        "SELECT m.msgID,u.username,m.senderID,COALESCE(m.receiverID,0),COALESCE(ur.username,''),COALESCE(m.roomID,0)," +
        "m.msgType,m.content_encrypted,m.timestamp,COALESCE(m.reply_to,0),COALESCE(rus.username,''),rm.content_encrypted," +
        "rm.msgType,COALESCE(rm.deleted,0),COALESCE(m.edited_at,0),m.deleted,COALESCE(m.pinned_by,0) " +
        "FROM Messages m JOIN Users u ON u.userID=m.senderID " +
        "LEFT JOIN Users ur ON ur.userID=m.receiverID " +
        "LEFT JOIN Messages rm ON rm.msgID=m.reply_to " +
        "LEFT JOIN Users rus ON rus.userID=rm.senderID ";

    private Models.Msg mapMsg(ResultSet rs) throws SQLException {
        Models.Msg m = new Models.Msg();
        m.id = rs.getLong(1);
        m.sender = rs.getString(2);
        m.senderId = rs.getInt(3);
        m.receiverId = rs.getInt(4);
        m.receiverName = rs.getString(5);
        m.roomId = rs.getInt(6);
        m.type = rs.getString(7);
        m.content = rs.getString(8);
        m.ts = rs.getLong(9);
        m.replyTo = rs.getLong(10);
        if (m.replyTo > 0) {
            m.replySender = rs.getString(11);
            String rc = rs.getString(12);
            String rt = rs.getString(13);
            boolean rdel = rs.getInt(14) != 0;
            String snippet;
            if (rc == null) snippet = "(tin nhắn gốc không còn)";
            else if (rdel) snippet = "(tin nhắn đã bị thu hồi)";
            else if ("FILE".equals(rt)) {
                String[] f = CryptoUtil.decrypt(rc).split("\\|", 3);
                snippet = "[File] " + (f.length > 1 ? f[1] : "");
            } else {
                snippet = CryptoUtil.decrypt(rc);
                if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";
            }
            m.replyEnc = CryptoUtil.encrypt(snippet); // server biet khoa AES nen co the rut gon roi ma hoa lai
        }
        m.editedAt = rs.getLong(15);
        m.deleted = rs.getInt(16) != 0;
        m.pinned = rs.getInt(17) != 0;
        if (m.deleted) m.content = "";
        return m;
    }

    public Models.Msg get(long id) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(MSG_SELECT + "WHERE m.msgID=?")) {
            ps.setLong(1, id);
            Models.Msg m = null;
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) m = mapMsg(rs); }
            if (m != null) fillReactions(c, Collections.singletonList(m));
            return m;
        }
    }

    // ============================== phan trang lich su ==============================
    /** Phan trang tin nhom: trang 0 = 50 tin moi nhat. SQL: ORDER BY msgID DESC LIMIT n OFFSET page*n. */
    public Models.Page loadGroupPage(int roomId, int pageIndex, int pageSize) throws SQLException {
        String sql = MSG_SELECT + "WHERE m.roomID=? ORDER BY m.msgID DESC LIMIT ? OFFSET ?";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setInt(2, pageSize + 1);              // lay du 1 dong de biet con trang sau hay khong
            ps.setLong(3, (long) pageIndex * pageSize);
            return readPage(c, ps, pageSize);
        }
    }

    public Models.Page loadPrivatePage(int userA, int userB, int pageIndex, int pageSize) throws SQLException {
        String sql = MSG_SELECT + "WHERE m.roomID IS NULL AND ((m.senderID=? AND m.receiverID=?) OR (m.senderID=? AND m.receiverID=?)) " +
                     "ORDER BY m.msgID DESC LIMIT ? OFFSET ?";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userA);
            ps.setInt(2, userB);
            ps.setInt(3, userB);
            ps.setInt(4, userA);
            ps.setInt(5, pageSize + 1);
            ps.setLong(6, (long) pageIndex * pageSize);
            return readPage(c, ps, pageSize);
        }
    }

    private Models.Page readPage(Connection c, PreparedStatement ps, int pageSize) throws SQLException {
        List<Models.Msg> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows.add(mapMsg(rs)); }
        Models.Page page = new Models.Page();
        if (rows.size() > pageSize) {
            page.hasMore = true;
            rows.remove(rows.size() - 1);
        }
        Collections.reverse(rows); // cu -> moi de hien thi
        fillReactions(c, rows);
        page.items = rows;
        return page;
    }

    // ============================== tim kiem ==============================
    private static final int SEARCH_SCAN_LIMIT = 100_000;

    /**
     * Tim tin nhan. Noi dung luu dang ma hoa AES nen Server giai ma tung tin (quet nguoc tu moi nhat) roi so khop
     * khong phan biet dau tieng Viet (strict=true thi phai dung dau). File duoc tim theo ten file.
     * Chi tim trong 1 cuoc tro chuyen (phong hoac cap 2 nguoi) -> khong the tim tin ngoai quyen truy cap.
     */
    public Models.Page search(boolean group, int roomId, int userA, int userB, String keyword, String senderName,
                              boolean strict, int pageIndex, int pageSize) throws SQLException {
        StringBuilder sql = new StringBuilder(MSG_SELECT + "WHERE m.deleted=0 AND ");
        if (group) sql.append("m.roomID=? ");
        else sql.append("m.roomID IS NULL AND ((m.senderID=? AND m.receiverID=?) OR (m.senderID=? AND m.receiverID=?)) ");
        boolean bySender = senderName != null && !senderName.isEmpty();
        if (bySender) sql.append("AND u.username=? COLLATE NOCASE ");
        sql.append("ORDER BY m.msgID DESC LIMIT ?");
        Models.Page page = new Models.Page();
        long skip = (long) pageIndex * pageSize;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            if (group) ps.setInt(i++, roomId);
            else { ps.setInt(i++, userA); ps.setInt(i++, userB); ps.setInt(i++, userB); ps.setInt(i++, userA); }
            if (bySender) ps.setString(i++, senderName);
            ps.setInt(i, SEARCH_SCAN_LIMIT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString(7);
                    String plain = CryptoUtil.decrypt(rs.getString(8));
                    String hay = plain;
                    if ("FILE".equals(type)) { String[] f = plain.split("\\|", 3); hay = f.length > 1 ? f[1] : plain; }
                    if (!TextUtil.matches(hay, keyword, strict)) continue;
                    if (skip > 0) { skip--; continue; }
                    if (page.items.size() >= pageSize) { page.hasMore = true; break; }
                    page.items.add(mapMsg(rs));
                }
            }
            fillReactions(c, page.items);
        }
        return page;
    }

    // ============================== sua / thu hoi / ghim ==============================
    public boolean edit(long id, int senderId, String enc, long ts) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE Messages SET content_encrypted=?, edited_at=? WHERE msgID=? AND senderID=? AND deleted=0 AND msgType='TEXT'")) {
            ps.setString(1, enc);
            ps.setLong(2, ts);
            ps.setLong(3, id);
            ps.setInt(4, senderId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Thu hoi: xoa noi dung that khoi CSDL (khong chi an), go ghim va reaction. false neu da thu hoi roi. */
    public boolean recall(long id) throws SQLException {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                int n;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE Messages SET deleted=1, content_encrypted='', pinned_by=NULL, pinned_at=NULL WHERE msgID=? AND deleted=0")) {
                    ps.setLong(1, id);
                    n = ps.executeUpdate();
                }
                if (n > 0) try (PreparedStatement ps = c.prepareStatement("DELETE FROM Reactions WHERE msgID=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                c.commit();
                return n > 0;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public boolean setPinned(long id, int byUser) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE Messages SET pinned_by=?, pinned_at=? WHERE msgID=? AND deleted=0")) {
            if (byUser > 0) { ps.setInt(1, byUser); ps.setLong(2, System.currentTimeMillis()); }
            else { ps.setNull(1, Types.INTEGER); ps.setNull(2, Types.INTEGER); }
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Models.Msg> listPinned(boolean group, int roomId, int userA, int userB) throws SQLException {
        String sql = MSG_SELECT + "WHERE m.deleted=0 AND m.pinned_by IS NOT NULL AND " +
                (group ? "m.roomID=? " : "m.roomID IS NULL AND ((m.senderID=? AND m.receiverID=?) OR (m.senderID=? AND m.receiverID=?)) ") +
                "ORDER BY m.pinned_at DESC LIMIT 50";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (group) ps.setInt(i, roomId);
            else { ps.setInt(i++, userA); ps.setInt(i++, userB); ps.setInt(i++, userB); ps.setInt(i, userA); }
            List<Models.Msg> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(mapMsg(rs)); }
            fillReactions(c, list);
            return list;
        }
    }

    // ============================== reaction ==============================
    /** Bam lan 1 = them, lan 2 = bo. Tra ve chuoi tom tat reaction moi cua tin: code:u1;u2,code2:u3 */
    public String toggleReaction(long msgId, int userId, String code) throws SQLException {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                int del;
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM Reactions WHERE msgID=? AND userID=? AND code=?")) {
                    ps.setLong(1, msgId); ps.setInt(2, userId); ps.setString(3, code);
                    del = ps.executeUpdate();
                }
                if (del == 0) try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO Reactions(msgID,userID,code,created_at) VALUES(?,?,?,?)")) {
                    ps.setLong(1, msgId); ps.setInt(2, userId); ps.setString(3, code); ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
            Models.Msg tmp = new Models.Msg();
            tmp.id = msgId;
            fillReactions(c, Collections.singletonList(tmp));
            return tmp.reactions;
        }
    }

    private void fillReactions(Connection c, List<Models.Msg> msgs) throws SQLException {
        if (msgs.isEmpty()) return;
        Map<Long, Models.Msg> byId = new HashMap<>();
        StringBuilder in = new StringBuilder();
        for (Models.Msg m : msgs) {
            byId.put(m.id, m);
            if (in.length() > 0) in.append(',');
            in.append(m.id);
        }
        Map<Long, Map<String, List<String>>> acc = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT r.msgID, r.code, u.username FROM Reactions r JOIN Users u ON u.userID=r.userID " +
                     "WHERE r.msgID IN (" + in + ") ORDER BY r.created_at")) {
            while (rs.next()) {
                acc.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>())
                   .computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
            }
        }
        for (Map.Entry<Long, Map<String, List<String>>> e : acc.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> r : e.getValue().entrySet()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(r.getKey()).append(':').append(String.join(";", r.getValue()));
            }
            Models.Msg m = byId.get(e.getKey());
            if (m != null) m.reactions = sb.toString();
        }
    }

    // ============================== da doc / chua doc ==============================
    /** Cap nhat moc da doc (chi tang, khong lui). Tra ve moc hien tai. */
    public long setRead(int userId, String scope, String key, long upTo) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO ReadState(userID,scope,convKey,last_msg) VALUES(?,?,?,?) " +
                 "ON CONFLICT(userID,scope,convKey) DO UPDATE SET last_msg=MAX(last_msg, excluded.last_msg)")) {
            ps.setInt(1, userId); ps.setString(2, scope); ps.setString(3, key); ps.setLong(4, upTo);
            ps.executeUpdate();
        }
        return getRead(userId, scope, key);
    }

    public long getRead(int userId, String scope, String key) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT last_msg FROM ReadState WHERE userID=? AND scope=? AND convKey=?")) {
            ps.setInt(1, userId); ps.setString(2, scope); ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    /** username -> moc da doc trong 1 phong. */
    public Map<String, Long> readStatesOfRoom(int roomId) throws SQLException {
        Map<String, Long> map = new LinkedHashMap<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.username, r.last_msg FROM ReadState r JOIN Users u ON u.userID=r.userID WHERE r.scope='G' AND r.convKey=?")) {
            ps.setString(1, String.valueOf(roomId));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) map.put(rs.getString(1), rs.getLong(2)); }
        }
        return map;
    }

    /** Id tin lon nhat cua cuoc tro chuyen (de gioi han moc da doc hop le). 0 neu chua co tin. */
    public long maxMsgId(boolean group, int roomId, int userA, int userB) throws SQLException {
        String sql = group ? "SELECT COALESCE(MAX(msgID),0) FROM Messages WHERE roomID=?"
                           : "SELECT COALESCE(MAX(msgID),0) FROM Messages WHERE roomID IS NULL AND ((senderID=? AND receiverID=?) OR (senderID=? AND receiverID=?))";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (group) ps.setInt(1, roomId);
            else { ps.setInt(1, userA); ps.setInt(2, userB); ps.setInt(3, userB); ps.setInt(4, userA); }
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    /** roomID -> so tin chua doc (khong tinh tin cua chinh minh, tin truoc khi tham gia, tin da thu hoi). */
    public Map<Integer, Integer> unreadGroups(int userId) throws SQLException {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT m.roomID, COUNT(*) FROM Messages m " +
                 "JOIN RoomMembers rm ON rm.roomID=m.roomID AND rm.userID=? " +
                 "LEFT JOIN ReadState rs ON rs.userID=? AND rs.scope='G' AND rs.convKey=CAST(m.roomID AS TEXT) " +
                 "WHERE m.senderID<>? AND m.deleted=0 AND m.timestamp>=rm.joined_at AND m.msgID>COALESCE(rs.last_msg,0) GROUP BY m.roomID")) {
            ps.setInt(1, userId); ps.setInt(2, userId); ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) map.put(rs.getInt(1), rs.getInt(2)); }
        }
        return map;
    }

    public Map<String, Integer> unreadPrivates(int userId) throws SQLException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT u.username, COUNT(*) FROM Messages m JOIN Users u ON u.userID=m.senderID " +
                 "LEFT JOIN ReadState rs ON rs.userID=? AND rs.scope='P' AND rs.convKey=LOWER(u.username) " +
                 "WHERE m.receiverID=? AND m.roomID IS NULL AND m.deleted=0 AND m.msgID>COALESCE(rs.last_msg,0) GROUP BY u.username")) {
            ps.setInt(1, userId); ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) map.put(rs.getString(1), rs.getInt(2)); }
        }
        return map;
    }

    // ============================== thong ke ==============================
    public long countMessages() throws SQLException { return scalar("SELECT COUNT(*) FROM Messages"); }
    public long countFiles() throws SQLException { return scalar("SELECT COUNT(*) FROM Files"); }
    public long sumFileBytes() throws SQLException { return scalar("SELECT COALESCE(SUM(size),0) FROM Files"); }

    private long scalar(String sql) throws SQLException {
        try (Connection c = DatabaseManager.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ============================== bang Files ==============================
    public long createFile(int uploaderId, int roomId, int receiverId, String name, long size) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO Files(originalName,size,uploaderID,roomID,receiverID,created_at) VALUES(?,?,?,?,?,?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setLong(2, size);
            ps.setInt(3, uploaderId);
            if (roomId > 0) ps.setInt(4, roomId); else ps.setNull(4, Types.INTEGER);
            if (receiverId > 0) ps.setInt(5, receiverId); else ps.setNull(5, Types.INTEGER);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    public void setFileSha(long id, String sha) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE Files SET sha256=? WHERE fileID=?")) {
            ps.setString(1, sha);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void deleteFile(long id) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM Files WHERE fileID=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public List<Long> fileIdsOfRoom(int roomId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT fileID FROM Files WHERE roomID=?")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
        }
        return ids;
    }

    public Models.FileRec getFile(long id) throws SQLException {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT fileID,originalName,size,uploaderID,COALESCE(roomID,0),COALESCE(receiverID,0),COALESCE(sha256,'') FROM Files WHERE fileID=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Models.FileRec f = new Models.FileRec();
                f.id = rs.getLong(1);
                f.name = rs.getString(2);
                f.size = rs.getLong(3);
                f.uploaderId = rs.getInt(4);
                f.roomId = rs.getInt(5);
                f.receiverId = rs.getInt(6);
                f.sha256 = rs.getString(7);
                return f;
            }
        }
    }
}
