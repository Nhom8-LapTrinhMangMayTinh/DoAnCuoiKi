package server;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Quan ly ket noi JDBC (SQLite). Moi lan goi getConnection() tra ve 1 ket noi moi -> an toan da luong. */
public final class DatabaseManager {
    private DatabaseManager() {}

    private static String url = "jdbc:sqlite:data/chat.db";

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS Users(" +
            "userID INTEGER PRIMARY KEY AUTOINCREMENT," +
            "username TEXT NOT NULL UNIQUE COLLATE NOCASE," +
            "password_hash TEXT NOT NULL," +
            "status TEXT NOT NULL DEFAULT 'OFFLINE'," +
            "created_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS Rooms(" +
            "roomID INTEGER PRIMARY KEY AUTOINCREMENT," +
            "roomName TEXT NOT NULL UNIQUE COLLATE NOCASE," +
            "password_hash TEXT," +
            "ownerID INTEGER NOT NULL REFERENCES Users(userID)," +
            "created_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS RoomMembers(" +
            "roomID INTEGER NOT NULL REFERENCES Rooms(roomID) ON DELETE CASCADE," +
            "userID INTEGER NOT NULL REFERENCES Users(userID)," +
            "role TEXT NOT NULL DEFAULT 'MEMBER'," +
            "joined_at INTEGER NOT NULL," +
            "PRIMARY KEY(roomID,userID))",
        "CREATE TABLE IF NOT EXISTS Messages(" +
            "msgID INTEGER PRIMARY KEY AUTOINCREMENT," +
            "senderID INTEGER NOT NULL REFERENCES Users(userID)," +
            "receiverID INTEGER REFERENCES Users(userID)," +
            "roomID INTEGER REFERENCES Rooms(roomID) ON DELETE CASCADE," +
            "msgType TEXT NOT NULL DEFAULT 'TEXT'," +
            "content_encrypted TEXT NOT NULL," +
            "timestamp INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_msg_room ON Messages(roomID,msgID)",
        "CREATE INDEX IF NOT EXISTS idx_msg_pm ON Messages(senderID,receiverID,msgID)",
        "CREATE TABLE IF NOT EXISTS Reactions(" +
            "msgID INTEGER NOT NULL REFERENCES Messages(msgID) ON DELETE CASCADE," +
            "userID INTEGER NOT NULL REFERENCES Users(userID)," +
            "code TEXT NOT NULL," +
            "created_at INTEGER NOT NULL," +
            "PRIMARY KEY(msgID,userID,code))",
        "CREATE TABLE IF NOT EXISTS ReadState(" +
            "userID INTEGER NOT NULL REFERENCES Users(userID)," +
            "scope TEXT NOT NULL," +
            "convKey TEXT NOT NULL," +
            "last_msg INTEGER NOT NULL," +
            "PRIMARY KEY(userID,scope,convKey))",
        "CREATE TABLE IF NOT EXISTS AuditLog(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts INTEGER NOT NULL," +
            "actor TEXT," +
            "action TEXT NOT NULL," +
            "target TEXT," +
            "detail TEXT," +
            "ip TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_audit_ts ON AuditLog(ts)",
        "CREATE TABLE IF NOT EXISTS Files(" +
            "fileID INTEGER PRIMARY KEY AUTOINCREMENT," +
            "originalName TEXT NOT NULL," +
            "size INTEGER NOT NULL," +
            "uploaderID INTEGER NOT NULL REFERENCES Users(userID)," +
            "roomID INTEGER," +
            "receiverID INTEGER," +
            "created_at INTEGER NOT NULL)"
    };

    /** Cot bo sung cho CSDL phien ban cu (ALTER TABLE chi chay khi cot chua co) -> khong mat du lieu cu. */
    private static final String[][] MIGRATIONS = {
        {"Users", "display_name", "TEXT"},
        {"Users", "avatar", "TEXT"},
        {"Users", "avatar_ver", "INTEGER NOT NULL DEFAULT 0"},
        {"Users", "last_seen", "INTEGER NOT NULL DEFAULT 0"},
        {"Users", "server_role", "TEXT NOT NULL DEFAULT 'USER'"},
        {"Users", "disabled", "INTEGER NOT NULL DEFAULT 0"},
        {"Messages", "reply_to", "INTEGER"},
        {"Messages", "edited_at", "INTEGER"},
        {"Messages", "deleted", "INTEGER NOT NULL DEFAULT 0"},
        {"Messages", "pinned_by", "INTEGER"},
        {"Messages", "pinned_at", "INTEGER"},
        {"Files", "sha256", "TEXT"},
    };

    private static void migrate(Connection c) throws SQLException {
        for (String[] m : MIGRATIONS) {
            boolean has = false;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + m[0] + ")")) {
                while (rs.next()) if (m[1].equalsIgnoreCase(rs.getString("name"))) { has = true; break; }
            }
            if (!has) try (Statement st = c.createStatement()) { st.execute("ALTER TABLE " + m[0] + " ADD COLUMN " + m[1] + " " + m[2]); }
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_msg_pinned ON Messages(roomID,pinned_by)");
        }
    }

    public static long dbSizeBytes() {
        if (!url.startsWith("jdbc:sqlite:")) return 0;
        File f = new File(url.substring("jdbc:sqlite:".length()));
        return f.length();
    }

    public static void init(String jdbcUrl) throws SQLException {
        if (jdbcUrl != null && !jdbcUrl.isEmpty()) url = jdbcUrl;
        if (url.startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            File parent = new File(path).getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
        }
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            try { st.execute("PRAGMA journal_mode=WAL"); } catch (SQLException ignored) { }
            for (String sql : SCHEMA) st.execute(sql);
            migrate(c);
        }
    }

    /**
     * Moi ket noi: bat khoa ngoai, cho khoa toi da 10s, WAL, va giao dich dang IMMEDIATE (lay khoa ghi ngay tu dau)
     * -> tranh loi SQLITE_BUSY khi 1 giao dich doc-roi-ghi gap giao dich ghi khac (vi du tao phong luc dang ghi nhat ky).
     */
    public static Connection getConnection() throws SQLException {
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.enforceForeignKeys(true);
        cfg.setBusyTimeout(10000);
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        cfg.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection(url, cfg.toProperties());
    }
    //...
}
