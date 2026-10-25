
package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RoomService {

    private final ClientHandler client;

    public RoomService(ClientHandler client) {
        this.client = client;
    }

    // =====================================================
    // 1. TAO PHONG CHAT
    // =====================================================

    public void handleCreateRoom(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts == null || parts.length < 3) {
            sendCreateFail("Thiếu tên phòng hoặc mật khẩu.");
            return;
        }

        String roomName = parts[1].trim();
        String password = parts[2];
        String username = client.getCurrentUsername();

        if (roomName.isEmpty()
                || roomName.contains("|")
                || roomName.contains(",")
                || roomName.contains(";")
                || password.contains("|")) {

            sendCreateFail(
                    "Tên phòng hoặc mật khẩu không hợp lệ."
            );
            return;
        }

        try (Connection conn = DatabaseManager.getConnection()) {

            conn.setAutoCommit(false);

            try {
                int roomId;

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO rooms(room_name, password, owner) "
                        + "VALUES(?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {

                    ps.setString(1, roomName);
                    ps.setString(2, password);
                    ps.setString(3, username);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            roomId = keys.getInt(1);
                        } else {
                            try (PreparedStatement query =
                                         conn.prepareStatement(
                                                 "SELECT id FROM rooms "
                                                 + "WHERE room_name = ?")) {

                                query.setString(1, roomName);

                                try (ResultSet rs = query.executeQuery()) {
                                    if (!rs.next()) {
                                        throw new Exception(
                                                "Không lấy được ID phòng."
                                        );
                                    }
                                    roomId = rs.getInt("id");
                                }
                            }
                        }
                    }
                }

                try (PreparedStatement member = conn.prepareStatement(
                        "INSERT INTO room_members "
                        + "(room_id, username, role) "
                        + "VALUES(?, ?, ?)")) {

                    member.setInt(1, roomId);
                    member.setString(2, username);
                    member.setString(3, Protocol.ROLE_OWNER);
                    member.executeUpdate();
                }

                conn.commit();

                // Tra ket qua tao phong.
                client.sendMessage(
                        Protocol.join(
                                Protocol.CREATE_OK,
                                roomId,
                                roomName
                        )
                );

                // Cap nhat danh sach phong tren cac Client.
                broadcastRoomsChanged();

                // Chu phong duoc tham gia ngay sau khi tao.
                // Hang hoi pass khong can loai bo vi owner da la member.
                handleJoinRoom(new String[]{
                    Protocol.JOIN_ROOM,
                    String.valueOf(roomId),
                    password
                });

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (Exception ignored) {
                }
            }

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi tạo phòng: " + e.getMessage()
            );

            sendCreateFail(
                    "Không thể tạo phòng. Tên phòng có thể đã tồn tại."
            );
        }
    }

    private void sendCreateFail(String message) {
        client.sendMessage(
                Protocol.join(
                        Protocol.CREATE_FAIL,
                        Protocol.clean(message)
                )
        );
    }

    // =====================================================
    // 2. THAM GIA PHONG CHAT
    // JOIN_ROOM|roomID|password
    // =====================================================

    public void handleJoinRoom(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts == null || parts.length < 2) {
            sendJoinFail("Thiếu ID phòng.");
            return;
        }

        int roomId;

        try {
            roomId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            sendJoinFail("ID phòng không hợp lệ.");
            return;
        }

        if (roomId < 1) {
            sendJoinFail("ID phòng không hợp lệ.");
            return;
        }

        String password = parts.length > 2 ? parts[2] : "";
        String username = client.getCurrentUsername();

        try (Connection conn = DatabaseManager.getConnection()) {

            String roomName;
            String roomPassword;
            String owner;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT room_name, password, owner "
                    + "FROM rooms WHERE id = ?")) {

                ps.setInt(1, roomId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendJoinFail("Phòng không tồn tại.");
                        return;
                    }

                    roomName = rs.getString("room_name");
                    roomPassword = rs.getString("password");
                    owner = rs.getString("owner");
                }
            }

            // Kiem tra thanh vien da ton tai trong database.
            String existingRole = getMemberRole(
                    conn, roomId, username
            );

            boolean alreadyMember = existingRole != null;
            boolean isOwner = owner != null
                    && owner.equals(username);

            // Thanh vien da tham gia truoc do duoc quay lai
            // ma khong phai nhap lai mat khau.
            if (!alreadyMember && !isOwner
                    && roomPassword != null
                    && !roomPassword.isEmpty()
                    && !roomPassword.equals(password)) {

                sendJoinFail("Mật khẩu phòng không đúng.");
                return;
            }

            String role;

            if (isOwner) {
                role = Protocol.ROLE_OWNER;

                // Dam bao owner luon co ban ghi trong room_members.
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO room_members "
                        + "(room_id, username, role) VALUES(?, ?, ?)")) {

                    ps.setInt(1, roomId);
                    ps.setString(2, username);
                    ps.setString(3, Protocol.ROLE_OWNER);
                    ps.executeUpdate();
                }

                // Bao dam vai tro trong database dung voi owner.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE room_members SET role = ? "
                        + "WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {

                    ps.setString(1, Protocol.ROLE_OWNER);
                    ps.setInt(2, roomId);
                    ps.setString(3, username);
                    ps.executeUpdate();
                }

            } else if (alreadyMember) {
                // Giu nguyen vai tro Member/Admin hien tai.
                role = existingRole;

            } else {
                // Nguoi moi tham gia phong.
                role = Protocol.ROLE_MEMBER;

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO room_members "
                        + "(room_id, username, role) "
                        + "VALUES(?, ?, ?)")) {

                    ps.setInt(1, roomId);
                    ps.setString(2, username);
                    ps.setString(3, role);
                    ps.executeUpdate();
                }
            }

            // Dang ky Client vao danh sach thanh vien dang online.
            CopyOnWriteArrayList<ClientHandler> members =
                    ChatServer.roomMembers.computeIfAbsent(
                            roomId,
                            key -> new CopyOnWriteArrayList<>()
                    );

            boolean newlyConnected = !members.contains(client);

            if (newlyConnected) {
                members.add(client);
            }

            // JOIN_OK|roomID|roomName|role
            client.sendMessage(
                    Protocol.join(
                            Protocol.JOIN_OK,
                            roomId,
                            Protocol.clean(roomName),
                            role
                    )
            );

            // Cap nhat danh sach thanh vien va so luong phong.
            if (newlyConnected) {
                client.broadcastToRoom(
                        roomId,
                        Protocol.join(
                                Protocol.MEMBERS_CHANGED,
                                roomId
                        )
                );

                broadcastRoomsChanged();
            }

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi tham gia phòng: "
                    + e.getMessage()
            );

            sendJoinFail("Không thể tham gia phòng.");
        }
    }

    private void sendJoinFail(String message) {
        client.sendMessage(
                Protocol.join(
                        Protocol.JOIN_FAIL,
                        Protocol.clean(message)
                )
        );
    }

    // =====================================================
    // 3. ROI PHONG CHAT
    // LEAVE_ROOM|roomID
    // =====================================================

    public void handleLeaveRoom(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts == null || parts.length < 2) {
            client.sendError("Thiếu ID phòng.");
            return;
        }

        int roomId;

        try {
            roomId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        if (roomId < 1) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        String username = client.getCurrentUsername();

        try (Connection conn = DatabaseManager.getConnection()) {

            String role = getMemberRole(conn, roomId, username);

            if (role == null) {
                client.sendError("Bạn chưa tham gia phòng này.");
                return;
            }

            // Khong de phong mat owner vi hien chua co chuc nang
            // chuyen quyen chu phong.
            if (Protocol.ROLE_OWNER.equals(role)) {
                client.sendError(
                        "Chủ phòng không thể rời phòng trực tiếp. "
                        + "Cần chuyển quyền chủ phòng trước."
                );
                return;
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM room_members "
                    + "WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {

                ps.setInt(1, roomId);
                ps.setString(2, username);

                if (ps.executeUpdate() != 1) {
                    client.sendError(
                            "Không thể cập nhật trạng thái thành viên."
                    );
                    return;
                }
            }

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi rời phòng: " + e.getMessage()
            );

            client.sendError("Không thể rời phòng.");
            return;
        }

        // Xoa khoi danh sach thanh vien dang online truoc, de cac broadcast
        // trang thai goi nhom khong gui nguoc lai cho thanh vien vua roi phong.
        CopyOnWriteArrayList<ClientHandler> members =
                ChatServer.roomMembers.get(roomId);

        if (members != null) {
            members.remove(client);

            // Thong bao cho cac thanh vien con lai.
            client.broadcastToRoom(
                    roomId,
                    Protocol.join(
                            Protocol.MEMBERS_CHANGED,
                            roomId
                    )
            );

            if (members.isEmpty()) {
                ChatServer.roomMembers.remove(roomId, members);
            }
        }

        // Roi cuoc goi sau khi loai khoi roomMembers; LEFT van duoc gui truc tiep
        // cho client roi phong, con cac ban tin state chi den thanh vien con lai.
        GroupCallService.onRoomLeave(client, roomId);

        client.sendMessage(
                Protocol.join(
                        Protocol.LEAVE_OK,
                        roomId
                )
        );

        broadcastRoomsChanged();
    }

    // =====================================================
    // 4. DANH SACH PHONG CHAT
    //
    // ROOM_LIST|id,name,locked,owner,count,role;...
    //
    // Quan trong:
    // role = rong neu chua tham gia.
    // =====================================================

    public void handleListRooms() {
        if (!client.requireLogin()) {
            return;
        }

        String username = client.getCurrentUsername();

        String sql =
                "SELECT r.id, r.room_name, r.password, r.owner, "
                + "(SELECT COUNT(*) FROM room_members rm "
                + " WHERE rm.room_id = r.id) AS member_count, "
                + "(SELECT rm.role FROM room_members rm "
                + " WHERE rm.room_id = r.id "
                + " AND rm.username COLLATE BINARY = ? COLLATE BINARY) AS my_role "
                + "FROM rooms r ORDER BY r.id";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {

                StringBuilder sb = new StringBuilder(
                        Protocol.ROOM_LIST
                ).append("|");

                boolean first = true;

                while (rs.next()) {

                    if (!first) {
                        sb.append(";");
                    }
                    first = false;

                    int id = rs.getInt("id");

                    String roomName = Protocol.clean(
                            rs.getString("room_name")
                    );

                    String password = rs.getString("password");

                    String owner = Protocol.clean(
                            rs.getString("owner")
                    );

                    String role = rs.getString("my_role");

                    // KHONG gan MEMBER cho nguoi chua tham gia.
                    if (role == null) {
                        role = "";
                    } else {
                        role = role.trim();
                    }

                    boolean locked = password != null
                            && !password.isEmpty();

                    int memberCount = rs.getInt("member_count");

                    sb.append(id).append(",")
                      .append(roomName).append(",")
                      .append(locked ? "1" : "0").append(",")
                      .append(owner).append(",")
                      .append(memberCount).append(",")
                      .append(Protocol.clean(role));
                }

                client.sendMessage(sb.toString());
            }

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi danh sách phòng: "
                    + e.getMessage()
            );

            client.sendError("Không thể tải danh sách phòng.");
        }
    }

    // =====================================================
    // 5. DANH SACH THANH VIEN
    //
    // MEMBER_LIST|roomID;username,role;username,role
    // =====================================================

    public void handleListMembers(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts == null || parts.length < 2) {
            client.sendError("Thiếu ID phòng.");
            return;
        }

        int roomId;

        try {
            roomId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        // Chi thanh vien dang tham gia moi duoc xem danh sach.
        if (!client.isCurrentRoomMember(roomId)) {
            client.sendError(
                    "Bạn phải vào phòng trước khi xem danh sách thành viên."
            );
            return;
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT username, role FROM room_members "
                     + "WHERE room_id = ? ORDER BY "
                     + "CASE role "
                     + "WHEN 'OWNER' THEN 1 "
                     + "WHEN 'ADMIN' THEN 2 "
                     + "ELSE 3 END, username")) {

            ps.setInt(1, roomId);

            try (ResultSet rs = ps.executeQuery()) {

                StringBuilder sb = new StringBuilder(
                        Protocol.MEMBER_LIST
                ).append("|").append(roomId);

                while (rs.next()) {

                    String memberName = Protocol.clean(
                            rs.getString("username")
                    );

                    String role = Protocol.clean(
                            rs.getString("role")
                    );

                    sb.append(";")
                      .append(memberName)
                      .append(",")
                      .append(role);
                }

                client.sendMessage(sb.toString());
            }

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi danh sách thành viên: "
                    + e.getMessage()
            );

            client.sendError(
                    "Không thể tải danh sách thành viên."
            );
        }
    }

    // =====================================================
    // 6. CAP / THU HOI QUYEN ADMIN
    // =====================================================

    public void handleChangeRole(String[] parts, boolean grant) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts == null || parts.length < 3) {
            client.sendError(
                    "Thiếu ID phòng hoặc username thành viên."
            );
            return;
        }

        int roomId;

        try {
            roomId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        if (roomId < 1) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        String actor = client.getCurrentUsername();
        String targetUser = parts[2].trim();

        if (targetUser.isEmpty()) {
            client.sendError("Username thành viên không hợp lệ.");
            return;
        }

        if (actor.equals(targetUser)) {
            client.sendError(
                    "Không thể thay đổi quyền của chính mình."
            );
            return;
        }

        String requiredRole = grant
                ? Protocol.ROLE_MEMBER
                : Protocol.ROLE_ADMIN;

        String newRole = grant
                ? Protocol.ROLE_ADMIN
                : Protocol.ROLE_MEMBER;

        try (Connection conn = DatabaseManager.getConnection()) {

            String actorRole = getMemberRole(
                    conn, roomId, actor
            );

            // Chi Owner duoc cap va thu hoi quyen Admin.
            if (!Protocol.ROLE_OWNER.equals(actorRole)) {
                client.sendError(
                        "Chỉ Owner mới được cấp hoặc thu hồi quyền Admin."
                );
                return;
            }

            String targetRole = getMemberRole(
                    conn, roomId, targetUser
            );

            if (targetRole == null) {
                client.sendError(
                        "Người dùng không phải thành viên của phòng."
                );
                return;
            }

            if (Protocol.ROLE_OWNER.equals(targetRole)) {
                client.sendError(
                        "Không thể thay đổi quyền của Owner."
                );
                return;
            }

            if (!requiredRole.equals(targetRole)) {
                client.sendError(
                        grant
                        ? "Người dùng này không phải thành viên thường."
                        : "Người dùng này không phải Admin."
                );
                return;
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE room_members SET role = ? "
                    + "WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY AND role = ?")) {

                ps.setString(1, newRole);
                ps.setInt(2, roomId);
                ps.setString(3, targetUser);
                ps.setString(4, requiredRole);

                if (ps.executeUpdate() != 1) {
                    client.sendError(
                            "Không thể cập nhật quyền thành viên."
                    );
                    return;
                }
            }

            String action = grant
                    ? "đã được cấp quyền Admin."
                    : "đã bị thu hồi quyền Admin.";

            client.broadcastToRoom(
                    roomId,
                    Protocol.join(
                            Protocol.SYS,
                            roomId,
                            Protocol.clean(targetUser + " " + action)
                    )
            );

            client.broadcastToRoom(
                    roomId,
                    Protocol.join(
                            Protocol.MEMBERS_CHANGED,
                            roomId
                    )
            );

            broadcastRoomsChanged();

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi cập nhật quyền: "
                    + e.getMessage()
            );

            client.sendError(
                    "Không thể cập nhật quyền thành viên."
            );
        }
    }

    // =====================================================
    // 7. KICK THANH VIEN
    // =====================================================

    public void handleKickMember(String[] parts) {
        if (!client.requireLogin()) {
            return;
        }

        if (parts == null || parts.length < 3) {
            client.sendError(
                    "Thiếu ID phòng hoặc username thành viên."
            );
            return;
        }

        int roomId;

        try {
            roomId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        if (roomId < 1) {
            client.sendError("ID phòng không hợp lệ.");
            return;
        }

        String actor = client.getCurrentUsername();
        String targetUser = parts[2].trim();

        if (targetUser.isEmpty()) {
            client.sendError("Username thành viên không hợp lệ.");
            return;
        }

        if (actor.equals(targetUser)) {
            client.sendError(
                    "Không thể tự kick chính mình. Hãy dùng Rời phòng."
            );
            return;
        }

        try (Connection conn = DatabaseManager.getConnection()) {

            String actorRole = getMemberRole(
                    conn, roomId, actor
            );

            boolean isOwner =
                    Protocol.ROLE_OWNER.equals(actorRole);

            boolean isAdmin =
                    Protocol.ROLE_ADMIN.equals(actorRole);

            if (!isOwner && !isAdmin) {
                client.sendError(
                        "Bạn không có quyền xóa thành viên."
                );
                return;
            }

            String targetRole = getMemberRole(
                    conn, roomId, targetUser
            );

            if (targetRole == null) {
                client.sendError(
                        "Người dùng không phải thành viên của phòng."
                );
                return;
            }

            if (Protocol.ROLE_OWNER.equals(targetRole)) {
                client.sendError("Không thể xóa Owner khỏi phòng.");
                return;
            }

            // Admin chi duoc kick Member.
            if (isAdmin
                    && !Protocol.ROLE_MEMBER.equals(targetRole)) {
                client.sendError(
                        "Admin chỉ được xóa thành viên thường."
                );
                return;
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM room_members "
                    + "WHERE room_id = ? AND username COLLATE BINARY = ? COLLATE BINARY")) {

                ps.setInt(1, roomId);
                ps.setString(2, targetUser);

                if (ps.executeUpdate() != 1) {
                    client.sendError(
                            "Không thể xóa thành viên khỏi phòng."
                    );
                    return;
                }
            }

            // Neu muc tieu dang online, go khoi phong va thong bao.
            ClientHandler target =
                    ChatServer.onlineClients.get(targetUser);

            if (target != null) {
                CopyOnWriteArrayList<ClientHandler> members =
                        ChatServer.roomMembers.get(roomId);

                if (members != null) {
                    members.remove(target);

                    if (members.isEmpty()) {
                        ChatServer.roomMembers.remove(
                                roomId, members
                        );
                    }
                }

                target.sendMessage(
                        Protocol.join(
                                Protocol.KICKED,
                                roomId
                        )
                );
            }

            // Xoa target khoi roomMembers truoc khi phat trang thai goi nhom.
            GroupCallService.onRoomMemberRemoved(roomId, targetUser, "Bị xóa khỏi phòng");

            client.broadcastToRoom(
                    roomId,
                    Protocol.join(
                            Protocol.SYS,
                            roomId,
                            Protocol.clean(
                                    targetUser + " đã bị xóa khỏi phòng."
                            )
                    )
            );

            client.broadcastToRoom(
                    roomId,
                    Protocol.join(
                            Protocol.MEMBERS_CHANGED,
                            roomId
                    )
            );

            broadcastRoomsChanged();

        } catch (Exception e) {
            System.err.println(
                    "[RoomService] Lỗi xóa thành viên: "
                    + e.getMessage()
            );

            client.sendError(
                    "Không thể xóa thành viên khỏi phòng."
            );
        }
    }

    // =====================================================
    // 8. HAM HO TRO: LAY VAI TRO
    // =====================================================

    private String getMemberRole(
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
                    return null;
                }

                String role = rs.getString("role");

                return role == null ? null : role.trim();
            }
        }
    }

    // =====================================================
    // 9. THONG BAO DANH SACH PHONG THAY DOI
    // =====================================================

    private void broadcastRoomsChanged() {

        String packet = Protocol.join(
                Protocol.ROOMS_CHANGED
        );

        for (ClientHandler onlineClient
                : ChatServer.onlineClients.values()) {

            if (onlineClient != null) {
                onlineClient.sendMessage(packet);
            }
        }
    }
}
