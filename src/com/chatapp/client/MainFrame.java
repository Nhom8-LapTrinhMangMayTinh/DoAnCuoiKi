package com.chatapp.client;

import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;
import com.chatapp.security.PasswordPolicy;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainFrame giao dien 3 cot:
 * - Tren: menu Tai khoan / Cuoc tro chuyen.
 * - Trai: ho so, phong chat, cac cuoc tro chuyen rieng va tim nguoi dung.
 * - Giua: noi dung chat va gui tin nhan/file.
 * - Phai: thanh vien, vai tro va cong cu quan tri theo quyen.
 */
public class MainFrame extends JFrame {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final String username;

    // Hồ sơ tài khoản và ảnh đại diện.
    private JLabel lblMyAvatar;
    private JLabel lblMyDisplayName;
    private UserProfileDialog profileDialog;
    private String profileDialogTarget = "";
    private String pendingAvatarUsername = "";
    private int expectedAvatarDownloadSize;
    private int expectedAvatarDownloadSequence;
    private java.io.ByteArrayOutputStream avatarDownloadBuffer;
    private String expectedAvatarDownloadHash = "";
    // Cache ảnh đại diện theo username; giữ byte gốc để vẽ chất lượng cao.
    private final Map<String, byte[]> userAvatarCache = new java.util.concurrent.ConcurrentHashMap<>();
    // Cache ảnh đại diện đã dựng ở độ phân giải cao cho danh sách chat riêng.
    private final Map<String, ImageIcon> userAvatarIconCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> requestedUserAvatars = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Tên hiển thị được tải từ hồ sơ; username vẫn được giữ làm khóa định danh nội bộ.
    private final Map<String, String> userDisplayNameCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> requestedUserProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Cache ảnh đại diện nhóm để hiển thị ngay trong danh sách phòng.
    // Tách trạng thái theo roomId để các luồng ảnh từ server không đè lẫn nhau.
    private final Map<Integer, byte[]> roomAvatarBytesCache = new HashMap<>();
    private final Map<String, ImageIcon> roomAvatarIconCache = new HashMap<>();
    private final Set<Integer> requestedRoomAvatarIds = new HashSet<>();
    private final Map<Integer, RoomAvatarDownload> roomAvatarDownloads = new HashMap<>();

    /**
     * Icon dùng bitmap có mật độ điểm ảnh cao nhưng giữ kích thước logic của Swing.
     * Trên màn hình HiDPI, Java2D có đủ mẫu ảnh để nội suy sắc nét; trên màn hình thường
     * ảnh được thu nhỏ bằng Bicubic.
     */
    private static final class HiDpiAvatarIcon extends ImageIcon {
        private final int logicalSize;

        HiDpiAvatarIcon(java.awt.image.BufferedImage highResolutionImage, int logicalSize) {
            super(highResolutionImage);
            this.logicalSize = logicalSize;
        }

        @Override public int getIconWidth() { return logicalSize; }
        @Override public int getIconHeight() { return logicalSize; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(getImage(), x, y, logicalSize, logicalSize, component);
            g.dispose();
        }
    }

    private static final class RoomAvatarDownload {
        final int expectedSize;
        final String expectedHash;
        final java.io.ByteArrayOutputStream buffer;
        int nextSequence;

        RoomAvatarDownload(int expectedSize, String expectedHash) {
            this.expectedSize = expectedSize;
            this.expectedHash = expectedHash == null ? "" : expectedHash;
            this.buffer = new java.io.ByteArrayOutputStream(Math.max(0, expectedSize));
            this.nextSequence = 0;
        }
    }

    // Trung tam chat.
    private ChatTranscriptPanel transcriptView;
    private MessageActionController messageActionController;
    private JTextField txtMessage;
    private JLabel lblCurrentConversation;
    private JLabel lblConversationInfo;
    private JLabel lblTypingStatus;
    private ConversationAvatar lblConversationAvatar;
    private JPanel pinnedBannerPanel;
    private JPanel groupCallBannerPanel;
    private JLabel lblGroupCallBanner;
    private JButton btnJoinGroupCallBanner;
    private JLabel lblPinnedSummary;
    private JButton btnPinnedCount;
    private boolean messagePlaceholderActive = false;
    private static final String MESSAGE_PLACEHOLDER = "Nhập tin nhắn tới cuộc trò chuyện";

    // Bộ điều phối trạng thái đọc/chưa đọc và typing đã tách riêng.
    private ConversationStateController conversationStateController;
    private JButton btnSend;
    private JButton btnAttachFile;

    // Danh sach phong.
    private JList<RoomItem> listRooms;
    private DefaultListModel<RoomItem> roomListModel;
    private JButton btnCreateRoom;
    private JButton btnJoinRoom;
    private JButton btnLeaveRoom;

    // Nguoi dung va cuoc tro chuyen rieng.
    private JTextField txtSearchUser;
    private JList<UserItem> listUsers;
    private DefaultListModel<UserItem> userListModel;
    private final Map<String, UserItem> allUsers = new LinkedHashMap<>();
    private final Set<String> recentContactNames = new LinkedHashSet<>();
    // Thứ tự hội thoại dựa trên ID tin nhắn mới nhất trong bảng messages.
    // ID messages là AUTOINCREMENT toàn cục nên ID lớn hơn tương ứng hoạt động mới hơn.
    private final Map<Integer, Long> roomLastActivityIds = new HashMap<>();
    private final Map<String, Long> privateLastActivityIds = new HashMap<>();
    // Username nhập vào nhưng chưa có trong cache; sẽ giải quyết sau khi server trả USER_LIST.
    private String pendingPrivateLookup;

    // Danh sach thanh vien va phan quyen.
    private JList<MemberItem> listMembers;
    private DefaultListModel<MemberItem> memberListModel;
    private JPanel memberActionsPanel;
    private JButton btnGrantAdmin;
    private JButton btnRevokeAdmin;
    private JButton btnKickMember;

    // Cac file hien thi trong cuoc tro chuyen hien tai.
    private final Set<Long> displayedFileIds = new HashSet<>();

    // Danh sach file trong cuoc tro chuyen hien tai (cot phai).
    private JTabbedPane rightTabs;
    private DefaultListModel<FileListItem> sharedFileListModel;
    private JList<FileListItem> listSharedFiles;
    private JLabel lblFileListStatus;
    private JButton btnMoreFiles;
    private int currentFilePage = 0;
    private boolean fileListHasMore = false;

    private FileTransferController fileTransferController;

    // Bộ điều phối xuất lịch sử được tách riêng khỏi giao diện.
    private JMenuItem exportHistoryMenuItem;
    private JMenuItem searchHistoryMenuItem;
    private JMenuItem adminDashboardMenuItem;
    private AdminDashboardFrame adminDashboardFrame;
    private VideoCallController videoCallController;
    private GroupCallController groupCallController;
    private JButton btnVideoCall;
    private HistoryExportController historyExportController;
    private MessageSearchController messageSearchController;

    private final List<DisplayItem> transcript = new ArrayList<>();
    private JButton btnLoadOlderMessages;
    private String historyKind = "";
    private String historyKey = "";
    private int historyCurrentPage = -1;
    private int historyLoadingPage = -1;
    private boolean historyHasMore = false;
    private boolean historyLoading = false;
    private final List<DisplayItem> pendingHistoryItems = new ArrayList<>();
    private final Map<Integer, String> roomRoles = new HashMap<>();
    private final Set<Integer> registeredRoomIds = new HashSet<>();

    private volatile int currentRoomId = -1;
    private volatile String currentRoomName = "";
    private volatile String currentRoomRole = "";
    private volatile String currentTarget = "";
    private volatile boolean isPrivateChat = false;
    private volatile boolean connected = true;

    private int pendingJoinRoomId = -1;
    private int pendingOpenRoomId = -1;
    private String pendingRoomSearchId = "";
    private RoomItem pendingFoundRoom;
    private boolean suppressRoomSelection = false;
    private boolean suppressUserSelection = false;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    // =========================================================
    // MODEL
    // =========================================================

    static class RoomItem {
        // id là khóa nội bộ dùng cho lệnh chat; publicId là ID 5 chữ số để hiển thị.
        final int id;
        final String publicId;
        final String name;
        final boolean locked;
        final String owner;
        final int memberCount;
        String role;

        RoomItem(int id, String name, boolean locked,
                 String owner, int memberCount, String role) {
            this(id, name, locked, owner, memberCount, role, "");
        }

        RoomItem(int id, String name, boolean locked,
                 String owner, int memberCount, String role, String publicId) {
            this.id = id;
            this.publicId = publicId == null ? "" : publicId.trim();
            this.name = name == null ? "" : name;
            this.locked = locked;
            this.owner = owner == null ? "" : owner;
            this.memberCount = memberCount;
            this.role = role == null ? "" : role.trim();
        }

        boolean joined() {
            return !role.isEmpty();
        }

        @Override
        public String toString() {
            return publicId.isEmpty() ? name : name + "  · ID " + publicId;
        }
    }

    static class UserItem {
        final String name;
        final boolean online;
        final String publicId;

        UserItem(String name, boolean online) {
            this(name, online, "");
        }

        UserItem(String name, boolean online, String publicId) {
            this.name = name == null ? "" : name;
            this.online = online;
            this.publicId = publicId == null ? "" : publicId.trim();
        }

        @Override
        public String toString() {
            return publicId.isEmpty() ? name : name + "  · ID " + publicId;
        }
    }

    private static class MemberItem {
        final String name;
        final String role;
        final String publicId;

        MemberItem(String name, String role) {
            this(name, role, "");
        }

        MemberItem(String name, String role, String publicId) {
            this.name = name == null ? "" : name;
            this.role = role == null ? "MEMBER" : role;
            this.publicId = publicId == null ? "" : publicId.trim();
        }

        @Override
        public String toString() {
            return publicId.isEmpty() ? name : name + "  · ID " + publicId;
        }
    }

    // =========================================================
    // CONSTRUCTOR
    // =========================================================

    public MainFrame(Socket socket, BufferedReader in,
                     PrintWriter out, String username) {
        super("ChatApp - " + username);
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.username = username;

        messageActionController = new MessageActionController(
                this, username, this::sendCommand);

        fileTransferController = new FileTransferController(
                this, this::sendCommand, this::appendMessage, this::onImagePreviewReady);

        conversationStateController = new ConversationStateController(
                new ConversationStateController.Host() {
                    @Override public String username() { return MainFrame.this.username; }
                    @Override public boolean hasCurrentConversation() { return MainFrame.this.hasCurrentConversation(); }
                    @Override public String currentConversationKind() { return MainFrame.this.currentConversationKind(); }
                    @Override public String currentConversationKey() { return MainFrame.this.currentConversationKey(); }
                    @Override public boolean isCurrentConversation(String kind, String key) {
                        return MainFrame.this.isCurrentConversation(kind, key);
                    }
                    @Override public boolean isConnected() { return connected; }
                    @Override public boolean isClosed() { return closed.get(); }
                    @Override public boolean sendCommand(String command) { return MainFrame.this.sendCommand(command); }
                    @Override public String draftText() { return txtMessage == null ? "" : txtMessage.getText(); }
                    @Override public List<DisplayItem> transcriptItems() { return transcript; }
                    @Override public JLabel typingStatusLabel() { return lblTypingStatus; }
                    @Override public void refreshUnreadBadges() { MainFrame.this.refreshUnreadBadges(); }
                    @Override public void renderTranscript() { MainFrame.this.renderTranscript(); }
                });

        setSize(1240, 760);
        setMinimumSize(new Dimension(1000, 600));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        historyExportController = new HistoryExportController(
                this,
                username,
                this::sendCommand,
                () -> connected && !closed.get(),
                () -> closed.get(),
                this::updateExportMenuState);
        messageSearchController = new MessageSearchController(
                this,
                username,
                this::sendCommand,
                () -> connected && !closed.get(),
                () -> closed.get());

        setJMenuBar(buildMenuBar());
        installAdminShortcut();
        initComponents();
        registerWindowEvents();
        videoCallController = new VideoCallController(this, username,
                () -> isPrivateChat ? currentTarget : "",
                this::getDisplayName,
                this::sendCommand);
        groupCallController = new GroupCallController(this, username, this::getDisplayName,
                this::sendCommand,
                () -> videoCallController != null && videoCallController.isInCall(),
                this::refreshGroupCallBanner,
                this::showGroupCallRoomNotice);
        updateVideoCallButtonState();

        Thread readerThread = new Thread(
                new IncomingReader(), "Chat-IncomingReader-" + username);
        readerThread.setDaemon(true);
        readerThread.start();

        sendCommand(Protocol.join(Protocol.LIST_ROOMS));
        sendCommand(Protocol.join(Protocol.LIST_USERS));
        sendCommand(Protocol.join(Protocol.LIST_PRIVATE_CONTACTS));
        sendCommand(Protocol.join(Protocol.GET_UNREAD_COUNTS));
        requestMyProfile();
    }

    // =========================================================
    // DOI MAT KHAU
    // =========================================================

    private void showChangePasswordDialog() {
        JPasswordField currentPasswordField = new JPasswordField(20);
        JPasswordField newPasswordField = new JPasswordField(20);
        JPasswordField confirmPasswordField = new JPasswordField(20);

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Mật khẩu hiện tại:"));
        panel.add(currentPasswordField);
        panel.add(new JLabel("Mật khẩu mới (tối thiểu 8 ký tự, có chữ hoa, chữ thường và số):"));
        panel.add(newPasswordField);
        panel.add(new JLabel("Xác nhận mật khẩu mới:"));
        panel.add(confirmPasswordField);

        int result = JOptionPane.showConfirmDialog(
                this, panel, "Đổi mật khẩu tài khoản",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        String currentPassword = new String(currentPasswordField.getPassword());
        String newPassword = new String(newPasswordField.getPassword());
        String confirmPassword = new String(confirmPasswordField.getPassword());

        if (currentPassword.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập mật khẩu hiện tại.",
                    "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (currentPassword.contains("|")) {
            JOptionPane.showMessageDialog(this, "Mật khẩu hiện tại chứa ký tự không được hỗ trợ.",
                    "Thông tin không hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String passwordError = PasswordPolicy.passwordError(newPassword, username);
        if (passwordError != null) {
            JOptionPane.showMessageDialog(this, passwordError,
                    "Mật khẩu mới chưa đạt yêu cầu", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            JOptionPane.showMessageDialog(this, "Mật khẩu mới và xác nhận không khớp.",
                    "Thông tin không hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (currentPassword.equals(newPassword)) {
            JOptionPane.showMessageDialog(this, "Mật khẩu mới phải khác mật khẩu hiện tại.",
                    "Thông tin không hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean sent = sendCommand(Protocol.join(
                Protocol.CHANGE_PASSWORD, currentPassword, newPassword, confirmPassword));
        if (sent) {
            appendMessage("Đã gửi yêu cầu đổi mật khẩu. Chờ phản hồi từ máy chủ...");
        }
    }

    // =========================================================
    // MENU BAR
    // =========================================================

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu accountMenu = new JMenu("Tài khoản");
        JMenuItem profileItem = new JMenuItem("Hồ sơ của tôi");
        JMenuItem changePasswordItem = new JMenuItem("Đổi mật khẩu...");
        JMenuItem logoutItem = new JMenuItem("Đăng xuất");

        profileItem.addActionListener(e -> openMyProfile());

        changePasswordItem.addActionListener(e -> showChangePasswordDialog());
        logoutItem.addActionListener(e -> logout());
        accountMenu.add(profileItem);
        accountMenu.add(changePasswordItem);
        accountMenu.addSeparator();
        accountMenu.add(logoutItem);

        JMenu conversationMenu = new JMenu("Cuộc trò chuyện");
        JMenuItem newPrivateItem = new JMenuItem("Chat riêng theo ID người dùng...");
        JMenuItem findRoomItem = new JMenuItem("Tìm / tham gia phòng theo ID...");
        JMenuItem refreshItem = new JMenuItem("Làm mới danh sách");
        JMenuItem createRoomItem = new JMenuItem("Tạo phòng mới...");
        exportHistoryMenuItem = new JMenuItem("Xuất lịch sử TXT/JSON...");
        searchHistoryMenuItem = new JMenuItem("Tìm tin nhắn...");

        newPrivateItem.addActionListener(e -> startPrivateChatDialog());
        findRoomItem.addActionListener(e -> findRoomByIdDialog());
        refreshItem.addActionListener(e -> refreshLists());
        createRoomItem.addActionListener(e -> createRoom());
        exportHistoryMenuItem.addActionListener(e -> exportCurrentHistory());
        searchHistoryMenuItem.addActionListener(e -> searchCurrentHistory());

        conversationMenu.add(newPrivateItem);
        conversationMenu.add(findRoomItem);
        conversationMenu.add(createRoomItem);
        conversationMenu.addSeparator();
        conversationMenu.add(searchHistoryMenuItem);
        conversationMenu.add(exportHistoryMenuItem);
        conversationMenu.add(refreshItem);

        JMenu adminMenu = new JMenu("Quản trị");
        adminDashboardMenuItem = new JMenuItem("Dashboard quản trị");
        adminDashboardMenuItem.setEnabled(false);
        adminDashboardMenuItem.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        adminDashboardMenuItem.addActionListener(e -> openAdminDashboard());
        adminMenu.add(adminDashboardMenuItem);

        bar.add(accountMenu);
        bar.add(conversationMenu);
        bar.add(adminMenu);
        return bar;
    }

    private void installAdminShortcut() {
        KeyStroke shortcut = KeyStroke.getKeyStroke(
                KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(shortcut, "openAdminDashboard");
        getRootPane().getActionMap().put("openAdminDashboard", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (adminDashboardMenuItem != null && adminDashboardMenuItem.isEnabled()) openAdminDashboard();
            }
        });
    }

    private void openAdminDashboard() {
        if (adminDashboardMenuItem == null || !adminDashboardMenuItem.isEnabled()) {
            JOptionPane.showMessageDialog(this, "Tài khoản hiện tại không có quyền quản trị.",
                    "Không đủ quyền", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (adminDashboardFrame == null || !adminDashboardFrame.isDisplayable()) {
            adminDashboardFrame = new AdminDashboardFrame(this::sendCommand, username, () -> adminDashboardFrame = null);
        }
        adminDashboardFrame.setVisible(true);
        adminDashboardFrame.toFront();
        adminDashboardFrame.requestFocus();
    }

    // =========================================================
    // KHOI TAO GIAO DIEN
    // =========================================================

    private void initComponents() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        root.add(buildLeftPanel(), BorderLayout.WEST);
        root.add(buildCenterPanel(), BorderLayout.CENTER);
        root.add(buildRightPanel(), BorderLayout.EAST);

        JLabel status = new JLabel("Đã kết nối • Tài khoản: " + username);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setForeground(new Color(80, 90, 100));
        root.add(status, BorderLayout.SOUTH);

        setContentPane(root);
        updateConnectionState();
        updateMemberActionVisibility();
    }

    private JPanel buildLeftPanel() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setPreferredSize(new Dimension(260, 0));

        // Ho so tai khoan.
        JPanel profilePanel = new JPanel(new BorderLayout(10, 0));
        profilePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Hồ sơ"),
                BorderFactory.createEmptyBorder(5, 6, 6, 6)));

        lblMyAvatar = new JLabel(
                username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase(),
                SwingConstants.CENTER);
        lblMyAvatar.setPreferredSize(new Dimension(52, 52));
        lblMyAvatar.setMinimumSize(new Dimension(52, 52));
        lblMyAvatar.setHorizontalAlignment(SwingConstants.CENTER);
        lblMyAvatar.setOpaque(false);
        lblMyAvatar.setBackground(new Color(37, 99, 235));
        lblMyAvatar.setForeground(Color.WHITE);
        lblMyAvatar.setFont(new Font("Segoe UI", Font.BOLD, 18));

        JPanel profileText = new JPanel(new GridLayout(2, 1));
        lblMyDisplayName = new JLabel(username);
        lblMyDisplayName.setFont(lblMyDisplayName.getFont().deriveFont(Font.BOLD, 14f));
        profileText.add(lblMyDisplayName);
        profileText.add(new JLabel("Đang sử dụng ChatApp"));

        profilePanel.add(lblMyAvatar, BorderLayout.WEST);
        profilePanel.add(profileText, BorderLayout.CENTER);
        profilePanel.setToolTipText("Nhấp đúp để xem hoặc chỉnh sửa hồ sơ của bạn");
        installProfileDoubleClick(profilePanel);

        // Danh sách phòng chat được tạo và quản lý giao diện bởi RoomPanel.
        RoomPanel roomPanel = new RoomPanel(new RoomPanel.Host() {
            @Override public int getUnreadCount(String kind, String key) {
                return MainFrame.this.getUnreadCount(kind, key);
            }
            @Override public String escapeHtml(String value) {
                return MainFrame.this.esc(value);
            }
            @Override public ImageIcon getRoomAvatarIcon(int roomId, String roomName, int size) {
                return MainFrame.this.getRoomAvatarIcon(roomId, roomName, size);
            }
            @Override public void onOpenRoom(RoomItem room) {
                MainFrame.this.openRoomFromList(room);
            }
            @Override public void onCreateRoom() {
                MainFrame.this.createRoom();
            }
            @Override public void onJoinRoom() {
                // Nút "Vào phòng" mở luồng nhập ID phòng rồi nhập mật khẩu nếu cần.
                MainFrame.this.findRoomByIdDialog();
            }
            @Override public void onLeaveRoom() {
                MainFrame.this.leaveSelectedRoom();
            }
        });
        listRooms = roomPanel.getListRooms();
        roomListModel = roomPanel.getRoomListModel();
        btnCreateRoom = roomPanel.getCreateButton();
        btnJoinRoom = roomPanel.getJoinButton();
        btnLeaveRoom = roomPanel.getLeaveButton();

        // Danh sach nguoi dung/chat rieng duoc tach sang UserPanel.
        UserPanel userPanel = new UserPanel(new UserPanel.Host() {
            @Override public int getUnreadCount(String kind, String key) {
                return MainFrame.this.getUnreadCount(kind, key);
            }
            @Override public String escapeHtml(String value) {
                return MainFrame.this.esc(value);
            }
            @Override public void onSearchTextChanged() {
                MainFrame.this.refreshUserList();
            }
            @Override public void onOpenUser(UserItem user) {
                if (user != null) MainFrame.this.openPrivateConversation(user.name);
            }
            @Override public void onFindOrOpenFromSearchField() {
                MainFrame.this.findOrOpenPrivateChatFromSearchField();
            }
            @Override public void onViewProfile(UserItem user) {
                if (user != null) MainFrame.this.openUserProfile(user.name, false);
            }
            @Override public String getDisplayName(String targetUsername) {
                return MainFrame.this.getDisplayName(targetUsername);
            }
            @Override public ImageIcon getAvatarIcon(String targetUsername, int size) {
                return MainFrame.this.getAvatarIcon(targetUsername, size);
            }
        });
        txtSearchUser = userPanel.getSearchField();
        listUsers = userPanel.getListUsers();
        userListModel = userPanel.getUserListModel();
        JPanel usersPanel = userPanel;

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, roomPanel, usersPanel);
        split.setResizeWeight(0.55);
        split.setBorder(null);

        left.add(profilePanel, BorderLayout.NORTH);
        left.add(split, BorderLayout.CENTER);
        return left;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(0, 0));
        Color chatBackground = new Color(234, 236, 240);
        Color accent = new Color(0, 110, 255);
        center.setBackground(chatBackground);

        // Thanh tiêu đề giống ứng dụng nhắn tin hiện đại.
        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.setBackground(Color.WHITE);
        heading.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(218, 222, 228)),
                BorderFactory.createEmptyBorder(10, 16, 10, 12)));

        lblConversationAvatar = new ConversationAvatar();
        lblConversationAvatar.setPreferredSize(new Dimension(64, 64));
        lblConversationAvatar.setMinimumSize(new Dimension(64, 64));
        lblConversationAvatar.setIdentity("", false);
        lblConversationAvatar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblConversationAvatar.setToolTipText("Nhấn để đổi ảnh đại diện phòng (chủ phòng)");
        lblConversationAvatar.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (isPrivateChat && currentTarget != null && !currentTarget.isBlank()) {
                    openUserProfile(currentTarget, false);
                } else {
                    chooseRoomAvatar();
                }
            }
        });
        lblConversationAvatar.setToolTipText("Nhấn để xem hồ sơ người dùng hoặc đổi ảnh phòng");

        JPanel titleStack = new JPanel(new GridLayout(3, 1, 0, 2));
        titleStack.setOpaque(false);
        lblCurrentConversation = new JLabel("Chọn phòng hoặc người dùng để bắt đầu");
        lblCurrentConversation.setFont(new Font("Segoe UI", Font.BOLD, 19));
        lblCurrentConversation.setForeground(new Color(17, 24, 39));
        lblCurrentConversation.setToolTipText("Nhấp đúp để xem hồ sơ khi chat riêng");
        lblCurrentConversation.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblCurrentConversation.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && isPrivateChat
                        && currentTarget != null && !currentTarget.isBlank()) {
                    openUserProfile(currentTarget, false);
                }
            }
        });
        lblConversationInfo = new JLabel(" ");
        lblConversationInfo.setForeground(new Color(75, 85, 99));
        lblConversationInfo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        lblTypingStatus = new JLabel(" ");
        lblTypingStatus.setForeground(new Color(0, 110, 255));
        lblTypingStatus.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        titleStack.add(lblCurrentConversation);
        titleStack.add(lblConversationInfo);
        titleStack.add(lblTypingStatus);

        JPanel titleBlock = new JPanel(new BorderLayout(10, 0));
        titleBlock.setOpaque(false);
        titleBlock.add(lblConversationAvatar, BorderLayout.WEST);
        titleBlock.add(titleStack, BorderLayout.CENTER);
        heading.add(titleBlock, BorderLayout.CENTER);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerActions.setOpaque(false);
        JButton btnHeaderMembers = createChatIconButton("♙+", "Xem thành viên");
        btnHeaderMembers.addActionListener(e -> {
            if (rightTabs != null) rightTabs.setSelectedIndex(0);
        });
        JButton btnHeaderFiles = createChatIconButton("▣", "Tệp đã gửi trong cuộc trò chuyện");
        btnHeaderFiles.addActionListener(e -> {
            if (rightTabs != null) rightTabs.setSelectedIndex(1);
        });
        JButton btnHeaderSearch = createChatIconButton("⌕", "Tìm tin nhắn trong cuộc trò chuyện");
        btnHeaderSearch.addActionListener(e -> searchCurrentHistory());
        JButton btnHeaderMore = createChatIconButton("⋯", "Tùy chọn cuộc trò chuyện");
        btnHeaderMore.addActionListener(e -> showConversationActionsPopup(btnHeaderMore));
        headerActions.add(btnHeaderMembers);
        headerActions.add(btnHeaderFiles);
        headerActions.add(btnHeaderSearch);
        headerActions.add(btnHeaderMore);
        heading.add(headerActions, BorderLayout.EAST);

        // Dải tin nhắn ghim: tự ẩn khi cuộc trò chuyện hiện tại không có tin ghim.
        pinnedBannerPanel = new JPanel(new BorderLayout(10, 0));
        pinnedBannerPanel.setBackground(Color.WHITE);
        pinnedBannerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(226, 230, 236)),
                BorderFactory.createEmptyBorder(8, 18, 8, 12)));
        JLabel pinnedIcon = new JLabel("▣");
        pinnedIcon.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 21));
        pinnedIcon.setForeground(accent);
        JPanel pinnedTextStack = new JPanel(new GridLayout(2, 1, 0, 3));
        pinnedTextStack.setOpaque(false);
        JLabel pinnedTitle = new JLabel("Tin nhắn ghim");
        pinnedTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        pinnedTitle.setForeground(new Color(31, 41, 55));
        lblPinnedSummary = new JLabel("Tin nhắn được ghim sẽ hiển thị tại đây");
        lblPinnedSummary.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        lblPinnedSummary.setForeground(new Color(75, 85, 99));
        lblPinnedSummary.setToolTipText("Bấm nút bên phải để xem các tin nhắn ghim");
        pinnedTextStack.add(pinnedTitle);
        pinnedTextStack.add(lblPinnedSummary);
        btnPinnedCount = createChatIconButton("Xem ghim", "Xem và đi tới tin nhắn ghim");
        btnPinnedCount.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        btnPinnedCount.addActionListener(e -> showPinnedMessages());
        pinnedBannerPanel.add(pinnedIcon, BorderLayout.WEST);
        pinnedBannerPanel.add(pinnedTextStack, BorderLayout.CENTER);
        pinnedBannerPanel.add(btnPinnedCount, BorderLayout.EAST);
        pinnedBannerPanel.setVisible(false);

        groupCallBannerPanel = new JPanel(new BorderLayout(10, 0));
        groupCallBannerPanel.setBackground(new Color(220, 252, 231));
        groupCallBannerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(134, 239, 172)),
                BorderFactory.createEmptyBorder(8, 14, 8, 12)));
        lblGroupCallBanner = new JLabel("📞 Cuộc gọi nhóm đang diễn ra — nhấn để tham gia");
        lblGroupCallBanner.setForeground(new Color(22, 101, 52));
        lblGroupCallBanner.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnJoinGroupCallBanner = new JButton("Tham gia gọi");
        stylePrimaryButton(btnJoinGroupCallBanner);
        btnJoinGroupCallBanner.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        btnJoinGroupCallBanner.addActionListener(e -> {
            if (groupCallController != null && currentRoomId > 0 && !isPrivateChat)
                groupCallController.startOrJoinCurrentRoom(currentRoomId, currentRoomName);
        });
        lblGroupCallBanner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblGroupCallBanner.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (groupCallController != null && currentRoomId > 0 && !isPrivateChat)
                    groupCallController.startOrJoinCurrentRoom(currentRoomId, currentRoomName);
            }
        });
        groupCallBannerPanel.add(lblGroupCallBanner, BorderLayout.CENTER);
        groupCallBannerPanel.add(btnJoinGroupCallBanner, BorderLayout.EAST);
        groupCallBannerPanel.setVisible(false);

        JPanel topArea = new JPanel();
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));
        topArea.setOpaque(false);
        topArea.add(heading);
        topArea.add(groupCallBannerPanel);
        topArea.add(pinnedBannerPanel);

        transcriptView = new ChatTranscriptPanel(
                username,
                this::readReceiptText,
                messageActionController::install,
                (fileId, fileName) -> fileTransferController.requestDownload(fileId, fileName),
                (fileId, fileName) -> {
                    if (VoiceMessageBubble.isVoiceMessageName(fileName)) {
                        fileTransferController.requestInlineAudio(fileId, fileName);
                    } else if (FileTransferController.isPreviewableVideo(fileName)) {
                        fileTransferController.requestInlineVideo(fileId, fileName);
                    } else {
                        fileTransferController.requestPreview(fileId, fileName);
                    }
                },
                fileTransferController::getPreviewPath,
                this::styleSecondaryButton,
                this::getCachedUserAvatar,
                this::getDisplayName);

        btnLoadOlderMessages = new JButton("↑ Tải tin nhắn cũ hơn");
        styleSecondaryButton(btnLoadOlderMessages);
        btnLoadOlderMessages.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        btnLoadOlderMessages.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        btnLoadOlderMessages.setContentAreaFilled(false);
        btnLoadOlderMessages.setEnabled(false);
        btnLoadOlderMessages.setToolTipText("Tải thêm các tin nhắn cũ từ máy chủ");
        btnLoadOlderMessages.addActionListener(e -> loadOlderHistory());
        JPanel olderHistoryRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
        olderHistoryRow.setOpaque(false);
        olderHistoryRow.add(btnLoadOlderMessages);
        JPanel transcriptContainer = new JPanel(new BorderLayout(0, 2));
        transcriptContainer.setBackground(chatBackground);
        transcriptContainer.add(olderHistoryRow, BorderLayout.NORTH);
        transcriptContainer.add(transcriptView, BorderLayout.CENTER);

        // Thanh công cụ ngay phía trên ô nhập, tương tự giao diện tham chiếu.
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 4));
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createEmptyBorder(3, 10, 1, 10));

        JButton btnEmoji = createChatToolbarButton("☺", "Chèn biểu tượng cảm xúc");
        btnEmoji.addActionListener(e -> showEmojiPicker(btnEmoji));
        JButton btnImage = createChatToolbarButton("▧", "Gửi hình ảnh trong cuộc trò chuyện");
        btnImage.addActionListener(e -> chooseFileToSend());
        btnAttachFile = createChatToolbarButton("📎", "Gửi tệp đính kèm");
        btnAttachFile.addActionListener(e -> chooseFileToSend());
        JButton btnVoiceRecord = createChatToolbarButton("Ghi âm", "Ghi âm tin nhắn thoại WAV, tối đa 120 giây");
        btnVoiceRecord.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnVoiceRecord.addActionListener(e -> startVoiceRecording());
        btnVideoCall = createChatToolbarButton("📹 Gọi video", "Gọi video 1-1 hoặc gọi nhóm trong phòng chat");
        btnVideoCall.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnVideoCall.addActionListener(e -> {
            if (isPrivateChat) {
                if (groupCallController != null && groupCallController.isInCall()) {
                    JOptionPane.showMessageDialog(this, "Bạn đang tham gia cuộc gọi nhóm. Hãy rời cuộc gọi đó trước.", "Gọi video", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (videoCallController != null) videoCallController.startOutgoingCall();
            } else if (groupCallController != null) {
                groupCallController.startOrJoinCurrentRoom(currentRoomId, currentRoomName);
            }
        });
        JButton btnMembersTool = createChatToolbarButton("▤", "Xem thành viên hoặc tệp đã gửi");
        btnMembersTool.addActionListener(e -> {
            if (rightTabs != null) rightTabs.setSelectedIndex(isPrivateChat ? 1 : 0);
        });
        JButton btnSearchTool = createChatToolbarButton("⌕", "Tìm tin nhắn");
        btnSearchTool.addActionListener(e -> searchCurrentHistory());
        JButton btnMoreTool = createChatToolbarButton("⋯", "Thao tác khác");
        btnMoreTool.addActionListener(e -> showConversationActionsPopup(btnMoreTool));
        toolbar.add(btnEmoji);
        toolbar.add(btnImage);
        toolbar.add(btnAttachFile);
        toolbar.add(btnVoiceRecord);
        toolbar.add(btnVideoCall);
        toolbar.add(btnMembersTool);
        toolbar.add(btnSearchTool);
        toolbar.add(btnMoreTool);

        txtMessage = new EmojiSupport.EmojiTextField();
        txtMessage.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        txtMessage.setForeground(new Color(31, 41, 55));
        txtMessage.setOpaque(false);
        txtMessage.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 8));
        txtMessage.setToolTipText("Nhập tin nhắn rồi nhấn Enter để gửi");
        installMessagePlaceholder();

        btnSend = createChatIconButton("👍", "Gửi nhanh biểu tượng thích");
        btnSend.setFont(new Font("Segoe UI Emoji", Font.BOLD, 20));
        btnSend.setPreferredSize(new Dimension(48, 42));
        btnSend.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        btnSend.addActionListener(e -> {
            if (!messagePlaceholderActive && !txtMessage.getText().trim().isEmpty()) {
                sendMessage();
            } else {
                sendQuickEmoji("👍");
            }
        });
        styleSendButton();

        JPanel inputFieldBorder = new JPanel(new BorderLayout(0, 0));
        inputFieldBorder.setBackground(Color.WHITE);
        inputFieldBorder.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 222, 228)),
                BorderFactory.createEmptyBorder(0, 2, 0, 2)));
        inputFieldBorder.add(txtMessage, BorderLayout.CENTER);
        inputFieldBorder.add(btnSend, BorderLayout.EAST);

        JPanel inputRow = new JPanel(new BorderLayout());
        inputRow.setBackground(Color.WHITE);
        inputRow.setBorder(BorderFactory.createEmptyBorder(4, 10, 9, 10));
        inputRow.add(inputFieldBorder, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 0));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(218, 222, 228)));
        bottom.add(toolbar, BorderLayout.NORTH);
        bottom.add(inputRow, BorderLayout.CENTER);

        txtMessage.addActionListener(e -> sendMessage());
        txtMessage.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateSendButtonState(); if (!messagePlaceholderActive) onMessageDraftChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { updateSendButtonState(); if (!messagePlaceholderActive) onMessageDraftChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { updateSendButtonState(); if (!messagePlaceholderActive) onMessageDraftChanged(); }
        });

        center.add(topArea, BorderLayout.NORTH);
        center.add(transcriptContainer, BorderLayout.CENTER);
        center.add(bottom, BorderLayout.SOUTH);
        refreshPinnedBanner();
        return center;
    }

    private JButton createChatIconButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(new Color(39, 55, 78));
        button.setBackground(new Color(244, 247, 251));
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(227, 232, 239)),
                BorderFactory.createEmptyBorder(7, 9, 7, 9)));
        button.setOpaque(true);
        return button;
    }

    private void styleSendButton() {
        if (btnSend == null) return;
        btnSend.setFocusPainted(false);
        btnSend.setOpaque(true);
        btnSend.setContentAreaFilled(true);
        btnSend.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnSend.setBackground(new Color(24, 119, 242));
        btnSend.setForeground(Color.WHITE);
        btnSend.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(24, 119, 242)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
    }

    /** Ô nhập rỗng: nút Like xanh; có nội dung: chuyển thành nút gửi màu xanh. */
    private void updateSendButtonState() {
        if (btnSend == null || txtMessage == null) return;
        boolean hasText = !messagePlaceholderActive && !txtMessage.getText().trim().isEmpty();
        btnSend.setText(hasText ? "➤" : "👍");
        btnSend.setToolTipText(hasText ? "Gửi tin nhắn" : "Gửi nhanh biểu tượng thích");
        btnSend.setFont(new Font(hasText ? "Segoe UI Symbol" : "Segoe UI Emoji",
                Font.BOLD, hasText ? 22 : 20));
        styleSendButton();
    }

    private JButton createChatToolbarButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(new Color(24, 91, 175));
        button.setBackground(Color.WHITE);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 21));
        button.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        return button;
    }

    private void installMessagePlaceholder() {
        if (txtMessage == null) return;
        txtMessage.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                clearMessagePlaceholder();
            }
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (txtMessage.getText().trim().isEmpty()) setMessagePlaceholder();
            }
        });
        setMessagePlaceholder();
    }

    private void clearMessagePlaceholder() {
        if (!messagePlaceholderActive || txtMessage == null) return;
        // Xóa placeholder khi cờ còn bật để DocumentListener không phát TYPING giả.
        txtMessage.setText("");
        messagePlaceholderActive = false;
        txtMessage.setForeground(new Color(31, 41, 55));
        updateSendButtonState();
    }

    private void setMessagePlaceholder() {
        if (txtMessage == null) return;
        messagePlaceholderActive = true;
        txtMessage.setForeground(new Color(107, 114, 128));
        String target = isPrivateChat && currentTarget != null && !currentTarget.isBlank()
                ? currentTarget
                : (!currentRoomName.isBlank() ? currentRoomName : "cuộc trò chuyện");
        txtMessage.setText("Nhập @, tin nhắn tới " + target);
        updateSendButtonState();
    }

    private void updateMessagePlaceholder() {
        if (txtMessage == null) return;
        if (messagePlaceholderActive || txtMessage.getText().trim().isEmpty()) {
            setMessagePlaceholder();
        }
    }

    /**
     * Bộ chọn emoji tích hợp, không cần tải ảnh icon bên ngoài.
     * Dùng font Segoe UI Emoji của Windows để hiển thị emoji nhiều màu.
     */
    private void showEmojiPicker(Component invoker) {
        if (invoker == null) return;
        if (conversationStateController != null) conversationStateController.stopTypingNow();
        EmojiPicker.show(this, invoker, this::insertEmoji);
    }

    private void insertEmoji(String emoji) {
        clearMessagePlaceholder();
        txtMessage.replaceSelection(emoji);
        txtMessage.requestFocusInWindow();
    }

    private void sendQuickEmoji(String emoji) {
        String existingDraft = messagePlaceholderActive ? "" : txtMessage.getText();
        boolean hadDraft = !existingDraft.trim().isEmpty();
        clearMessagePlaceholder();
        txtMessage.setText(emoji);
        sendMessage();

        // Không làm mất nội dung người dùng đang soạn khi nhấn gửi nhanh 👍.
        if (hadDraft) {
            txtMessage.setText(existingDraft);
            txtMessage.setForeground(new Color(31, 41, 55));
            messagePlaceholderActive = false;
        } else if (!txtMessage.hasFocus() && txtMessage.getText().trim().isEmpty()) {
            setMessagePlaceholder();
        }
    }

    private void showConversationActionsPopup(Component invoker) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem search = new JMenuItem("Tìm tin nhắn...");
        search.addActionListener(e -> searchCurrentHistory());
        JMenuItem export = new JMenuItem("Xuất lịch sử TXT/JSON...");
        export.addActionListener(e -> exportCurrentHistory());
        JMenuItem members = new JMenuItem("Xem thành viên");
        members.addActionListener(e -> { if (rightTabs != null) rightTabs.setSelectedIndex(0); });
        JMenuItem files = new JMenuItem("Xem tệp đã gửi");
        files.addActionListener(e -> { if (rightTabs != null) rightTabs.setSelectedIndex(1); });
        JMenuItem refresh = new JMenuItem("Làm mới danh sách");
        refresh.addActionListener(e -> refreshLists());
        popup.add(search);
        popup.add(export);
        if (isPrivateChat && currentTarget != null && !currentTarget.isBlank()) {
            JMenuItem viewProfile = new JMenuItem("Xem hồ sơ người dùng...");
            viewProfile.addActionListener(e -> openUserProfile(currentTarget, false));
            popup.add(viewProfile);
        }
        popup.addSeparator();
        popup.add(members);
        popup.add(files);
        popup.addSeparator();
        popup.add(refresh);
        if (!isPrivateChat && currentRoomId > 0
                && Protocol.ROLE_OWNER.equalsIgnoreCase(currentRoomRole)) {
            popup.addSeparator();
            JMenuItem changeAvatar = new JMenuItem("Đổi ảnh đại diện phòng...");
            changeAvatar.addActionListener(e -> chooseRoomAvatar());
            JMenuItem dissolve = new JMenuItem("Giải tán phòng...");
            dissolve.addActionListener(e -> dissolveCurrentRoom());
            popup.add(changeAvatar);
            popup.add(dissolve);
        }
        popup.show(invoker, 0, invoker.getHeight());
    }

    private List<DisplayItem> currentPinnedMessages() {
        List<DisplayItem> result = new ArrayList<>();
        String kind = isPrivateChat ? "P" : "G";
        String key = isPrivateChat ? currentTarget : String.valueOf(currentRoomId);
        if (key == null || key.isBlank() || (!isPrivateChat && currentRoomId <= 0)) return result;
        for (DisplayItem item : transcript) {
            if (item == null || item.id <= 0 || !item.pinned || item.deleted) continue;
            if (kind.equals(item.kind) && key.equals(item.key)) result.add(item);
        }
        // Ưu tiên 3 tin ghim mới nhất theo ID tin nhắn.
        result.sort(Comparator.comparingLong(item -> item.id));
        if (result.size() > 3) {
            result = new ArrayList<>(result.subList(result.size() - 3, result.size()));
        }
        return result;
    }

    private void refreshPinnedBanner() {
        if (pinnedBannerPanel == null || lblPinnedSummary == null || btnPinnedCount == null) return;
        List<DisplayItem> pins = currentPinnedMessages();
        boolean visible = !pins.isEmpty();
        pinnedBannerPanel.setVisible(visible);
        if (visible) {
            StringBuilder html = new StringBuilder("<html>");
            for (int i = pins.size() - 1; i >= 0; i--) {
                DisplayItem item = pins.get(i);
                String sender = item.sender == null || item.sender.isBlank() ? "Tin nhắn" : item.sender;
                String content = item.deleted ? "Tin nhắn đã bị thu hồi"
                        : (Protocol.TYPE_FILE.equals(item.type) ? safeText(item.fileName) : safeText(item.text));
                content = content.replace('\n', ' ').trim();
                if (content.length() > 75) content = content.substring(0, 72) + "...";
                if (html.length() > 6) html.append("<br>");
                html.append("<b>").append(i + 1).append(". ").append(esc(sender))
                    .append(":</b> ").append(esc(content));
            }
            html.append("</html>");
            lblPinnedSummary.setText(html.toString());
            lblPinnedSummary.setToolTipText("Đang hiển thị tối đa 3 tin nhắn ghim mới nhất. Bấm Xem ghim để mở tin.");
            btnPinnedCount.setText("Xem ghim (" + pins.size() + ")");
        } else {
            lblPinnedSummary.setText("Tin nhắn được ghim sẽ hiển thị tại đây");
        }
        pinnedBannerPanel.revalidate();
        pinnedBannerPanel.repaint();
    }

    private void showPinnedMessages() {
        List<DisplayItem> pins = currentPinnedMessages();
        if (pins.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Cuộc trò chuyện này chưa có tin nhắn ghim.",
                    "Tin nhắn ghim", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DisplayItem chosen;
        if (pins.size() == 1) {
            chosen = pins.get(0);
        } else {
            String[] labels = new String[pins.size()];
            for (int i = 0; i < pins.size(); i++) {
                DisplayItem item = pins.get(i);
                String content = item.deleted ? "Tin nhắn đã bị thu hồi"
                        : (Protocol.TYPE_FILE.equals(item.type) ? safeText(item.fileName) : safeText(item.text));
                content = content.replace('\n', ' ').trim();
                if (content.length() > 55) content = content.substring(0, 52) + "...";
                labels[i] = safeText(item.sender) + " · " + safeText(item.timestamp) + " · " + content;
            }
            Object selected = JOptionPane.showInputDialog(this, "Chọn tin nhắn ghim để đi tới:",
                    "Tin nhắn ghim", JOptionPane.PLAIN_MESSAGE, null, labels, labels[labels.length - 1]);
            if (selected == null) return;
            int index = java.util.Arrays.asList(labels).indexOf(selected.toString());
            if (index < 0) return;
            chosen = pins.get(index);
        }
        if (transcriptView != null) transcriptView.scrollToMessage(chosen.id);
    }

    private static final class ConversationAvatar extends JComponent {
        private String label = "#";
        private boolean group;
        private java.awt.image.BufferedImage avatarImage;
        ConversationAvatar() { setPreferredSize(new Dimension(48, 48)); setMinimumSize(new Dimension(48, 48)); }
        void setIdentity(String value, boolean isGroup) {
            group = isGroup;
            avatarImage = null;
            String clean = value == null ? "" : value.trim();
            if (isGroup) label = "#";
            else if (clean.isEmpty()) label = "?";
            else label = clean.substring(0, Math.min(2, clean.length())).toUpperCase(Locale.ROOT);
            repaint();
        }
        void setImage(byte[] bytes) {
            try { avatarImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)); }
            catch (Exception ex) { avatarImage = null; }
            repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Color fill = group ? new Color(220, 232, 250) : new Color(206, 229, 221);
            Color text = group ? new Color(39, 92, 165) : new Color(34, 110, 83);
            int d = Math.min(getWidth(), getHeight()) - 4;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g.setColor(new Color(226, 232, 240));
            g.fillOval(x - 1, y - 1, d + 2, d + 2);
            g.setColor(fill);
            g.fillOval(x, y, d, d);
            if (avatarImage != null) {
                java.awt.Shape oldClip = g.getClip();
                g.setClip(new java.awt.geom.Ellipse2D.Double(x, y, d, d));
                int side = Math.min(avatarImage.getWidth(), avatarImage.getHeight());
                int sx = (avatarImage.getWidth() - side) / 2;
                int sy = (avatarImage.getHeight() - side) / 2;
                g.drawImage(avatarImage, x, y, x + d, y + d,
                        sx, sy, sx + side, sy + side, null);
                g.setClip(oldClip);
                g.setColor(new Color(255, 255, 255, 220));
                g.setStroke(new BasicStroke(1.2f));
                g.drawOval(x, y, d - 1, d - 1);
                g.dispose();
                return;
            }
            g.setColor(text);
            g.setFont(new Font("Segoe UI", Font.BOLD, 19));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, (getWidth() - fm.stringWidth(label)) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    private void stylePrimaryButton(JButton button) {
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(new Color(37, 99, 235));
        button.setForeground(Color.WHITE);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        button.setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 16));
        button.setOpaque(true);
        button.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(29, 78, 216));
            }
            @Override public void mouseExited(MouseEvent e) {
                button.setBackground(new Color(37, 99, 235));
            }
        });
    }

    private void styleSecondaryButton(JButton button) {
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(new Color(239, 246, 255));
        button.setForeground(new Color(29, 78, 216));
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(191, 219, 254)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        button.setOpaque(true);
    }

    private JPanel buildRightPanel() {
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setPreferredSize(new Dimension(255, 0));
        right.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 1));
        right.setBackground(new Color(248, 250, 252));

        rightTabs = new JTabbedPane();
        rightTabs.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

        // Tab thanh vien
        JPanel membersTab = new JPanel(new BorderLayout(0, 6));
        membersTab.setBackground(Color.WHITE);
        JLabel membersTitle = new JLabel("  THÀNH VIÊN NHÓM");
        membersTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        membersTitle.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 2));

        memberListModel = new DefaultListModel<>();
        listMembers = new JList<>(memberListModel);
        listMembers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listMembers.setFixedCellHeight(54);
        listMembers.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                MemberItem member = (MemberItem) value;
                label.setText("<html><b>" + esc(member.name)
                        + "</b><br><font color='gray' size='2'>"
                        + esc(roleVietnamese(member.role)) + "</font></html>");
                label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 6));
                return label;
            }
        });
        listMembers.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateMemberActionButtons();
        });

        btnGrantAdmin = new JButton("Cấp Admin");
        btnRevokeAdmin = new JButton("Thu hồi Admin");
        btnKickMember = new JButton("Xóa thành viên");
        styleSecondaryButton(btnGrantAdmin);
        styleSecondaryButton(btnRevokeAdmin);
        styleSecondaryButton(btnKickMember);

        btnGrantAdmin.addActionListener(e -> changeSelectedMemberRole(true));
        btnRevokeAdmin.addActionListener(e -> changeSelectedMemberRole(false));
        btnKickMember.addActionListener(e -> kickSelectedMember());

        memberActionsPanel = new JPanel(new GridLayout(0, 1, 5, 5));
        memberActionsPanel.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
        memberActionsPanel.setBackground(Color.WHITE);
        memberActionsPanel.add(btnGrantAdmin);
        memberActionsPanel.add(btnRevokeAdmin);
        memberActionsPanel.add(btnKickMember);

        membersTab.add(membersTitle, BorderLayout.NORTH);
        membersTab.add(new JScrollPane(listMembers), BorderLayout.CENTER);
        membersTab.add(memberActionsPanel, BorderLayout.SOUTH);

        // Tab file da gui / nhan
        JPanel filesTab = new JPanel(new BorderLayout(0, 6));
        filesTab.setBackground(Color.WHITE);
        filesTab.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));

        JPanel filesHeader = new JPanel(new BorderLayout(0, 4));
        filesHeader.setOpaque(false);
        JLabel filesTitle = new JLabel("TỆP TRONG CUỘC TRÒ CHUYỆN");
        filesTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        lblFileListStatus = new JLabel("Chọn một cuộc trò chuyện");
        lblFileListStatus.setForeground(new Color(100, 116, 139));
        lblFileListStatus.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        filesHeader.add(filesTitle, BorderLayout.NORTH);
        filesHeader.add(lblFileListStatus, BorderLayout.SOUTH);

        sharedFileListModel = new DefaultListModel<>();
        listSharedFiles = new JList<>(sharedFileListModel);
        listSharedFiles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listSharedFiles.setFixedCellHeight(68);
        listSharedFiles.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                FileListItem item = (FileListItem) value;
                label.setText("<html><b>" + esc(item.name) + "</b><br>"
                        + "<font color='#64748B' size='2'>" + esc(item.sender)
                        + " • " + formatFileSize(item.size) + "</font><br>"
                        + "<font color='#94A3B8' size='2'>" + esc(item.timestamp)
                        + "</font></html>");
                label.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 5));
                return label;
            }
        });
        listSharedFiles.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) downloadSelectedFile();
            }
        });

        JButton refreshFilesButton = new JButton("Làm mới");
        JButton downloadFileButton = new JButton("Tải tệp đã chọn");
        btnMoreFiles = new JButton("Tải thêm tệp");
        styleSecondaryButton(refreshFilesButton);
        stylePrimaryButton(downloadFileButton);
        styleSecondaryButton(btnMoreFiles);
        refreshFilesButton.addActionListener(e -> requestCurrentFileList());
        downloadFileButton.addActionListener(e -> downloadSelectedFile());
        btnMoreFiles.addActionListener(e -> requestMoreFiles());
        btnMoreFiles.setEnabled(false);

        JPanel fileButtons = new JPanel(new GridLayout(0, 1, 5, 5));
        fileButtons.setOpaque(false);
        fileButtons.add(downloadFileButton);
        fileButtons.add(btnMoreFiles);
        fileButtons.add(refreshFilesButton);

        filesTab.add(filesHeader, BorderLayout.NORTH);
        filesTab.add(new JScrollPane(listSharedFiles), BorderLayout.CENTER);
        filesTab.add(fileButtons, BorderLayout.SOUTH);

        rightTabs.addTab("Thành viên", membersTab);
        rightTabs.addTab("Tệp đã gửi", filesTab);

        right.add(rightTabs, BorderLayout.CENTER);
        return right;
    }

    private void registerWindowEvents() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                logout();
            }
        });
    }

    // =========================================================
    // HỒ SƠ TÀI KHOẢN
    // =========================================================

    private static String profileEncode(String value) {
        return Base64.getEncoder().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String profileDecode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private void requestMyProfile() {
        if (connected && !closed.get()) {
            sendCommand(Protocol.join(Protocol.GET_USER_PROFILE, username));
            sendCommand(Protocol.join(Protocol.GET_USER_AVATAR, username));
        }
    }

    private void openMyProfile() {
        openUserProfile(username, true);
    }

    /** Mở hồ sơ: chính chủ được sửa, hồ sơ người khác chỉ xem. */
    private void openUserProfile(String targetUsername, boolean editable) {
        if (!connected || closed.get()) {
            JOptionPane.showMessageDialog(this, "Bạn chưa kết nối tới máy chủ.");
            return;
        }
        if (targetUsername == null || targetUsername.trim().isEmpty()) return;

        final String target = targetUsername.trim();
        if (editable && !target.equals(username)) {
            JOptionPane.showMessageDialog(this, "Bạn chỉ có thể chỉnh sửa hồ sơ của chính mình.");
            return;
        }

        profileDialogTarget = target;
        profileDialog = new UserProfileDialog(this, target, editable,
                new UserProfileDialog.Host() {
                    @Override
                    public void updateProfile(String displayName, String email, String phone,
                                              String birthday, String gender, String bio) {
                        if (!editable || !target.equals(username)) return;
                        sendCommand(Protocol.join(Protocol.UPDATE_USER_PROFILE,
                                profileEncode(displayName), profileEncode(email),
                                profileEncode(phone), profileEncode(birthday),
                                profileEncode(gender), profileEncode(bio)));
                    }

                    @Override
                    public void uploadAvatar(byte[] jpegBytes) {
                        if (editable && target.equals(username)) {
                            uploadProfileAvatar(jpegBytes);
                        }
                    }
                });
        sendCommand(Protocol.join(Protocol.GET_USER_PROFILE, target));
        sendCommand(Protocol.join(Protocol.GET_USER_AVATAR, target));
        profileDialog.setVisible(true);
    }

    private void processProfileData(String[] p) {
        if (p.length < 10) return;
        String target = profileDecode(p[1]);
        String displayName = profileDecode(p[2]);
        String publicId = p.length > 10 ? profileDecode(p[10]) : "";
        String email = profileDecode(p[3]);
        String phone = profileDecode(p[4]);
        String birthday = profileDecode(p[5]);
        String gender = profileDecode(p[6]);
        String bio = profileDecode(p[7]);
        String normalizedTarget = target;
        userDisplayNameCache.put(normalizedTarget, displayName == null ? "" : displayName.trim());
        requestedUserProfiles.add(normalizedTarget);
        if (!publicId.isBlank()) {
            UserItem existing = allUsers.get(normalizedTarget);
            if (existing != null) {
                allUsers.put(normalizedTarget, new UserItem(existing.name, existing.online, publicId));
            }
        }

        if (target.equals(username)) {
            String visibleName = displayName.trim().isEmpty() ? username : displayName.trim();
            if (!publicId.isBlank()) visibleName += "  · ID " + publicId;
            if (lblMyDisplayName != null) lblMyDisplayName.setText(visibleName);
        }
        if (isPrivateChat && currentTarget != null && target.equals(currentTarget)) {
            if (lblCurrentConversation != null) lblCurrentConversation.setText(getDisplayName(currentTarget));
            if (transcriptView != null) renderTranscript();
        }
        if (listUsers != null) listUsers.repaint();
        if (profileDialog != null && profileDialog.isDisplayable()
                && target.equals(profileDialogTarget)) {
            profileDialog.setProfile(displayName, email, phone, birthday, gender, bio);
            profileDialog.setPublicId(publicId);
            profileDialog.setStatus("Đã tải hồ sơ.", false);
        }
    }

    private void onProfileUpdateOk(String[] p) {
        String target = p.length > 1 ? profileDecode(p[1]) : username;
        if (target.isEmpty()) target = username;
        if (target.equals(username)) {
            requestMyProfile();
            if (profileDialog != null && profileDialog.isDisplayable()) {
                profileDialog.setStatus("Đã lưu thông tin cá nhân.", false);
            }
        }
    }

    private void showProfileError(String[] p, String fallback) {
        String message = p.length > 1 ? profileDecode(p[1]) : fallback;
        if (message == null || message.isBlank()) message = fallback;
        if (profileDialog != null && profileDialog.isDisplayable()) {
            profileDialog.setStatus(message, true);
        } else {
            appendMessage("[Hồ sơ] " + message);
        }
    }

    private void uploadProfileAvatar(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        if (bytes.length > 512 * 1024) {
            JOptionPane.showMessageDialog(this, "Ảnh đại diện không được vượt quá 512 KB.");
            return;
        }
        try {
            String hash = sha256Hex(bytes);
            if (!sendCommand(Protocol.join(Protocol.UPLOAD_USER_AVATAR_BEGIN, bytes.length, hash))) return;
            final int chunkSize = 8 * 1024;
            int sequence = 0;
            for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                int length = Math.min(chunkSize, bytes.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(bytes, offset, chunk, 0, length);
                if (!sendCommand(Protocol.join(Protocol.UPLOAD_USER_AVATAR_CHUNK,
                        sequence++, Base64.getEncoder().encodeToString(chunk)))) return;
            }
            sendCommand(Protocol.join(Protocol.UPLOAD_USER_AVATAR_END));
            if (profileDialog != null && profileDialog.isDisplayable()) {
                profileDialog.setStatus("Đang tải ảnh đại diện lên máy chủ...", false);
            }
        } catch (Exception ex) {
            if (profileDialog != null && profileDialog.isDisplayable()) {
                profileDialog.setStatus("Không thể chuẩn bị ảnh đại diện.", true);
            }
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private void beginAvatarDownload(String[] p) {
        if (p.length < 4) return;
        pendingAvatarUsername = profileDecode(p[1]);
        try {
            expectedAvatarDownloadSize = Integer.parseInt(p[2]);
            expectedAvatarDownloadHash = p[3];
        } catch (NumberFormatException ex) {
            avatarDownloadBuffer = null;
            return;
        }
        if (expectedAvatarDownloadSize < 0 || expectedAvatarDownloadSize > 512 * 1024) {
            avatarDownloadBuffer = null;
            return;
        }
        expectedAvatarDownloadSequence = 0;
        avatarDownloadBuffer = new java.io.ByteArrayOutputStream(expectedAvatarDownloadSize);
    }

    private void receiveAvatarChunk(String[] p) {
        if (p.length < 4 || avatarDownloadBuffer == null) return;
        if (!profileDecode(p[1]).equals(pendingAvatarUsername)) return;
        try {
            int sequence = Integer.parseInt(p[2]);
            byte[] bytes = Base64.getDecoder().decode(p[3]);
            if (sequence != expectedAvatarDownloadSequence
                    || avatarDownloadBuffer.size() + bytes.length > expectedAvatarDownloadSize) {
                avatarDownloadBuffer = null;
                return;
            }
            avatarDownloadBuffer.write(bytes);
            expectedAvatarDownloadSequence++;
        } catch (Exception ex) {
            avatarDownloadBuffer = null;
        }
    }

    private void finishAvatarDownload(String[] p) {
        if (p.length < 2 || avatarDownloadBuffer == null) return;
        if (!profileDecode(p[1]).equals(pendingAvatarUsername)) return;
        byte[] bytes = avatarDownloadBuffer.toByteArray();
        avatarDownloadBuffer = null;
        if (bytes.length != expectedAvatarDownloadSize) return;
        try {
            if (!expectedAvatarDownloadHash.isEmpty()
                    && !sha256Hex(bytes).equalsIgnoreCase(expectedAvatarDownloadHash)) return;
        } catch (Exception ex) { return; }

        String cacheKey = pendingAvatarUsername;
        userAvatarCache.put(cacheKey, bytes.clone());
        userAvatarIconCache.keySet().removeIf(key -> key.startsWith(cacheKey + ":"));
        requestedUserAvatars.add(cacheKey);

        if (pendingAvatarUsername.equals(username)) {
            if (lblMyAvatar != null && bytes.length > 0) setAvatarIcon(lblMyAvatar, bytes, 52);
            else if (lblMyAvatar != null) {
                lblMyAvatar.setIcon(null);
                lblMyAvatar.setText(username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase(Locale.ROOT));
            }
        }
        if (profileDialog != null && profileDialog.isDisplayable()
                && pendingAvatarUsername.equals(profileDialogTarget)) {
            profileDialog.setAvatarBytes(bytes);
        }
        if (isPrivateChat && currentTarget != null
                && pendingAvatarUsername.equals(currentTarget)
                && lblConversationAvatar != null) {
            if (bytes.length > 0) lblConversationAvatar.setImage(bytes);
            else lblConversationAvatar.setIdentity(currentTarget, false);
        }
        // Avatar vừa về phải làm mới cả avatar cạnh tin nhắn và danh sách liên hệ.
        renderTranscript();
        if (listUsers != null) listUsers.repaint();
    }

    private byte[] getCachedUserAvatar(String targetUsername) {
        if (targetUsername == null || targetUsername.isBlank()) return null;
        byte[] bytes = userAvatarCache.get(targetUsername.trim());
        return bytes == null ? null : bytes.clone();
    }

    /** Trả về tên hiển thị; username chỉ được dùng khi hồ sơ chưa có tên hiển thị. */
    private String getDisplayName(String targetUsername) {
        if (targetUsername == null || targetUsername.trim().isEmpty()) return "Người dùng";
        String key = targetUsername.trim();
        String value = userDisplayNameCache.get(key);
        return value == null || value.trim().isEmpty() ? targetUsername.trim() : value.trim();
    }

    private void requestUserProfile(String targetUsername) {
        if (targetUsername == null || targetUsername.trim().isEmpty()
                || targetUsername.equals(username)
                || !connected || closed.get()) return;
        String key = targetUsername.trim();
        if (userDisplayNameCache.containsKey(key) || !requestedUserProfiles.add(key)) return;
        if (!sendCommand(Protocol.join(Protocol.GET_USER_PROFILE, targetUsername.trim()))) {
            requestedUserProfiles.remove(key);
        }
    }

    /** Icon avatar tròn, khử răng cưa; dùng chữ cái đầu khi người đó chưa đặt ảnh. */
    private ImageIcon getAvatarIcon(String targetUsername, int size) {
        String value = targetUsername == null || targetUsername.trim().isEmpty()
                ? "?" : targetUsername.trim();
        int diameter = Math.max(24, Math.min(88, size));
        String userKey = value;
        String cacheKey = userKey + ":" + diameter;
        ImageIcon cached = userAvatarIconCache.get(cacheKey);
        if (cached != null) return cached;

        // Render bitmap at 4x then expose only the requested logical size to Swing.
        final int scale = 4;
        final int d = diameter * scale;
        java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                d, d, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setColor(new Color(226, 232, 240));
        g.fillOval(0, 0, d - 1, d - 1);
        g.setClip(new java.awt.geom.Ellipse2D.Double(1, 1, d - 2, d - 2));

        byte[] bytes = getCachedUserAvatar(value);
        java.awt.image.BufferedImage decoded = null;
        if (bytes != null && bytes.length > 0) {
            try { decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)); }
            catch (IOException ignored) { decoded = null; }
        }

        if (decoded != null && decoded.getWidth() > 0 && decoded.getHeight() > 0) {
            // Center-crop to a square, scaling straight from the original profile bitmap.
            int side = Math.min(decoded.getWidth(), decoded.getHeight());
            int sx = (decoded.getWidth() - side) / 2;
            int sy = (decoded.getHeight() - side) / 2;
            g.drawImage(decoded, 1, 1, d - 1, d - 1,
                    sx, sy, sx + side, sy + side, null);
            decoded.flush();
        } else {
            Color[] colors = {new Color(218, 232, 246), new Color(234, 222, 246),
                    new Color(219, 238, 226), new Color(250, 230, 204), new Color(246, 219, 224)};
            g.setColor(colors[Math.floorMod(userKey.hashCode(), colors.length)]);
            g.fillOval(1, 1, d - 2, d - 2);
            String initial = value.equals("?") ? "?" : value.substring(0, 1).toUpperCase(Locale.ROOT);
            g.setClip(null);
            g.setColor(new Color(55, 65, 81));
            g.setFont(new Font("Segoe UI", Font.BOLD, Math.max(36, (int) (diameter * scale * 0.46))));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(initial, (d - fm.stringWidth(initial)) / 2,
                    (d - fm.getHeight()) / 2 + fm.getAscent());
        }
        g.setClip(null);
        g.setStroke(new BasicStroke(Math.max(2f, diameter * scale / 28f)));
        g.setColor(new Color(255, 255, 255, 225));
        g.drawOval(2, 2, d - 4, d - 4);
        g.dispose();

        ImageIcon result = new HiDpiAvatarIcon(canvas, diameter);
        userAvatarIconCache.put(cacheKey, result);
        return result;
    }

    private void installProfileDoubleClick(Component component) {
        if (component == null) return;
        component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        component.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    openMyProfile();
                }
            }
        });
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                installProfileDoubleClick(child);
            }
        }
    }

    private void requestUserAvatar(String targetUsername) {
        if (targetUsername == null || targetUsername.isBlank()
                || targetUsername.equals(username)
                || !connected || closed.get()) return;
        String key = targetUsername.trim();
        if (userAvatarCache.containsKey(key) || !requestedUserAvatars.add(key)) return;
        if (!sendCommand(Protocol.join(Protocol.GET_USER_AVATAR, targetUsername.trim()))) {
            requestedUserAvatars.remove(key);
        }
    }

    private void setAvatarIcon(JLabel label, byte[] bytes, int size) {
        try {
            java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));
            if (source == null) return;
            int diameter = Math.max(1, size);
            int scale = 3;
            java.awt.image.BufferedImage result = new java.awt.image.BufferedImage(
                    diameter * scale, diameter * scale, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = result.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new Color(226, 232, 240));
            g.fillOval(0, 0, diameter * scale - 1, diameter * scale - 1);
            g.setClip(new java.awt.geom.Ellipse2D.Double(1, 1, diameter * scale - 2, diameter * scale - 2));
            int side = Math.min(source.getWidth(), source.getHeight());
            int sx = (source.getWidth() - side) / 2;
            int sy = (source.getHeight() - side) / 2;
            g.drawImage(source, 1, 1, diameter * scale - 1, diameter * scale - 1,
                    sx, sy, sx + side, sy + side, null);
            g.dispose();
            label.setText("");
            label.setIcon(new ImageIcon(result.getScaledInstance(size, size, Image.SCALE_SMOOTH)));
            source.flush();
        } catch (IOException ignored) { }
    }

    private void onAvatarUploadOk(String[] p) {
        String target = p.length > 1 ? profileDecode(p[1]) : username;
        if (target.equals(username)) {
            requestMyProfile();
            if (profileDialog != null && profileDialog.isDisplayable()) {
                profileDialog.setStatus("Đã lưu ảnh đại diện.", false);
            }
        }
    }

    private void processUserProfileChanged(String[] p) {
        if (p.length < 2) return;
        String target = profileDecode(p[1]);
        String key = target;
        userDisplayNameCache.remove(key);
        requestedUserProfiles.remove(key);
        userAvatarCache.remove(key);
        userAvatarIconCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(key + ":"));
        requestedUserAvatars.remove(key);

        if (target.equals(username)) {
            requestMyProfile();
        } else if ((isPrivateChat && currentTarget.equals(target))
                || recentContactNames.contains(key)
                || (profileDialog != null && profileDialog.isDisplayable()
                    && target.equals(profileDialogTarget))) {
            requestUserProfile(target);
        }

        if (profileDialog != null && profileDialog.isDisplayable()
                && target.equals(profileDialogTarget)) {
            sendCommand(Protocol.join(Protocol.GET_USER_PROFILE, target));
            sendCommand(Protocol.join(Protocol.GET_USER_AVATAR, target));
        }
        if (isPrivateChat && currentTarget.equals(target)) {
            if (lblConversationAvatar != null) lblConversationAvatar.setIdentity(target, false);
            requestUserAvatar(target);
            if (lblCurrentConversation != null) lblCurrentConversation.setText(getDisplayName(target));
        }
        if (listUsers != null) listUsers.repaint();
    }

    // =========================================================
    // SEND COMMAND
    // =========================================================

    private boolean sendCommand(String command) {
        if (!connected || closed.get()) {
            appendMessage("Bạn đã ngắt kết nối với Server.");
            return false;
        }
        try {
            out.println(command);
            if (out.checkError()) {
                appendMessage("Không thể gửi dữ liệu đến Server.");
                return false;
            }
            return true;
        } catch (Exception e) {
            appendMessage("Lỗi gửi dữ liệu: " + e.getMessage());
            return false;
        }
    }

    private void updateVideoCallButtonState() {
        if (btnVideoCall == null) return;
        boolean inRoom = !isPrivateChat && currentRoomId > 0;
        boolean inPrivate = isPrivateChat && currentTarget != null && !currentTarget.isBlank();
        btnVideoCall.setEnabled(connected && !closed.get() && (inRoom || inPrivate));
        if (isPrivateChat) {
            btnVideoCall.setText("📹 Gọi video");
            btnVideoCall.setToolTipText("Gọi video 1-1 với " + getDisplayName(currentTarget));
        } else if (inRoom) {
            boolean activeGroup = groupCallController != null && groupCallController.isRoomCallActive(currentRoomId);
            btnVideoCall.setText(activeGroup ? "📞 Tham gia gọi" : "📞 Gọi nhóm");
            btnVideoCall.setToolTipText(activeGroup
                    ? "Tham gia cuộc gọi nhóm đang diễn ra trong phòng này"
                    : "Bắt đầu cuộc gọi video nhóm trong phòng này");
        } else {
            btnVideoCall.setText("📹 Gọi video");
            btnVideoCall.setToolTipText("Mở chat riêng để gọi video hoặc mở phòng để gọi nhóm");
        }
    }

    private void refreshGroupCallBanner() {
        SwingUtilities.invokeLater(() -> {
            if (groupCallBannerPanel == null) return;
            boolean show = connected && !closed.get() && !isPrivateChat && currentRoomId > 0
                    && groupCallController != null && groupCallController.isRoomCallActive(currentRoomId);
            groupCallBannerPanel.setVisible(show);
            if (show) {
                String text = groupCallController.getBannerText(currentRoomId);
                lblGroupCallBanner.setText(text);
                btnJoinGroupCallBanner.setText(groupCallController.isInCall() ? "Đang tham gia" : "Tham gia gọi");
                btnJoinGroupCallBanner.setEnabled(!groupCallController.isInCall());
            }
            updateVideoCallButtonState();
            groupCallBannerPanel.revalidate();
            groupCallBannerPanel.repaint();
            if (groupCallBannerPanel.getParent() != null) groupCallBannerPanel.getParent().revalidate();
        });
    }

    private void showGroupCallRoomNotice(int roomId, String message) {
        SwingUtilities.invokeLater(() -> {
            if (message == null || message.isBlank()) return;
            if (!isPrivateChat && currentRoomId == roomId) {
                appendMessage("[Cuộc gọi nhóm] " + message);
                return;
            }
            if (message.contains("đã bắt đầu cuộc gọi nhóm")) {
                JDialog toast = new JDialog(this, "Thông báo cuộc gọi nhóm", false);
                JPanel content = new JPanel(new BorderLayout(8, 6));
                content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
                JLabel text = new JLabel("📞 Phòng ID " + roomId + ": " + message);
                text.setFont(new Font("Segoe UI", Font.BOLD, 12));
                content.add(text, BorderLayout.CENTER);
                JButton join = new JButton("Mở phòng / tham gia");
                join.addActionListener(e -> {
                    toast.dispose();
                    if (groupCallController != null) {
                        RoomItem room = findRoomById(roomId);
                        if (room != null) openRoomFromList(room);
                    }
                });
                content.add(join, BorderLayout.SOUTH);
                toast.setContentPane(content);
                toast.pack();
                toast.setLocationRelativeTo(this);
                toast.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                toast.setVisible(true);
                Timer dismiss = new Timer(10000, e -> toast.dispose());
                dismiss.setRepeats(false); dismiss.start();
            }
        });
    }

    private void refreshLists() {
        sendCommand(Protocol.join(Protocol.LIST_ROOMS));
        sendCommand(Protocol.join(Protocol.LIST_USERS));
        sendCommand(Protocol.join(Protocol.LIST_PRIVATE_CONTACTS));
        sendCommand(Protocol.join(Protocol.GET_UNREAD_COUNTS));
        if (!isPrivateChat && currentRoomId > 0) {
            sendCommand(Protocol.join(Protocol.LIST_MEMBERS, currentRoomId));
        }
        requestCurrentFileList();
    }

    // =========================================================
    // ROOM FLOW
    // =========================================================

    private void createRoom() {
        String name = JOptionPane.showInputDialog(
                this, "Nhập tên phòng mới:", "Tạo phòng", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || name.contains("|") || name.contains(",") || name.contains(";")) {
            JOptionPane.showMessageDialog(this,
                    "Tên phòng không được trống hoặc chứa ký tự | , ;");
            return;
        }
        String password = JOptionPane.showInputDialog(
                this,
                "Nhập mật khẩu phòng. Để trống nếu phòng công khai:",
                "Mật khẩu phòng", JOptionPane.QUESTION_MESSAGE);
        if (password == null) return;
        pendingOpenRoomId = -1;
        sendCommand(Protocol.join(Protocol.CREATE_ROOM, name, password));
    }

    private void openRoomFromList(RoomItem room) {
        if (room == null) return;

        // Neu da la thanh vien: mo lich su, khong bat nguoi dung tham gia lai.
        if (room.joined() || registeredRoomIds.contains(room.id)) {
            openGroupRoom(room);
            return;
        }

        String password = "";
        if (room.locked) {
            String entered = JOptionPane.showInputDialog(
                    this,
                    "Nhập mật khẩu phòng \"" + room.name + "\":",
                    "Tham gia phòng",
                    JOptionPane.QUESTION_MESSAGE);
            if (entered == null) return;
            password = entered;
        }

        pendingJoinRoomId = room.id;
        sendCommand(Protocol.join(Protocol.JOIN_ROOM, room.id, password));
    }

    private void openGroupRoom(RoomItem room) {
        if (room == null) return;

        stopTypingNow();
        currentRoomId = room.id;
        currentRoomName = room.name;
        currentRoomRole = room.role == null ? "" : room.role;
        currentTarget = String.valueOf(room.id);
        isPrivateChat = false;
        updateVideoCallButtonState();
        if (groupCallController != null) groupCallController.requestRoomState(room.id);
        clearUnreadForConversation("G", String.valueOf(room.id));

        lblCurrentConversation.setText(room.name);
        lblCurrentConversation.setToolTipText("Phòng chat nhóm");
        if (lblConversationAvatar != null) {
            lblConversationAvatar.setToolTipText("Nhấn để đổi ảnh đại diện phòng (chủ phòng)");
        }
        lblConversationInfo.setText(room.memberCount + " thành viên"
                + "  •  ID phòng: " + (room.publicId.isEmpty() ? "đang tải" : room.publicId)
                + "  •  Vai trò: " + roleVietnamese(currentRoomRole));
        if (lblConversationAvatar != null) {
            byte[] cachedRoomAvatar = roomAvatarBytesCache.get(room.id);
            if (cachedRoomAvatar != null && cachedRoomAvatar.length > 0) {
                lblConversationAvatar.setImage(cachedRoomAvatar);
            } else {
                lblConversationAvatar.setIdentity(room.name, true);
            }
        }
        updateMessagePlaceholder();

        clearConversationDisplay("Đang tải lịch sử phòng " + room.name + "...");
        startInitialHistoryLoad("G", String.valueOf(room.id));
        sendCommand(Protocol.join(Protocol.LOAD_HISTORY_PAGED, room.id, 0));
        requestCurrentConversationStates();
        sendCommand(Protocol.join(Protocol.LIST_MEMBERS, room.id));
        requestRoomAvatar(room.id);
        requestCurrentFileList();
        updateMemberActionVisibility();
        txtMessage.requestFocusInWindow();
    }

    private void leaveSelectedRoom() {
        RoomItem selected = listRooms.getSelectedValue();
        int roomId = selected != null ? selected.id : currentRoomId;

        if (roomId <= 0 || (selected != null && !selected.joined())) {
            JOptionPane.showMessageDialog(this, "Hãy chọn phòng bạn đã tham gia.");
            return;
        }

        int result = JOptionPane.showConfirmDialog(
                this,
                "Bạn có chắc muốn rời phòng này không?",
                "Rời phòng", JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            sendCommand(Protocol.join(Protocol.LEAVE_ROOM, roomId));
        }
    }

    private void chooseRoomAvatar() {
        if (isPrivateChat || currentRoomId <= 0) {
            JOptionPane.showMessageDialog(this, "Hãy mở một phòng chat trước.");
            return;
        }
        if (!Protocol.ROLE_OWNER.equalsIgnoreCase(currentRoomRole)) {
            JOptionPane.showMessageDialog(this, "Chỉ chủ phòng mới được đổi ảnh đại diện.");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Chọn ảnh đại diện phòng chat");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Ảnh (PNG, JPG, JPEG, GIF)", "png", "jpg", "jpeg", "gif"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        try {
            java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(chooser.getSelectedFile());
            if (source == null) throw new IllegalArgumentException("Tệp không phải ảnh hợp lệ.");
            int w = source.getWidth(), h = source.getHeight();
            double scale = Math.min(1.0, 512.0 / Math.max(w, h));
            int nw = Math.max(1, (int)Math.round(w * scale));
            int nh = Math.max(1, (int)Math.round(h * scale));
            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                    nw, nh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, nw, nh);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, nw, nh, null); g.dispose();
            java.io.ByteArrayOutputStream bytesOut = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(scaled, "jpg", bytesOut);
            byte[] bytes = bytesOut.toByteArray();
            if (bytes.length > 512 * 1024) {
                JOptionPane.showMessageDialog(this,
                        "Ảnh sau khi thu nhỏ vẫn vượt quá 512 KB. Hãy chọn ảnh khác.");
                return;
            }
            uploadRoomAvatar(currentRoomId, bytes);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Không thể đọc ảnh: " + ex.getMessage(),
                    "Lỗi ảnh đại diện", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void uploadRoomAvatar(int roomId, byte[] bytes) {
        try {
            if (!sendCommand(Protocol.join(Protocol.UPLOAD_ROOM_AVATAR_BEGIN,
                    roomId, bytes.length, sha256Hex(bytes)))) return;
            final int chunkSize = 8 * 1024;
            int seq = 0;
            for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                int length = Math.min(chunkSize, bytes.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(bytes, offset, chunk, 0, length);
                if (!sendCommand(Protocol.join(Protocol.UPLOAD_ROOM_AVATAR_CHUNK,
                        seq++, Base64.getEncoder().encodeToString(chunk)))) return;
            }
            sendCommand(Protocol.join(Protocol.UPLOAD_ROOM_AVATAR_END));
            appendMessage("[Phòng] Đang cập nhật ảnh đại diện...");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Không thể chuẩn bị ảnh đại diện phòng.");
        }
    }

    private void dissolveCurrentRoom() {
        if (isPrivateChat || currentRoomId <= 0) return;
        if (!Protocol.ROLE_OWNER.equalsIgnoreCase(currentRoomRole)) {
            JOptionPane.showMessageDialog(this, "Chỉ chủ phòng mới được giải tán phòng.");
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                "Bạn chắc chắn muốn giải tán phòng \"" + currentRoomName + "\"?\n"
                + "Phòng sẽ bị xóa khỏi hệ thống và mọi thành viên sẽ không thể tiếp tục trò chuyện.",
                "Xác nhận giải tán phòng", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            sendCommand(Protocol.join(Protocol.DISSOLVE_ROOM, currentRoomId));
        }
    }

    private void requestRoomAvatar(int roomId) {
        if (roomId <= 0 || !connected || closed.get()) return;
        if (roomAvatarBytesCache.containsKey(roomId) || !requestedRoomAvatarIds.add(roomId)) return;
        if (!sendCommand(Protocol.join(Protocol.GET_ROOM_AVATAR, roomId))) {
            requestedRoomAvatarIds.remove(roomId);
        }
    }

    private void beginRoomAvatar(String[] p) {
        if (p.length < 4) return;
        try {
            int roomId = Integer.parseInt(p[1]);
            int size = Integer.parseInt(p[2]);
            String hash = p[3];
            if (roomId <= 0 || size < 0 || size > 512 * 1024) {
                roomAvatarDownloads.remove(roomId);
                return;
            }
            roomAvatarDownloads.put(roomId, new RoomAvatarDownload(size, hash));
        } catch (Exception ex) {
            // Bỏ qua metadata ảnh phòng sai định dạng.
        }
    }

    private void receiveRoomAvatarChunk(String[] p) {
        if (p.length < 4) return;
        try {
            int roomId = Integer.parseInt(p[1]);
            int sequence = Integer.parseInt(p[2]);
            RoomAvatarDownload download = roomAvatarDownloads.get(roomId);
            if (download == null) return;
            byte[] bytes = Base64.getDecoder().decode(p[3]);
            if (sequence != download.nextSequence
                    || download.buffer.size() + bytes.length > download.expectedSize) {
                roomAvatarDownloads.remove(roomId);
                return;
            }
            download.buffer.write(bytes);
            download.nextSequence++;
        } catch (Exception ex) {
            // Chunk không hợp lệ sẽ bị bỏ qua; các frame khác vẫn tiếp tục được xử lý.
        }
    }

    private void finishRoomAvatar(String[] p) {
        if (p.length < 2) return;
        try {
            int roomId = Integer.parseInt(p[1]);
            RoomAvatarDownload download = roomAvatarDownloads.remove(roomId);
            if (download == null) return;
            byte[] bytes = download.buffer.toByteArray();
            if (bytes.length != download.expectedSize) return;
            if (bytes.length > 0 && !sha256Hex(bytes).equalsIgnoreCase(download.expectedHash)) return;

            roomAvatarBytesCache.put(roomId, bytes);
            roomAvatarIconCache.keySet().removeIf(key -> key.startsWith(roomId + ":"));
            if (listRooms != null) listRooms.repaint();

            if (!isPrivateChat && currentRoomId == roomId && lblConversationAvatar != null) {
                if (bytes.length == 0) lblConversationAvatar.setIdentity(currentRoomName, true);
                else lblConversationAvatar.setImage(bytes);
            }
        } catch (Exception ignored) {
            // Không làm gián đoạn giao diện nếu một ảnh bị lỗi.
        }
    }

    /** Avatar tròn cho danh sách phòng: ảnh thật nếu đã tải, chữ cái đầu nếu chưa có ảnh. */
    private ImageIcon getRoomAvatarIcon(int roomId, String roomName, int size) {
        int diameter = Math.max(32, Math.min(96, size));
        String cacheKey = roomId + ":" + diameter;
        ImageIcon cached = roomAvatarIconCache.get(cacheKey);
        if (cached != null) return cached;

        int scale = 4;
        int d = diameter * scale;
        java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                d, d, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        String name = roomName == null ? "" : roomName.trim();
        int hash = (name + "#" + roomId).toLowerCase(Locale.ROOT).hashCode();
        Color[] fills = {new Color(219, 234, 254), new Color(237, 233, 254),
                new Color(209, 250, 229), new Color(254, 243, 199), new Color(252, 231, 243)};
        g.setColor(fills[Math.floorMod(hash, fills.length)]);
        g.fillOval(0, 0, d - 1, d - 1);
        g.setClip(new java.awt.geom.Ellipse2D.Double(1, 1, d - 2, d - 2));

        byte[] raw = roomAvatarBytesCache.get(roomId);
        java.awt.image.BufferedImage source = null;
        if (raw != null && raw.length > 0) {
            try { source = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw)); }
            catch (IOException ignored) { source = null; }
        }
        if (source != null && source.getWidth() > 0 && source.getHeight() > 0) {
            int side = Math.min(source.getWidth(), source.getHeight());
            int sx = (source.getWidth() - side) / 2;
            int sy = (source.getHeight() - side) / 2;
            g.drawImage(source, 1, 1, d - 1, d - 1,
                    sx, sy, sx + side, sy + side, null);
            source.flush();
        } else {
            String initial = name.isEmpty() ? "#" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            g.setClip(null);
            g.setColor(new Color(51, 84, 140));
            g.setFont(new Font("Segoe UI", Font.BOLD, Math.max(36, (int) (diameter * scale * 0.46))));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(initial, (d - fm.stringWidth(initial)) / 2,
                    (d - fm.getHeight()) / 2 + fm.getAscent());
        }
        g.setClip(null);
        g.setStroke(new BasicStroke(Math.max(2f, diameter / 24f)));
        g.setColor(new Color(255, 255, 255, 220));
        g.drawOval(1, 1, d - 3, d - 3);
        g.dispose();

        ImageIcon result = new HiDpiAvatarIcon(canvas, diameter);
        roomAvatarIconCache.put(cacheKey, result);
        return result;
    }

    private void processRoomDissolved(String[] p) {
        if (p.length < 2) return;
        try {
            int id = Integer.parseInt(p[1]);
            registeredRoomIds.remove(id);
            roomRoles.remove(id);
            roomAvatarBytesCache.remove(id);
            roomAvatarIconCache.keySet().removeIf(key -> key.startsWith(id + ":"));
            requestedRoomAvatarIds.remove(id);
            roomAvatarDownloads.remove(id);
            for (int i = roomListModel.size() - 1; i >= 0; i--) {
                if (roomListModel.get(i).id == id) roomListModel.remove(i);
            }
            if (!isPrivateChat && currentRoomId == id) {
                stopTypingNow();
                currentRoomId = -1; currentRoomName = ""; currentRoomRole = "";
                currentTarget = "";
                lblCurrentConversation.setText("Phòng đã được giải tán");
                lblConversationInfo.setText("Cuộc trò chuyện này không còn tồn tại.");
                if (lblConversationAvatar != null) lblConversationAvatar.setIdentity("", true);
                clearConversationDisplay("Phòng đã được chủ phòng giải tán.");
                updateMemberActionVisibility();
            }
            sendCommand(Protocol.join(Protocol.LIST_ROOMS));
        } catch (Exception ignored) { }
    }

    private String decodeBase64Text(String value) {
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception ex) { return value == null ? "" : value; }
    }

    private void processRoomList(String[] parts) {
        int selectedId = listRooms.getSelectedValue() == null
                ? -1 : listRooms.getSelectedValue().id;

        List<RoomItem> rooms = new ArrayList<>();
        if (parts.length >= 2 && !parts[1].isEmpty()) {
            String[] entries = parts[1].split(";");
            for (String entry : entries) {
                String[] f = entry.split(",", -1);
                if (f.length < 2) continue;
                try {
                    int id = Integer.parseInt(f[0]);
                    String name = f[1];
                    boolean locked = f.length > 2 && "1".equals(f[2]);
                    String owner = f.length > 3 ? f[3] : "";
                    int memberCount = f.length > 4 ? Integer.parseInt(f[4]) : 0;
                    String role = f.length > 5 ? f[5] : "";
                    String publicId = f.length > 6 ? f[6] : "";
                    long latestActivityId = f.length > 7
                            ? parseLongOrDefault(f[7], 0L)
                            : roomLastActivityIds.getOrDefault(id, 0L);
                    roomLastActivityIds.merge(id, latestActivityId, Math::max);
                    RoomItem item = new RoomItem(id, name, locked, owner, memberCount, role, publicId);
                    // Chỉ hiển thị phòng tài khoản đã là thành viên.
                    if (item.joined()) rooms.add(item);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        sortRoomsByLatestActivity(rooms);
        suppressRoomSelection = true;
        roomListModel.clear();
        roomRoles.clear();

        Set<Integer> joinedRoomIds = new HashSet<>();
        for (RoomItem room : rooms) joinedRoomIds.add(room.id);
        registeredRoomIds.retainAll(joinedRoomIds);

        for (RoomItem room : rooms) {
            roomListModel.addElement(room);
            roomRoles.put(room.id, room.role);
            if (room.joined()) {
                // Dang nhap lai: dang ky socket vao cac phong da tham gia.
                // Server can bo qua yeu cau mat khau neu tai khoan da la thanh vien.
                if (registeredRoomIds.add(room.id)) {
                    sendCommand(Protocol.join(Protocol.JOIN_ROOM, room.id, ""));
                }
                requestRoomAvatar(room.id);
            } else {
                registeredRoomIds.remove(room.id);
            }
        }

        if (selectedId > 0) {
            RoomItem selected = findRoomById(selectedId);
            if (selected != null) listRooms.setSelectedValue(selected, true);
        }

        if (pendingOpenRoomId > 0) {
            RoomItem created = findRoomById(pendingOpenRoomId);
            if (created != null && created.joined()) {
                pendingOpenRoomId = -1;
                openGroupRoom(created);
            }
        }

        suppressRoomSelection = false;
        listRooms.repaint();
        updateMemberActionVisibility();
    }

    private RoomItem findRoomById(int id) {
        for (int i = 0; i < roomListModel.size(); i++) {
            RoomItem r = roomListModel.get(i);
            if (r.id == id) return r;
        }
        return null;
    }

    // =========================================================
    // USERS / PRIVATE CONTACTS
    // =========================================================

    private void processUserList(String[] parts) {
        allUsers.clear();
        if (parts.length >= 2 && !parts[1].isEmpty()) {
            String[] entries = parts[1].split(";");
            for (String entry : entries) {
                String[] f = entry.split(",", -1);
                if (f.length < 1 || f[0].trim().isEmpty()) continue;
                String name = f[0].trim();
                if (name.equals(username)) continue;
                boolean online = f.length > 1 && "1".equals(f[1]);
                String publicId = f.length > 3 ? f[3].trim() : "";
                allUsers.put(name, new UserItem(name, online, publicId));
            }
        }
        refreshUserList();
        requestRecentContactProfiles();
        resolvePendingPrivateLookup();
    }

    private void processPrivateContacts(String[] parts) {
        recentContactNames.clear();
        if (parts.length >= 2 && !parts[1].isEmpty()) {
            String[] entries = parts[1].split(";");
            for (String entry : entries) {
                String[] f = entry.split(",", -1);
                if (f.length > 0 && !f[0].trim().isEmpty()) {
                    if (!f[0].trim().equals(username)) {
                        String peerKey = f[0].trim();
                        recentContactNames.add(peerKey);
                        if (f.length > 3) {
                            long latestId = parseLongOrDefault(f[3], 0L);
                            privateLastActivityIds.merge(peerKey, latestId, Math::max);
                        }
                    }
                }
            }
        }
        refreshUserList();
        requestRecentContactProfiles();
    }

    private void requestRecentContactProfiles() {
        for (String key : recentContactNames) {
            UserItem item = allUsers.get(key);
            String target = item == null ? key : item.name;
            requestUserProfile(target);
            // Tai avatar san cho danh sach chat rieng, khong can mo cuoc tro chuyen truoc.
            requestUserAvatar(target);
        }
    }

    private void refreshUserList() {
        if (userListModel == null) return;
        String query = txtSearchUser == null
                ? "" : txtSearchUser.getText().trim().toLowerCase(Locale.ROOT);

        userListModel.clear();
        List<UserItem> shown = new ArrayList<>();

        // Danh sách luôn chỉ gồm những người đã từng có tin nhắn riêng.
        // Ô tìm kiếm lọc danh sách này; người mới chỉ được tra cứu trực tiếp bằng ID 5 số.
        for (String key : recentContactNames) {
            UserItem user = allUsers.get(key);
            if (user == null) user = new UserItem(key, false);
            if (query.isEmpty()
                    || user.name.toLowerCase(Locale.ROOT).contains(query)
                    || user.publicId.toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(user);
            }
        }

        shown.sort((a, b) -> {
            long activityA = privateLastActivityIds.getOrDefault(
                    a.name, 0L);
            long activityB = privateLastActivityIds.getOrDefault(
                    b.name, 0L);
            int byActivity = Long.compare(activityB, activityA);
            if (byActivity != 0) return byActivity;
            if (a.online != b.online) return a.online ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        for (UserItem user : shown) userListModel.addElement(user);
    }

    /** Nút bên cạnh ô tìm kiếm tìm bằng nội dung đã nhập, không bật dialog thứ hai. */
    private void findOrOpenPrivateChatFromSearchField() {
        String query = txtSearchUser == null ? "" : txtSearchUser.getText().trim();
        if (query.isEmpty()) {
            startPrivateChatDialog();
            return;
        }

        // Nếu là liên hệ cũ, mở từ cache; nếu không thì tra cứu theo ID trên server.
        for (String key : recentContactNames) {
            UserItem contact = allUsers.get(key);
            if (contact != null && (contact.publicId.equals(query)
                    || contact.name.equals(query))) {
                openPrivateConversation(contact.name);
                return;
            }
            if (contact == null && key.equals(query)) {
                openPrivateConversation(key);
                return;
            }
        }

        if (!query.matches("\\d{5}")) {
            JOptionPane.showMessageDialog(this,
                    "Nhập ID người dùng gồm đúng 5 chữ số để tìm người chưa từng chat.",
                    "Tìm người dùng", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        sendCommand(Protocol.join(Protocol.FIND_USER_BY_ID, query));
    }

    private List<UserItem> findUserMatches(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<UserItem> matches = new ArrayList<>();
        for (UserItem user : allUsers.values()) {
            if (user.name.toLowerCase(Locale.ROOT).contains(normalized)
                    || user.publicId.toLowerCase(Locale.ROOT).contains(normalized)) {
                matches.add(user);
            }
        }
        return matches;
    }

    private void resolvePendingPrivateLookup() {
        if (pendingPrivateLookup == null || pendingPrivateLookup.trim().isEmpty()) return;

        String requested = pendingPrivateLookup.trim();
        pendingPrivateLookup = null;

        UserItem exact = allUsers.get(requested);
        if (exact != null) {
            openPrivateConversation(exact.name);
            return;
        }

        List<UserItem> matches = findUserMatches(requested);
        if (matches.size() == 1) {
            openPrivateConversation(matches.get(0).name);
        } else if (matches.size() > 1) {
            choosePrivateChatUser(matches);
        } else {
            JOptionPane.showMessageDialog(
                    this,
                    "Server không trả về tài khoản '" + requested + "'. "
                    + "Hãy kiểm tra username hoặc ID 5 chữ số đã chính xác chưa.",
                    "Không tìm thấy người dùng",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void choosePrivateChatUser(List<UserItem> matches) {
        Object[] options = matches.stream().map(u -> u.name).toArray();
        Object selected = JOptionPane.showInputDialog(
                this, "Có nhiều tài khoản khớp. Chọn người cần chat riêng:",
                "Chọn người dùng", JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (selected != null) {
            String chosen = selected.toString();
            openPrivateConversation(chosen);
        }
    }

    private void startPrivateChatDialog() {
        JTextField idField = new JTextField(8);
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Nhập ID người dùng gồm 5 chữ số:"));
        panel.add(idField);
        int result = JOptionPane.showConfirmDialog(this, panel,
                "Chat riêng theo ID", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        String id = idField.getText().trim();
        if (!id.matches("\\d{5}")) {
            JOptionPane.showMessageDialog(this, "ID người dùng phải gồm đúng 5 chữ số.");
            return;
        }
        sendCommand(Protocol.join(Protocol.FIND_USER_BY_ID, id));
    }

    private void processUserFound(String[] p) {
        if (p.length < 4) {
            JOptionPane.showMessageDialog(this, "Phản hồi tìm người dùng không hợp lệ.");
            return;
        }
        String peer = p[1].trim();
        boolean online = "1".equals(p[2]);
        String publicId = p[3].trim();
        if (peer.equals(username)) {
            JOptionPane.showMessageDialog(this, "Bạn không thể chat riêng với chính mình.");
            return;
        }
        allUsers.put(peer, new UserItem(peer, online, publicId));
        openPrivateConversation(peer);
    }

    private void findRoomByIdDialog() {
        JTextField idField = new JTextField(8);
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Nhập ID phòng gồm 5 chữ số:"));
        panel.add(idField);
        int result = JOptionPane.showConfirmDialog(this, panel,
                "Tìm / tham gia phòng", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        String id = idField.getText().trim();
        if (!id.matches("\\d{5}")) {
            JOptionPane.showMessageDialog(this, "ID phòng phải gồm đúng 5 chữ số.");
            return;
        }
        pendingRoomSearchId = id;
        pendingFoundRoom = null;
        sendCommand(Protocol.join(Protocol.FIND_ROOM_BY_ID, id));
    }

    private void processRoomFound(String[] p) {
        if (p.length < 8) {
            pendingRoomSearchId = "";
            JOptionPane.showMessageDialog(this, "Phản hồi tìm phòng không hợp lệ.");
            return;
        }
        try {
            int id = Integer.parseInt(p[1]);
            String publicId = p[2];
            String name = p[3];
            boolean locked = "1".equals(p[4]);
            String owner = p[5];
            int memberCount = Integer.parseInt(p[6]);
            String role = p[7];
            RoomItem found = new RoomItem(id, name, locked, owner, memberCount, role, publicId);

            // Nếu đã là thành viên, yêu cầu server đăng ký socket vào phòng rồi mở.
            // Phòng chưa tham gia sẽ hỏi mật khẩu bên dưới.
            if (found.joined()) {
                pendingRoomSearchId = "";
                pendingFoundRoom = found;
                pendingJoinRoomId = id;
                sendCommand(Protocol.join(Protocol.JOIN_ROOM, id, ""));
                return;
            }

            JPasswordField passwordField = new JPasswordField(18);
            JPanel passwordPanel = new JPanel(new GridLayout(0, 1, 4, 4));
            passwordPanel.add(new JLabel("Phòng: " + name + " (ID " + publicId + ")"));
            passwordPanel.add(new JLabel(locked
                    ? "Nhập mật khẩu phòng để tham gia:"
                    : "Phòng này không đặt mật khẩu; để trống để tiếp tục:"));
            passwordPanel.add(passwordField);
            int choice = JOptionPane.showConfirmDialog(this, passwordPanel,
                    "Tham gia phòng", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                pendingRoomSearchId = "";
                pendingFoundRoom = null;
                return;
            }

            String password = new String(passwordField.getPassword());
            pendingFoundRoom = found;
            pendingJoinRoomId = id;
            sendCommand(Protocol.join(Protocol.JOIN_ROOM, id, password));
        } catch (NumberFormatException ex) {
            pendingRoomSearchId = "";
            pendingFoundRoom = null;
            JOptionPane.showMessageDialog(this, "Thông tin phòng trả về không hợp lệ.");
        }
    }

    private void openPrivateConversation(String peer) {
        if (peer == null || peer.trim().isEmpty() || peer.equals(username)) return;

        stopTypingNow();
        currentRoomId = -1;
        currentRoomName = "";
        currentRoomRole = "";
        currentTarget = peer;
        isPrivateChat = true;
        updateVideoCallButtonState();
        clearUnreadForConversation("P", peer);

        refreshUserList();

        lblCurrentConversation.setText(getDisplayName(peer));
        lblCurrentConversation.setToolTipText("Nhấp đúp để xem hồ sơ của " + getDisplayName(peer));
        if (lblConversationAvatar != null) {
            lblConversationAvatar.setToolTipText("Nhấn để xem hồ sơ của " + getDisplayName(peer));
        }
        requestUserProfile(peer);
        UserItem peerItem = allUsers.get(peer);
        String peerPublicId = peerItem == null ? "" : peerItem.publicId;
        lblConversationInfo.setText("Cuộc trò chuyện riêng"
                + (peerPublicId.isBlank() ? "" : "  •  ID người dùng: " + peerPublicId));
        if (lblConversationAvatar != null) {
            lblConversationAvatar.setIdentity(peer, false);
            byte[] cachedAvatar = getCachedUserAvatar(peer);
            if (cachedAvatar != null && cachedAvatar.length > 0) {
                lblConversationAvatar.setImage(cachedAvatar);
            }
        }
        requestUserAvatar(peer);
        updateMessagePlaceholder();

        clearConversationDisplay("Đang tải lịch sử chat riêng với " + peer + "...");
        startInitialHistoryLoad("P", peer);
        sendCommand(Protocol.join(Protocol.LOAD_PRIVATE_HISTORY, peer, 0));
        requestCurrentConversationStates();
        requestCurrentFileList();
        updateMemberActionVisibility();
        txtMessage.requestFocusInWindow();
    }

    private static class FileListItem {
        final long id;
        final String sender;
        final String name;
        final long size;
        final String timestamp;

        FileListItem(long id, String sender, String name, long size, String timestamp) {
            this.id = id;
            this.sender = sender == null ? "" : sender;
            this.name = name == null ? "Tệp" : name;
            this.size = size;
            this.timestamp = timestamp == null ? "" : timestamp;
        }

        @Override public String toString() { return name; }
    }

    private void requestCurrentFileList() {
        if (sharedFileListModel == null) return;

        currentFilePage = 0;
        fileListHasMore = false;
        if (btnMoreFiles != null) btnMoreFiles.setEnabled(false);
        sharedFileListModel.clear();

        if (isPrivateChat && currentTarget != null && !currentTarget.isBlank()) {
            lblFileListStatus.setText("Đang tải danh sách tệp...");
            sendCommand(Protocol.join(Protocol.LIST_FILES, "P", currentTarget, 0));
        } else if (!isPrivateChat && currentRoomId > 0) {
            lblFileListStatus.setText("Đang tải danh sách tệp...");
            sendCommand(Protocol.join(Protocol.LIST_FILES, "G", currentRoomId, 0));
        } else {
            lblFileListStatus.setText("Chọn một cuộc trò chuyện");
        }
    }

    private void requestMoreFiles() {
        if (!fileListHasMore) return;
        int nextPage = currentFilePage + 1;

        if (isPrivateChat && currentTarget != null && !currentTarget.isBlank()) {
            sendCommand(Protocol.join(Protocol.LIST_FILES, "P", currentTarget, nextPage));
        } else if (!isPrivateChat && currentRoomId > 0) {
            sendCommand(Protocol.join(Protocol.LIST_FILES, "G", currentRoomId, nextPage));
        }
    }

    private void processFileList(String[] parts) {
        if (parts.length < 6) return;

        String kind = parts[1];
        String key = parts[2];
        if ("G".equals(kind)) {
            if (isPrivateChat || !String.valueOf(currentRoomId).equals(key)) return;
        } else if ("P".equals(kind)) {
            if (!isPrivateChat || currentTarget == null
                    || !currentTarget.equals(key)) return;
        } else {
            return;
        }

        int responsePage;
        try {
            responsePage = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }
        if (responsePage == 0) {
            sharedFileListModel.clear();
        } else if (responsePage != currentFilePage + 1) {
            return;
        }

        String records = parts[5];

        if (!records.isEmpty()) {
            for (String record : records.split(";")) {
                String[] fields = record.split(",", -1);
                if (fields.length < 5) continue;

                try {
                    long id = Long.parseLong(fields[0]);
                    String sender = fields[1];
                    String name = new String(
                            Base64.getUrlDecoder().decode(fields[2]),
                            StandardCharsets.UTF_8);
                    long size = Long.parseLong(fields[3]);
                    String timestamp = fields[4];

                    if (id > 0 && size >= 0) {
                        sharedFileListModel.addElement(
                                new FileListItem(id, sender, name, size, timestamp));
                    }
                } catch (Exception ignored) {
                    // Bỏ qua record metadata bị lỗi.
                }
            }
        }

        currentFilePage = responsePage;
        fileListHasMore = "1".equals(parts[4]);
        if (btnMoreFiles != null) btnMoreFiles.setEnabled(fileListHasMore);

        int count = sharedFileListModel.size();
        lblFileListStatus.setText(count == 0
                ? "Chưa có tệp trong cuộc trò chuyện"
                : count + " tệp • Nhấp đúp để tải xuống"
                    + (fileListHasMore ? " • Còn tệp cũ hơn" : ""));
    }

    private void downloadSelectedFile() {
        FileListItem item = listSharedFiles == null
                ? null : listSharedFiles.getSelectedValue();
        if (item == null) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một tệp trước.");
            return;
        }
        fileTransferController.requestDownload(item.id, item.name);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // =========================================================
    // MEMBER LIST + PERMISSIONS
    // =========================================================

    private void processMemberList(String[] parts) {
        if (isPrivateChat || currentRoomId <= 0 || parts.length < 2) return;

        String[] entries = parts[1].split(";");
        if (entries.length == 0) return;

        int roomId;
        try {
            roomId = Integer.parseInt(entries[0]);
        } catch (NumberFormatException e) {
            return;
        }

        if (roomId != currentRoomId) return;

        memberListModel.clear();
        String ownRoleFromServer = null;
        for (int i = 1; i < entries.length; i++) {
            String[] f = entries[i].split(",", -1);
            if (f.length < 2 || f[0].trim().isEmpty()) continue;

            String memberName = f[0].trim();
            String memberRole = f[1].trim();
            String publicId = f.length > 2 ? f[2].trim() : "";
            memberListModel.addElement(new MemberItem(memberName, memberRole, publicId));

            // Quyền trong MEMBER_LIST là trạng thái mới nhất từ server.
            // So sánh username chính xác vì ChatApp cho phép Huy và huy là hai tài khoản.
            if (memberName.equals(username)) {
                ownRoleFromServer = memberRole;
            }
        }

        // Trước đây currentRoomRole chỉ được cập nhật ở JOIN_OK. Khi Owner cấp
        // Admin, client nhận MEMBERS_CHANGED và tải MEMBER_LIST nhưng vẫn giữ
        // currentRoomRole=MEMBER nên toàn bộ nút dành cho Admin tiếp tục bị ẩn.
        // Đồng bộ quyền tại đây để cập nhật UI ngay khi server phát thông báo.
        if (ownRoleFromServer != null && !ownRoleFromServer.isBlank()) {
            currentRoomRole = ownRoleFromServer;
            roomRoles.put(roomId, ownRoleFromServer);

            RoomItem existing = findRoomById(roomId);
            if (existing != null) {
                RoomItem updated = new RoomItem(
                        existing.id,
                        existing.name,
                        existing.locked,
                        existing.owner,
                        existing.memberCount,
                        ownRoleFromServer,
                        existing.publicId);
                updateRoomItem(updated);
                if (listRooms != null) listRooms.repaint();
            }

            if (lblConversationInfo != null) {
                RoomItem room = findRoomById(roomId);
                if (room != null) {
                    lblConversationInfo.setText(room.memberCount + " thành viên"
                            + "  •  ID phòng: "
                            + (room.publicId.isEmpty() ? "đang tải" : room.publicId)
                            + "  •  Vai trò: " + roleVietnamese(currentRoomRole));
                }
            }
        } else {
            // Không giữ quyền quản trị cũ nếu server không còn xác nhận tài khoản
            // hiện tại trong danh sách thành viên của phòng.
            currentRoomRole = Protocol.ROLE_MEMBER;
            roomRoles.put(roomId, currentRoomRole);
        }

        updateMemberActionVisibility();
        updateMemberActionButtons();
    }

    private void updateMemberActionVisibility() {
        if (memberActionsPanel == null) return;

        boolean groupConversation = !isPrivateChat && currentRoomId > 0;
        boolean owner = Protocol.ROLE_OWNER.equalsIgnoreCase(currentRoomRole);
        boolean admin = Protocol.ROLE_ADMIN.equalsIgnoreCase(currentRoomRole);

        // Thanh vien thuong chi xem danh sach, khong thay cac nut quan tri.
        memberActionsPanel.setVisible(groupConversation && (owner || admin));
        btnGrantAdmin.setVisible(groupConversation && owner);
        btnRevokeAdmin.setVisible(groupConversation && owner);
        btnKickMember.setVisible(groupConversation && (owner || admin));

        if (!groupConversation) memberListModel.clear();
        updateMemberActionButtons();
        revalidate();
        repaint();
    }

    private void updateMemberActionButtons() {
        if (btnGrantAdmin == null) return;

        boolean owner = Protocol.ROLE_OWNER.equalsIgnoreCase(currentRoomRole);
        boolean admin = Protocol.ROLE_ADMIN.equalsIgnoreCase(currentRoomRole);
        MemberItem target = listMembers == null ? null : listMembers.getSelectedValue();
        boolean validTarget = target != null
                && !target.name.equals(username)
                && !Protocol.ROLE_OWNER.equalsIgnoreCase(target.role);

        btnGrantAdmin.setEnabled(owner && validTarget
                && Protocol.ROLE_MEMBER.equalsIgnoreCase(target.role));
        btnRevokeAdmin.setEnabled(owner && validTarget
                && Protocol.ROLE_ADMIN.equalsIgnoreCase(target.role));
        btnKickMember.setEnabled(validTarget && (owner
                || (admin && Protocol.ROLE_MEMBER.equalsIgnoreCase(target.role))));
    }

    private void changeSelectedMemberRole(boolean grant) {
        MemberItem target = listMembers.getSelectedValue();
        if (target == null || currentRoomId <= 0) return;

        String command = grant ? Protocol.GRANT_ADMIN : Protocol.REVOKE_ADMIN;
        String action = grant ? "cấp quyền Admin cho " : "thu hồi quyền Admin của ";

        int answer = JOptionPane.showConfirmDialog(
                this,
                "Bạn có chắc muốn " + action + target.name + "?",
                "Xác nhận thay đổi quyền",
                JOptionPane.YES_NO_OPTION);

        if (answer == JOptionPane.YES_OPTION) {
            sendCommand(Protocol.join(command, currentRoomId, target.name));
        }
    }

    private void kickSelectedMember() {
        MemberItem target = listMembers.getSelectedValue();
        if (target == null || currentRoomId <= 0) return;

        int answer = JOptionPane.showConfirmDialog(
                this,
                "Bạn có chắc muốn xóa " + target.name + " khỏi phòng?",
                "Xóa thành viên",
                JOptionPane.YES_NO_OPTION);

        if (answer == JOptionPane.YES_OPTION) {
            sendCommand(Protocol.join(Protocol.KICK_MEMBER, currentRoomId, target.name));
        }
    }

    // =========================================================
    // FILE TRANSFER
    // =========================================================

    private void chooseFileToSend() {
        if (isPrivateChat) {
            if (currentTarget == null || currentTarget.trim().isEmpty()) {
                appendMessage("Vui lòng chọn người nhận trước khi gửi tệp.");
                return;
            }
            fileTransferController.chooseAndUpload("PRIVATE", currentTarget);
        } else {
            if (currentRoomId < 0) {
                appendMessage("Vui lòng mở phòng chat trước khi gửi tệp.");
                return;
            }
            fileTransferController.chooseAndUpload("GROUP", String.valueOf(currentRoomId));
        }
    }

    private void startVoiceRecording() {
        if (!connected || closed.get()) {
            appendMessage("Không thể ghi/gửi tin thoại khi mất kết nối với server.");
            return;
        }
        final String scope;
        final String target;
        if (isPrivateChat) {
            if (currentTarget == null || currentTarget.trim().isEmpty()) {
                appendMessage("Vui lòng chọn cuộc trò chuyện riêng trước khi ghi âm.");
                return;
            }
            scope = "PRIVATE";
            target = currentTarget;
        } else {
            if (currentRoomId <= 0) {
                appendMessage("Vui lòng mở một phòng chat trước khi ghi âm.");
                return;
            }
            scope = "GROUP";
            target = String.valueOf(currentRoomId);
        }
        VoiceRecorderDialog.showRecorder(this, path -> {
            if (!connected || closed.get()) {
                try { java.nio.file.Files.deleteIfExists(path); } catch (Exception ignored) { }
                appendMessage("Kết nối đã đóng; bản ghi thoại không được gửi.");
                return;
            }
            fileTransferController.uploadRecordedVoice(path, scope, target);
        });
    }

    private String fileIconFor(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".pdf")) return "📕";
        if (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")) return "🖼";
        if (lower.endsWith(".zip") || lower.endsWith(".rar")
                || lower.endsWith(".7z")) return "🗜";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "📘";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "📗";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "📙";
        return "📎";
    }

    private FileInfo parseFileContent(String content) {
        if (content == null || !content.startsWith("FILE:")) return null;
        String[] fields = content.split(":", 5);
        if (fields.length != 5) return null;
        try {
            long id = Long.parseLong(fields[1]);
            String fileName = new String(
                    Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8);
            long size = Long.parseLong(fields[3]);
            String sha = fields[4];
            if (id <= 0 || size < 0 || fileName.isBlank()
                    || !sha.matches("[0-9a-fA-F]{64}")) return null;
            return new FileInfo(id, fileName, size);
        } catch (Exception e) {
            return null;
        }
    }

    private void addFileMessage(
            long messageId, String kind, String key,
            String sender, String content) {
        addFileMessage(messageId, kind, key, sender, content, "");
    }

    private void addFileMessage(
            long messageId, String kind, String key,
            String sender, String content, String timestamp) {
        DisplayItem item = createFileDisplayItem(messageId, kind, key, sender, content, timestamp);
        if (item == null || !displayedFileIds.add(item.fileId)) return;
        addTrackedMessage(item);
    }

    private DisplayItem createFileDisplayItem(
            long messageId, String kind, String key,
            String sender, String content, String timestamp) {
        FileInfo info = parseFileContent(content);
        if (info == null) return null;
        DisplayItem item = new DisplayItem(
                messageId, kind, key, sender,
                info.name, Protocol.TYPE_FILE,
                false, false, false, "", timestamp);
        item.fileId = info.id;
        item.fileName = info.name;
        item.fileSize = info.size;
        if (fileTransferController != null) {
            fileTransferController.registerFile(info.id, info.name);
        }
        return item;
    }

    private static class FileInfo {
        final long id;
        final String name;
        final long size;
        FileInfo(long id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }
    }

    // =========================================================
    // READ RECEIPTS + TYPING INDICATOR
    // =========================================================

    private String currentConversationKind() {
        return isPrivateChat ? "P" : "G";
    }

    private String currentConversationKey() {
        return isPrivateChat ? currentTarget : String.valueOf(currentRoomId);
    }

    private boolean hasCurrentConversation() {
        return isPrivateChat
                ? currentTarget != null && !currentTarget.trim().isEmpty()
                : currentRoomId > 0;
    }

    private void requestCurrentConversationStates() {
        if (conversationStateController != null) conversationStateController.requestCurrentConversationStates();
    }

    private int getUnreadCount(String kind, String key) {
        return conversationStateController == null ? 0 : conversationStateController.getUnreadCount(kind, key);
    }

    private void refreshUnreadBadges() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshUnreadBadges);
            return;
        }
        if (listRooms != null) listRooms.repaint();
        if (listUsers != null) listUsers.repaint();
    }

    private void clearUnreadForConversation(String kind, String key) {
        if (conversationStateController != null) conversationStateController.clearUnreadForConversation(kind, key);
    }

    private void incrementUnreadForIncoming(String kind, String key, String sender, long messageId) {
        if (conversationStateController != null) {
            conversationStateController.incrementUnreadForIncoming(kind, key, sender, messageId);
        }
    }

    private void processUnreadBegin() {
        if (conversationStateController != null) conversationStateController.processUnreadBegin();
    }

    private void processUnreadCount(String[] p) {
        if (conversationStateController != null) conversationStateController.processUnreadCount(p);
    }

    private void processUnreadEnd() {
        if (conversationStateController != null) conversationStateController.processUnreadEnd();
    }

    private void processReadState(String[] p) {
        if (conversationStateController != null) conversationStateController.processReadState(p);
    }

    private void processTypingState(String[] p) {
        if (conversationStateController != null) conversationStateController.processTypingState(p);
    }

    private boolean isCurrentConversation(String kind, String key) {
        if (!hasCurrentConversation() || !currentConversationKind().equals(kind)) return false;
        return currentConversationKey().equals(key);
    }

    private void markCurrentConversationRead() {
        if (conversationStateController != null) conversationStateController.markCurrentConversationRead();
    }

    private void markReadUpTo(String kind, String key, long messageId) {
        if (conversationStateController != null) conversationStateController.markReadUpTo(kind, key, messageId);
    }

    /** Trả nhãn trạng thái chỉ cho tin nhắn do tài khoản hiện tại gửi. */
    private String readReceiptText(DisplayItem item) {
        return conversationStateController == null ? null : conversationStateController.readReceiptText(item);
    }

    private void onMessageDraftChanged() {
        updateSendButtonState();
        if (conversationStateController != null) conversationStateController.onMessageDraftChanged();
    }

    private void stopTypingNow() {
        if (conversationStateController != null) conversationStateController.stopTypingNow();
    }

    private void renderTypingStatus() {
        if (conversationStateController != null) conversationStateController.renderTypingStatus();
    }

    // =========================================================
    // SEND TEXT
    // =========================================================

    private void sendMessage() {
        if (messagePlaceholderActive) return;
        String text = txtMessage.getText().trim();
        if (text.isEmpty()) return;

        if (!connected) {
            appendMessage("Không thể gửi tin nhắn khi mất kết nối.");
            return;
        }

        if (text.length() > Protocol.MAX_TEXT_CHARS) {
            appendMessage("Tin nhắn tối đa " + Protocol.MAX_TEXT_CHARS + " ký tự.");
            return;
        }

        String encrypted = AESUtils.encrypt(text);
        if (encrypted == null || encrypted.isEmpty()) {
            appendMessage("Không thể mã hóa tin nhắn.");
            return;
        }

        String command;
        if (isPrivateChat) {
            if (currentTarget == null || currentTarget.isBlank()) {
                appendMessage("Vui lòng chọn người nhận.");
                return;
            }
            command = Protocol.join(Protocol.SEND_MSG_PRIVATE, currentTarget, encrypted);
        } else {
            if (currentRoomId <= 0) {
                appendMessage("Vui lòng mở phòng chat trước khi gửi tin nhắn.");
                return;
            }
            command = Protocol.join(Protocol.SEND_MSG_GROUP, currentRoomId, encrypted);
        }

        if (sendCommand(command)) {
            txtMessage.setText("");
            if (isPrivateChat) {
                // TCP giữ thứ tự lệnh: server xử lý tin nhắn trước yêu cầu làm mới danh sách.
                sendCommand(Protocol.join(Protocol.LIST_PRIVATE_CONTACTS));
                sendCommand(Protocol.join(Protocol.LIST_USERS));
            }
        }
    }

    // =========================================================
    // NETWORK READER
    // =========================================================

    private class IncomingReader implements Runnable {
        @Override
        public void run() {
            try {
                String line;
                while (!closed.get() && (line = in.readLine()) != null) {
                    String[] parts = Protocol.split(line);

                    if (parts.length > 0 && VideoCallController.isMediaPacket(parts[0])) {
                        VideoCallController controller = videoCallController;
                        if (controller != null) controller.handleMediaPacket(parts);
                        continue;
                    }
                    if (parts.length > 0 && GroupCallController.isGroupMediaPacket(parts[0])) {
                        GroupCallController controller = groupCallController;
                        if (controller != null) controller.handleMediaPacket(parts);
                        continue;
                    }

                    if (parts.length > 0
                            && fileTransferController.isDownloadPacket(parts[0])) {
                        // Chunk download xu ly tren reader thread.
                        fileTransferController.handleServerMessage(parts);
                        continue;
                    }

                    final String frame = line;
                    SwingUtilities.invokeLater(() -> processIncomingMessage(frame));
                }
                if (!closed.get()) handleDisconnect("Server đã đóng kết nối.");
            } catch (IOException e) {
                if (!closed.get()) {
                    handleDisconnect("Mất kết nối tới Server: " + e.getMessage());
                }
            }
        }
    }

    private void processIncomingMessage(String line) {
        if (line == null || line.isBlank()) return;
        String[] p = Protocol.split(line);
        if (p.length == 0) return;

        if (GroupCallController.isGroupControlPacket(p[0])) {
            if (groupCallController != null) groupCallController.handleServerMessage(p);
            return;
        }
        if (VideoCallController.isCallControlPacket(p[0])) {
            if (videoCallController != null) videoCallController.handleServerMessage(p);
            return;
        }

        if (Protocol.ADMIN_STATUS.equals(p[0])) {
            boolean isAdmin = p.length > 1 && "1".equals(p[1]);
            if (adminDashboardMenuItem != null) adminDashboardMenuItem.setEnabled(isAdmin);
            if (!isAdmin && adminDashboardFrame != null && adminDashboardFrame.isDisplayable()) {
                adminDashboardFrame.dispose();
                adminDashboardFrame = null;
            }
            return;
        }
        if (AdminDashboardFrame.isAdminPacket(p[0])) {
            if (adminDashboardFrame != null && adminDashboardFrame.isDisplayable()) {
                adminDashboardFrame.handleServerMessage(p);
            }
            return;
        }

        try {
            switch (p[0]) {
                case Protocol.AUTH_OK:
                    sendCommand(Protocol.join(Protocol.LIST_ROOMS));
                    sendCommand(Protocol.join(Protocol.LIST_USERS));
                    sendCommand(Protocol.join(Protocol.LIST_PRIVATE_CONTACTS));
                    sendCommand(Protocol.join(Protocol.GET_UNREAD_COUNTS));
                    requestMyProfile();
                    break;

                case Protocol.PROFILE_DATA:
                    processProfileData(p);
                    break;

                case Protocol.PROFILE_UPDATE_OK:
                    onProfileUpdateOk(p);
                    break;

                case Protocol.PROFILE_UPDATE_FAIL:
                    showProfileError(p, "Không thể cập nhật hồ sơ.");
                    break;

                case Protocol.USER_AVATAR_BEGIN:
                    beginAvatarDownload(p);
                    break;

                case Protocol.USER_AVATAR_CHUNK:
                    receiveAvatarChunk(p);
                    break;

                case Protocol.USER_AVATAR_END:
                    finishAvatarDownload(p);
                    break;

                case Protocol.USER_AVATAR_FAIL:
                    showProfileError(p, "Không thể tải ảnh đại diện.");
                    break;

                case Protocol.USER_AVATAR_UPLOAD_OK:
                    onAvatarUploadOk(p);
                    break;

                case Protocol.USER_AVATAR_UPLOAD_FAIL:
                    showProfileError(p, "Không thể lưu ảnh đại diện.");
                    break;

                case Protocol.USER_PROFILE_CHANGED:
                    processUserProfileChanged(p);
                    break;

                case Protocol.ROOM_LIST:
                    processRoomList(p);
                    break;

                case Protocol.ROOM_AVATAR_BEGIN:
                    beginRoomAvatar(p);
                    break;
                case Protocol.ROOM_AVATAR_CHUNK:
                    receiveRoomAvatarChunk(p);
                    break;
                case Protocol.ROOM_AVATAR_END:
                    finishRoomAvatar(p);
                    break;
                case Protocol.ROOM_AVATAR_FAIL:
                    appendMessage("[Ảnh phòng] " + (p.length > 1 ? decodeBase64Text(p[1]) : "Không thể tải ảnh."));
                    break;
                case Protocol.ROOM_AVATAR_UPLOAD_OK:
                    if (p.length > 1) {
                        try {
                            int roomId = Integer.parseInt(p[1]);
                            roomAvatarIconCache.keySet().removeIf(key -> key.startsWith(roomId + ":"));
                            if (listRooms != null) listRooms.repaint();
                        } catch (NumberFormatException ignored) { }
                    }
                    appendMessage("[Phòng] Đã cập nhật ảnh đại diện.");
                    break;
                case Protocol.ROOM_AVATAR_UPLOAD_FAIL:
                    JOptionPane.showMessageDialog(this,
                            p.length > 1 ? decodeBase64Text(p[1]) : "Không thể đổi ảnh đại diện phòng.");
                    break;
                case Protocol.ROOM_DISSOLVED:
                    processRoomDissolved(p);
                    break;
                case Protocol.ROOM_DISSOLVE_FAIL:
                    JOptionPane.showMessageDialog(this,
                            p.length > 1 ? decodeBase64Text(p[1]) : "Không thể giải tán phòng.",
                            "Giải tán phòng thất bại", JOptionPane.ERROR_MESSAGE);
                    break;

                case Protocol.USER_LIST:
                    processUserList(p);
                    break;

                case Protocol.USER_FOUND:
                    processUserFound(p);
                    break;

                case Protocol.USER_NOT_FOUND:
                    pendingPrivateLookup = null;
                    JOptionPane.showMessageDialog(this,
                            getPart(p, 1).isBlank() ? "Không tìm thấy người dùng." : getPart(p, 1),
                            "Tìm người dùng", JOptionPane.INFORMATION_MESSAGE);
                    break;

                case Protocol.ROOM_FOUND:
                    processRoomFound(p);
                    break;

                case Protocol.ROOM_NOT_FOUND:
                    pendingRoomSearchId = "";
                    pendingFoundRoom = null;
                    JOptionPane.showMessageDialog(this,
                            getPart(p, 1).isBlank() ? "Không tìm thấy phòng." : getPart(p, 1),
                            "Tìm phòng", JOptionPane.INFORMATION_MESSAGE);
                    break;

                case Protocol.PRIVATE_CONTACTS:
                    processPrivateContacts(p);
                    break;

                case Protocol.FILE_LIST:
                    processFileList(p);
                    break;

                case Protocol.ROOMS_CHANGED:
                    sendCommand(Protocol.join(Protocol.LIST_ROOMS));
                    break;

                case Protocol.MSG_GROUP:
                    processGroupMessage(p);
                    break;

                case Protocol.MSG_PRIVATE:
                    processPrivateMessage(p);
                    break;

                case Protocol.HISTORY_ITEM:
                    processHistoryItem(p);
                    break;

                case Protocol.HISTORY_BEGIN:
                    processHistoryBegin(p);
                    break;

                case Protocol.HISTORY_END:
                    processHistoryEnd(p);
                    break;

                case Protocol.READ_STATE:
                    processReadState(p);
                    break;

                case Protocol.TYPING_STATE:
                    processTypingState(p);
                    break;

                case Protocol.UNREAD_BEGIN:
                    processUnreadBegin();
                    break;

                case Protocol.UNREAD_COUNT:
                    processUnreadCount(p);
                    break;

                case Protocol.UNREAD_END:
                    processUnreadEnd();
                    break;

                case Protocol.SEARCH_BEGIN:
                case Protocol.SEARCH_ITEM:
                case Protocol.SEARCH_END:
                case Protocol.SEARCH_FAIL:
                    if (messageSearchController != null) {
                        messageSearchController.handlePacket(p);
                    }
                    break;

                case Protocol.CREATE_OK:
                    if (p.length >= 2) {
                        pendingOpenRoomId = Integer.parseInt(p[1]);
                        sendCommand(Protocol.join(Protocol.LIST_ROOMS));
                    }
                    break;

                case Protocol.CREATE_FAIL:
                    pendingOpenRoomId = -1;
                    appendMessage("[Tạo phòng thất bại] " + getPart(p, 1));
                    break;

                case Protocol.JOIN_OK:
                    processJoinOk(p);
                    break;

                case Protocol.JOIN_FAIL:
                    pendingJoinRoomId = -1;
                    pendingFoundRoom = null;
                    pendingRoomSearchId = "";
                    JOptionPane.showMessageDialog(this,
                            "Không thể tham gia phòng: " + getPart(p, 1),
                            "Tham gia phòng thất bại", JOptionPane.WARNING_MESSAGE);
                    break;

                case Protocol.LEAVE_OK:
                    processLeaveOk(p);
                    break;

                case Protocol.MEMBER_LIST:
                    processMemberList(p);
                    break;

                case Protocol.MEMBERS_CHANGED:
                    if (!isPrivateChat && currentRoomId > 0
                            && p.length > 1
                            && String.valueOf(currentRoomId).equals(p[1])) {
                        sendCommand(Protocol.join(Protocol.LIST_MEMBERS, currentRoomId));
                        sendCommand(Protocol.join(Protocol.LIST_ROOMS));
                    }
                    break;

                case Protocol.MSG_EDITED:
                    processEditedMessage(p);
                    break;

                case Protocol.MSG_RECALLED:
                    processRecalledMessage(p);
                    break;

                case Protocol.MSG_PINNED:
                    processPinnedMessage(p);
                    break;

                case Protocol.REACTIONS_UPDATED:
                    processReactionsUpdated(p);
                    break;

                case Protocol.KICKED:
                    processKicked(p);
                    break;

                case Protocol.CHANGE_PASSWORD_OK:
                    JOptionPane.showMessageDialog(
                            this,
                            getPart(p, 1).isEmpty() ? "Đổi mật khẩu thành công." : getPart(p, 1),
                            "Đổi mật khẩu",
                            JOptionPane.INFORMATION_MESSAGE);
                    break;

                case Protocol.CHANGE_PASSWORD_FAIL:
                    JOptionPane.showMessageDialog(
                            this,
                            getPart(p, 1).isEmpty() ? "Không thể đổi mật khẩu." : getPart(p, 1),
                            "Đổi mật khẩu thất bại",
                            JOptionPane.ERROR_MESSAGE);
                    break;

                case Protocol.SYS:
                    appendMessage("[Hệ thống] " + getPart(p, p.length > 2 ? 2 : 1));
                    break;

                case Protocol.UPLOAD_READY:
                case Protocol.UPLOAD_REJECT:
                case Protocol.UPLOAD_DONE:
                    fileTransferController.handleServerMessage(p);
                    if (Protocol.UPLOAD_DONE.equals(p[0])) {
                        requestCurrentFileList();
                    }
                    break;

                case Protocol.ERROR:
                    if (historyExportController != null) {
                        historyExportController.handleServerError(getPart(p, 1));
                    }
                    if (historyLoading) {
                        historyLoading = false;
                        pendingHistoryItems.clear();
                        updateHistoryButton();
                    }
                    appendMessage("[LỖI SERVER] " + getPart(p, 1));
                    break;

                default:
                    appendMessage("[SERVER] " + line);
                    break;
            }
        } catch (Exception e) {
            appendMessage("Lỗi xử lý dữ liệu từ Server: " + e.getMessage());
        }
    }

    private void processHistoryBegin(String[] p) {
        if (p.length < 4 || !historyLoading) return;
        String kind = p[1];
        String key = p[2];
        int page;
        try { page = Integer.parseInt(p[3]); }
        catch (NumberFormatException ex) { return; }

        if (!isHistoryTargetCurrent(kind, key)
                || !kind.equals(historyKind)
                || !sameHistoryKey(kind, historyKey, key)) return;
        historyLoadingPage = page;
        pendingHistoryItems.clear();
        updateHistoryButton();
    }

    private void processHistoryEnd(String[] p) {
        if (historyExportController != null && historyExportController.handleHistoryEnd(p)) {
            return;
        }
        if (p.length < 4 || !historyLoading) return;
        String kind = p[1];
        String key = p[2];
        int page;
        try { page = Integer.parseInt(p[3]); }
        catch (NumberFormatException ex) { return; }

        if (!isHistoryTargetCurrent(kind, key)
                || !kind.equals(historyKind)
                || !sameHistoryKey(kind, historyKey, key)
                || page != historyLoadingPage) return;

        boolean hasMore = p.length >= 5 && "1".equals(p[4]);
        List<DisplayItem> freshItems = new ArrayList<>();
        Set<Long> seenMessageIds = new HashSet<>();
        for (DisplayItem existing : transcript) {
            if (existing.id > 0 && existing.kind != null && existing.key != null
                    && existing.kind.equals(kind) && sameHistoryKey(kind, existing.key, key)) {
                seenMessageIds.add(existing.id);
            }
        }
        for (DisplayItem item : pendingHistoryItems) {
            if (item == null || item.id <= 0 || !seenMessageIds.add(item.id)) continue;
            if (item.fileId > 0 && displayedFileIds.contains(item.fileId)) continue;
            if (item.fileId > 0) displayedFileIds.add(item.fileId);
            freshItems.add(item);
        }
        pendingHistoryItems.clear();

        if (page == 0) {
            // Ghép lịch sử nhận được với tin live có thể tới trong lúc tải,
            // sau đó sắp xếp theo ID để bảo đảm đúng thứ tự thời gian.
            List<DisplayItem> notices = new ArrayList<>();
            List<DisplayItem> messages = new ArrayList<>();
            for (DisplayItem item : transcript) {
                if (item.id <= 0) {
                    if (item.notice == null || !item.notice.startsWith("Đang tải lịch sử")) {
                        notices.add(item);
                    }
                } else {
                    messages.add(item);
                }
            }
            messages.addAll(freshItems);
            messages.sort(Comparator.comparingLong(item -> item.id));
            transcript.clear();
            transcript.addAll(notices);
            transcript.addAll(messages);
            renderTranscript();
        } else {
            // Page 1, 2... chứa các tin cũ hơn; chèn lên đầu danh sách.
            transcript.addAll(0, freshItems);
            if (transcriptView != null) transcriptView.renderPreservingScroll(transcript);
        }

        historyCurrentPage = page;
        historyHasMore = hasMore;
        historyLoading = false;
        historyLoadingPage = -1;
        updateHistoryButton();

        if (page == 0) {
            if (transcript.isEmpty()) {
                transcript.add(new DisplayItem("Chưa có tin nhắn nào trong cuộc trò chuyện. Hãy bắt đầu nhé!"));
                renderTranscript();
            }
            markCurrentConversationRead();
            // MARK_READ được gửi trước GET_UNREAD_COUNTS trên cùng socket.
            sendCommand(Protocol.join(Protocol.GET_UNREAD_COUNTS));
        }
    }

    private boolean isHistoryTargetCurrent(String kind, String key) {
        if ("G".equals(kind)) {
            return !isPrivateChat && String.valueOf(currentRoomId).equals(key);
        }
        if ("P".equals(kind)) {
            return isPrivateChat && currentTarget != null
                    && currentTarget.equals(key);
        }
        return false;
    }

    private boolean sameHistoryKey(String kind, String a, String b) {
        if (a == null || b == null) return false;
        return "P".equals(kind) ? a.equals(b) : a.equals(b);
    }

    private void startInitialHistoryLoad(String kind, String key) {
        historyKind = kind;
        historyKey = key == null ? "" : key;
        historyCurrentPage = -1;
        historyLoadingPage = 0;
        historyHasMore = false;
        historyLoading = true;
        pendingHistoryItems.clear();
        updateHistoryButton();
    }

    private void loadOlderHistory() {
        if (!connected || closed.get() || historyLoading || !historyHasMore) return;
        String kind = isPrivateChat ? "P" : "G";
        String key = isPrivateChat ? currentTarget : String.valueOf(currentRoomId);
        if (!isHistoryTargetCurrent(kind, key)) return;

        int nextPage = historyCurrentPage + 1;
        historyKind = kind;
        historyKey = key;
        historyLoadingPage = nextPage;
        historyLoading = true;
        pendingHistoryItems.clear();
        updateHistoryButton();

        if ("G".equals(kind)) {
            sendCommand(Protocol.join(Protocol.LOAD_HISTORY_PAGED, Integer.parseInt(key), nextPage));
        } else {
            sendCommand(Protocol.join(Protocol.LOAD_PRIVATE_HISTORY, key, nextPage));
        }
    }

    private void resetHistoryPagination() {
        historyKind = "";
        historyKey = "";
        historyCurrentPage = -1;
        historyLoadingPage = -1;
        historyHasMore = false;
        historyLoading = false;
        pendingHistoryItems.clear();
        updateHistoryButton();
    }

    private void updateHistoryButton() {
        if (btnLoadOlderMessages == null) return;
        if (historyLoading) {
            btnLoadOlderMessages.setEnabled(false);
            btnLoadOlderMessages.setText(historyLoadingPage > 0
                    ? "Đang tải tin nhắn cũ hơn..." : "Đang tải lịch sử...");
        } else if (historyHasMore && connected && !closed.get()) {
            btnLoadOlderMessages.setEnabled(true);
            btnLoadOlderMessages.setText("↑ Tải tin nhắn cũ hơn");
        } else if (historyCurrentPage >= 0) {
            btnLoadOlderMessages.setEnabled(false);
            btnLoadOlderMessages.setText("Đã tải hết lịch sử");
        } else {
            btnLoadOlderMessages.setEnabled(false);
            btnLoadOlderMessages.setText("↑ Tải tin nhắn cũ hơn");
        }
    }

    private void processJoinOk(String[] p) {
        if (p.length < 3) {
            appendMessage("Phản hồi JOIN_OK không hợp lệ.");
            return;
        }

        int id = Integer.parseInt(p[1]);
        String name = p[2];
        String role = p.length > 3 ? p[3] : Protocol.ROLE_MEMBER;
        registeredRoomIds.add(id);

        RoomItem old = findRoomById(id);
        if (old == null && pendingFoundRoom != null && pendingFoundRoom.id == id) {
            old = pendingFoundRoom;
        }
        RoomItem updated = new RoomItem(id, name,
                old != null && old.locked,
                old == null ? "" : old.owner,
                old == null ? 0 : old.memberCount,
                role,
                old == null ? "" : old.publicId);
        updateRoomItem(updated);
        roomRoles.put(id, role);
        requestRoomAvatar(id);
        listRooms.repaint();

        if (pendingJoinRoomId == id) {
            pendingJoinRoomId = -1;
            pendingFoundRoom = null;
            pendingRoomSearchId = "";
            openGroupRoom(updated);
            sendCommand(Protocol.join(Protocol.LIST_ROOMS));
        } else if (currentRoomId == id && !isPrivateChat) {
            currentRoomRole = role;
            updateMemberActionVisibility();
        }
    }

    private void processLeaveOk(String[] p) {
        if (p.length < 2) return;
        int id;
        try { id = Integer.parseInt(p[1]); }
        catch (NumberFormatException e) { return; }

        registeredRoomIds.remove(id);
        roomRoles.remove(id);
        roomAvatarBytesCache.remove(id);
        roomAvatarIconCache.keySet().removeIf(key -> key.startsWith(id + ":"));
        requestedRoomAvatarIds.remove(id);
        if (currentRoomId == id && !isPrivateChat) {
            stopTypingNow();
            currentRoomId = -1;
            currentRoomName = "";
            currentRoomRole = "";
            clearConversationDisplay("Bạn đã rời phòng chat.");
            lblCurrentConversation.setText("Chưa chọn phòng chat");
            lblConversationInfo.setText(" ");
            memberListModel.clear();
        }
        sendCommand(Protocol.join(Protocol.LIST_ROOMS));
        updateMemberActionVisibility();
        appendMessage("Đã rời phòng thành công.");
    }

    private void processGroupMessage(String[] p) {
        if (p.length < 7) return;
        int roomId = Integer.parseInt(p[1]);
        long messageId = Long.parseLong(p[2]);
        String sender = p[3];
        String timestamp = p[4];
        String type = p[5];
        String content = p[6];
        String key = String.valueOf(roomId);

        roomLastActivityIds.merge(roomId, messageId, Math::max);
        reorderRoomListByActivity();

        if (!sender.equals(username)) {
            if (isCurrentConversation("G", key)) {
                markReadUpTo("G", key, messageId);
            } else {
                incrementUnreadForIncoming("G", key, sender, messageId);
            }
        }

        if (isPrivateChat || roomId != currentRoomId) return;

        if (Protocol.TYPE_FILE.equals(type)) {
            addFileMessage(messageId, "G", key, sender, content, timestamp);
            requestCurrentFileList();
            return;
        }

        addTrackedMessage(new DisplayItem(
                messageId, "G", key, sender,
                displayMessageContent(type, content), type,
                false, false, false, "", timestamp));
    }

    private void processPrivateMessage(String[] p) {
        if (p.length < 7) return;
        long messageId = Long.parseLong(p[1]);
        String from = p[2];
        String to = p[3];
        String timestamp = p[4];
        String type = p[5];
        String content = p[6];
        String peer = from.equals(username) ? to : from;
        String peerKey = peer;
        privateLastActivityIds.merge(peerKey, messageId, Math::max);

        boolean wasKnownContact = recentContactNames.contains(peerKey);
        recentContactNames.add(peerKey);
        refreshUserList();
        if (!wasKnownContact) {
            sendCommand(Protocol.join(Protocol.LIST_PRIVATE_CONTACTS));
            sendCommand(Protocol.join(Protocol.LIST_USERS));
        }

        boolean currentPeer = isCurrentConversation("P", peer);
        if (!from.equals(username)) {
            if (currentPeer) {
                markReadUpTo("P", peer, messageId);
            } else {
                incrementUnreadForIncoming("P", peer, from, messageId);
            }
        }

        if (currentPeer) {
            if (Protocol.TYPE_FILE.equals(type)) {
                addFileMessage(messageId, "P", peer, from, content, timestamp);
                requestCurrentFileList();
                return;
            }

            addTrackedMessage(new DisplayItem(
                    messageId, "P", peer, from,
                    displayMessageContent(type, content), type,
                    false, false, false, "", timestamp));
        } else if (!from.equals(username)) {
            appendMessage("Bạn có " + getUnreadCount("P", peer)
                    + " tin nhắn chưa đọc từ " + from + ".");
        }
    }

    private void processHistoryItem(String[] p) {
        if (historyExportController != null && historyExportController.handleHistoryItem(p)) {
            return;
        }
        if (p.length < 8 || !historyLoading) return;

        String kind = p[1];
        String key = p[2];
        long id = Long.parseLong(p[3]);
        String sender = p[4];
        String timestamp = p[5];
        String type = p[6];
        String content = p[7];

        if (!isHistoryTargetCurrent(kind, key)
                || !kind.equals(historyKind)
                || !sameHistoryKey(kind, historyKey, key)) {
            return;
        }

        boolean edited = p.length > 8 && "1".equals(p[8]);
        boolean deleted = p.length > 9 && "1".equals(p[9]);
        boolean pinned = p.length > 10 && "1".equals(p[10]);

        DisplayItem item;
        if (Protocol.TYPE_FILE.equals(type)) {
            item = createFileDisplayItem(id, kind, key, sender, content, timestamp);
        } else {
            item = new DisplayItem(id, kind, key, sender,
                    displayMessageContent(type, content), type,
                    edited, deleted, pinned, "", timestamp);
        }
        if (item != null) {
            item.edited = edited;
            item.deleted = deleted;
            item.pinned = pinned;
            pendingHistoryItems.add(item);
        }
    }

    private String displayMessageContent(String type, String content) {
        if (Protocol.TYPE_FILE.equals(type)) {
            FileInfo info = parseFileContent(content);
            return info == null ? "[Tệp đính kèm]" : "📎 " + info.name;
        }
        return safeText(AESUtils.decrypt(content));
    }

    // =========================================================
    // MESSAGE ACTION EVENTS
    // =========================================================

    private void processEditedMessage(String[] p) {
        if (p.length < 6) return;
        long id;
        try { id = Long.parseLong(p[1]); }
        catch (NumberFormatException e) { return; }

        String key = p[3];
        String kind = p[4];
        DisplayItem item = findDisplayedMessage(id);
        if (item == null || item.deleted) return;
        if (!item.kind.equals(kind) || !item.key.equals(key)) return;

        item.text = safeText(AESUtils.decrypt(p[5]));
        item.edited = true;
        renderTranscript();
    }

    private void processRecalledMessage(String[] p) {
        if (p.length < 5) return;
        long id;
        try { id = Long.parseLong(p[1]); }
        catch (NumberFormatException e) { return; }

        String key = p[3];
        String kind = p[4];
        DisplayItem item = findDisplayedMessage(id);
        if (item == null) return;
        if (!item.kind.equals(kind) || !item.key.equals(key)) return;

        item.deleted = true;
        item.text = "";
        item.reactions.clear();
        renderTranscript();
    }

    private void processPinnedMessage(String[] p) {
        if (p.length < 5) return;
        long id;
        int roomId;
        int pinned;
        try {
            id = Long.parseLong(p[1]);
            roomId = Integer.parseInt(p[2]);
            pinned = Integer.parseInt(p[4]);
        } catch (NumberFormatException e) { return; }

        DisplayItem item = findDisplayedMessage(id);
        if (item == null || !"G".equals(item.kind)) return;
        if (!String.valueOf(roomId).equals(item.key)) return;
        item.pinned = pinned == 1;
        renderTranscript();
    }

    private void processReactionsUpdated(String[] p) {
        if (p.length < 7) return;
        long id;
        int active;
        try {
            id = Long.parseLong(p[1]);
            active = Integer.parseInt(p[6]);
        } catch (NumberFormatException e) { return; }

        String kind = p[2];
        String key = p[3];
        String reactingUser = p[4];
        String reaction = p[5];
        boolean supported = false;
        for (String item : Protocol.REACTIONS) {
            if (item.equals(reaction)) { supported = true; break; }
        }
        if (!supported) return;

        DisplayItem message = findDisplayedMessage(id);
        if (message == null || message.deleted) return;
        if (!message.kind.equals(kind) || !message.key.equals(key)) return;

        Set<String> users = message.reactions.computeIfAbsent(
                reaction, k -> new HashSet<>());
        if (active == 1) users.add(reactingUser);
        else {
            users.remove(reactingUser);
            if (users.isEmpty()) message.reactions.remove(reaction);
        }
        renderTranscript();
    }

    private void processKicked(String[] p) {
        if (p.length < 2) return;
        int id;
        try { id = Integer.parseInt(p[1]); }
        catch (NumberFormatException e) { return; }

        registeredRoomIds.remove(id);
        roomRoles.remove(id);
        if (currentRoomId == id && !isPrivateChat) {
            stopTypingNow();
            currentRoomId = -1;
            currentRoomName = "";
            currentRoomRole = "";
            clearConversationDisplay("Bạn đã bị xóa khỏi phòng.");
            lblCurrentConversation.setText("Bạn đã bị xóa khỏi phòng");
            lblConversationInfo.setText(" ");
            memberListModel.clear();
        }
        sendCommand(Protocol.join(Protocol.LIST_ROOMS));
        updateMemberActionVisibility();
    }

    // =========================================================
    // EXPORT CHAT HISTORY
    // =========================================================

    private void searchCurrentHistory() {
        if (historyExportController != null && historyExportController.isInProgress()) {
            JOptionPane.showMessageDialog(this,
                    "Đang xuất lịch sử. Hãy đợi hoàn tất trước khi tìm kiếm.",
                    "Tìm tin nhắn", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String kind;
        String key;
        String title;
        if (isPrivateChat) {
            if (currentTarget == null || currentTarget.isBlank()) {
                JOptionPane.showMessageDialog(this,
                        "Hãy mở một cuộc trò chuyện riêng trước.",
                        "Tìm tin nhắn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            kind = "P";
            key = currentTarget;
            title = "Chat riêng với " + currentTarget;
        } else {
            if (currentRoomId <= 0) {
                JOptionPane.showMessageDialog(this,
                        "Hãy mở một phòng chat trước.",
                        "Tìm tin nhắn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            kind = "G";
            key = String.valueOf(currentRoomId);
            title = "Phòng " + currentRoomName;
        }
        messageSearchController.start(kind, key, title);
    }

    private void exportCurrentHistory() {
        if (messageSearchController != null && messageSearchController.isInProgress()) {
            JOptionPane.showMessageDialog(this,
                    "Đang tìm tin nhắn. Hãy đợi hoàn tất trước khi xuất lịch sử.",
                    "Xuất lịch sử", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!connected || closed.get()) {
            JOptionPane.showMessageDialog(this, "Bạn chưa kết nối tới Server.",
                    "Xuất lịch sử", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!transcript.isEmpty()
                && transcript.get(0).id <= 0
                && transcript.get(0).notice != null
                && transcript.get(0).notice.startsWith("Đang tải lịch sử")) {
            JOptionPane.showMessageDialog(this,
                    "Lịch sử đang tải. Vui lòng đợi tải xong rồi xuất lại.",
                    "Xuất lịch sử", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String kind;
        String key;
        String title;
        String filePrefix;
        if (isPrivateChat) {
            if (currentTarget == null || currentTarget.isBlank()) {
                JOptionPane.showMessageDialog(this,
                        "Hãy mở một cuộc trò chuyện riêng trước.",
                        "Xuất lịch sử", JOptionPane.WARNING_MESSAGE);
                return;
            }
            kind = "P";
            key = currentTarget;
            title = "Chat riêng - " + username + " và " + currentTarget;
            filePrefix = "chat_rieng_" + currentTarget;
        } else {
            if (currentRoomId <= 0) {
                JOptionPane.showMessageDialog(this,
                        "Hãy mở một phòng chat trước.",
                        "Xuất lịch sử", JOptionPane.WARNING_MESSAGE);
                return;
            }
            kind = "G";
            key = String.valueOf(currentRoomId);
            title = "Phòng " + currentRoomName + " (ID " + currentRoomId + ")";
            filePrefix = "lich_su_" + currentRoomName;
        }

        historyExportController.start(kind, key, title, filePrefix);
    }

    private void updateExportMenuState() {
        if (exportHistoryMenuItem != null) {
            exportHistoryMenuItem.setEnabled(connected && !closed.get()
                    && historyExportController != null
                    && !historyExportController.isInProgress());
        }
    }

    // =========================================================
    // DISPLAY TRANSCRIPT
    // =========================================================

    // Phần render timeline/bubble được quản lý bởi ChatTranscriptPanel.

    private void addTrackedMessage(DisplayItem item) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> addTrackedMessage(item));
            return;
        }

        if (item.id > 0) {
            if (!transcript.isEmpty()
                    && transcript.get(0).id <= 0
                    && transcript.get(0).notice != null
                    && transcript.get(0).notice.startsWith("Đang tải lịch sử")) {
                transcript.remove(0);
            }
            for (DisplayItem existing : transcript) {
                if (existing.id == item.id
                        && safeText(existing.kind).equals(safeText(item.kind))
                        && safeText(existing.key).equals(safeText(item.key))) {
                    return;
                }
            }
        }

        transcript.add(item);
        renderTranscript();
    }

    private void appendMessage(String message) {
        if (message == null) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendMessage(message));
            return;
        }
        transcript.add(new DisplayItem(message));
        renderTranscript();
    }

    private void clearConversationDisplay(String message) {
        resetHistoryPagination();
        transcript.clear();
        displayedFileIds.clear();
        if (sharedFileListModel != null) sharedFileListModel.clear();
        if (lblFileListStatus != null) {
            lblFileListStatus.setText("Đang tải danh sách tệp...");
        }
        transcript.add(new DisplayItem(message));
        renderTranscript();
    }

    private void onImagePreviewReady(long fileId, Path previewPath) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onImagePreviewReady(fileId, previewPath));
            return;
        }
        renderTranscript();
        VoiceMessageBubble.audioFileReady(fileId, previewPath);
    }

    private void renderTranscript() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::renderTranscript);
            return;
        }
        if (transcriptView == null) return;
        // Tải avatar của người gửi trong lịch sử/realtime; request chỉ gửi một lần mỗi user.
        for (DisplayItem item : transcript) {
            if (item != null && item.id > 0 && item.sender != null
                    && !item.sender.equals(username)) {
                requestUserAvatar(item.sender);
            }
        }
        transcriptView.render(transcript);
        refreshPinnedBanner();
    }

    private DisplayItem findDisplayedMessage(long id) {
        for (DisplayItem item : transcript) if (item.id == id) return item;
        return null;
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private long parseLongOrDefault(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void sortRoomsByLatestActivity(List<RoomItem> rooms) {
        rooms.sort((a, b) -> {
            long activityA = roomLastActivityIds.getOrDefault(a.id, 0L);
            long activityB = roomLastActivityIds.getOrDefault(b.id, 0L);
            int byActivity = Long.compare(activityB, activityA);
            if (byActivity != 0) return byActivity;
            // Khi chưa có tin nhắn, phòng tạo gần hơn (ID DB lớn hơn) đứng trước.
            return Integer.compare(b.id, a.id);
        });
    }

    /** Sắp xếp lại danh sách phòng ngay khi có tin mới, nhưng giữ nguyên phòng đang chọn. */
    private void reorderRoomListByActivity() {
        if (roomListModel == null || roomListModel.size() < 2) return;
        int selectedId = listRooms.getSelectedValue() == null
                ? -1 : listRooms.getSelectedValue().id;
        List<RoomItem> rooms = new ArrayList<>();
        for (int i = 0; i < roomListModel.size(); i++) {
            rooms.add(roomListModel.get(i));
        }
        sortRoomsByLatestActivity(rooms);

        boolean wasSuppressed = suppressRoomSelection;
        suppressRoomSelection = true;
        roomListModel.clear();
        for (RoomItem room : rooms) roomListModel.addElement(room);
        if (selectedId > 0) {
            RoomItem selected = findRoomById(selectedId);
            if (selected != null) listRooms.setSelectedValue(selected, true);
        }
        suppressRoomSelection = wasSuppressed;
        listRooms.repaint();
    }

    private void updateRoomItem(RoomItem updated) {
        for (int i = 0; i < roomListModel.size(); i++) {
            if (roomListModel.get(i).id == updated.id) {
                roomListModel.set(i, updated);
                return;
            }
        }
        roomListModel.addElement(updated);
    }

    private String roleVietnamese(String role) {
        if (Protocol.ROLE_OWNER.equalsIgnoreCase(role)) return "Owner / Chủ phòng";
        if (Protocol.ROLE_ADMIN.equalsIgnoreCase(role)) return "Admin / Quản trị viên";
        if (Protocol.ROLE_MEMBER.equalsIgnoreCase(role)) return "Thành viên";
        return "Chưa tham gia";
    }

    private String getPart(String[] p, int i) {
        return i >= 0 && i < p.length ? p[i] : "";
    }

    private String safeText(String value) { return value == null ? "" : value; }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void updateConnectionState() {
        boolean enabled = connected;
        if (btnSend != null) btnSend.setEnabled(enabled);
        if (btnAttachFile != null) btnAttachFile.setEnabled(enabled);
        if (btnCreateRoom != null) btnCreateRoom.setEnabled(enabled);
        if (btnJoinRoom != null) btnJoinRoom.setEnabled(enabled);
        if (btnLeaveRoom != null) btnLeaveRoom.setEnabled(enabled);
        if (txtMessage != null) txtMessage.setEnabled(enabled);
        updateExportMenuState();
        if (searchHistoryMenuItem != null) {
            searchHistoryMenuItem.setEnabled(enabled
                    && messageSearchController != null
                    && !messageSearchController.isInProgress());
        }
    }

    private void handleDisconnect(String message) {
        if (closed.get()) return;
        connected = false;
        if (videoCallController != null) videoCallController.onConnectionLost();
        if (groupCallController != null) groupCallController.onConnectionLost();
        SwingUtilities.invokeLater(() -> {
            if (conversationStateController != null) conversationStateController.onDisconnected();
            updateConnectionState();
            if (historyExportController != null) {
                historyExportController.failForDisconnect();
            }
            if (messageSearchController != null) {
                messageSearchController.failForDisconnect();
            }
            appendMessage(message);
        });
    }

    private void logout() {
        stopTypingNow();
        VoiceMessageBubble.stopAllPlayback();
        if (adminDashboardFrame != null && adminDashboardFrame.isDisplayable()) {
            adminDashboardFrame.dispose();
            adminDashboardFrame = null;
        }
        if (closed.get()) {
            dispose();
            return;
        }
        try { out.println(Protocol.join(Protocol.LOGOUT)); }
        catch (Exception ignored) { }
        closeConnection();
        dispose();
    }

    private void closeConnection() {
        stopTypingNow();
        if (videoCallController != null) videoCallController.onConnectionLost();
        if (groupCallController != null) groupCallController.onConnectionLost();
        if (conversationStateController != null) conversationStateController.dispose();
        if (!closed.compareAndSet(false, true)) return;
        connected = false;
        if (historyExportController != null) {
            historyExportController.cancelSilently();
        }
        if (messageSearchController != null) {
            messageSearchController.cancelSilently();
        }
        if (fileTransferController != null) {
            fileTransferController.close();
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Lỗi đóng Socket: " + e.getMessage());
        }
        updateConnectionState();
    }
}
