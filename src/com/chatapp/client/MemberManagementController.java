package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public final class MemberManagementController {

    private final JFrame parent;
    private final String username;

    private final IntSupplier roomIdSupplier;
    private final BooleanSupplier privateChatSupplier;
    private final Consumer<String> sendCommand;
    private final Consumer<String> appendMessage;

    public MemberManagementController(
            JFrame parent,
            String username,
            IntSupplier roomIdSupplier,
            BooleanSupplier privateChatSupplier,
            Consumer<String> sendCommand,
            Consumer<String> appendMessage) {

        this.parent = parent;
        this.username = username;
        this.roomIdSupplier = roomIdSupplier;
        this.privateChatSupplier = privateChatSupplier;
        this.sendCommand = sendCommand;
        this.appendMessage = appendMessage;
    }

    // =====================================================
    // YÊU CẦU DANH SÁCH THÀNH VIÊN
    // =====================================================

    public void requestMembers() {
        int roomId = roomIdSupplier.getAsInt();

        if (privateChatSupplier.getAsBoolean() || roomId < 1) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Vui lòng tham gia một phòng chat nhóm trước."
            );
            return;
        }

        sendCommand.accept(
                Protocol.join(
                        Protocol.LIST_MEMBERS,
                        roomId
                )
        );
    }

    // =====================================================
    // XỬ LÝ PHẢN HỒI MEMBER_LIST
    // =====================================================

    public void processMemberList(String[] parts) {
        if (parts.length < 2 || parts[1].isEmpty()) {
            appendMessage.accept(
                    "Danh sách thành viên không hợp lệ."
            );
            return;
        }

        String[] entries = parts[1].split(";", -1);

        int roomId;

        try {
            roomId = Integer.parseInt(entries[0]);
        } catch (NumberFormatException e) {
            appendMessage.accept(
                    "ID phòng trong danh sách thành viên không hợp lệ."
            );
            return;
        }

        // Bỏ qua phản hồi của phòng không còn được chọn.
        if (privateChatSupplier.getAsBoolean()
                || roomId != roomIdSupplier.getAsInt()) {
            return;
        }

        List<MemberItem> members = new ArrayList<>();

        for (int i = 1; i < entries.length; i++) {
            if (entries[i].trim().isEmpty()) {
                continue;
            }

            String[] fields = entries[i].split(",", 2);

            if (fields.length < 2) {
                continue;
            }

            String memberUsername = fields[0].trim();
            String role = fields[1].trim();

            if (!memberUsername.isEmpty()) {
                members.add(new MemberItem(memberUsername, role));
            }
        }

        if (members.isEmpty()) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Danh sách thành viên đang trống."
            );
            return;
        }

        Object selected = JOptionPane.showInputDialog(
                parent,
                "Chọn thành viên cần quản lý:",
                "Quản lý thành viên",
                JOptionPane.PLAIN_MESSAGE,
                null,
                members.toArray(),
                members.get(0)
        );

        if (!(selected instanceof MemberItem)) {
            return;
        }

        MemberItem member = (MemberItem) selected;

        if (member.username.equals(username)) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Vui lòng chọn một thành viên khác."
            );
            return;
        }

        showMemberActions(roomId, member);
    }

    // =====================================================
    // CÁC THAO TÁC QUẢN LÝ THÀNH VIÊN
    // =====================================================

    private void showMemberActions(
            int roomId,
            MemberItem member) {

        String[] actions = {
            "Cấp quyền Admin",
            "Thu hồi quyền Admin",
            "Kick thành viên"
        };

        String action = (String) JOptionPane.showInputDialog(
                parent,
                "Thành viên: " + member.username
                + "\nVai trò hiện tại: " + member.role
                + "\nChọn thao tác:",
                "Quản lý thành viên",
                JOptionPane.PLAIN_MESSAGE,
                null,
                actions,
                actions[0]
        );

        if (action == null) {
            return;
        }

        String command;

        switch (action) {
            case "Cấp quyền Admin":
                if (equalsRole(member.role, Protocol.ROLE_ADMIN)) {
                    JOptionPane.showMessageDialog(
                            parent,
                            "Thành viên này đã là Admin."
                    );
                    return;
                }

                if (equalsRole(member.role, Protocol.ROLE_OWNER)) {
                    JOptionPane.showMessageDialog(
                            parent,
                            "Không thể thay đổi quyền của chủ phòng."
                    );
                    return;
                }

                command = Protocol.join(
                        Protocol.GRANT_ADMIN,
                        roomId,
                        member.username
                );
                break;

            case "Thu hồi quyền Admin":
                if (!equalsRole(member.role, Protocol.ROLE_ADMIN)) {
                    JOptionPane.showMessageDialog(
                            parent,
                            "Thành viên này không phải Admin."
                    );
                    return;
                }

                command = Protocol.join(
                        Protocol.REVOKE_ADMIN,
                        roomId,
                        member.username
                );
                break;

            case "Kick thành viên":
                if (equalsRole(member.role, Protocol.ROLE_OWNER)) {
                    JOptionPane.showMessageDialog(
                            parent,
                            "Không thể kick chủ phòng."
                    );
                    return;
                }

                int confirm = JOptionPane.showConfirmDialog(
                        parent,
                        "Bạn có chắc muốn kick "
                        + member.username + " khỏi phòng?",
                        "Xác nhận kick thành viên",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );

                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }

                command = Protocol.join(
                        Protocol.KICK_MEMBER,
                        roomId,
                        member.username
                );
                break;

            default:
                return;
        }

        sendCommand.accept(command);

        appendMessage.accept(
                "Đã gửi yêu cầu \"" + action
                + "\" cho " + member.username + "."
        );

        /*
         * Gửi tiếp yêu cầu tải danh sách.
         * Server đọc các lệnh theo thứ tự trên cùng kết nối TCP.
         */
        Timer refreshTimer = new Timer(300, e -> {
            if (!privateChatSupplier.getAsBoolean()
                    && roomId == roomIdSupplier.getAsInt()) {

                requestMembers();
            }
        });

        refreshTimer.setRepeats(false);
        refreshTimer.start();
    }

    private boolean equalsRole(String role, String expected) {
        return role != null
                && expected != null
                && role.equalsIgnoreCase(expected);
    }

    // =====================================================
    // DỮ LIỆU THÀNH VIÊN
    // =====================================================

    private static final class MemberItem {

        final String username;
        final String role;

        MemberItem(String username, String role) {
            this.username = username;
            this.role = role;
        }

        @Override
        public String toString() {
            return username + " (" + role + ")";
        }
    }
}