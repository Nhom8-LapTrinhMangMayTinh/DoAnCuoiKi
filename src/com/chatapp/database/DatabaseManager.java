
package com.chatapp.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DatabaseManager {

    private static final String DB_DIR = "data";
    private static final String DB_FILE = "chat.db";
    private static final String DB_URL =
            "jdbc:sqlite:" + DB_DIR + "/" + DB_FILE;
    private static final SecureRandom ID_RANDOM = new SecureRandom();

    private DatabaseManager() {
    }

    // =====================================================
    // KẾT NỐI DATABASE
    // =====================================================

    public static synchronized Connection getConnection()
            throws SQLException {

        File dir = new File(DB_DIR);

        if (!dir.exists() && !dir.mkdirs()) {
            throw new SQLException(
                    "Không thể tạo thư mục database: " + DB_DIR);
        }

        Connection conn = DriverManager.getConnection(DB_URL);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA busy_timeout = 5000");
        }

        return conn;
    }

    // =====================================================
    // KHỞI TẠO VÀ NÂNG CẤP SCHEMA
    // =====================================================

    public static void initializeDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Cho phép SQLite đọc và ghi hiệu quả hơn khi có
            // nhiều client cùng hoạt động.
            stmt.execute("PRAGMA journal_mode = WAL");

            // ---------------- USERS ----------------

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS users ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "username TEXT UNIQUE NOT NULL, "
                    + "password TEXT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ")"
            );

            // Cờ quyền/khóa quản trị được thêm an toàn cho database cũ.
            ensureColumn(conn, "users", "is_admin", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "users", "admin_locked", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "users", "failed_attempts", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "users", "locked_until", "INTEGER NOT NULL DEFAULT 0");

            // Username được so sánh chính xác theo từng ký tự: Huy và huy là hai tài khoản riêng.
            // Gỡ các trigger/index NOCASE cũ và cài ràng buộc duy nhất BINARY mà không đổi/xóa dữ liệu.
            ensureUsernameCaseSensitiveUniqueness(conn);

            // ---------------- ROOMS ----------------

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS rooms ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "room_name TEXT UNIQUE NOT NULL, "
                    + "password TEXT, "
                    + "owner TEXT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ")"
            );

            // Nâng cấp an toàn cho database cũ: ảnh đại diện của phòng.
            boolean hasAvatarData = false;
            boolean hasAvatarHash = false;
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(rooms)")) {
                while (rs.next()) {
                    String column = rs.getString("name");
                    if ("avatar_data".equalsIgnoreCase(column)) hasAvatarData = true;
                    if ("avatar_sha256".equalsIgnoreCase(column)) hasAvatarHash = true;
                }
            }
            if (!hasAvatarData) stmt.execute("ALTER TABLE rooms ADD COLUMN avatar_data BLOB");
            if (!hasAvatarHash) stmt.execute("ALTER TABLE rooms ADD COLUMN avatar_sha256 TEXT");

            // ---------------- ROOM MEMBERS ----------------

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS room_members ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "room_id INTEGER NOT NULL, "
                    + "username TEXT NOT NULL, "
                    + "role TEXT NOT NULL DEFAULT 'MEMBER', "
                    + "UNIQUE(room_id, username), "
                    + "FOREIGN KEY(room_id) REFERENCES rooms(id) "
                    + "ON DELETE CASCADE"
                    + ")"
            );

            // ---------------- MESSAGES ----------------
            // Các cột cũ được giữ nguyên để tương thích với
            // ClientHandler hiện tại.

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "sender TEXT NOT NULL, "
                    + "receiver TEXT, "
                    + "room_id INTEGER, "
                    + "room_name TEXT, "
                    + "content TEXT NOT NULL, "
                    + "msg_type TEXT DEFAULT 'TEXT', "
                    + "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, "

                    // Tin nhắn trả lời
                    + "reply_to_id INTEGER, "

                    // Sửa tin nhắn
                    + "edited INTEGER NOT NULL DEFAULT 0, "
                    + "edited_at DATETIME, "

                    // Thu hồi tin nhắn
                    + "deleted INTEGER NOT NULL DEFAULT 0, "
                    + "deleted_at DATETIME, "

                    // Ghim tin nhắn
                    + "pinned INTEGER NOT NULL DEFAULT 0, "
                    + "pinned_by TEXT, "
                    + "pinned_at DATETIME"
                    + ")"
            );

            // ---------------- MIGRATION MESSAGES ----------------
            // Bổ sung cột còn thiếu mà không xóa dữ liệu cũ.

            ensureColumn(conn, "messages", "room_id", "INTEGER");
            ensureColumn(conn, "messages", "room_name", "TEXT");
            ensureColumn(conn, "messages", "msg_type",
                    "TEXT DEFAULT 'TEXT'");

            ensureColumn(conn, "messages", "reply_to_id", "INTEGER");

            ensureColumn(conn, "messages", "edited",
                    "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "messages", "edited_at", "DATETIME");

            ensureColumn(conn, "messages", "deleted",
                    "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "messages", "deleted_at", "DATETIME");

            ensureColumn(conn, "messages", "pinned",
                    "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(conn, "messages", "pinned_by", "TEXT");
            ensureColumn(conn, "messages", "pinned_at", "DATETIME");

            // Chuyển các tin nhắn cũ chỉ có room_name sang room_id.
            stmt.execute(
                    "UPDATE messages "
                    + "SET room_id = ("
                    + "SELECT rooms.id "
                    + "FROM rooms "
                    + "WHERE rooms.room_name = messages.room_name"
                    + ") "
                    + "WHERE room_id IS NULL "
                    + "AND room_name IS NOT NULL"
            );

            // ---------------- MESSAGE REACTIONS ----------------
            // Một người có thể thả nhiều loại cảm xúc cho một tin nhắn,
            // nhưng mỗi loại chỉ được lưu một lần cho người đó.

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS message_reactions ("
                    + "message_id INTEGER NOT NULL, "
                    + "username TEXT NOT NULL, "
                    + "reaction TEXT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY(message_id, username, reaction), "
                    + "FOREIGN KEY(message_id) REFERENCES messages(id) "
                    + "ON DELETE CASCADE"
                    + ")"
            );

            // ---------------- READ STATES ----------------
            // conversation_type: G = group, P = private.
            // conversation_key: room ID hoặc username người trò chuyện.

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS read_states ("
                    + "username TEXT NOT NULL, "
                    + "conversation_type TEXT NOT NULL, "
                    + "conversation_key TEXT NOT NULL, "
                    + "last_read_message_id INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY("
                    + "username, conversation_type, conversation_key"
                    + ")"
                    + ")"
            );

            // ---------------- AUDIT LOGS ----------------
            // Nhật ký thao tác để hỗ trợ chức năng quản trị sau này.

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS audit_logs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "actor TEXT, "
                    + "action TEXT NOT NULL, "
                    + "target_type TEXT, "
                    + "target_id TEXT, "
                    + "details TEXT, "
                    + "remote_address TEXT, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP"
                    + ")"
            );

            // ---------------- DEFAULT ROOM ----------------

            stmt.execute(
                    "INSERT OR IGNORE INTO rooms "
                    + "(room_name, password, owner) "
                    + "VALUES ('General', '', 'System')"
            );

            // ---------------- PUBLIC IDs 5 CHỮ SỐ ----------------
            // ID hiển thị được tách khỏi khóa nội bộ AUTOINCREMENT để
            // không làm hỏng các quan hệ lịch sử/tệp/thành viên hiện có.
            initializePublicIds(conn);

            // ---------------- INDEXES ----------------

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_messages_room "
                    + "ON messages(room_id, id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_messages_private "
                    + "ON messages(sender, receiver, id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_room_members_room "
                    + "ON room_members(room_id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_messages_reply "
                    + "ON messages(reply_to_id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_messages_pinned "
                    + "ON messages(room_id, pinned, id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_reactions_message "
                    + "ON message_reactions(message_id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_read_states_user "
                    + "ON read_states(username)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_audit_created "
                    + "ON audit_logs(created_at)"
            );
            
            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_chat_files_room "
                    + "ON chat_files(scope, room_id, id)"
            );

            stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_chat_files_private "
                    + "ON chat_files(scope, sender, receiver, id)"
            );

            System.out.println(
                    "[Database] SQLite khởi tạo/nâng cấp thành công tại: "
                    + DB_DIR + "/" + DB_FILE);

        } catch (SQLException e) {
            System.err.println(
                    "[Database] Lỗi khởi tạo/nâng cấp database: "
                    + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Enforce exact-case username uniqueness. Huy and huy are distinct users.
     * Removes legacy NOCASE indexes/triggers but never renames/deletes user rows.
     * If exact duplicates already exist in a legacy schema, preserve them and
     * keep triggers enabled; the server logs the conflict instead of destroying data.
     */
    public static void ensureUsernameCaseSensitiveUniqueness(Connection conn) throws SQLException {
        if (conn == null) throw new SQLException("Kết nối database không hợp lệ.");

        dropLegacyUsernameNoCaseObjects(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TRIGGER IF EXISTS trg_users_username_nocase_insert");
            stmt.execute("DROP TRIGGER IF EXISTS trg_users_username_nocase_update");
            stmt.execute("DROP INDEX IF EXISTS idx_users_username_nocase");
            stmt.execute("CREATE TRIGGER IF NOT EXISTS trg_users_username_exact_insert "
                    + "BEFORE INSERT ON users FOR EACH ROW "
                    + "WHEN EXISTS (SELECT 1 FROM users AS existing "
                    + "WHERE existing.username COLLATE BINARY = NEW.username COLLATE BINARY) "
                    + "BEGIN SELECT RAISE(ABORT, 'username already exists (exact-case)'); END");
            stmt.execute("CREATE TRIGGER IF NOT EXISTS trg_users_username_exact_update "
                    + "BEFORE UPDATE OF username ON users FOR EACH ROW "
                    + "WHEN EXISTS (SELECT 1 FROM users AS existing "
                    + "WHERE existing.id <> OLD.id "
                    + "AND existing.username COLLATE BINARY = NEW.username COLLATE BINARY) "
                    + "BEGIN SELECT RAISE(ABORT, 'username already exists (exact-case)'); END");
        }

        List<String> exactConflicts = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT GROUP_CONCAT(username, ', ') AS names "
                     + "FROM users GROUP BY username COLLATE BINARY HAVING COUNT(*) > 1")) {
            while (rs.next()) {
                String names = rs.getString("names");
                if (names != null && !names.trim().isEmpty()) exactConflicts.add(names);
            }
        }
        if (exactConflicts.isEmpty()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_exact "
                        + "ON users(username COLLATE BINARY)");
            }
        } else {
            System.err.println("[Database] Có tên đăng nhập trùng chính xác; dữ liệu được giữ nguyên.");
            for (String names : exactConflicts) {
                System.err.println("[Database] Nhóm tài khoản trùng chính xác: " + names);
            }
        }
    }

    /** Drop only legacy index/trigger objects on users that enforce NOCASE username comparisons. */
    private static void dropLegacyUsernameNoCaseObjects(Connection conn) throws SQLException {
        final class DbObject {
            final String type;
            final String name;
            DbObject(String type, String name) { this.type = type; this.name = name; }
        }
        List<DbObject> objects = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT type, name, sql FROM sqlite_master "
                + "WHERE tbl_name='users' AND type IN ('index','trigger') AND sql IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String sql = rs.getString("sql");
                if (sql == null) continue;
                String upper = sql.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("NOCASE") && upper.contains("USERNAME")) {
                    objects.add(new DbObject(rs.getString("type"), rs.getString("name")));
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            for (DbObject object : objects) {
                if (object.name == null || object.name.trim().isEmpty()) continue;
                String quoted = "\"" + object.name.replace("\"", "\"\"") + "\"";
                if ("index".equalsIgnoreCase(object.type)) {
                    stmt.execute("DROP INDEX IF EXISTS " + quoted);
                    System.out.println("[Database] Đã gỡ index username NOCASE cũ: " + object.name);
                } else if ("trigger".equalsIgnoreCase(object.type)) {
                    stmt.execute("DROP TRIGGER IF EXISTS " + quoted);
                    System.out.println("[Database] Đã gỡ trigger username NOCASE cũ: " + object.name);
                }
            }
        }
    }

    // =====================================================
    // PUBLIC ID NGẪU NHIÊN 5 CHỮ SỐ - DÙNG CHUNG USERS/ROOMS
    // =====================================================

    /**
     * Mỗi tài khoản/phòng có một public_id từ 10000 đến 99999.
     * public_id_registry giữ lại mọi ID đã cấp kể cả khi đối tượng bị xóa,
     * do đó ID không bao giờ được tái sử dụng.
     */
    private static void initializePublicIds(Connection conn) throws SQLException {
        ensureColumn(conn, "users", "public_id", "TEXT");
        ensureColumn(conn, "rooms", "public_id", "TEXT");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS public_id_registry ("
                    + "public_id TEXT PRIMARY KEY, "
                    + "entity_type TEXT NOT NULL, "
                    + "entity_db_id INTEGER NOT NULL, "
                    + "allocated_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "CHECK(length(public_id) = 5), "
                    + "CHECK(public_id NOT GLOB '*[^0-9]*')"
                    + ")"
            );

            // Nếu một lần khởi tạo trước đã gán ID nhưng bị ngắt giữa chừng,
            // đăng ký lại các ID đang có trước khi cấp ID cho bản ghi còn thiếu.
            stmt.executeUpdate(
                    "INSERT OR IGNORE INTO public_id_registry "
                    + "(public_id, entity_type, entity_db_id) "
                    + "SELECT public_id, 'USER', id FROM users "
                    + "WHERE public_id IS NOT NULL AND length(public_id)=5 "
                    + "AND public_id NOT GLOB '*[^0-9]*'"
            );
            stmt.executeUpdate(
                    "INSERT OR IGNORE INTO public_id_registry "
                    + "(public_id, entity_type, entity_db_id) "
                    + "SELECT public_id, 'ROOM', id FROM rooms "
                    + "WHERE public_id IS NOT NULL AND length(public_id)=5 "
                    + "AND public_id NOT GLOB '*[^0-9]*'"
            );
        }

        List<Long> userIds = readIdsNeedingPublicId(conn, "users");
        for (long id : userIds) {
            String publicId = allocateAndReservePublicId(conn, "USER", id);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET public_id=? WHERE id=?")) {
                ps.setString(1, publicId);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }

        List<Long> roomIds = readIdsNeedingPublicId(conn, "rooms");
        for (long id : roomIds) {
            String publicId = allocateAndReservePublicId(conn, "ROOM", id);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE rooms SET public_id=? WHERE id=?")) {
                ps.setString(1, publicId);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_users_public_id "
                    + "ON users(public_id) WHERE public_id IS NOT NULL AND public_id <> ''");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_rooms_public_id "
                    + "ON rooms(public_id) WHERE public_id IS NOT NULL AND public_id <> ''");

            // Trigger tự cấp ID ngay khi AuthService hoặc RoomService thêm bản ghi mới.
            // Việc cấp ID diễn ra trong cùng thao tác INSERT nên hai client đồng thời
            // không thể nhận cùng ID; khóa chính registry là lớp bảo vệ cuối cùng.
            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_users_public_id_after_insert
                    AFTER INSERT ON users
                    WHEN NEW.public_id IS NULL OR TRIM(NEW.public_id) = ''
                    BEGIN
                        UPDATE users
                        SET public_id = (
                            WITH RECURSIVE candidates(candidate, attempts) AS (
                                SELECT printf('%05d', abs(random() % 90000) + 10000), 0
                                UNION ALL
                                SELECT printf('%05d',
                                    CASE WHEN CAST(candidate AS INTEGER) >= 99999
                                         THEN 10000 ELSE CAST(candidate AS INTEGER) + 1 END),
                                    attempts + 1
                                FROM candidates
                                WHERE attempts < 89999
                                  AND EXISTS (
                                      SELECT 1 FROM public_id_registry r
                                      WHERE r.public_id = candidates.candidate
                                  )
                            )
                            SELECT candidate FROM candidates
                            WHERE NOT EXISTS (
                                SELECT 1 FROM public_id_registry r
                                WHERE r.public_id = candidates.candidate
                            )
                            LIMIT 1
                        )
                        WHERE id = NEW.id;

                        INSERT INTO public_id_registry(public_id, entity_type, entity_db_id)
                        SELECT public_id, 'USER', id FROM users
                        WHERE id = NEW.id AND public_id IS NOT NULL AND public_id <> '';
                    END
                    """);

            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_rooms_public_id_after_insert
                    AFTER INSERT ON rooms
                    WHEN NEW.public_id IS NULL OR TRIM(NEW.public_id) = ''
                    BEGIN
                        UPDATE rooms
                        SET public_id = (
                            WITH RECURSIVE candidates(candidate, attempts) AS (
                                SELECT printf('%05d', abs(random() % 90000) + 10000), 0
                                UNION ALL
                                SELECT printf('%05d',
                                    CASE WHEN CAST(candidate AS INTEGER) >= 99999
                                         THEN 10000 ELSE CAST(candidate AS INTEGER) + 1 END),
                                    attempts + 1
                                FROM candidates
                                WHERE attempts < 89999
                                  AND EXISTS (
                                      SELECT 1 FROM public_id_registry r
                                      WHERE r.public_id = candidates.candidate
                                  )
                            )
                            SELECT candidate FROM candidates
                            WHERE NOT EXISTS (
                                SELECT 1 FROM public_id_registry r
                                WHERE r.public_id = candidates.candidate
                            )
                            LIMIT 1
                        )
                        WHERE id = NEW.id;

                        INSERT INTO public_id_registry(public_id, entity_type, entity_db_id)
                        SELECT public_id, 'ROOM', id FROM rooms
                        WHERE id = NEW.id AND public_id IS NOT NULL AND public_id <> '';
                    END
                    """);
        }
    }

    private static List<Long> readIdsNeedingPublicId(Connection conn, String table)
            throws SQLException {
        if (!"users".equals(table) && !"rooms".equals(table)) {
            throw new IllegalArgumentException("Bảng không hợp lệ");
        }
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM " + table
                + " WHERE public_id IS NULL OR TRIM(public_id)='' "
                + "OR length(public_id)<>5 OR public_id GLOB '*[^0-9]*'";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) ids.add(rs.getLong(1));
        }
        return ids;
    }

    private static String allocateAndReservePublicId(
            Connection conn, String entityType, long entityDbId) throws SQLException {
        for (int i = 0; i < 180000; i++) {
            String candidate = String.format(Locale.ROOT, "%05d", ID_RANDOM.nextInt(90000) + 10000);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public_id_registry(public_id, entity_type, entity_db_id) "
                    + "VALUES (?, ?, ?)")) {
                ps.setString(1, candidate);
                ps.setString(2, entityType);
                ps.setLong(3, entityDbId);
                ps.executeUpdate();
                return candidate;
            } catch (SQLException ex) {
                String message = ex.getMessage();
                if (ex.getErrorCode() == 19
                        || (message != null && message.toUpperCase(Locale.ROOT).contains("UNIQUE"))) {
                    continue; // ID đã cấp cho người dùng hoặc phòng khác.
                }
                throw ex;
            }
        }
        throw new SQLException("Đã hết ID 5 chữ số chưa sử dụng.");
    }

    // =====================================================
    // KIỂM TRA VÀ BỔ SUNG CỘT
    // =====================================================

    private static void ensureColumn(
            Connection conn,
            String tableName,
            String columnName,
            String columnType) throws SQLException {

        boolean exists = false;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "PRAGMA table_info(" + tableName + ")")) {

            while (rs.next()) {
                if (columnName.equalsIgnoreCase(
                        rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }

        if (!exists) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "ALTER TABLE " + tableName
                        + " ADD COLUMN " + columnName
                        + " " + columnType);
            }

            System.out.println(
                    "[Database] Đã bổ sung cột "
                    + tableName + "." + columnName);
        }
    }

    // =====================================================
    // GHI NHẬT KÝ HOẠT ĐỘNG
    // =====================================================

    public static void recordAudit(
            String actor,
            String action,
            String targetType,
            String targetId,
            String details) {

        recordAudit(
                actor, action, targetType, targetId, details, null);
    }

    public static void recordAudit(
            String actor,
            String action,
            String targetType,
            String targetId,
            String details,
            String remoteAddress) {

        if (action == null || action.trim().isEmpty()) {
            return;
        }

        String sql =
                "INSERT INTO audit_logs "
                + "(actor, action, target_type, target_id, "
                + "details, remote_address) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, targetType);
            ps.setString(4, targetId);
            ps.setString(5, details);
            ps.setString(6, remoteAddress);

            ps.executeUpdate();

        } catch (SQLException e) {
            // Lỗi ghi audit không được làm sập luồng chat.
            System.err.println(
                    "[Audit] Không thể ghi nhật ký: "
                    + e.getMessage());
        }
    }
}
