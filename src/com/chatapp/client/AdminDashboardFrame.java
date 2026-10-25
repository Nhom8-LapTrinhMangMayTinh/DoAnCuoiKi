package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.GridLayout;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Swing admin workspace. Authorization and all mutations are verified again by ChatServer. */
public final class AdminDashboardFrame extends JFrame {
    private final Consumer<String> sendCommand;
    private final String currentUsername;
    private final Runnable onClosed;

    private final JPanel metricCards = new JPanel(new GridLayout(0, 3, 10, 10));
    private final JLabel metricStatus = new JLabel("Đang chờ số liệu từ server...");
    private final JLabel[] metricValues = new JLabel[18];
    private final String[] metricNames = {
            "Người dùng online", "Kết nối TCP", "Phòng chat", "CPU hệ thống", "RAM tiến trình", "Số luồng",
            "Tổng tin nhắn", "Tin nhắn/phút", "Tệp đã gửi", "Dung lượng tệp", "Đăng nhập đúng", "Đăng nhập sai",
            "Spam bị chặn", "Lỗi server", "Dung lượng CSDL", "Số cuộc gọi", "Lưu lượng nhận", "Lưu lượng gửi"
    };
    private final TimeSeriesChart cpuChart = new TimeSeriesChart("CPU hệ thống (%)", new Color(37, 99, 235), 100);
    private final TimeSeriesChart ramChart = new TimeSeriesChart("RAM tiến trình (MiB)", new Color(124, 58, 237), 100);
    private final TimeSeriesChart msgChart = new TimeSeriesChart("Tin nhắn mỗi phút", new Color(5, 150, 105), 100);
    private final TimeSeriesChart trafficChart = new TimeSeriesChart("Lưu lượng mạng (KiB/s)", new Color(217, 119, 6), 100);

    private final DefaultTableModel usersModel = model("Tên đăng nhập", "ID", "Trạng thái", "Khóa", "Quyền", "Sai mật khẩu", "Hạn chế spam");
    private final JTable usersTable = new JTable(usersModel);
    private final DefaultTableModel roomsModel = model("ID nội bộ", "ID công khai", "Tên phòng", "Chủ phòng", "Thành viên", "Ngày tạo");
    private final JTable roomsTable = new JTable(roomsModel);
    private final DefaultTableModel membersModel = model("Tài khoản", "Vai trò", "Trực tuyến");
    private final JTable membersTable = new JTable(membersModel);
    private final DefaultTableModel logsModel = model("Thời gian", "Người thực hiện", "Sự kiện", "Loại", "Đối tượng", "Chi tiết", "Địa chỉ TCP");
    private final JTable logsTable = new JTable(logsModel);

    private final JTextField logFilterField = new JTextField(22);
    private final JLabel logPageLabel = new JLabel("Trang 1");
    private final JLabel logCountLabel = new JLabel("0 nhật ký");
    private final JLabel actionStatus = new JLabel("Sẵn sàng");
    private int logPage;
    private long logTotal;
    private int selectedRoomId = -1;
    private boolean rebuildingRooms;
    private boolean subscribed;
    private long prevSampleTime;
    private long prevInBytes;
    private long prevOutBytes;
    private double cpuPercent = -1;
    private double ramMiB;
    private double messagesPerMinute;
    private double trafficKiBPerSecond;

    public AdminDashboardFrame(Consumer<String> sendCommand, String currentUsername, Runnable onClosed) {
        super("ChatApp - Dashboard quản trị");
        this.sendCommand = sendCommand;
        this.currentUsername = currentUsername;
        this.onClosed = onClosed;
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1060, 720));
        setSize(1280, 850);
        setLocationByPlatform(true);
        buildUi();
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { subscribe(); }
            @Override public void windowClosed(WindowEvent e) {
                if (subscribed) send(Protocol.join(Protocol.ADMIN_UNSUBSCRIBE));
                subscribed = false;
                if (onClosed != null) onClosed.run();
            }
        });
    }

    public static boolean isAdminPacket(String command) {
        return Protocol.ADMIN_METRICS.equals(command)
                || Protocol.ADMIN_USERS.equals(command)
                || Protocol.ADMIN_ROOMS.equals(command)
                || Protocol.ADMIN_ROOM_MEMBERS.equals(command)
                || Protocol.ADMIN_LOGS.equals(command)
                || Protocol.ADMIN_RESULT.equals(command);
    }

    public void handleServerMessage(String[] p) {
        if (p == null || p.length == 0) return;
        switch (p[0]) {
            case Protocol.ADMIN_METRICS -> updateMetrics(p);
            case Protocol.ADMIN_USERS -> populateUsers(p.length > 1 ? decode(p[1]) : "");
            case Protocol.ADMIN_ROOMS -> populateRooms(p.length > 1 ? decode(p[1]) : "");
            case Protocol.ADMIN_ROOM_MEMBERS -> {
                int roomId = -1;
                try { if (p.length > 1) roomId = Integer.parseInt(p[1]); } catch (NumberFormatException ignored) { }
                if (roomId == selectedRoomId) populateMembers(p.length > 2 ? decode(p[2]) : "");
            }
            case Protocol.ADMIN_LOGS -> {
                try { logPage = Integer.parseInt(p[1]); } catch (Exception ignored) { }
                try { logTotal = Long.parseLong(p[2]); } catch (Exception ignored) { logTotal = 0; }
                populateLogs(p.length > 3 ? decode(p[3]) : "");
            }
            case Protocol.ADMIN_RESULT -> {
                boolean ok = p.length > 1 && "1".equals(p[1]);
                String message = p.length > 2 ? decode(p[2]) : (ok ? "Thao tác thành công." : "Thao tác thất bại.");
                actionStatus.setText(message);
                actionStatus.setForeground(ok ? new Color(21, 128, 61) : new Color(185, 28, 28));
                if (ok) {
                    requestUsers();
                    requestRooms();
                    requestLogs();
                    if (selectedRoomId > 0) requestMembers(selectedRoomId);
                }
            }
        }
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel header = new JPanel(new BorderLayout(8, 8));
        JLabel title = new JLabel("Dashboard quản trị");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel user = new JLabel("Quản trị viên: " + currentUsername);
        user.setForeground(new Color(71, 85, 105));
        header.add(title, BorderLayout.WEST);
        header.add(user, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Tổng quan", buildOverviewTab());
        tabs.addTab("Tài khoản", buildUsersTab());
        tabs.addTab("Phòng", buildRoomsTab());
        tabs.addTab("Nhật ký", buildLogsTab());
        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 4));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        footer.add(actionStatus, BorderLayout.CENTER);
        JButton refreshAll = new JButton("Làm mới tất cả");
        refreshAll.addActionListener(e -> refreshAll());
        footer.add(refreshAll, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        Font tableFont = new Font("Segoe UI", Font.PLAIN, 12);
        for (JTable table : new JTable[]{usersTable, roomsTable, membersTable, logsTable}) {
            table.setFont(tableFont);
            table.setRowHeight(25);
            table.setAutoCreateRowSorter(true);
            table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            table.getTableHeader().setReorderingAllowed(false);
        }
        logsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {145, 125, 160, 80, 100, 340, 160};
        for (int i = 0; i < widths.length; i++) logsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }

    private JPanel buildOverviewTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout(6, 6));
        JLabel note = new JLabel("Số liệu được server đẩy mỗi giây. RAM là bộ nhớ heap của tiến trình Java.");
        note.setForeground(new Color(71, 85, 105));
        top.add(note, BorderLayout.WEST);
        top.add(metricStatus, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        metricCards.setBorder(BorderFactory.createEmptyBorder(6, 2, 8, 2));
        for (int i = 0; i < metricNames.length; i++) {
            JPanel card = new JPanel(new BorderLayout(2, 6));
            card.setBackground(new Color(248, 250, 252));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(226, 232, 240)),
                    BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            JLabel name = new JLabel(metricNames[i]);
            name.setForeground(new Color(71, 85, 105));
            metricValues[i] = new JLabel("—");
            metricValues[i].setFont(metricValues[i].getFont().deriveFont(Font.BOLD, 20f));
            metricValues[i].setForeground(new Color(15, 23, 42));
            card.add(name, BorderLayout.NORTH);
            card.add(metricValues[i], BorderLayout.CENTER);
            metricCards.add(card);
        }
        JScrollPane metricScroll = new JScrollPane(metricCards);
        metricScroll.setBorder(BorderFactory.createEmptyBorder());
        metricScroll.setPreferredSize(new Dimension(700, 300));

        JPanel charts = new JPanel(new GridLayout(2, 2, 10, 10));
        charts.add(cpuChart); charts.add(ramChart); charts.add(msgChart); charts.add(trafficChart);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, metricScroll, charts);
        split.setResizeWeight(0.42);
        split.setBorder(BorderFactory.createEmptyBorder());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildUsersTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 6, 6, 6));
        panel.add(new JScrollPane(usersTable), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton refresh = new JButton("Tải danh sách");
        JButton lock = new JButton("Khóa tài khoản");
        JButton unlock = new JButton("Mở khóa");
        JButton disconnect = new JButton("Ngắt kết nối");
        JButton reset = new JButton("Đặt lại mật khẩu");
        JButton clearSpam = new JButton("Gỡ hạn chế spam");
        refresh.addActionListener(e -> requestUsers());
        lock.addActionListener(e -> actOnSelectedUser("LOCK"));
        unlock.addActionListener(e -> actOnSelectedUser("UNLOCK"));
        disconnect.addActionListener(e -> actOnSelectedUser("DISCONNECT"));
        reset.addActionListener(e -> resetSelectedUserPassword());
        clearSpam.addActionListener(e -> actOnSelectedUser("CLEAR_SPAM"));
        actions.add(refresh); actions.add(lock); actions.add(unlock); actions.add(disconnect); actions.add(reset); actions.add(clearSpam);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildRoomsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 6, 6, 6));
        JPanel roomSide = new JPanel(new BorderLayout(4, 4));
        roomSide.add(new JLabel("Danh sách phòng"), BorderLayout.NORTH);
        roomSide.add(new JScrollPane(roomsTable), BorderLayout.CENTER);
        JButton refreshRooms = new JButton("Làm mới phòng");
        refreshRooms.addActionListener(e -> requestRooms());
        roomSide.add(refreshRooms, BorderLayout.SOUTH);

        JPanel memberSide = new JPanel(new BorderLayout(4, 4));
        memberSide.add(new JLabel("Thành viên của phòng đang chọn"), BorderLayout.NORTH);
        memberSide.add(new JScrollPane(membersTable), BorderLayout.CENTER);
        JPanel memberButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton reloadMembers = new JButton("Tải thành viên");
        JButton setRole = new JButton("Đặt vai trò");
        JButton transfer = new JButton("Chuyển chủ phòng");
        JButton kick = new JButton("Xóa thành viên");
        JButton delete = new JButton("Xóa phòng");
        reloadMembers.addActionListener(e -> { if (selectedRoomId > 0) requestMembers(selectedRoomId); });
        setRole.addActionListener(e -> setSelectedMemberRole());
        transfer.addActionListener(e -> transferSelectedRoomOwner());
        kick.addActionListener(e -> kickSelectedMember());
        delete.addActionListener(e -> deleteSelectedRoom());
        memberButtons.add(reloadMembers); memberButtons.add(setRole); memberButtons.add(transfer); memberButtons.add(kick); memberButtons.add(delete);
        memberSide.add(memberButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, roomSide, memberSide);
        split.setResizeWeight(0.48);
        panel.add(split, BorderLayout.CENTER);
        roomsTable.getSelectionModel().addListSelectionListener(this::roomSelectionChanged);
        roomsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) { int id = selectedRoomId(); if (id > 0) requestMembers(id); }
            }
        });
        return panel;
    }

    private JPanel buildLogsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 6, 6, 6));
        JPanel filter = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filter.add(new JLabel("Lọc nhật ký:"));
        filter.add(logFilterField);
        JButton apply = new JButton("Lọc");
        JButton reset = new JButton("Xóa lọc");
        apply.addActionListener(e -> { logPage = 0; requestLogs(); });
        reset.addActionListener(e -> { logFilterField.setText(""); logPage = 0; requestLogs(); });
        logFilterField.addActionListener(e -> { logPage = 0; requestLogs(); });
        filter.add(apply); filter.add(reset); filter.add(logCountLabel);
        panel.add(filter, BorderLayout.NORTH);
        panel.add(new JScrollPane(logsTable), BorderLayout.CENTER);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JButton previous = new JButton("Trang trước");
        JButton next = new JButton("Trang sau");
        previous.addActionListener(e -> { if (logPage > 0) { logPage--; requestLogs(); } });
        next.addActionListener(e -> { if ((long) (logPage + 1) * 50 < logTotal) { logPage++; requestLogs(); } });
        paging.add(previous); paging.add(logPageLabel); paging.add(next);
        panel.add(paging, BorderLayout.SOUTH);
        return panel;
    }

    private void subscribe() {
        if (subscribed) return;
        subscribed = true;
        send(Protocol.join(Protocol.ADMIN_SUBSCRIBE));
        requestUsers();
        requestRooms();
        requestLogs();
    }

    private void refreshAll() {
        send(Protocol.join(Protocol.ADMIN_SUBSCRIBE));
        requestUsers(); requestRooms(); requestLogs();
        if (selectedRoomId > 0) requestMembers(selectedRoomId);
    }
    private void requestUsers() { send(Protocol.join(Protocol.ADMIN_LIST_USERS)); }
    private void requestRooms() { send(Protocol.join(Protocol.ADMIN_LIST_ROOMS)); }
    private void requestMembers(int id) { selectedRoomId = id; send(Protocol.join(Protocol.ADMIN_GET_ROOM_MEMBERS, id)); }
    private void requestLogs() {
        String filter = logFilterField.getText().trim();
        send(Protocol.join(Protocol.ADMIN_LOGS_REQUEST, logPage, encode(filter)));
    }
    private void send(String command) {
        if (sendCommand != null) sendCommand.accept(command);
    }

    private void updateMetrics(String[] p) {
        if (p.length < 21) return;
        long sampleTime = parseLong(p[1]);
        long online = parseLong(p[2]);
        long tcp = parseLong(p[3]);
        long rooms = parseLong(p[4]);
        double cpu = parseDouble(p[5], -1);
        long ramUsed = parseLong(p[6]);
        long ramMax = parseLong(p[7]);
        long threads = parseLong(p[8]);
        long messages = parseLong(p[9]);
        long perMinute = parseLong(p[10]);
        long files = parseLong(p[11]);
        long fileBytes = parseLong(p[12]);
        long loginOk = parseLong(p[13]);
        long loginFail = parseLong(p[14]);
        long spam = parseLong(p[15]);
        long errors = parseLong(p[16]);
        long dbBytes = parseLong(p[17]);
        long calls = parseLong(p[18]);
        long in = parseLong(p[19]);
        long out = parseLong(p[20]);

        double inboundRate = 0, outboundRate = 0;
        if (prevSampleTime > 0 && sampleTime > prevSampleTime) {
            double seconds = (sampleTime - prevSampleTime) / 1000.0;
            inboundRate = Math.max(0, in - prevInBytes) / seconds / 1024.0;
            outboundRate = Math.max(0, out - prevOutBytes) / seconds / 1024.0;
        }
        prevSampleTime = sampleTime; prevInBytes = in; prevOutBytes = out;
        cpuPercent = cpu < 0 ? -1 : cpu * 100.0;
        ramMiB = ramUsed / (1024.0 * 1024.0);
        messagesPerMinute = perMinute;
        trafficKiBPerSecond = inboundRate + outboundRate;

        String[] values = {
                String.valueOf(online), String.valueOf(tcp), String.valueOf(rooms),
                cpu < 0 ? "Chưa có" : String.format(Locale.ROOT, "%.1f%%", cpu * 100.0),
                formatBytes(ramUsed) + " / " + formatBytes(ramMax), String.valueOf(threads),
                formatNumber(messages), formatNumber(perMinute), formatNumber(files), formatBytes(fileBytes),
                formatNumber(loginOk), formatNumber(loginFail), formatNumber(spam), formatNumber(errors),
                formatBytes(dbBytes), formatNumber(calls), formatBytes(in) + " tổng", formatBytes(out) + " tổng"
        };
        for (int i = 0; i < metricValues.length; i++) metricValues[i].setText(values[i]);
        metricStatus.setText("Cập nhật " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(sampleTime)));
        cpuChart.addValue(cpu < 0 ? 0 : cpu * 100.0);
        ramChart.addValue(ramMiB);
        msgChart.addValue(perMinute);
        trafficChart.addValue(trafficKiBPerSecond);
    }

    private void populateUsers(String text) {
        usersModel.setRowCount(0);
        for (String line : text.split("\\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\\t", -1);
            if (f.length < 8) continue;
            usersModel.addRow(new Object[]{f[0], f[1], "1".equals(f[2]) ? "Online" : "Offline",
                    "1".equals(f[3]) ? "Có" : "Không", "1".equals(f[4]) ? "ADMIN" : "USER", f[5], f[7]});
        }
    }
    private void populateRooms(String text) {
        int keepSelectedRoom = selectedRoomId;
        rebuildingRooms = true;
        try {
            roomsModel.setRowCount(0);
            int modelRowToRestore = -1;
            for (String line : text.split("\\n")) {
                if (line.isBlank()) continue;
                String[] f = line.split("\\t", -1);
                if (f.length < 6) continue;
                if (parseLong(f[0]) == keepSelectedRoom) modelRowToRestore = roomsModel.getRowCount();
                roomsModel.addRow(new Object[]{f[0], f[1], f[2], f[3], f[4], f[5]});
            }
            selectedRoomId = -1;
            if (modelRowToRestore >= 0) {
                selectedRoomId = keepSelectedRoom;
                int viewRow = roomsTable.convertRowIndexToView(modelRowToRestore);
                if (viewRow >= 0) roomsTable.setRowSelectionInterval(viewRow, viewRow);
            } else {
                membersModel.setRowCount(0);
            }
        } finally {
            rebuildingRooms = false;
        }
        if (selectedRoomId <= 0 && keepSelectedRoom > 0) {
            boolean exists = false;
            for (int i = 0; i < roomsModel.getRowCount(); i++) {
                if (parseLong(String.valueOf(roomsModel.getValueAt(i, 0))) == keepSelectedRoom) { exists = true; break; }
            }
            if (!exists) { selectedRoomId = -1; membersModel.setRowCount(0); }
        }
    }
    private void populateMembers(String text) {
        membersModel.setRowCount(0);
        for (String line : text.split("\\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\\t", -1);
            if (f.length < 3) continue;
            membersModel.addRow(new Object[]{f[0], f[1], "1".equals(f[2]) ? "Online" : "Offline"});
        }
    }
    private void populateLogs(String text) {
        logsModel.setRowCount(0);
        for (String line : text.split("\\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\\t", -1);
            if (f.length < 7) continue;
            logsModel.addRow(new Object[]{f[0], f[1], f[2], f[3], f[4], f[5], f[6]});
        }
        logCountLabel.setText(formatNumber(logTotal) + " nhật ký");
        logPageLabel.setText("Trang " + (logPage + 1) + " / " + Math.max(1, (logTotal + 49) / 50));
    }

    private void actOnSelectedUser(String action) {
        int row = usersTable.getSelectedRow();
        if (row < 0) { info("Hãy chọn tài khoản trước."); return; }
        row = usersTable.convertRowIndexToModel(row);
        String name = String.valueOf(usersModel.getValueAt(row, 0));
        boolean admin = "ADMIN".equalsIgnoreCase(String.valueOf(usersModel.getValueAt(row, 4)));
        if ("LOCK".equals(action) && (name.equals(currentUsername) || admin)) {
            info("Không được khóa chính mình hoặc tài khoản Admin."); return;
        }
        if ("DISCONNECT".equals(action) && name.equals(currentUsername)) {
            info("Không thể tự ngắt kết nối Dashboard."); return;
        }
        String prompt = switch (action) {
            case "LOCK" -> "Khóa tài khoản " + name + " và ngắt phiên đang online?";
            case "UNLOCK" -> "Mở khóa tài khoản " + name + "?";
            case "DISCONNECT" -> "Ngắt kết nối đang hoạt động của " + name + "?";
            case "CLEAR_SPAM" -> "Gỡ hạn chế spam cho " + name + "?";
            default -> "Thực hiện thao tác với " + name + "?";
        };
        if (JOptionPane.showConfirmDialog(this, prompt, "Xác nhận", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        send(Protocol.join(Protocol.ADMIN_USER_ACTION, action, encode(name), ""));
    }

    private void resetSelectedUserPassword() {
        int row = usersTable.getSelectedRow();
        if (row < 0) { info("Hãy chọn tài khoản trước."); return; }
        row = usersTable.convertRowIndexToModel(row);
        String name = String.valueOf(usersModel.getValueAt(row, 0));
        JPasswordField password = new JPasswordField(18);
        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.add(new JLabel("Mật khẩu mới cho " + name + ":"), BorderLayout.NORTH);
        content.add(password, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, content, "Đặt lại mật khẩu", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String value = new String(password.getPassword());
        if (value.isEmpty()) { info("Mật khẩu mới không được để trống."); return; }
        send(Protocol.join(Protocol.ADMIN_USER_ACTION, "RESET_PASSWORD", encode(name), encode(value)));
        password.setText("");
    }

    private void roomSelectionChanged(ListSelectionEvent e) {
        if (rebuildingRooms || e.getValueIsAdjusting()) return;
        int id = selectedRoomId();
        if (id > 0) requestMembers(id);
        else { selectedRoomId = -1; membersModel.setRowCount(0); }
    }
    private int selectedRoomId() {
        int row = roomsTable.getSelectedRow();
        if (row < 0) return -1;
        try { return Integer.parseInt(String.valueOf(roomsModel.getValueAt(roomsTable.convertRowIndexToModel(row), 0))); }
        catch (Exception e) { return -1; }
    }
    private String selectedMember() {
        int row = membersTable.getSelectedRow();
        if (row < 0) return "";
        return String.valueOf(membersModel.getValueAt(membersTable.convertRowIndexToModel(row), 0));
    }
    private void setSelectedMemberRole() {
        if (selectedRoomId <= 0) { info("Hãy chọn phòng trước."); return; }
        String member = selectedMember();
        if (member.isEmpty()) { info("Hãy chọn thành viên trước."); return; }
        Object role = JOptionPane.showInputDialog(this, "Vai trò mới cho " + member + ":", "Đặt vai trò",
                JOptionPane.QUESTION_MESSAGE, null, new String[]{"MEMBER", "ADMIN"}, "MEMBER");
        if (role == null) return;
        send(Protocol.join(Protocol.ADMIN_ROOM_ACTION, "SET_ROLE", selectedRoomId, encode(member), encode(String.valueOf(role))));
    }
    private void transferSelectedRoomOwner() {
        if (selectedRoomId <= 0) { info("Hãy chọn phòng trước."); return; }
        String member = selectedMember();
        if (member.isEmpty()) { info("Hãy chọn thành viên làm chủ phòng mới."); return; }
        if (JOptionPane.showConfirmDialog(this, "Chuyển quyền chủ phòng cho " + member + "?", "Xác nhận chuyển chủ", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        send(Protocol.join(Protocol.ADMIN_ROOM_ACTION, "TRANSFER_OWNER", selectedRoomId, encode(member), encode("OWNER")));
    }
    private void kickSelectedMember() {
        if (selectedRoomId <= 0) { info("Hãy chọn phòng trước."); return; }
        String member = selectedMember();
        if (member.isEmpty()) { info("Hãy chọn thành viên trước."); return; }
        if (JOptionPane.showConfirmDialog(this, "Xóa " + member + " khỏi phòng?", "Xác nhận", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        send(Protocol.join(Protocol.ADMIN_ROOM_ACTION, "KICK_MEMBER", selectedRoomId, encode(member), ""));
    }
    private void deleteSelectedRoom() {
        int roomId = selectedRoomId();
        if (roomId <= 0) { info("Hãy chọn phòng cần xóa."); return; }
        int row = roomsTable.convertRowIndexToModel(roomsTable.getSelectedRow());
        String roomName = String.valueOf(roomsModel.getValueAt(row, 2));
        if (JOptionPane.showConfirmDialog(this, "Xóa phòng '" + roomName + "' cùng toàn bộ lịch sử tin nhắn nhóm?", "Xóa phòng", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        send(Protocol.join(Protocol.ADMIN_ROOM_ACTION, "DELETE_ROOM", roomId, "", ""));
        selectedRoomId = -1;
        membersModel.setRowCount(0);
    }

    private void info(String text) { JOptionPane.showMessageDialog(this, text, "Dashboard quản trị", JOptionPane.INFORMATION_MESSAGE); }
    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int column) { return false; } };
    }
    private static String encode(String value) { return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
    private static long parseLong(String value) { try { return Long.parseLong(value); } catch (Exception e) { return 0; } }
    private static double parseDouble(String value, double fallback) { try { return Double.parseDouble(value); } catch (Exception e) { return fallback; } }
    private static String formatNumber(long value) { return java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value); }
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private static final class TimeSeriesChart extends JPanel {
        private final String title;
        private final Color lineColor;
        private final int maxPoints;
        private final List<Double> values = new ArrayList<>();
        TimeSeriesChart(String title, Color color, int maxPoints) {
            this.title = title; this.lineColor = color; this.maxPoints = maxPoints;
            setPreferredSize(new Dimension(340, 210));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(226, 232, 240)));
        }
        void addValue(double value) {
            values.add(Double.isFinite(value) ? Math.max(0, value) : 0);
            while (values.size() > maxPoints) values.remove(0);
            repaint();
        }
        @Override protected void paintComponent(Graphics raw) {
            super.paintComponent(raw);
            Graphics2D g = (Graphics2D) raw.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(15, 23, 42));
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g.drawString(title, 14, 21);
            int left = 42, right = w - 14, top = 36, bottom = h - 26;
            g.setFont(getFont().deriveFont(10f));
            double max = 1;
            for (double v : values) max = Math.max(max, v);
            max = max * 1.12;
            for (int i = 0; i <= 4; i++) {
                int y = top + (bottom - top) * i / 4;
                g.setColor(new Color(226, 232, 240));
                g.drawLine(left, y, right, y);
                g.setColor(new Color(100, 116, 139));
                g.drawString(String.format(Locale.ROOT, "%.0f", max * (4 - i) / 4), 5, y + 4);
            }
            g.setColor(new Color(148, 163, 184));
            g.drawLine(left, top, left, bottom); g.drawLine(left, bottom, right, bottom);
            if (values.size() >= 2 && right > left && bottom > top) {
                g.setColor(lineColor); g.setStroke(new java.awt.BasicStroke(2f));
                int oldX = 0, oldY = 0;
                for (int i = 0; i < values.size(); i++) {
                    int x = left + (right - left) * i / Math.max(1, values.size() - 1);
                    int y = bottom - (int) ((bottom - top) * values.get(i) / max);
                    if (i > 0) g.drawLine(oldX, oldY, x, y);
                    g.fillOval(x - 2, y - 2, 4, 4);
                    oldX = x; oldY = y;
                }
            } else {
                g.setColor(new Color(100, 116, 139));
                g.drawString("Đang thu thập dữ liệu theo thời gian...", left + 14, (top + bottom) / 2);
            }
            g.dispose();
        }
    }
}
