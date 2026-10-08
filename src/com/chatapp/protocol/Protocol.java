package com.chatapp.protocol;

/**
 * Giao thức truyền nhận thông điệp giữa Client và Server.
 * Format dữ liệu: COMMAND|PARAM1|PARAM2|...
 */
public final class Protocol {

    private Protocol() {
        // Private constructor để ngăn tạo đối tượng không cần thiết
    }

    // Ký tự phân tách
    public static final String DELIMITER = "|";

    // Cấu hình giới hạn an toàn
    public static final int PAGE_SIZE = 50;          // Số tin nhắn hiển thị trên mỗi trang lịch sử
    public static final int MAX_TEXT_CHARS = 2000;   // Độ dài tối đa của 1 tin nhắn văn bản (trước khi mã hóa)
    public static final int MAX_FRAME_CHARS = 20000; // Giới hạn ký tự tối đa cho writeUTF/readUTF (tránh tràn buffer)

    // ==========================================
    // 1. CLIENT -> SERVER (Lệnh gửi từ Client)
    // ==========================================
    
    // Xác thực & Hệ thống
    public static final String AUTH_REGISTER = "AUTH_REGISTER";       // |username|password
    public static final String AUTH_LOGIN = "AUTH_LOGIN";             // |username|password
    public static final String LOGOUT = "LOGOUT";                     // (không có tham số)
    public static final String PING = "PING";                         // Duy trì kết nối Keep-Alive

    // Quản lý danh sách & Nhóm
    public static final String LIST_ROOMS = "LIST_ROOMS";             // Yêu cầu lấy danh sách phòng
    public static final String LIST_USERS = "LIST_USERS";             // Yêu cầu lấy danh sách người dùng online
    public static final String CREATE_ROOM = "CREATE_ROOM";           // |roomName|password (password có thể rỗng)
    public static final String JOIN_ROOM = "JOIN_ROOM";               // |roomID|password
    public static final String LEAVE_ROOM = "LEAVE_ROOM";             // |roomID
    public static final String LIST_MEMBERS = "LIST_MEMBERS";         // |roomID

    // Quản trị phòng (Owner / Admin)
    public static final String GRANT_ADMIN = "GRANT_ADMIN";           // |roomID|username
    public static final String REVOKE_ADMIN = "REVOKE_ADMIN";         // |roomID|username
    public static final String KICK_MEMBER = "KICK_MEMBER";           // |roomID|username

    // Truyền nhận Tin nhắn
    public static final String SEND_MSG_GROUP = "SEND_MSG_GROUP";     // |roomID|AES_encrypted_content
    public static final String SEND_MSG_PRIVATE = "SEND_MSG_PRIVATE"; // |receiverUsername|AES_encrypted_content

    // Lịch sử trò chuyện
    public static final String LOAD_HISTORY_PAGED = "LOAD_HISTORY_PAGED";     // |roomID|pageIndex
    public static final String LOAD_PRIVATE_HISTORY = "LOAD_PRIVATE_HISTORY"; // |peerUsername|pageIndex

    // Truyền nhận File
    public static final String SEND_FILE = "SEND_FILE";               // |GROUP/PRIVATE|targetID|fileName|fileSize
    public static final String DOWNLOAD_FILE = "DOWNLOAD_FILE";       // |fileID

    // ==========================================
    // 2. SERVER -> CLIENT (Phản hồi từ Server)
    // ==========================================
    
    // Kết quả Xác thực
    public static final String AUTH_OK = "AUTH_OK";                   // |username
    public static final String AUTH_FAIL = "AUTH_FAIL";               // |reason
    public static final String REGISTER_OK = "REGISTER_OK";           // Thành công
    public static final String REGISTER_FAIL = "REGISTER_FAIL";       // |reason

    // Trạng thái chung & Danh sách
    public static final String PONG = "PONG";                         // Phản hồi PING
    public static final String ERROR = "ERROR";                       // |reason
    public static final String ROOM_LIST = "ROOM_LIST";               // |id,name,locked,owner,count,role;...
    public static final String USER_LIST = "USER_LIST";               // |user,online(0/1);...
    public static final String ROOMS_CHANGED = "ROOMS_CHANGED";       // Thông báo danh sách phòng đã thay đổi
    public static final String MEMBERS_CHANGED = "MEMBERS_CHANGED";   // |roomID
    public static final String MEMBER_LIST = "MEMBER_LIST";           // |roomID|user,role,online;...

    // Kết quả Thao tác phòng
    public static final String CREATE_OK = "CREATE_OK";               // |roomID
    public static final String CREATE_FAIL = "CREATE_FAIL";           // |reason
    public static final String JOIN_OK = "JOIN_OK";                   // |roomID|roomName|role
    public static final String JOIN_FAIL = "JOIN_FAIL";               // |reason
    public static final String LEAVE_OK = "LEAVE_OK";                 // |roomID
    public static final String KICKED = "KICKED";                     // |roomID|roomName

    // Thông điệp & Lịch sử
    public static final String SYS = "SYS";                           // |roomID|text (Tin nhắn hệ thống)
    public static final String MSG_GROUP = "MSG_GROUP";               // |roomID|msgID|sender|timestamp|type|AES_content
    public static final String MSG_PRIVATE = "MSG_PRIVATE";           // |msgID|from|to|timestamp|type|AES_content
    public static final String HISTORY_BEGIN = "HISTORY_BEGIN";       // |G/P|key|page
    public static final String HISTORY_ITEM = "HISTORY_ITEM";         // |G/P|key|msgID|sender|timestamp|type|AES_content
    public static final String HISTORY_END = "HISTORY_END";           // |G/P|key|page|hasMore(0/1)

    // Truyền nhận File
    public static final String UPLOAD_READY = "UPLOAD_READY";         // |fileID
    public static final String UPLOAD_REJECT = "UPLOAD_REJECT";       // |reason
    public static final String UPLOAD_DONE = "UPLOAD_DONE";           // |fileID
    public static final String FILE_DATA = "FILE_DATA";               // |fileID|fileName|fileSize + <binary_bytes>

    // ==========================================
    // 3. LOẠI TIN NHẮN & VAI TRÒ
    // ==========================================
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_FILE = "FILE";

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    // ==========================================
    // 4. HÀM TIỆN ÍCH (HELPER METHODS)
    // ==========================================

    /**
     * Ghép các tham số thành chuỗi chuẩn định dạng giao thức (phân cách bởi dấu '|')
     * Vi dụ: Protocol.join("AUTH_LOGIN", "admin", "123456") -> "AUTH_LOGIN|admin|123456"
     */
    public static String join(Object... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(parts[i] != null ? parts[i] : "");
        }
        return sb.toString();
    }

    /**
     * Tách chuỗi nhận từ Socket dựa trên ký tự '|'
     * Vi dụ: Protocol.split("AUTH_LOGIN|admin|123456") -> ["AUTH_LOGIN", "admin", "123456"]
     */
    public static String[] split(String line) {
        if (line == null) {
            return new String[0];
        }
        return line.split("\\|", -1);
    }

    /**
     * Loại bỏ các ký tự đặc biệt có thể phá vỡ định dạng giao thức trong dữ liệu người dùng nhập
     * (Chuyển dấu '|' thành '_', xóa ký tự xuống dòng '\n', '\r'...)
     */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('|', '_')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace(',', '_')
                .trim();
    }
}