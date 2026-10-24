package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientHandler implements Runnable {

    private final Socket socket;

    private PrintWriter out;
    private BufferedReader in;

    private volatile String currentUsername;
    private volatile boolean disconnected = false;
    private final AtomicBoolean tcpCounterReleased = new AtomicBoolean(false);

    // Các service xử lý nghiệp vụ.
    private final AuthService authService;
    private final RoomService roomService;
    private final MessageService messageService;
    private final MessageActionService messageActionService;
    private final ReactionService reactionService;
    private final MessageSearchService messageSearchService;
    private final ReadStateService readStateService;
    // Xử lý hồ sơ cá nhân và ảnh đại diện.
    private final UserProfileService userProfileService;
    private final RoomManagementService roomManagementService;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        ChatServer.tcpConnections.incrementAndGet();

        this.authService = new AuthService(this);
        this.roomService = new RoomService(this);
        this.messageService = new MessageService(this);
        this.messageActionService = new MessageActionService(this);
        this.reactionService = new ReactionService(this);
        this.messageSearchService = new MessageSearchService(this);
        this.readStateService = new ReadStateService(this);
        this.userProfileService = new UserProfileService(this);
        this.roomManagementService = new RoomManagementService(this);
    }

    // =========================================================
    // 1. VÒNG ĐỜI KẾT NỐI VÀ ĐIỀU PHỐI LỆNH
    // =========================================================

    @Override
    public void run() {
        try {
            in = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            out = new PrintWriter(
                    socket.getOutputStream(),
                    true,
                    StandardCharsets.UTF_8
            );

            String line;

            while (!disconnected && (line = in.readLine()) != null) {
                AdminService.recordInbound(line.getBytes(StandardCharsets.UTF_8).length + 1L);

                int frameLimit = (line.startsWith(Protocol.CALL_VIDEO + "|")
                        || line.startsWith(Protocol.GROUP_CALL_VIDEO + "|"))
                        ? Protocol.MAX_CALL_VIDEO_FRAME_CHARS : Protocol.MAX_FRAME_CHARS;
                if (line.length() > frameLimit) {
                    sendError("Gói tin quá dài.");
                    continue;
                }

                String[] parts = Protocol.split(line);

                if (parts.length == 0 || parts[0].isEmpty()) {
                    continue;
                }

                try {
                    dispatchCommand(parts);
                } catch (Exception e) {
                    AdminService.recordError();
                    System.err.println(
                            "[ClientHandler] Lỗi xử lý lệnh "
                            + parts[0] + " của "
                            + currentUsername + ": " + e.getMessage()
                    );
                    sendError("Không thể xử lý yêu cầu.");
                }
            }

        } catch (IOException e) {
            if (!disconnected) {
                System.out.println(
                        "[ClientHandler] Mất kết nối từ: "
                        + currentUsername
                );
            }

        } catch (Exception e) {
            if (!disconnected) {
                AdminService.recordError();
                System.err.println(
                        "[ClientHandler] Lỗi client "
                        + currentUsername + ": " + e.getMessage()
                );
                e.printStackTrace();
            }

        } finally {
            disconnect();
        }
    }

    private void dispatchCommand(String[] parts) {

        switch (parts[0]) {

            // ---------------- TÀI KHOẢN ----------------

            case Protocol.AUTH_LOGIN:
                authService.handleLogin(parts);
                break;

            case Protocol.AUTH_REGISTER:
                authService.handleRegister(parts);
                break;

            case Protocol.CHANGE_PASSWORD:
                authService.handleChangePassword(parts);
                break;

            case Protocol.LOGOUT:
                if (currentUsername != null) {
                    sendMessage(
                            Protocol.join(
                                    Protocol.SYS,
                                    "Đã đăng xuất."
                            )
                    );
                }
                disconnect();
                break;

            case Protocol.PING:
                sendMessage(Protocol.PONG);
                break;

            // ---------------- GOI VIDEO 1-1 ----------------
            case Protocol.CALL_INVITE:
            case Protocol.CALL_ACCEPT:
            case Protocol.CALL_REJECT:
            case Protocol.CALL_CANCEL:
            case Protocol.CALL_END:
            case Protocol.CALL_KEY_PUBLIC:
            case Protocol.CALL_KEY_OFFER:
            case Protocol.CALL_KEY_READY:
            case Protocol.CALL_MEDIA_STATE:
            case Protocol.CALL_AUDIO:
            case Protocol.CALL_VIDEO:
            case Protocol.CALL_PING:
            case Protocol.CALL_PONG:
                CallService.handle(this, parts, Protocol.join((Object[]) parts));
                break;

            // ---------------- GOI VIDEO NHOM ----------------
            case Protocol.GROUP_CALL_START:
            case Protocol.GROUP_CALL_JOIN:
            case Protocol.GROUP_CALL_LEAVE:
            case Protocol.GROUP_CALL_STATE_REQUEST:
            case Protocol.GROUP_CALL_KEY_PUBLIC:
            case Protocol.GROUP_CALL_KEY_OFFER:
            case Protocol.GROUP_CALL_KEY_READY:
            case Protocol.GROUP_CALL_MEDIA_STATE:
            case Protocol.GROUP_CALL_AUDIO:
            case Protocol.GROUP_CALL_VIDEO:
            case Protocol.GROUP_CALL_PING:
                GroupCallService.handle(this, parts);
                break;

            // ---------------- DASHBOARD QUẢN TRỊ ----------------
            case Protocol.ADMIN_SUBSCRIBE:
            case Protocol.ADMIN_UNSUBSCRIBE:
            case Protocol.ADMIN_LIST_USERS:
            case Protocol.ADMIN_USER_ACTION:
            case Protocol.ADMIN_LIST_ROOMS:
            case Protocol.ADMIN_GET_ROOM_MEMBERS:
            case Protocol.ADMIN_ROOM_ACTION:
            case Protocol.ADMIN_LOGS_REQUEST:
                AdminService.handle(this, parts);
                break;

            // ---------------- QUẢN LÝ PHÒNG ----------------

            case Protocol.CREATE_ROOM:
                roomService.handleCreateRoom(parts);
                break;

            case Protocol.JOIN_ROOM:
                roomService.handleJoinRoom(parts);
                break;

            case Protocol.LEAVE_ROOM:
                roomService.handleLeaveRoom(parts);
                break;

            case Protocol.LIST_ROOMS:
                handleListRoomsByLatestActivity();
                break;

            case Protocol.LIST_USERS:
                handleListUsers();
                break;

            case Protocol.FIND_USER_BY_ID:
                handleFindUserByPublicId(parts);
                break;

            case Protocol.FIND_ROOM_BY_ID:
                handleFindRoomByPublicId(parts);
                break;

            case Protocol.LIST_MEMBERS:
                roomService.handleListMembers(parts);
                break;

            case Protocol.GRANT_ADMIN:
                roomService.handleChangeRole(parts, true);
                break;

            case Protocol.REVOKE_ADMIN:
                roomService.handleChangeRole(parts, false);
                break;

            case Protocol.KICK_MEMBER:
                roomService.handleKickMember(parts);
                break;

            // ---------------- DANH SÁCH CHAT RIÊNG ----------------

            case Protocol.LIST_PRIVATE_CONTACTS:
                handleListPrivateContacts();
                break;

            // ---------------- GỬI TIN NHẮN ----------------

            case Protocol.SEND_MSG_GROUP:
                if (parts.length >= 3 && requireLogin() && AdminService.allowMessage(this, parts[2])) {
                    messageService.handleSendGroupMessage(parts);
                }
                break;

            case Protocol.SEND_MSG_PRIVATE:
                if (parts.length >= 3 && requireLogin() && AdminService.allowMessage(this, parts[2])) {
                    messageService.handleSendPrivateMessage(parts);
                }
                break;

            // ---------------- UPLOAD FILE ----------------

            case Protocol.SEND_FILE:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleSendFile(
                                    this,
                                    currentUsername,
                                    parts
                            );
                }
                break;

            case Protocol.UPLOAD_CHUNK:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleUploadChunk(
                                    this,
                                    currentUsername,
                                    parts
                            );
                }
                break;

            case Protocol.UPLOAD_FINISH:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleUploadFinish(
                                    this,
                                    currentUsername,
                                    parts
                            );
                }
                break;

            case Protocol.UPLOAD_CANCEL:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleUploadCancel(
                                    this,
                                    currentUsername,
                                    parts
                            );
                }
                break;

            // ---------------- DOWNLOAD FILE ----------------

            case Protocol.DOWNLOAD_FILE:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleDownloadFile(
                                    this,
                                    currentUsername,
                                    parts
                            );
                }
                break;

            case Protocol.DOWNLOAD_CANCEL:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleDownloadCancel(this, currentUsername, parts);
                }
                break;

            // ---------------- DANH SÁCH FILE ----------------

            /*
             * LIST_FILES|G|roomId|page
             * LIST_FILES|P|peerUsername|page
             */
            case Protocol.LIST_FILES:
                if (requireLogin()) {
                    FileTransferService.getInstance()
                            .handleListFiles(
                                    this,
                                    currentUsername,
                                    parts
                            );
                }
                break;

            // ---------------- THAO TÁC TIN NHẮN ----------------

            case Protocol.EDIT_MSG:
                messageActionService.handleEditMessage(parts);
                break;

            case Protocol.RECALL_MSG:
                messageActionService.handleRecallMessage(parts);
                break;

            case Protocol.PIN_MSG:
                messageActionService.handlePinMessage(parts);
                break;

            case Protocol.REACT_MSG:
                reactionService.handleReactMessage(parts);
                break;

            // ---------------- LỊCH SỬ CHAT ----------------

            case Protocol.LOAD_HISTORY_PAGED:
                messageService.handleLoadHistoryPaged(parts);
                break;

            case Protocol.LOAD_PRIVATE_HISTORY:
                messageService.handleLoadPrivateHistory(parts);
                break;

            // ---------------- TÌM KIẾM TIN NHẮN ----------------

            case Protocol.SEARCH_MESSAGES:
                messageSearchService.handleSearchMessages(parts);
                break;

            // ---------------- TRANG THAI DA DOC / DANG NHAP ----------------

            case Protocol.MARK_READ:
                readStateService.handleMarkRead(parts);
                break;

            case Protocol.GET_READ_STATE:
                readStateService.handleGetReadState(parts);
                break;

            case Protocol.TYPING:
                readStateService.handleTyping(parts);
                break;

            case Protocol.GET_TYPING_STATE:
                readStateService.handleGetTypingState(parts);
                break;

            case Protocol.GET_UNREAD_COUNTS:
                readStateService.handleGetUnreadCounts(parts);
                break;

            // ---------------- HỒ SƠ TÀI KHOẢN / ẢNH ĐẠI DIỆN ----------------

            case Protocol.DISSOLVE_ROOM:
                roomManagementService.handleDissolveRoom(parts);
                break;

            case Protocol.GET_ROOM_AVATAR:
                roomManagementService.handleGetRoomAvatar(parts);
                break;

            case Protocol.UPLOAD_ROOM_AVATAR_BEGIN:
                roomManagementService.handleAvatarUploadBegin(parts);
                break;

            case Protocol.UPLOAD_ROOM_AVATAR_CHUNK:
                roomManagementService.handleAvatarUploadChunk(parts);
                break;

            case Protocol.UPLOAD_ROOM_AVATAR_END:
                roomManagementService.handleAvatarUploadEnd();
                break;

            case Protocol.GET_USER_PROFILE:
                userProfileService.handleGetProfile(parts);
                break;

            case Protocol.UPDATE_USER_PROFILE:
                userProfileService.handleUpdateProfile(parts);
                break;

            case Protocol.GET_USER_AVATAR:
                userProfileService.handleGetAvatar(parts);
                break;

            case Protocol.UPLOAD_USER_AVATAR_BEGIN:
                userProfileService.handleAvatarUploadBegin(parts);
                break;

            case Protocol.UPLOAD_USER_AVATAR_CHUNK:
                userProfileService.handleAvatarUploadChunk(parts);
                break;

            case Protocol.UPLOAD_USER_AVATAR_END:
                userProfileService.handleAvatarUploadEnd();
                break;

            default:
                sendError(
                        "Lệnh không hợp lệ: "
                        + Protocol.clean(parts[0])
                );
                break;
        }
    }

    // =========================================================
    // 2. DANH SÁCH TẤT CẢ TÀI KHOẢN ĐỂ TÌM CHAT RIÊNG
    // =========================================================

    /**
     * Trả toàn bộ tài khoản đã đăng ký, kể cả người đang offline.
     * Giao thức: USER_LIST|username,online;username,online;...
     * Dùng trực tiếp DB ở đây để không phụ thuộc cách AuthService
     * lọc người dùng đang online hoặc chỉ các liên hệ gần đây.
     */
    private void handleListUsers() {
        if (!requireLogin()) return;

        // Chỉ đưa về client những tài khoản đã có hội thoại riêng với người hiện tại.
        // Tra cứu người lạ theo ID đi qua FIND_USER_BY_ID, không bung toàn bộ directory.
        String sql = "SELECT u.username, u.last_seen, u.public_id "
                + "FROM users u WHERE u.username COLLATE BINARY <> ? COLLATE BINARY AND u.username IN ("
                + " SELECT CASE WHEN m.sender COLLATE BINARY = ? COLLATE BINARY THEN m.receiver ELSE m.sender END "
                + " FROM messages m WHERE m.receiver IS NOT NULL "
                + " AND (m.sender COLLATE BINARY = ? COLLATE BINARY OR m.receiver COLLATE BINARY = ? COLLATE BINARY) "
                + " AND (CASE WHEN m.sender COLLATE BINARY = ? COLLATE BINARY THEN m.receiver ELSE m.sender END) IS NOT NULL"
                + ") ORDER BY u.username COLLATE BINARY";

        StringBuilder response = new StringBuilder(Protocol.USER_LIST).append('|');
        boolean first = true;
        try {
            PresenceService.ensureSchema();
        } catch (Exception ex) {
            System.err.println("[Presence] Không thể chuẩn bị cột last_seen: " + ex.getMessage());
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentUsername);
            ps.setString(2, currentUsername);
            ps.setString(3, currentUsername);
            ps.setString(4, currentUsername);
            ps.setString(5, currentUsername);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String user = rs.getString("username");
                    if (user == null || user.isBlank()) continue;
                    if (!first) response.append(';');
                    first = false;
                    response.append(Protocol.clean(user)).append(',')
                            .append(isUserOnline(user) ? '1' : '0').append(',')
                            .append(Protocol.clean(rs.getString("last_seen"))).append(',')
                            .append(Protocol.clean(rs.getString("public_id")));
                }
            }
            sendMessage(response.toString());
        } catch (Exception e) {
            System.err.println("[ClientHandler] Không thể tải liên hệ đã chat: " + e.getMessage());
            sendError("Không thể tải danh sách người đã từng chat.");
        }
    }

    /** Client -> FIND_USER_BY_ID|publicId; server -> USER_FOUND|username|online|publicId. */
    private void handleFindUserByPublicId(String[] parts) {
        if (!requireLogin()) return;
        String publicId = parts != null && parts.length > 1 ? parts[1].trim() : "";
        if (!publicId.matches("\\d{5}")) {
            sendMessage(Protocol.join(Protocol.USER_NOT_FOUND, "ID người dùng phải gồm đúng 5 chữ số."));
            return;
        }
        String sql = "SELECT username, public_id FROM users WHERE public_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendMessage(Protocol.join(Protocol.USER_NOT_FOUND, "Không tìm thấy người dùng có ID " + publicId + "."));
                    return;
                }
                String foundUsername = rs.getString("username");
                if (foundUsername == null || foundUsername.equals(currentUsername)) {
                    sendMessage(Protocol.join(Protocol.USER_NOT_FOUND, "Bạn không thể chat riêng với chính mình."));
                    return;
                }
                sendMessage(Protocol.join(Protocol.USER_FOUND,
                        Protocol.clean(foundUsername),
                        isUserOnline(foundUsername) ? "1" : "0",
                        Protocol.clean(rs.getString("public_id"))));
            }
        } catch (Exception ex) {
            System.err.println("[ClientHandler] Lỗi tìm người theo ID: " + ex.getMessage());
            sendMessage(Protocol.join(Protocol.USER_NOT_FOUND, "Có lỗi khi tìm người dùng."));
        }
    }

    /** Client -> FIND_ROOM_BY_ID|publicId; room password never leaves the server. */
    private void handleFindRoomByPublicId(String[] parts) {
        if (!requireLogin()) return;
        String publicId = parts != null && parts.length > 1 ? parts[1].trim() : "";
        if (!publicId.matches("\\d{5}")) {
            sendMessage(Protocol.join(Protocol.ROOM_NOT_FOUND, "ID phòng phải gồm đúng 5 chữ số."));
            return;
        }

        String sql = "SELECT r.id, r.public_id, r.room_name, "
                + "CASE WHEN COALESCE(r.password, '') = '' THEN 0 ELSE 1 END AS locked, "
                + "r.owner, (SELECT COUNT(*) FROM room_members x WHERE x.room_id = r.id) AS member_count, "
                + "COALESCE((SELECT rm.role FROM room_members rm WHERE rm.room_id = r.id AND rm.username COLLATE BINARY = ? COLLATE BINARY), '') AS my_role "
                + "FROM rooms r WHERE r.public_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentUsername);
            ps.setString(2, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendMessage(Protocol.join(Protocol.ROOM_NOT_FOUND, "Không tìm thấy phòng có ID " + publicId + "."));
                    return;
                }
                sendMessage(Protocol.join(Protocol.ROOM_FOUND,
                        rs.getInt("id"),
                        Protocol.clean(rs.getString("public_id")),
                        Protocol.clean(rs.getString("room_name")),
                        rs.getInt("locked"),
                        Protocol.clean(rs.getString("owner")),
                        rs.getInt("member_count"),
                        Protocol.clean(rs.getString("my_role"))));
            }
        } catch (Exception ex) {
            System.err.println("[ClientHandler] Lỗi tìm phòng theo ID: " + ex.getMessage());
            sendMessage(Protocol.join(Protocol.ROOM_NOT_FOUND, "Có lỗi khi tìm phòng."));
        }
    }

    private boolean isUserOnline(String username) {
        for (String onlineName : ChatServer.onlineClients.keySet()) {
            if (onlineName != null && onlineName.equals(username)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================
    // DANH SÁCH PHÒNG XẾP THEO TIN NHẮN MỚI NHẤT
    // ROOM_LIST fields: id,name,locked,owner,memberCount,role,publicId,lastMessageId
    // =========================================================

    private void handleListRoomsByLatestActivity() {
        if (!requireLogin()) return;

        String sql = "SELECT r.id, r.public_id, r.room_name, r.password, r.owner, "
                + "(SELECT COUNT(*) FROM room_members rm WHERE rm.room_id = r.id) AS member_count, "
                + "COALESCE((SELECT rm.role FROM room_members rm "
                + "WHERE rm.room_id = r.id AND rm.username COLLATE BINARY = ? COLLATE BINARY), '') AS my_role, "
                + "COALESCE((SELECT MAX(m.id) FROM messages m WHERE m.room_id = r.id), 0) AS last_message_id "
                + "FROM rooms r "
                + "ORDER BY last_message_id DESC, r.id DESC";

        StringBuilder response = new StringBuilder(Protocol.ROOM_LIST).append('|');
        boolean first = true;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currentUsername);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) response.append(';');
                    first = false;

                    String password = rs.getString("password");
                    String role = rs.getString("my_role");
                    if (role == null) role = "";
                    response.append(rs.getInt("id")).append(',')
                            .append(Protocol.clean(rs.getString("room_name"))).append(',')
                            .append(password != null && !password.isEmpty() ? '1' : '0').append(',')
                            .append(Protocol.clean(rs.getString("owner"))).append(',')
                            .append(rs.getInt("member_count")).append(',')
                            .append(Protocol.clean(role)).append(',')
                            .append(Protocol.clean(rs.getString("public_id"))).append(',')
                            .append(rs.getLong("last_message_id"));
                }
            }
            sendMessage(response.toString());
        } catch (Exception e) {
            System.err.println("[ClientHandler] Không thể tải danh sách phòng theo hoạt động: " + e.getMessage());
            sendError("Không thể tải danh sách phòng.");
        }
    }

    // =========================================================
    // 3. DANH SÁCH NGƯỜI ĐÃ TỪNG CHAT RIÊNG
    // =========================================================

    private void handleListPrivateContacts() {

        if (!requireLogin()) {
            return;
        }

        String sql =
                "SELECT peer, MAX(last_id) AS last_message_id "
                + "FROM ( "
                + "    SELECT "
                + "        CASE WHEN sender COLLATE BINARY = ? COLLATE BINARY THEN receiver "
                + "             ELSE sender END AS peer, "
                + "        id AS last_id "
                + "    FROM messages "
                + "    WHERE receiver IS NOT NULL "
                + "      AND (sender COLLATE BINARY = ? COLLATE BINARY OR receiver COLLATE BINARY = ? COLLATE BINARY) "
                + ") "
                + "WHERE peer IS NOT NULL "
                + "  AND peer <> ? "
                + "GROUP BY peer "
                + "ORDER BY last_message_id DESC";

        StringBuilder response =
                new StringBuilder(Protocol.PRIVATE_CONTACTS)
                        .append("|");

        boolean first = true;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, currentUsername);
            ps.setString(2, currentUsername);
            ps.setString(3, currentUsername);
            ps.setString(4, currentUsername);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {

                    String peer = rs.getString("peer");

                    if (peer == null
                            || peer.trim().isEmpty()
                            || peer.equals(currentUsername)) {
                        continue;
                    }

                    if (!first) {
                        response.append(";");
                    }
                    first = false;

                    boolean online = isUserOnline(peer);
                    String lastSeen = "";
                    try {
                        lastSeen = PresenceService.getLastSeen(peer);
                    } catch (Exception ignored) { }

                    response.append(Protocol.clean(peer))
                            .append(",")
                            .append(online ? "1" : "0")
                            .append(",")
                            .append(Protocol.clean(lastSeen))
                            .append(",")
                            .append(rs.getLong("last_message_id"));
                }
            }

            sendMessage(response.toString());

        } catch (Exception e) {
            System.err.println(
                    "[ClientHandler] Lỗi tải danh sách chat riêng: "
                    + e.getMessage()
            );

            sendError(
                    "Không thể tải danh sách cuộc trò chuyện riêng."
            );
        }
    }

    // =========================================================
    // 4. CÁC PHƯƠNG THỨC HỖ TRỢ CHO SERVICE
    // =========================================================

    String getCurrentUsername() {
        return currentUsername;
    }

    void setCurrentUsername(String username) {
        this.currentUsername = username;
    }

    public boolean requireLogin() {
        if (currentUsername == null || disconnected) {
            sendError("Vui lòng đăng nhập trước.");
            return false;
        }

        return true;
    }

    void sendError(String message) {
        sendMessage(
                Protocol.join(
                        Protocol.ERROR,
                        Protocol.clean(message)
                )
        );
    }

    // =========================================================
    // 5. KIỂM TRA THÀNH VIÊN PHÒNG ĐANG KẾT NỐI
    // =========================================================

    boolean isCurrentRoomMember(int roomId) {

        CopyOnWriteArrayList<ClientHandler> members =
                ChatServer.roomMembers.get(roomId);

        return members != null && members.contains(this);
    }

    // =========================================================
    // 6. KIỂM TRA QUYỀN OWNER / ADMIN
    // =========================================================

    boolean isOwnerOrAdmin(
            Connection conn,
            int roomId,
            String username) throws Exception {

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT role FROM room_members "
                + "WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {

            ps.setInt(1, roomId);
            ps.setString(2, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }

                String role = rs.getString("role");

                return Protocol.ROLE_OWNER.equalsIgnoreCase(role)
                        || Protocol.ROLE_ADMIN.equalsIgnoreCase(role);
            }
        }
    }

    // =========================================================
    // 7. PHÁT TIN NHẮN ĐẾN THÀNH VIÊN PHÒNG
    // =========================================================

    void broadcastToRoom(int roomId, String message) {

        CopyOnWriteArrayList<ClientHandler> members =
                ChatServer.roomMembers.get(roomId);

        if (members != null) {
            for (ClientHandler member : members) {
                member.sendMessage(message);
            }
        }
    }

    // =========================================================
    // BỔ SUNG ID HIỂN THỊ VÀO CÁC GÓI DANH SÁCH CŨ
    // =========================================================

    /**
     * Giữ nguyên ID nội bộ ở đầu gói tin để các lệnh chat/lịch sử/tệp cũ
     * vẫn hoạt động; public_id chỉ được thêm ở cuối mỗi mục để hiển thị.
     */
    private String appendPublicIdsToListPacket(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        try {
            if (msg.startsWith(Protocol.ROOM_LIST + "|")) {
                String[] packet = Protocol.split(msg);
                if (packet.length < 2 || packet[1].isEmpty()) return msg;
                String[] entries = packet[1].split(";", -1);
                StringBuilder result = new StringBuilder(Protocol.ROOM_LIST).append('|');
                try (Connection conn = DatabaseManager.getConnection()) {
                    for (int i = 0; i < entries.length; i++) {
                        if (i > 0) result.append(';');
                        String[] fields = entries[i].split(",", -1);
                        if (fields.length >= 7) {
                            result.append(entries[i]);
                            continue;
                        }
                        if (fields.length == 0 || fields[0].isBlank()) {
                            result.append(entries[i]);
                            continue;
                        }
                        result.append(entries[i]);
                        int fieldCount = fields.length;
                        while (fieldCount < 6) {
                            result.append(',');
                            fieldCount++;
                        }
                        String publicId = getRoomPublicId(conn, fields[0].trim());
                        result.append(',').append(Protocol.clean(publicId));
                    }
                }
                return result.toString();
            }

            if (msg.startsWith(Protocol.MEMBER_LIST + "|")) {
                String[] packet = Protocol.split(msg);
                if (packet.length < 2 || packet[1].isEmpty()) return msg;
                String[] entries = packet[1].split(";", -1);
                if (entries.length <= 1) return msg;
                StringBuilder result = new StringBuilder(Protocol.MEMBER_LIST).append('|');
                try (Connection conn = DatabaseManager.getConnection()) {
                    for (int i = 0; i < entries.length; i++) {
                        if (i > 0) result.append(';');
                        String entry = entries[i];
                        if (i == 0 || entry.isBlank()) {
                            result.append(entry);
                            continue;
                        }
                        String[] fields = entry.split(",", -1);
                        if (fields.length >= 3) {
                            result.append(entry);
                            continue;
                        }
                        result.append(entry);
                        while (fields.length < 2) {
                            result.append(',');
                            fields = java.util.Arrays.copyOf(fields, fields.length + 1);
                        }
                        String publicId = fields.length > 0
                                ? getUserPublicId(conn, fields[0].trim()) : "";
                        result.append(',').append(Protocol.clean(publicId));
                    }
                }
                return result.toString();
            }
        } catch (Exception ex) {
            System.err.println("[PublicID] Không thể bổ sung ID vào danh sách: " + ex.getMessage());
        }
        return msg;
    }

    private String getRoomPublicId(Connection conn, String internalId) throws Exception {
        int id = Integer.parseInt(internalId);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT public_id FROM rooms WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private String getUserPublicId(Connection conn, String username) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT public_id FROM users WHERE username COLLATE BINARY=? COLLATE BINARY")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    // =========================================================
    // 8. GỬI DỮ LIỆU ĐẾN CLIENT
    // =========================================================

    public synchronized void sendMessage(String msg) {

        PrintWriter writer = out;
        if (writer == null
                || msg == null
                || disconnected
                || socket == null
                || socket.isClosed()) {
            return;
        }

        msg = appendPublicIdsToListPacket(msg);
        writer.println(msg);
        writer.flush();
        if (!writer.checkError()) {
            AdminService.recordOutbound(msg.getBytes(StandardCharsets.UTF_8).length + 1L);
        }

        if (writer.checkError()) {
            System.err.println(
                    "[ClientHandler] Không gửi được dữ liệu tới "
                    + currentUsername
            );
        }
    }

    String getRemoteAddress() {
        try { return socket == null ? "" : String.valueOf(socket.getRemoteSocketAddress()); }
        catch (Exception ignored) { return ""; }
    }

    /** Close an account's active TCP session after an administrative action. */
    public void forceDisconnect(String reason) {
        if (reason != null && !reason.isBlank()) {
            sendMessage(Protocol.join(Protocol.SYS, Protocol.clean(reason)));
        }
        disconnect();
    }

    // =========================================================
    // 9. NGẮT KẾT NỐI VÀ DỌN DẸP
    // =========================================================

    private synchronized void disconnect() {

        if (disconnected) {
            return;
        }

        disconnected = true;
        if (tcpCounterReleased.compareAndSet(false, true)) {
            ChatServer.tcpConnections.updateAndGet(value -> Math.max(0, value - 1));
        }
        CallService.onClientDisconnected(this);
        GroupCallService.onClientDisconnected(this);
        AdminService.onClientDisconnected(this);

        // Hủy những phiên upload chưa hoàn tất của client.
        try {
            FileTransferService.getInstance()
                    .cancelUploadsByClient(this);
            FileTransferService.getInstance()
                    .cancelDownloadsByClient(this);
        } catch (Exception e) {
            System.err.println(
                    "[ClientHandler] Lỗi dọn upload: "
                    + e.getMessage()
            );
        }

        String username = currentUsername;

        try {
            readStateService.onDisconnect();
        } catch (Exception e) {
            System.err.println("[ClientHandler] Lỗi dọn trạng thái đang nhập: " + e.getMessage());
        }

        if (username != null) {

            // Chỉ phát trạng thái offline khi map thực sự gỡ đúng phiên kết nối này.
            boolean removedCurrentSession = ChatServer.onlineClients.remove(username, this);
            if (removedCurrentSession) {
                PresenceService.userLoggedOut(username);
            }

            // Gỡ client khỏi các phòng đang tham gia trên bộ nhớ.
            for (Map.Entry<Integer, CopyOnWriteArrayList<ClientHandler>> entry
                    : ChatServer.roomMembers.entrySet()) {

                CopyOnWriteArrayList<ClientHandler> members =
                        entry.getValue();

                members.remove(this);

                if (members.isEmpty()) {
                    ChatServer.roomMembers.remove(
                            entry.getKey(),
                            members
                    );
                }
            }

            currentUsername = null;

            System.out.println(
                    "[Server] Người dùng ngắt kết nối: "
                    + username
            );
        }

        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
        }

        if (out != null) {
            out.close();
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
