package com.chatapp.protocol;

public final class Protocol {

    private Protocol() {
        // Khong cho phep tao doi tuong Protocol.
    }

    // =========================================================
    // 1. CAU HINH CHUNG
    // =========================================================

    public static final String DELIMITER = "|";

    public static final int PAGE_SIZE = 50;
    public static final int MAX_TEXT_CHARS = 2000;
    public static final int MAX_FRAME_CHARS = 20000;
    // Video JPEG payloads can exceed ordinary command frames; bounded separately.
    public static final int MAX_CALL_VIDEO_FRAME_CHARS = 500000;

    // Moi chunk file toi da 12 KB truoc khi ma hoa Base64.
    public static final int FILE_CHUNK_BYTES = 12 * 1024;

    // =========================================================
    // 2. XAC THUC TAI KHOAN
    // =========================================================

    public static final String AUTH_REGISTER = "AUTH_REGISTER";
    public static final String AUTH_LOGIN = "AUTH_LOGIN";

    public static final String LOGOUT = "LOGOUT";
    public static final String PING = "PING";
    public static final String PONG = "PONG";

    public static final String AUTH_OK = "AUTH_OK";
    public static final String AUTH_FAIL = "AUTH_FAIL";

    public static final String REGISTER_OK = "REGISTER_OK";
    public static final String REGISTER_FAIL = "REGISTER_FAIL";

    // Đổi mật khẩu trên kết nối đã đăng nhập.
    // Client -> Server: CHANGE_PASSWORD|currentPassword|newPassword|confirmNewPassword
    public static final String CHANGE_PASSWORD = "CHANGE_PASSWORD";
    public static final String CHANGE_PASSWORD_OK = "CHANGE_PASSWORD_OK";
    public static final String CHANGE_PASSWORD_FAIL = "CHANGE_PASSWORD_FAIL";

    public static final String ERROR = "ERROR";

    // =========================================================
    // 2A. DASHBOARD QUAN TRI (ADMIN) - server always rechecks rights
    // =========================================================
    public static final String ADMIN_STATUS = "ADMIN_STATUS";
    public static final String ADMIN_SUBSCRIBE = "ADMIN_SUBSCRIBE";
    public static final String ADMIN_UNSUBSCRIBE = "ADMIN_UNSUBSCRIBE";
    public static final String ADMIN_METRICS = "ADMIN_METRICS";
    public static final String ADMIN_LIST_USERS = "ADMIN_LIST_USERS";
    public static final String ADMIN_USERS = "ADMIN_USERS";
    public static final String ADMIN_USER_ACTION = "ADMIN_USER_ACTION";
    public static final String ADMIN_LIST_ROOMS = "ADMIN_LIST_ROOMS";
    public static final String ADMIN_ROOMS = "ADMIN_ROOMS";
    public static final String ADMIN_GET_ROOM_MEMBERS = "ADMIN_GET_ROOM_MEMBERS";
    public static final String ADMIN_ROOM_MEMBERS = "ADMIN_ROOM_MEMBERS";
    public static final String ADMIN_ROOM_ACTION = "ADMIN_ROOM_ACTION";
    public static final String ADMIN_LOGS_REQUEST = "ADMIN_LOGS_REQUEST";
    public static final String ADMIN_LOGS = "ADMIN_LOGS";
    public static final String ADMIN_RESULT = "ADMIN_RESULT";

    // =========================================================
    // 2B. GOI VIDEO 1-1 (media end-to-end encrypted; server relays only)
    // =========================================================
    public static final String CALL_INVITE = "CALL_INVITE";
    public static final String CALL_INCOMING = "CALL_INCOMING";
    public static final String CALL_RINGING = "CALL_RINGING";
    public static final String CALL_ACCEPT = "CALL_ACCEPT";
    public static final String CALL_ACCEPTED = "CALL_ACCEPTED";
    public static final String CALL_REJECT = "CALL_REJECT";
    public static final String CALL_REJECTED = "CALL_REJECTED";
    public static final String CALL_CANCEL = "CALL_CANCEL";
    public static final String CALL_CANCELED = "CALL_CANCELED";
    public static final String CALL_BUSY = "CALL_BUSY";
    public static final String CALL_OFFLINE = "CALL_OFFLINE";
    public static final String CALL_TIMEOUT = "CALL_TIMEOUT";
    public static final String CALL_MISSED = "CALL_MISSED";
    public static final String CALL_END = "CALL_END";
    public static final String CALL_ENDED = "CALL_ENDED";
    public static final String CALL_ERROR = "CALL_ERROR";
    public static final String CALL_RATE_LIMIT = "CALL_RATE_LIMIT";
    public static final String CALL_KEY_PUBLIC = "CALL_KEY_PUBLIC";
    public static final String CALL_KEY_OFFER = "CALL_KEY_OFFER";
    public static final String CALL_KEY_READY = "CALL_KEY_READY";
    public static final String CALL_MEDIA_STATE = "CALL_MEDIA_STATE";
    public static final String CALL_AUDIO = "CALL_AUDIO";
    public static final String CALL_VIDEO = "CALL_VIDEO";
    public static final String CALL_PING = "CALL_PING";
    public static final String CALL_PONG = "CALL_PONG";

    // =========================================================
    // 2C. GOI VIDEO NHOM (toi da 6 thanh vien, media duoc ma hoa dau cuoi)
    // =========================================================
    public static final String GROUP_CALL_START = "GROUP_CALL_START";                 // roomId|callId
    public static final String GROUP_CALL_JOIN = "GROUP_CALL_JOIN";                   // roomId|callId
    public static final String GROUP_CALL_LEAVE = "GROUP_CALL_LEAVE";                 // callId
    public static final String GROUP_CALL_STATE_REQUEST = "GROUP_CALL_STATE_REQUEST"; // roomId
    public static final String GROUP_CALL_STARTED = "GROUP_CALL_STARTED";             // roomId|callId|starter
    public static final String GROUP_CALL_ALREADY_ACTIVE = "GROUP_CALL_ALREADY_ACTIVE"; // roomId|callId|owner
    public static final String GROUP_CALL_ANNOUNCE = "GROUP_CALL_ANNOUNCE";             // roomId|callId|starter
    public static final String GROUP_CALL_STATE = "GROUP_CALL_STATE";                   // roomId|callId|starter|keyOwner|participantsB64
    public static final String GROUP_CALL_IDLE = "GROUP_CALL_IDLE";                     // roomId
    public static final String GROUP_CALL_JOINED = "GROUP_CALL_JOINED";                 // roomId|callId|username
    public static final String GROUP_CALL_LEFT = "GROUP_CALL_LEFT";                     // roomId|callId|username|reason
    public static final String GROUP_CALL_ENDED = "GROUP_CALL_ENDED";                   // roomId|callId|reason
    public static final String GROUP_CALL_ERROR = "GROUP_CALL_ERROR";                   // callId|message
    public static final String GROUP_CALL_KEY_PUBLIC = "GROUP_CALL_KEY_PUBLIC";         // callId|username|publicKeyB64
    public static final String GROUP_CALL_KEY_OFFER = "GROUP_CALL_KEY_OFFER";           // callId|sourceOwner|wrappedKeyB64
    public static final String GROUP_CALL_KEY_READY = "GROUP_CALL_KEY_READY";           // callId|username
    public static final String GROUP_CALL_KEY_OWNER = "GROUP_CALL_KEY_OWNER";           // callId|username
    public static final String GROUP_CALL_MEDIA_STATE = "GROUP_CALL_MEDIA_STATE";       // callId|username|micOn|cameraOn
    public static final String GROUP_CALL_AUDIO = "GROUP_CALL_AUDIO";                   // C->S callId|encryptedUlaw; S->C callId|sender|encryptedUlaw
    public static final String GROUP_CALL_VIDEO = "GROUP_CALL_VIDEO";                   // C->S callId|quality|encryptedJpeg; S->C callId|sender|quality|encryptedJpeg
    public static final String GROUP_CALL_PING = "GROUP_CALL_PING";                     // callId|sentAt
    public static final String GROUP_CALL_PONG = "GROUP_CALL_PONG";                     // callId|sentAt

    // =========================================================
    // 3. QUAN LY PHONG CHAT
    // =========================================================

    public static final String LIST_ROOMS = "LIST_ROOMS";

    public static final String CREATE_ROOM = "CREATE_ROOM";
    public static final String JOIN_ROOM = "JOIN_ROOM";
    public static final String LEAVE_ROOM = "LEAVE_ROOM";
    public static final String LIST_MEMBERS = "LIST_MEMBERS";

    public static final String ROOM_LIST = "ROOM_LIST";
    public static final String ROOMS_CHANGED = "ROOMS_CHANGED";

    public static final String CREATE_OK = "CREATE_OK";
    public static final String CREATE_FAIL = "CREATE_FAIL";

    public static final String JOIN_OK = "JOIN_OK";
    public static final String JOIN_FAIL = "JOIN_FAIL";
    public static final String LEAVE_OK = "LEAVE_OK";
    public static final String KICKED = "KICKED";

    public static final String SYS = "SYS";

    // =========================================================
    // 4. DANH SACH NGUOI DUNG VA CUOC TRO CHUYEN
    // =========================================================

    public static final String LIST_USERS = "LIST_USERS";
    public static final String USER_LIST = "USER_LIST";

    // Tìm người dùng theo public ID 5 chữ số.
    public static final String FIND_USER_BY_ID = "FIND_USER_BY_ID";
    public static final String USER_FOUND = "USER_FOUND";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    // Server -> Client: PRESENCE_UPDATE|username|online(1/0)|lastSeen
    public static final String PRESENCE_UPDATE = "PRESENCE_UPDATE";

    public static final String LIST_PRIVATE_CONTACTS =
            "LIST_PRIVATE_CONTACTS";

    public static final String PRIVATE_CONTACTS =
            "PRIVATE_CONTACTS";

    // Danh sách hội thoại: nhóm đã tham gia + chat riêng có lịch sử.
    public static final String LIST_CONVERSATIONS = "LIST_CONVERSATIONS";
    public static final String CONVERSATIONS_LIST = "CONVERSATIONS_LIST";

    // Tìm nhóm theo ID; server không gửi mật khẩu về client.
    public static final String FIND_ROOM_BY_ID = "FIND_ROOM_BY_ID";
    public static final String ROOM_FOUND = "ROOM_FOUND";
    public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";

    // =========================================================
    // 5. QUAN LY THANH VIEN VA PHAN QUYEN
    // =========================================================

    public static final String MEMBERS_CHANGED = "MEMBERS_CHANGED";
    // Server -> target client: ROOM_ROLE_CHANGED|roomId|newRole
    public static final String ROOM_ROLE_CHANGED = "ROOM_ROLE_CHANGED";
    public static final String MEMBER_LIST = "MEMBER_LIST";

    public static final String GRANT_ADMIN = "GRANT_ADMIN";
    public static final String REVOKE_ADMIN = "REVOKE_ADMIN";
    public static final String KICK_MEMBER = "KICK_MEMBER";

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    // =========================================================
    // 6. GUI TIN NHAN
    // =========================================================

    public static final String SEND_MSG_GROUP = "SEND_MSG_GROUP";
    public static final String SEND_MSG_PRIVATE = "SEND_MSG_PRIVATE";

    public static final String MSG_GROUP = "MSG_GROUP";
    public static final String MSG_PRIVATE = "MSG_PRIVATE";

    // =========================================================
    // 7. LICH SU TIN NHAN
    // =========================================================

    public static final String LOAD_HISTORY_PAGED =
            "LOAD_HISTORY_PAGED";

    public static final String LOAD_PRIVATE_HISTORY =
            "LOAD_PRIVATE_HISTORY";

    public static final String HISTORY_BEGIN = "HISTORY_BEGIN";
    public static final String HISTORY_ITEM = "HISTORY_ITEM";
    public static final String HISTORY_END = "HISTORY_END";

    // =========================================================
    // 8. SUA, THU HOI VA GHIM TIN NHAN
    // =========================================================

    public static final String EDIT_MSG = "EDIT_MSG";
    public static final String RECALL_MSG = "RECALL_MSG";
    public static final String PIN_MSG = "PIN_MSG";

    public static final String MSG_EDITED = "MSG_EDITED";
    public static final String MSG_RECALLED = "MSG_RECALLED";
    public static final String MSG_PINNED = "MSG_PINNED";

    // =========================================================
    // 9. CAM XUC TIN NHAN
    // =========================================================

    public static final String REACT_MSG = "REACT_MSG";
    public static final String REACTIONS_UPDATED = "REACTIONS_UPDATED";

    public static final String[] REACTIONS = {
        "LIKE",
        "LOVE",
        "HAHA",
        "WOW",
        "SAD",
        "ANGRY"
    };

    // =========================================================
    // 10. UPLOAD FILE
    // =========================================================

    // SEND_FILE|GROUP/PRIVATE|target|fileNameBase64|fileSize|sha256
    public static final String SEND_FILE = "SEND_FILE";

    public static final String UPLOAD_READY = "UPLOAD_READY";
    public static final String UPLOAD_REJECT = "UPLOAD_REJECT";
    public static final String UPLOAD_CHUNK = "UPLOAD_CHUNK";
    public static final String UPLOAD_FINISH = "UPLOAD_FINISH";
    public static final String UPLOAD_CANCEL = "UPLOAD_CANCEL";
    public static final String UPLOAD_DONE = "UPLOAD_DONE";

    // =========================================================
    // 11. DOWNLOAD FILE
    // =========================================================

    public static final String DOWNLOAD_FILE = "DOWNLOAD_FILE";
    public static final String DOWNLOAD_BEGIN = "DOWNLOAD_BEGIN";
    public static final String DOWNLOAD_CHUNK = "DOWNLOAD_CHUNK";
    public static final String DOWNLOAD_DONE = "DOWNLOAD_DONE";
    public static final String DOWNLOAD_FAIL = "DOWNLOAD_FAIL";

    // =========================================================
    // 12. DANH SACH FILE TRONG CUOC TRO CHUYEN
    // =========================================================

    /*
     * Client -> Server:
     * LIST_FILES|G|roomId|page
     * LIST_FILES|P|peerUsername|page
     *
     * G = Group chat.
     * P = Private chat.
     */
    public static final String LIST_FILES = "LIST_FILES";

    /*
     * Server -> Client:
     * FILE_LIST|G/P|key|page|hasMore|records
     *
     * Mỗi record:
     * fileId,sender,fileNameBase64,size,timestamp
     * Các record phân cách bằng dấu chấm phẩy.
     */
    public static final String FILE_LIST = "FILE_LIST";

    // Giu lai de tuong thich voi code cu neu dang su dung.
    public static final String FILE_DATA = "FILE_DATA";

    // =========================================================
    // 13. TIM KIEM TIN NHAN (MOI)
    // =========================================================

    // Client -> Server: SEARCH_MESSAGES|G/P|roomId/peerUsername|queryToken
    // queryToken la tu khoa ma hoa bang Base64 URL-safe UTF-8.
    public static final String SEARCH_MESSAGES = "SEARCH_MESSAGES";

    // Server -> Client: SEARCH_BEGIN|G/P|key|queryToken
    public static final String SEARCH_BEGIN = "SEARCH_BEGIN";

    // Server -> Client:
    // SEARCH_ITEM|G/P|key|queryToken|id|sender|timestamp|type|contentB64|edited|deleted|pinned
    public static final String SEARCH_ITEM = "SEARCH_ITEM";

    // Server -> Client: SEARCH_END|G/P|key|queryToken|count|truncated(0/1)
    public static final String SEARCH_END = "SEARCH_END";

    // Server -> Client: SEARCH_FAIL|G/P|key|queryToken|reasonBase64Url
    public static final String SEARCH_FAIL = "SEARCH_FAIL";

    // =========================================================
    // 15. TRANG THAI DA DOC VA DANG NHAP TIN NHAN
    // =========================================================

    // Client -> Server: MARK_READ|G/P|roomId/peerUsername|lastReadMessageId
    public static final String MARK_READ = "MARK_READ";

    // Client -> Server: GET_READ_STATE|G/P|roomId/peerUsername
    public static final String GET_READ_STATE = "GET_READ_STATE";

    // Server -> Client: READ_STATE|G/P|key|readerUsername|lastReadMessageId
    public static final String READ_STATE = "READ_STATE";

    // Client -> Server: TYPING|G/P|roomId/peerUsername|1/0
    public static final String TYPING = "TYPING";

    // Client -> Server: GET_TYPING_STATE|G/P|roomId/peerUsername
    public static final String GET_TYPING_STATE = "GET_TYPING_STATE";

    // Server -> Client: TYPING_STATE|G/P|key|username|1/0
    public static final String TYPING_STATE = "TYPING_STATE";

    // Client -> Server: yêu cầu đồng bộ số tin chưa đọc.
    public static final String GET_UNREAD_COUNTS = "GET_UNREAD_COUNTS";

    // Server -> Client: bắt đầu/kết thúc snapshot số tin chưa đọc.
    public static final String UNREAD_BEGIN = "UNREAD_BEGIN";
    public static final String UNREAD_END = "UNREAD_END";

    // Server -> Client:
    // UNREAD_COUNT|G|roomId|count|latestMessageId
    // UNREAD_COUNT|P|peerUsernameBase64Url|count|latestMessageId
    public static final String UNREAD_COUNT = "UNREAD_COUNT";

    // =========================================================
    // 14. LOAI TIN NHAN
    // =========================================================

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_FILE = "FILE";
    public static final String TYPE_CALL = "CALL";

    // =========================================================
    // TƯƠNG THÍCH: HỒ SƠ NGƯỜI DÙNG VÀ TỆP TRUYỀN
    // Các lệnh này được dùng bởi UserProfileService và FileTransferService.
    // =========================================================
    public static final String CONVERSATION_FILES = "CONVERSATION_FILES";
    public static final String DOWNLOAD_CANCEL = "DOWNLOAD_CANCEL";
    public static final String GET_USER_AVATAR = "GET_USER_AVATAR";
    public static final String GET_USER_PROFILE = "GET_USER_PROFILE";
    public static final String LIST_CONVERSATION_FILES = "LIST_CONVERSATION_FILES";
    public static final String PROFILE_DATA = "PROFILE_DATA";
    public static final String PROFILE_UPDATE_FAIL = "PROFILE_UPDATE_FAIL";
    public static final String PROFILE_UPDATE_OK = "PROFILE_UPDATE_OK";
    public static final String UPDATE_USER_PROFILE = "UPDATE_USER_PROFILE";
    public static final String UPLOAD_USER_AVATAR_BEGIN = "UPLOAD_USER_AVATAR_BEGIN";
    public static final String UPLOAD_USER_AVATAR_CHUNK = "UPLOAD_USER_AVATAR_CHUNK";
    public static final String UPLOAD_USER_AVATAR_END = "UPLOAD_USER_AVATAR_END";
    public static final String USER_AVATAR_BEGIN = "USER_AVATAR_BEGIN";
    public static final String USER_AVATAR_CHUNK = "USER_AVATAR_CHUNK";
    public static final String USER_AVATAR_END = "USER_AVATAR_END";
    public static final String USER_AVATAR_FAIL = "USER_AVATAR_FAIL";
    public static final String USER_AVATAR_UPLOAD_FAIL = "USER_AVATAR_UPLOAD_FAIL";
    public static final String USER_AVATAR_UPLOAD_OK = "USER_AVATAR_UPLOAD_OK";
    public static final String USER_PROFILE_CHANGED = "USER_PROFILE_CHANGED";

    // Quản lý ảnh đại diện phòng và giải tán phòng.
    public static final String DISSOLVE_ROOM = "DISSOLVE_ROOM";
    public static final String ROOM_DISSOLVED = "ROOM_DISSOLVED";
    public static final String ROOM_DISSOLVE_FAIL = "ROOM_DISSOLVE_FAIL";
    public static final String GET_ROOM_AVATAR = "GET_ROOM_AVATAR";
    public static final String UPLOAD_ROOM_AVATAR_BEGIN = "UPLOAD_ROOM_AVATAR_BEGIN";
    public static final String UPLOAD_ROOM_AVATAR_CHUNK = "UPLOAD_ROOM_AVATAR_CHUNK";
    public static final String UPLOAD_ROOM_AVATAR_END = "UPLOAD_ROOM_AVATAR_END";
    public static final String ROOM_AVATAR_BEGIN = "ROOM_AVATAR_BEGIN";
    public static final String ROOM_AVATAR_CHUNK = "ROOM_AVATAR_CHUNK";
    public static final String ROOM_AVATAR_END = "ROOM_AVATAR_END";
    public static final String ROOM_AVATAR_FAIL = "ROOM_AVATAR_FAIL";
    public static final String ROOM_AVATAR_UPLOAD_OK = "ROOM_AVATAR_UPLOAD_OK";
    public static final String ROOM_AVATAR_UPLOAD_FAIL = "ROOM_AVATAR_UPLOAD_FAIL";
    public static final String ROOM_AVATAR_CHANGED = "ROOM_AVATAR_CHANGED";

    // =========================================================
    // 15. TAO GOI TIN
    // =========================================================

    public static String join(Object... parts) {

        if (parts == null || parts.length == 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(DELIMITER);
            }

            if (parts[i] != null) {
                result.append(parts[i]);
            }
        }

        return result.toString();
    }

    // =========================================================
    // 16. TACH GOI TIN
    // =========================================================

    public static String[] split(String line) {

        if (line == null) {
            return new String[0];
        }

        return line.split("\\|", -1);
    }

    // =========================================================
    // 17. LAM SACH DU LIEU
    // =========================================================

    public static String clean(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("|", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace(",", " ")
                .trim();
    }
}
