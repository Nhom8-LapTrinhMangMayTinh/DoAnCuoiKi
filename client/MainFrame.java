package client;

import common.CryptoUtil;
import common.Protocol;
import common.TextUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Man hinh chat chinh: phong, chat rieng, reply/reaction/sua/thu hoi/ghim, tim kiem, da doc, dang nhap,
 * truyen file co tien trinh + huy, thong bao, tu dong ket noi lai, xuat lich su, dashboard quan tri.
 */
public class MainFrame extends JFrame implements ChatClient.Listener {

    // ======================= du lieu =======================
    static class RoomItem {
        int id; String name; boolean locked; String owner; int count; String role = "";
        boolean joined() { return !role.isEmpty(); }
    }
    static class UserItem { String name; boolean online; long lastSeen; }
    static class MemberItem { String name, role; boolean online; }

    private static final Color C_ME = new Color(0xDBEAFE), C_OTHER = Color.WHITE, C_FILE = new Color(0xFEF3C7);
    private static final Font MSG_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final int BUBBLE_MAX_W = 340;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm dd/MM");

    final ChatClient client;
    final String me;
    private final String serverInfo;
    private final Properties cfg;

    private final DefaultListModel<RoomItem> roomModel = new DefaultListModel<>();
    private final JList<RoomItem> roomList = new JList<>(roomModel);
    private final DefaultListModel<UserItem> userModel = new DefaultListModel<>();
    private final JList<UserItem> userList = new JList<>(userModel);
    private final DefaultListModel<MemberItem> memberModel = new DefaultListModel<>();
    private final JList<MemberItem> memberList = new JList<>(memberModel);

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JLabel notJoinedLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel titleLabel = new JLabel(" ");
    private final JLabel subLabel = new JLabel(" ");
    private final JLabel typingLabel = new JLabel(" ");
    private final JLabel banner = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel pinBanner = new JLabel(" ");
    private final JTextField input = UiKit.styleField(new JTextField());
    private final JButton sendBtn = UiKit.primary("Gửi");
    private final JButton fileBtn = UiKit.ghost("Đính kèm");
    private final JLabel statusBar = new JLabel(" ");
    private final JLabel rttLabel = new JLabel(" ");
    private final JButton createBtn = UiKit.ghost("Tạo");
    private final JButton joinBtn = UiKit.ghost("Vào");
    private final JButton leaveBtn = UiKit.ghost("Rời");
    private final JButton grantBtn = UiKit.ghost("Cấp Admin");
    private final JButton revokeBtn = UiKit.ghost("Thu hồi Admin");
    private final JButton kickBtn = UiKit.ghost("Xóa khỏi phòng");
    private final JPanel memberPanel = new JPanel(new BorderLayout(0, 6));
    private final JPanel transferPanel = new JPanel();
    private final JPanel contextStrip = new JPanel(new BorderLayout());
    private final JLabel contextLabel = new JLabel();
    private final JLabel myAvatarLabel = new JLabel();
    private final JLabel myNameLabel = new JLabel();

    private final Map<String, Conversation> convs = new HashMap<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final Map<String, List<ClientMsg>> histBuf = new HashMap<>();
    private final Map<String, String> displayNames = new HashMap<>();       // username(lower) -> ten hien thi
    private final Map<String, Integer> avatarVers = new HashMap<>();
    private final Map<String, BufferedImage> avatarImgs = new HashMap<>();
    private final Set<String> avatarRequested = new HashSet<>();
    private final Map<String, String> pendingAck = new LinkedHashMap<>();   // clientId -> frame chua duoc server xac nhan
    private final Map<Long, TransferRow> transfers = new HashMap<>();
    private Conversation cur;
    private boolean suppressSel = false;
    private int pendingSelectRoom = -1;
    private boolean closing = false;
    private boolean notificationsOn = true;
    private int maxFileMB = 1024;
    private Set<String> blockedExt = new HashSet<>(), allowedExt = new HashSet<>();
    private boolean isAdmin = false;
    private String myDisplay = "";
    private ClientMsg replyTarget, editTarget;
    private long lastTypingSent = 0;
    private AdminFrame adminFrame;
    private SearchDialog searchDialog;
    private JMenu adminMenu;
    private final ScheduledExecutorService bg = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "ui-bg"); t.setDaemon(true); return t; });
    private final Random rnd = new Random();

    // ======================= 1 cuoc tro chuyen =======================
    class Conversation {
        final String key; final boolean group; final int roomId; final String peer; final String title;
        final String scope;     // "G" / "P"
        final String skey;      // roomID hoac ten nguoi kia - khoa gui len server
        final JPanel messages = new JPanel();
        final JLabel topLabel = new JLabel(" ", SwingConstants.CENTER);
        final JLabel seenLabel = new JLabel(" ", SwingConstants.RIGHT);
        final JScrollPane scroll;
        final Set<Long> seen = new HashSet<>();
        final Map<Long, ClientMsg> msgs = new HashMap<>();
        final Map<Long, JPanel> rows = new HashMap<>();
        final List<ClientMsg> pendingLive = new ArrayList<>();
        final Map<String, Long> reads = new HashMap<>();     // user(lower) -> moc da doc
        final Map<String, Long> typing = new HashMap<>();    // user -> het han (ms)
        final List<ClientMsg> pins = new ArrayList<>();
        int nextPage = 0; boolean hasMore = true, loading = false, initialDone = false, historyRequested = false;
        long lastMsgId = 0, lastReadSent = 0, jumpTarget = 0;
        boolean resync = false;
        List<ClientMsg> exportBuf; File exportFile; boolean exportCsv; int exportPage;

        Conversation(String key, boolean group, int roomId, String peer, String title) {
            this.key = key; this.group = group; this.roomId = roomId; this.peer = peer; this.title = title;
            this.scope = group ? "G" : "P";
            this.skey = group ? String.valueOf(roomId) : peer;
            messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
            messages.setOpaque(false);
            topLabel.setForeground(UiKit.MUTED);
            topLabel.setFont(UiKit.FONT_SMALL);
            topLabel.setBorder(new EmptyBorder(6, 0, 6, 0));
            seenLabel.setForeground(UiKit.MUTED);
            seenLabel.setFont(UiKit.FONT_SMALL);
            seenLabel.setBorder(new EmptyBorder(0, 0, 4, 14));
            JPanel north = new JPanel(new BorderLayout());
            north.setOpaque(false);
            north.add(topLabel, BorderLayout.NORTH);
            north.add(messages, BorderLayout.CENTER);
            north.add(seenLabel, BorderLayout.SOUTH);
            JPanel content = new JPanel(new BorderLayout());
            content.setBackground(UiKit.BG);
            content.add(north, BorderLayout.NORTH);
            scroll = new JScrollPane(content);
            scroll.setBorder(null);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            // PHAN TRANG: keo thanh cuon len dau -> tai them 50 tin cu
            scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
                if (scroll.getVerticalScrollBar().getValue() == 0) loadMore();
            });
            scroll.addMouseWheelListener(e -> {
                if (e.getWheelRotation() < 0 && scroll.getVerticalScrollBar().getValue() == 0) loadMore();
            });
        }

        void requestPage(int page) {
            if (group) client.trySend(Protocol.join(Protocol.LOAD_HISTORY_PAGED, roomId, page));
            else client.trySend(Protocol.join(Protocol.LOAD_PRIVATE_HISTORY, peer, page));
        }

        void loadMore() {
            if (!initialDone || !hasMore || loading || exportBuf != null) return;
            loading = true;
            topLabel.setText("Đang tải thêm 50 tin cũ ...");
            requestPage(nextPage);
        }
    }

    // ======================= dung giao dien =======================
    public MainFrame(ChatClient client, String me, String host, int port) {
        this(client, me, host, port, new Properties());
    }

    public MainFrame(ChatClient client, String me, String host, int port, Properties cfg) {
        super("GroupChat - " + me + " @ " + host + ":" + port);
        this.client = client;
        this.me = me;
        this.cfg = cfg;
        this.serverInfo = host + ":" + port;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { logoutAndExit(); }
            @Override public void windowGainedFocus(WindowEvent e) { markReadIfNeeded(cur); }
            @Override public void windowActivated(WindowEvent e) { markReadIfNeeded(cur); setTitle(baseTitle()); }
        });
        setJMenuBar(buildMenu());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiKit.BG);
        root.add(buildLeft(), BorderLayout.WEST);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildRight(), BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(UiKit.SURFACE);
        south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiKit.BORDER));
        statusBar.setBorder(new EmptyBorder(4, 10, 4, 8));
        statusBar.setFont(UiKit.FONT_SMALL);
        rttLabel.setBorder(new EmptyBorder(4, 8, 4, 10));
        rttLabel.setFont(UiKit.FONT_SMALL);
        rttLabel.setForeground(UiKit.MUTED);
        status("Đã kết nối " + serverInfo + "  |  Kênh TLS + AES-128", false);
        south.add(statusBar, BorderLayout.CENTER);
        south.add(rttLabel, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1180, 740);
        setMinimumSize(new Dimension(960, 560));
        setLocationRelativeTo(null);
        showEmpty("Chọn một phòng ở bên trái hoặc chọn một người dùng để chat riêng.<br><br>"
                + "<font size=3 color='#64748B'>Ctrl+F tìm kiếm • Ctrl+O gửi file • F1 xem phím tắt</font>");
        installShortcuts();
        updateButtons();
        refreshMyHeader();
        client.trySend(Protocol.LIST_ROOMS);
        client.trySend(Protocol.LIST_USERS);
        new javax.swing.Timer(1000, e -> tickTyping()).start();
        bg.scheduleAtFixedRate(() -> {
            if (!client.isConnected()) return;
            double ms = client.ping(3000);
            SwingUtilities.invokeLater(() -> rttLabel.setText(ms < 0 ? "RTT: quá hạn" : String.format(Locale.ROOT, "RTT %.0f ms", ms)));
        }, 3, 10, TimeUnit.SECONDS);
    }

    private String baseTitle() { return "GroupChat - " + me + " @ " + serverInfo; }

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu acc = new JMenu("Tài khoản");
        acc.add(item("Hồ sơ & ảnh đại diện...", null, e -> profileDialog()));
        acc.add(item("Đổi mật khẩu...", null, e -> passwordDialog()));
        JCheckBoxMenuItem noti = new JCheckBoxMenuItem("Thông báo tin nhắn mới", true);
        noti.addActionListener(e -> notificationsOn = noti.isSelected());
        acc.add(noti);
        acc.addSeparator();
        acc.add(item("Đăng xuất", null, e -> logoutAndExit()));
        JMenu conv = new JMenu("Cuộc trò chuyện");
        conv.add(item("Tìm kiếm tin nhắn", "ctrl F", e -> openSearch()));
        conv.add(item("Tin nhắn đã ghim", "ctrl P", e -> openPins()));
        conv.add(item("Gửi file / ảnh", "ctrl O", e -> sendFile()));
        conv.add(item("Xuất lịch sử (TXT/CSV)", "ctrl E", e -> exportHistory()));
        conv.add(item("Tạo phòng mới", "ctrl N", e -> createRoomDialog()));
        adminMenu = new JMenu("Quản trị");
        adminMenu.add(item("Dashboard Server", "ctrl shift D", e -> openAdmin()));
        adminMenu.setVisible(false);
        JMenu help = new JMenu("Trợ giúp");
        help.add(item("Phím tắt", "F1", e -> showHelp()));
        bar.add(acc); bar.add(conv); bar.add(adminMenu); bar.add(help);
        return bar;
    }

    private JMenuItem item(String text, String accel, ActionListener a) {
        JMenuItem i = new JMenuItem(text);
        if (accel != null) i.setAccelerator(KeyStroke.getKeyStroke(accel));
        i.addActionListener(a);
        return i;
    }

    private void installShortcuts() {
        JRootPane rp = getRootPane();
        InputMap im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke("F1"), "help");
        rp.getActionMap().put("help", new AbstractAction() { public void actionPerformed(ActionEvent e) { showHelp(); } });
        // o nhap: Esc huy tra loi/sua, Up sua tin cuoi, PageUp/PageDown cuon khung chat
        input.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancelCtx");
        input.getActionMap().put("cancelCtx", new AbstractAction() { public void actionPerformed(ActionEvent e) { clearContext(); } });
        input.getInputMap().put(KeyStroke.getKeyStroke("UP"), "editLast");
        input.getActionMap().put("editLast", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { if (input.getText().isEmpty()) editLastOwn(); }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke("PAGE_UP"), "pgUp");
        input.getActionMap().put("pgUp", new AbstractAction() { public void actionPerformed(ActionEvent e) { scrollChat(-1); } });
        input.getInputMap().put(KeyStroke.getKeyStroke("PAGE_DOWN"), "pgDn");
        input.getActionMap().put("pgDn", new AbstractAction() { public void actionPerformed(ActionEvent e) { scrollChat(1); } });
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { typingPing(); }
            public void removeUpdate(DocumentEvent e) { }
            public void changedUpdate(DocumentEvent e) { }
        });
    }

    private void scrollChat(int dir) {
        if (cur == null) return;
        JScrollBar b = cur.scroll.getVerticalScrollBar();
        b.setValue(b.getValue() + dir * (b.getVisibleAmount() - 40));
    }

    void showHelp() {
        JOptionPane.showMessageDialog(this,
                "<html><b>Phím tắt</b><br><table cellpadding=2>"
                + "<tr><td>Ctrl+F</td><td>Tìm kiếm tin nhắn (từ khóa, người gửi)</td></tr>"
                + "<tr><td>Ctrl+O</td><td>Gửi file / ảnh</td></tr>"
                + "<tr><td>Ctrl+P</td><td>Xem tin đã ghim</td></tr>"
                + "<tr><td>Ctrl+E</td><td>Xuất lịch sử ra TXT/CSV</td></tr>"
                + "<tr><td>Ctrl+N</td><td>Tạo phòng mới</td></tr>"
                + "<tr><td>Ctrl+Shift+D</td><td>Dashboard quản trị (nếu là admin)</td></tr>"
                + "<tr><td>Enter</td><td>Gửi tin nhắn</td></tr>"
                + "<tr><td>↑ (ô nhập trống)</td><td>Sửa tin nhắn cuối của bạn</td></tr>"
                + "<tr><td>Esc</td><td>Hủy trả lời / hủy sửa</td></tr>"
                + "<tr><td>PageUp / PageDown</td><td>Cuộn khung chat (cuộn lên đầu tự tải thêm lịch sử)</td></tr>"
                + "<tr><td>Chuột phải</td><td>Menu tin nhắn: trả lời, cảm xúc, sửa, thu hồi, ghim</td></tr>"
                + "<tr><td>F1</td><td>Bảng này</td></tr></table></html>", "Phím tắt", JOptionPane.INFORMATION_MESSAGE);
    }

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setPreferredSize(new Dimension(300, 0));
        left.setBorder(new EmptyBorder(8, 8, 8, 4));
        left.setOpaque(false);

        JPanel me = UiKit.card(new BorderLayout(10, 0));
        me.setBorder(new EmptyBorder(8, 10, 8, 10));
        myNameLabel.setFont(UiKit.FONT_BOLD);
        me.add(myAvatarLabel, BorderLayout.WEST);
        me.add(myNameLabel, BorderLayout.CENTER);
        JButton prof = UiKit.ghost("Hồ sơ");
        prof.addActionListener(e -> profileDialog());
        me.add(prof, BorderLayout.EAST);
        left.add(me, BorderLayout.NORTH);

        JPanel lists = new JPanel(new GridLayout(2, 1, 0, 8));
        lists.setOpaque(false);

        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.setFixedCellHeight(-1);
        roomList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, f);
                RoomItem r = (RoomItem) v;
                int un = unread.getOrDefault("G:" + r.id, 0);
                String fg = sel ? "#FFFFFF" : "#0F172A", sub = sel ? "#DBEAFE" : "#64748B";
                String dot = r.joined() ? "<font color='" + (sel ? "#BBF7D0" : "#16A34A") + "'>●</font> " : "<font color='#94A3B8'>○</font> ";
                c.setIcon(UiKit.avatar(r.name, null, 34));
                c.setIconTextGap(10);
                c.setText("<html>" + dot + "<b><font color='" + fg + "'>" + UiKit.esc(r.name) + "</font></b>"
                        + (un > 0 ? " <font color='" + (sel ? "#FDE68A" : "#DC2626") + "'><b>(" + un + ")</b></font>" : "")
                        + "<br><font size=2 color='" + sub + "'>" + (r.locked ? "có mật khẩu • " : "") + r.count + " thành viên"
                        + (r.joined() ? " • " + roleVi(r.role) : "") + "</font></html>");
                c.setBorder(new EmptyBorder(5, 8, 5, 6));
                c.setOpaque(true);
                c.setBackground(sel ? UiKit.PRIMARY : Color.WHITE);
                return c;
            }
        });
        roomList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !suppressSel) onRoomSelected(); });
        roomList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { RoomItem r = roomList.getSelectedValue(); if (r != null && !r.joined()) joinSelected(); }
            }
        });
        JPanel roomBtns = new JPanel(new GridLayout(1, 3, 6, 0));
        roomBtns.setOpaque(false);
        roomBtns.add(createBtn); roomBtns.add(joinBtn); roomBtns.add(leaveBtn);
        JPanel roomPanel = UiKit.card(new BorderLayout(0, 6));
        roomPanel.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(6, 6, 6, 6),
                BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(), "Phòng chat")));
        JScrollPane rs = new JScrollPane(roomList);
        rs.setBorder(null);
        roomPanel.add(rs, BorderLayout.CENTER);
        roomPanel.add(roomBtns, BorderLayout.SOUTH);

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, f);
                UserItem u = (UserItem) v;
                int un = unread.getOrDefault("P:" + u.name.toLowerCase(), 0);
                String fg = sel ? "#FFFFFF" : "#0F172A";
                c.setIcon(UiKit.avatar(dn(u.name), avatarImgs.get(u.name.toLowerCase()), 30));
                c.setIconTextGap(10);
                String status = u.online ? "<font color='" + (sel ? "#BBF7D0" : "#16A34A") + "' size=2>đang online</font>"
                        : "<font color='" + (sel ? "#DBEAFE" : "#94A3B8") + "' size=2>" + lastSeenText(u.lastSeen) + "</font>";
                c.setText("<html><b><font color='" + fg + "'>" + UiKit.esc(dn(u.name)) + "</font></b>"
                        + (un > 0 ? " <font color='" + (sel ? "#FDE68A" : "#DC2626") + "'><b>(" + un + " tin mới)</b></font>" : "")
                        + "<br>" + status + "</html>");
                c.setBorder(new EmptyBorder(4, 8, 4, 6));
                c.setOpaque(true);
                c.setBackground(sel ? UiKit.PRIMARY : Color.WHITE);
                return c;
            }
        });
        userList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !suppressSel) onUserSelected(); });
        JPanel userPanel = UiKit.card(new BorderLayout());
        userPanel.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(6, 6, 6, 6),
                BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(), "Người dùng (chọn để chat riêng)")));
        JScrollPane us = new JScrollPane(userList);
        us.setBorder(null);
        userPanel.add(us, BorderLayout.CENTER);

        lists.add(roomPanel);
        lists.add(userPanel);
        left.add(lists, BorderLayout.CENTER);
        createBtn.addActionListener(e -> createRoomDialog());
        joinBtn.addActionListener(e -> joinSelected());
        leaveBtn.addActionListener(e -> leaveSelected());
        return left;
    }

    private static String lastSeenText(long ts) {
        if (ts <= 0) return "offline";
        long mins = Math.max(0, (System.currentTimeMillis() - ts) / 60000);
        if (mins < 1) return "vừa offline";
        if (mins < 60) return "offline " + mins + " phút trước";
        if (mins < 1440) return "offline " + (mins / 60) + " giờ trước";
        return "offline " + (mins / 1440) + " ngày trước";
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(new EmptyBorder(8, 4, 8, 4));
        center.setOpaque(false);

        JPanel head = UiKit.card(new BorderLayout(8, 0));
        head.setBorder(new EmptyBorder(8, 14, 8, 10));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 17f));
        subLabel.setForeground(UiKit.MUTED);
        subLabel.setFont(UiKit.FONT_SMALL);
        typingLabel.setForeground(UiKit.PRIMARY);
        typingLabel.setFont(UiKit.FONT_SMALL.deriveFont(Font.ITALIC));
        JPanel t = new JPanel(new GridLayout(3, 1));
        t.setOpaque(false);
        t.add(titleLabel); t.add(subLabel); t.add(typingLabel);
        head.add(t, BorderLayout.CENTER);
        JPanel hb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        hb.setOpaque(false);
        JButton sb = UiKit.ghost("Tìm"), pb = UiKit.ghost("Ghim"), eb = UiKit.ghost("Xuất");
        sb.setToolTipText("Tìm kiếm trong cuộc trò chuyện (Ctrl+F)");
        pb.setToolTipText("Tin nhắn đã ghim (Ctrl+P)");
        eb.setToolTipText("Xuất lịch sử ra TXT/CSV (Ctrl+E)");
        sb.addActionListener(e -> openSearch());
        pb.addActionListener(e -> openPins());
        eb.addActionListener(e -> exportHistory());
        hb.add(sb); hb.add(pb); hb.add(eb);
        head.add(hb, BorderLayout.EAST);

        banner.setOpaque(true);
        banner.setBackground(new Color(0xFEF3C7));
        banner.setForeground(new Color(0x92400E));
        banner.setBorder(new EmptyBorder(5, 8, 5, 8));
        banner.setVisible(false);
        pinBanner.setOpaque(true);
        pinBanner.setBackground(new Color(0xEFF6FF));
        pinBanner.setForeground(UiKit.PRIMARY_DARK);
        pinBanner.setBorder(new EmptyBorder(4, 12, 4, 8));
        pinBanner.setFont(UiKit.FONT_SMALL);
        pinBanner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pinBanner.setVisible(false);
        pinBanner.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { openPins(); } });
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.add(head);
        top.add(banner);
        top.add(pinBanner);
        center.add(top, BorderLayout.NORTH);

        notJoinedLabel.setFont(notJoinedLabel.getFont().deriveFont(15f));
        notJoinedLabel.setForeground(UiKit.MUTED);
        JPanel empty = new JPanel(new BorderLayout());
        empty.setBackground(UiKit.BG);
        empty.add(notJoinedLabel, BorderLayout.CENTER);
        cardPanel.add(empty, "EMPTY");
        cardPanel.setBorder(BorderFactory.createLineBorder(UiKit.BORDER));
        center.add(cardPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setOpaque(false);
        transferPanel.setLayout(new BoxLayout(transferPanel, BoxLayout.Y_AXIS));
        transferPanel.setOpaque(false);
        bottom.add(transferPanel);
        contextStrip.setBackground(new Color(0xEFF6FF));
        contextStrip.setBorder(new EmptyBorder(4, 12, 4, 6));
        contextLabel.setFont(UiKit.FONT_SMALL);
        JButton x = new JButton("✕");
        x.setBorderPainted(false); x.setContentAreaFilled(false); x.setFocusPainted(false);
        x.addActionListener(e -> clearContext());
        contextStrip.add(contextLabel, BorderLayout.CENTER);
        contextStrip.add(x, BorderLayout.EAST);
        contextStrip.setVisible(false);
        contextStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(contextStrip);
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 0, 0));
        row.add(fileBtn, BorderLayout.WEST);
        row.add(input, BorderLayout.CENTER);
        row.add(sendBtn, BorderLayout.EAST);
        bottom.add(row);
        center.add(bottom, BorderLayout.SOUTH);

        input.addActionListener(e -> sendText());
        sendBtn.addActionListener(e -> sendText());
        fileBtn.addActionListener(e -> sendFile());
        return center;
    }

    private JComponent buildRight() {
        memberPanel.setPreferredSize(new Dimension(210, 0));
        memberPanel.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(8, 4, 8, 8),
                BorderFactory.createTitledBorder("Thành viên phòng")));
        memberList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, f);
                MemberItem m = (MemberItem) v;
                c.setIcon(UiKit.avatar(dn(m.name), avatarImgs.get(m.name.toLowerCase()), 24));
                c.setIconTextGap(8);
                c.setText("<html><font color='" + (m.online ? "#16A34A" : "#94A3B8") + "'>●</font> " + UiKit.esc(dn(m.name))
                        + (m.role.equals("MEMBER") ? "" : " <font color='#EA580C' size=2>[" + m.role + "]</font>") + "</html>");
                return c;
            }
        });
        memberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        memberList.addListSelectionListener(e -> updateButtons());
        JPanel btns = new JPanel(new GridLayout(3, 1, 0, 4));
        btns.add(grantBtn); btns.add(revokeBtn); btns.add(kickBtn);
        memberPanel.add(new JScrollPane(memberList), BorderLayout.CENTER);
        memberPanel.add(btns, BorderLayout.SOUTH);
        grantBtn.addActionListener(e -> memberAction(Protocol.GRANT_ADMIN));
        revokeBtn.addActionListener(e -> memberAction(Protocol.REVOKE_ADMIN));
        kickBtn.addActionListener(e -> memberAction(Protocol.KICK_MEMBER));
        memberPanel.setVisible(false);
        memberPanel.setOpaque(false);
        return memberPanel;
    }

    // ======================= tien ich =======================
    private static String esc(String s) { return UiKit.esc(s); }

    private static String roleVi(String r) {
        switch (r) {
            case "OWNER": return "Chủ phòng";
            case "ADMIN": return "Admin";
            default: return "Member";
        }
    }

    private static String sizeStr(long b) { return TextUtil.sizeStr(b); }

    String dn(String user) {
        String d = displayNames.get(user.toLowerCase());
        return d == null || d.isEmpty() ? user : d;
    }

    private void status(String msg, boolean error) {
        statusBar.setText(msg);
        statusBar.setForeground(error ? UiKit.DANGER : UiKit.MUTED);
    }

    private RoomItem roomById(int id) {
        for (int i = 0; i < roomModel.size(); i++) if (roomModel.get(i).id == id) return roomModel.get(i);
        return null;
    }

    private void refreshMyHeader() {
        String name = myDisplay.isEmpty() ? me : myDisplay;
        myNameLabel.setText("<html><b>" + esc(name) + "</b><br><font size=2 color='#64748B'>@" + esc(me) + (isAdmin ? " • quản trị" : "") + "</font></html>");
        myAvatarLabel.setIcon(UiKit.avatar(name, avatarImgs.get(me.toLowerCase()), 40));
    }

    private void showEmpty(String text) {
        cur = null;
        notJoinedLabel.setText("<html><div style='text-align:center;width:460px'>" + text + "</div></html>");
        cards.show(cardPanel, "EMPTY");
        titleLabel.setText(" ");
        subLabel.setText(" ");
        typingLabel.setText(" ");
        pinBanner.setVisible(false);
        memberPanel.setVisible(false);
        clearContext();
        updateButtons();
    }

    private void updateButtons() {
        RoomItem sel = roomList.getSelectedValue();
        joinBtn.setEnabled(sel != null && !sel.joined());
        leaveBtn.setEnabled(sel != null && sel.joined());
        boolean chatting = cur != null;
        input.setEnabled(chatting);
        sendBtn.setEnabled(chatting);
        fileBtn.setEnabled(chatting);
        String myRole = (cur != null && cur.group && roomById(cur.roomId) != null) ? roomById(cur.roomId).role : "";
        MemberItem m = memberList.getSelectedValue();
        boolean hasTarget = m != null && !m.name.equalsIgnoreCase(me) && !m.role.equals("OWNER");
        grantBtn.setEnabled(hasTarget && myRole.equals("OWNER") && m.role.equals("MEMBER"));
        revokeBtn.setEnabled(hasTarget && myRole.equals("OWNER") && m.role.equals("ADMIN"));
        kickBtn.setEnabled(hasTarget && (myRole.equals("OWNER") || (myRole.equals("ADMIN") && m.role.equals("MEMBER"))));
    }

    private boolean iAmMod(Conversation c) {
        if (!c.group) return false;
        RoomItem r = roomById(c.roomId);
        return r != null && (r.role.equals("OWNER") || r.role.equals("ADMIN"));
    }

    // ======================= chon phong / user =======================
    private void onRoomSelected() {
        RoomItem r = roomList.getSelectedValue();
        if (r == null) { updateButtons(); return; }
        suppressSel = true; userList.clearSelection(); suppressSel = false;
        if (r.joined()) {
            openConversation(getConversation("G:" + r.id, true, r.id, null, r.name));
        } else {
            showEmpty("Bạn chưa tham gia phòng <b>" + esc(r.name) + "</b>" + (r.locked ? " (có mật khẩu)" : "")
                    + ".<br>Nhấn nút <b>Vào</b> (hoặc nhấp đúp) để tham gia phòng.");
        }
        updateButtons();
    }

    private void onUserSelected() {
        UserItem u = userList.getSelectedValue();
        if (u == null) return;
        suppressSel = true; roomList.clearSelection(); suppressSel = false;
        if (u.name.equalsIgnoreCase(me)) { showEmpty("Đây là tài khoản của bạn."); return; }
        openConversation(getConversation("P:" + u.name.toLowerCase(), false, 0, u.name, dn(u.name)));
    }

    private Conversation getConversation(String key, boolean group, int roomId, String peer, String title) {
        Conversation c = convs.get(key);
        if (c == null) {
            c = new Conversation(key, group, roomId, peer, title);
            convs.put(key, c);
            cardPanel.add(c.scroll, key);
        }
        return c;
    }

    private void openConversation(Conversation c) {
        if (cur != c) clearContext();
        cur = c;
        unread.remove(c.key);
        cards.show(cardPanel, c.key);
        if (c.group) {
            titleLabel.setText("<html><font color='#1D4ED8'>Phòng: " + esc(c.title) + "</font></html>");
            RoomItem r = roomById(c.roomId);
            subLabel.setText(r == null ? " " : "Chủ phòng: " + dn(r.owner) + " • " + r.count + " thành viên • Vai trò của bạn: " + roleVi(r.role));
            memberPanel.setVisible(true);
            client.trySend(Protocol.join(Protocol.LIST_MEMBERS, c.roomId));
        } else {
            titleLabel.setText("<html><font color='#6D28D9'>Chat riêng với " + esc(c.title) + "</font></html>");
            subLabel.setText("Tin nhắn riêng - chỉ hai người nhìn thấy, mã hóa AES + TLS");
            memberPanel.setVisible(false);
        }
        if (!c.historyRequested) {
            c.historyRequested = true;
            c.loading = true;
            c.topLabel.setText("Đang tải lịch sử ...");
            c.requestPage(0);
            client.trySend(Protocol.join(Protocol.LIST_PINS, c.scope, c.skey));
        }
        updatePinBanner(c);
        updateTypingLabel();
        roomList.repaint(); userList.repaint();
        updateButtons();
        markReadIfNeeded(c);
        input.requestFocusInWindow();
    }

    private void removeConversation(String key) {
        Conversation c = convs.remove(key);
        if (c != null) {
            cardPanel.remove(c.scroll);
            if (cur == c) showEmpty("Bạn đã rời khỏi cuộc trò chuyện này.");
        }
    }

    // ======================= hanh dong cua nguoi dung =======================
    private void createRoomDialog() {
        JTextField name = UiKit.styleField(new JTextField(16));
        JPasswordField pass = UiKit.styleField(new JPasswordField(16));
        JPanel p = new JPanel(new GridLayout(4, 1, 0, 4));
        p.add(new JLabel("Tên phòng:")); p.add(name);
        p.add(new JLabel("Mật khẩu phòng (để trống nếu không cần):")); p.add(pass);
        if (JOptionPane.showConfirmDialog(this, p, "Tạo phòng mới", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String n = name.getText().trim(), pw = new String(pass.getPassword());
        if (n.isEmpty()) return;
        if (pw.contains("|")) { JOptionPane.showMessageDialog(this, "Mật khẩu không được chứa ký tự |"); return; }
        client.trySend(Protocol.join(Protocol.CREATE_ROOM, n, pw));
    }

    private void joinSelected() {
        RoomItem r = roomList.getSelectedValue();
        if (r == null || r.joined()) return;
        String pw = "";
        if (r.locked) {
            JPasswordField pass = new JPasswordField(16);
            if (JOptionPane.showConfirmDialog(this, new Object[]{"Nhập mật khẩu phòng \"" + r.name + "\":", pass},
                    "Phòng có mật khẩu", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            pw = new String(pass.getPassword());
            if (pw.contains("|")) { JOptionPane.showMessageDialog(this, "Mật khẩu không hợp lệ"); return; }
        }
        pendingSelectRoom = r.id;
        client.trySend(Protocol.join(Protocol.JOIN_ROOM, r.id, pw));
    }

    private void leaveSelected() {
        RoomItem r = roomList.getSelectedValue();
        if (r == null || !r.joined()) return;
        if (JOptionPane.showConfirmDialog(this, "Rời phòng \"" + r.name + "\"?", "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            client.trySend(Protocol.join(Protocol.LEAVE_ROOM, r.id));
    }

    private void memberAction(String cmd) {
        MemberItem m = memberList.getSelectedValue();
        if (m == null || cur == null || !cur.group) return;
        if (cmd.equals(Protocol.KICK_MEMBER) && JOptionPane.showConfirmDialog(this, "Xóa " + dn(m.name) + " khỏi phòng?",
                "Xác nhận", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        client.trySend(Protocol.join(cmd, cur.roomId, m.name));
    }

    // ---------- gui tin ----------
    private void clearContext() {
        replyTarget = null;
        editTarget = null;
        contextStrip.setVisible(false);
        contextStrip.revalidate();
    }

    private void startReply(ClientMsg m) {
        editTarget = null;
        replyTarget = m;
        contextLabel.setText("<html>Đang trả lời <b>" + esc(dn(m.sender)) + "</b>: " + esc(snippet(m)) + "</html>");
        contextStrip.setVisible(true);
        contextStrip.revalidate();
        input.requestFocusInWindow();
    }

    private void startEdit(ClientMsg m) {
        replyTarget = null;
        editTarget = m;
        contextLabel.setText("<html><b>Đang sửa tin nhắn</b> (Enter để lưu, Esc để hủy)</html>");
        contextStrip.setVisible(true);
        contextStrip.revalidate();
        input.setText(m.text);
        input.requestFocusInWindow();
        input.selectAll();
    }

    private void editLastOwn() {
        if (cur == null) return;
        ClientMsg best = null;
        for (ClientMsg m : cur.msgs.values())
            if (m.sender.equalsIgnoreCase(me) && !m.deleted && Protocol.TYPE_TEXT.equals(m.type) && (best == null || m.id > best.id)) best = m;
        if (best != null) startEdit(best);
    }

    private static String snippet(ClientMsg m) {
        if (m.deleted) return "(tin nhắn đã bị thu hồi)";
        if (Protocol.TYPE_FILE.equals(m.type)) return "[File] " + m.fileName;
        String t = m.text.replace('\n', ' ');
        return t.length() > 70 ? t.substring(0, 70) + "..." : t;
    }

    private void sendText() {
        if (cur == null) return;
        String t = input.getText().trim();
        if (t.isEmpty()) return;
        if (t.length() > Protocol.MAX_TEXT_CHARS) {
            JOptionPane.showMessageDialog(this, "Tin nhắn tối đa " + Protocol.MAX_TEXT_CHARS + " ký tự.");
            return;
        }
        String enc = CryptoUtil.encrypt(t); // Message -> AES -> gui Socket
        if (editTarget != null) {
            client.trySend(Protocol.join(Protocol.EDIT_MSG, editTarget.id, enc));
            clearContext();
            input.setText("");
            return;
        }
        String cid = Long.toHexString(System.nanoTime()) + Integer.toHexString(rnd.nextInt(0xFFFF));
        long reply = replyTarget == null ? 0 : replyTarget.id;
        String frame = cur.group ? Protocol.join(Protocol.SEND_MSG_GROUP, cur.roomId, enc, reply, cid)
                                 : Protocol.join(Protocol.SEND_MSG_PRIVATE, cur.peer, enc, reply, cid);
        pendingAck.put(cid, frame);
        if (client.isConnected()) client.trySend(frame);
        else addLive(cur, ClientMsg.system("Mất kết nối - tin nhắn sẽ tự được gửi khi kết nối lại"));
        input.setText("");
        clearContext();
        client.trySend(Protocol.join(Protocol.TYPING, cur.scope, cur.skey, 0));
        lastTypingSent = 0;
    }

    private void typingPing() {
        if (cur == null || !client.isConnected() || editTarget != null) return;
        long now = System.currentTimeMillis();
        if (now - lastTypingSent > 2500) {
            lastTypingSent = now;
            client.trySend(Protocol.join(Protocol.TYPING, cur.scope, cur.skey, 1));
        }
    }

    private void tickTyping() {
        long now = System.currentTimeMillis();
        for (Conversation c : convs.values()) c.typing.values().removeIf(t -> t < now);
        updateTypingLabel();
    }

    private void updateTypingLabel() {
        if (cur == null || cur.typing.isEmpty()) { typingLabel.setText(" "); return; }
        List<String> names = new ArrayList<>();
        for (String u : cur.typing.keySet()) names.add(dn(u));
        String s = names.size() == 1 ? names.get(0) + " đang nhập..."
                : names.size() == 2 ? names.get(0) + " và " + names.get(1) + " đang nhập..." : "Nhiều người đang nhập...";
        typingLabel.setText(s);
    }

    private void markReadIfNeeded(Conversation c) {
        if (c == null || c != cur || !isActive() || !c.initialDone) return;
        if (c.lastMsgId > c.lastReadSent) {
            c.lastReadSent = c.lastMsgId;
            client.trySend(Protocol.join(Protocol.MARK_READ, c.scope, c.skey, c.lastMsgId));
        }
        unread.remove(c.key);
        roomList.repaint(); userList.repaint();
    }

    // ======================= truyen file =======================
    static class SpeedMeter {
        private final long t0 = System.nanoTime();
        private long lastT = t0, lastDone = 0; private double speed = 0;
        double update(long done) {
            long now = System.nanoTime();
            if (now - lastT > 400_000_000L) {
                double inst = (done - lastDone) / ((now - lastT) / 1e9);
                speed = speed == 0 ? inst : speed * 0.6 + inst * 0.4;
                lastT = now; lastDone = done;
            }
            if (speed == 0) speed = done / Math.max(1e-3, (now - t0) / 1e9);
            return speed;
        }
    }

    class TransferRow extends JPanel {
        final long fileId; final String name; final boolean upload;
        final JProgressBar bar = new JProgressBar(0, 100);
        final JLabel info = new JLabel();
        final JButton cancel = UiKit.ghost("Hủy");
        final SpeedMeter meter = new SpeedMeter();
        volatile boolean finished;

        TransferRow(long fileId, String name, boolean upload, Runnable onCancel) {
            super(new BorderLayout(8, 2));
            this.fileId = fileId; this.name = name; this.upload = upload;
            setBackground(UiKit.SURFACE);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UiKit.BORDER), new EmptyBorder(4, 8, 4, 8)));
            info.setFont(UiKit.FONT_SMALL);
            bar.setStringPainted(true);
            JPanel mid = new JPanel(new GridLayout(2, 1, 0, 2));
            mid.setOpaque(false);
            mid.add(info); mid.add(bar);
            add(mid, BorderLayout.CENTER);
            add(cancel, BorderLayout.EAST);
            cancel.addActionListener(e -> { cancel.setEnabled(false); info.setText("Đang hủy " + name + " ..."); onCancel.run(); });
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            update(0, 1);
        }

        void update(long done, long total) {
            int pct = total <= 0 ? 0 : (int) (done * 100 / total);
            double sp = meter.update(done);
            long eta = sp > 1 ? (long) ((total - done) / sp) : 0;
            bar.setValue(pct);
            bar.setString(pct + "%");
            info.setText((upload ? "Đang gửi " : "Đang tải ") + name + " — " + sizeStr(done) + " / " + sizeStr(total)
                    + " • " + TextUtil.speedStr(sp) + (eta > 0 ? " • còn ~" + eta + " giây" : ""));
        }

        void finish(boolean ok, String msg) {
            finished = true;
            cancel.setVisible(false);
            bar.setValue(ok ? 100 : bar.getValue());
            bar.setString(ok ? "Hoàn tất" : "Dừng");
            info.setForeground(ok ? UiKit.OK : UiKit.DANGER);
            info.setText((ok ? "✔ " : "✖ ") + name + " — " + msg);
            javax.swing.Timer t = new javax.swing.Timer(ok ? 4000 : 8000, e -> removeTransfer(this));
            t.setRepeats(false);
            t.start();
        }
    }

    private void addTransfer(TransferRow r) {
        transferPanel.add(r);
        transferPanel.revalidate();
        transferPanel.repaint();
    }

    private void removeTransfer(TransferRow r) {
        transferPanel.remove(r);
        transfers.remove(r.fileId, r);
        transferPanel.revalidate();
        transferPanel.repaint();
    }

    private void sendFile() {
        if (cur == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Chọn file hoặc hình ảnh để gửi");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        startUpload(fc.getSelectedFile());
    }

    /** Bat dau gui 1 file vao cuoc tro chuyen hien tai (da chon file). Tach rieng de kiem thu giao dien. */
    void startUpload(File f) {
        if (cur == null) return;
        String ext = TextUtil.extOf(f.getName());
        if (f.length() <= 0) { status("File rỗng hoặc không đọc được: " + f.getName(), true); return; }
        if (f.length() > (long) maxFileMB * 1024 * 1024) {
            JOptionPane.showMessageDialog(this, "File vượt quá giới hạn " + maxFileMB + " MB của Server.", "Không gửi được", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (blockedExt.contains(ext) || (!allowedExt.isEmpty() && !allowedExt.contains(ext))) {
            JOptionPane.showMessageDialog(this, "Loại file ." + ext + " không được phép gửi.", "Không gửi được", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String scope = cur.group ? "GROUP" : "PRIVATE";
        final String key = cur.group ? String.valueOf(cur.roomId) : cur.peer;
        final ChatClient.Transfer tr = new ChatClient.Transfer();
        final long rowId = -System.nanoTime();   // chua co fileId that -> khoa tam am
        final TransferRow row = new TransferRow(rowId, f.getName(), true, tr::cancel);
        transfers.put(rowId, row);
        addTransfer(row);
        new SwingWorker<Void, long[]>() {
            @Override protected Void doInBackground() throws Exception {
                client.uploadFile(scope, key, f, (d, t) -> publish(new long[]{d, t}), tr);
                return null;
            }
            @Override protected void process(List<long[]> chunks) {
                long[] l = chunks.get(chunks.size() - 1);
                row.update(l[0], l[1]);
            }
            @Override protected void done() {
                try {
                    get();
                    row.finish(true, "đã gửi, SHA-256 đã được server xác nhận");
                    notifyUser("Gửi file thành công", f.getName() + " (" + sizeStr(f.length()) + ")");
                    status("Đã gửi file " + f.getName() + " thành công", false);
                } catch (ExecutionException | InterruptedException e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    String m = c.getMessage() == null ? c.toString() : c.getMessage();
                    row.finish(false, m);
                    status("Gửi file thất bại: " + m, true);
                }
            }
        }.execute();
    }

    private void downloadFile(long fileId, String name) {
        if (transfers.containsKey(fileId)) { status("File này đang được tải", true); return; }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Lưu file về máy");
        fc.setSelectedFile(ChatClient.uniqueFile(new File(fc.getCurrentDirectory(), name)));   // tự đổi tên nếu trùng
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File target = fc.getSelectedFile();
        if (target.exists() && JOptionPane.showConfirmDialog(this, "File đã tồn tại. Ghi đè?", "Xác nhận", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            TransferRow row = new TransferRow(fileId, name, false, () -> client.cancelDownload(fileId));
            transfers.put(fileId, row);
            addTransfer(row);
            client.requestDownload(fileId, target);
        } catch (IOException e) {
            TransferRow r = transfers.get(fileId);
            if (r != null) r.finish(false, "Không gửi được yêu cầu tải: " + e.getMessage());
        }
    }

    @Override
    public void onDownloadProgress(long fileId, long done, long total) {
        TransferRow r = transfers.get(fileId);
        if (r != null && !r.finished) r.update(done, total);
    }

    @Override
    public void onDownloadFinished(long fileId, File target, boolean ok, String message) {
        if (thumbActive == fileId) { thumbDone(fileId, target, ok); return; }
        TransferRow r = transfers.get(fileId);
        if (r != null) r.finish(ok, ok ? "đã lưu " + target.getName() + " — " + message : message);
        if (ok) {
            notifyUser("Tải file thành công", target.getName() + " • " + message);
            status("Đã tải " + target.getName() + " (" + message + ")", false);
            if (TextUtil.isImageName(target.getName())) showImagePreview(target);
        } else status("Tải file không thành công: " + message, true);
    }

    // ---------- anh xem truoc ngay trong khung chat ----------
    private final Map<Long, ImageIcon> thumbCache = new HashMap<>();
    private final Map<Long, File> thumbFiles = new HashMap<>();
    private final Map<Long, List<JLabel>> thumbWaiters = new HashMap<>();
    private final LinkedList<Long> thumbQueue = new LinkedList<>();
    private final Map<Long, String> thumbNames = new HashMap<>();
    private long thumbActive = 0;
    private static final long THUMB_MAX_BYTES = 4L * 1024 * 1024;

    private void requestThumb(ClientMsg m, JLabel holder) {
        ImageIcon ic = thumbCache.get(m.fileId);
        if (ic != null) { holder.setIcon(ic); holder.setText(null); return; }
        thumbWaiters.computeIfAbsent(m.fileId, k -> new ArrayList<>()).add(holder);
        if (!thumbQueue.contains(m.fileId) && thumbActive != m.fileId) { thumbQueue.add(m.fileId); thumbNames.put(m.fileId, m.fileName); }
        pumpThumbs();
    }

    private void pumpThumbs() {
        if (thumbActive != 0 || thumbQueue.isEmpty() || !client.isConnected()) return;
        long id = thumbQueue.poll();
        File dir = new File(System.getProperty("java.io.tmpdir"), "groupchat-cache");
        dir.mkdirs();
        File target = new File(dir, "t" + id + "-" + me + "." + TextUtil.extOf(thumbNames.getOrDefault(id, "x.png")));
        if (target.isFile() && target.length() > 0) { thumbActive = id; thumbDone(id, target, true); return; }
        thumbActive = id;
        try { client.requestDownload(id, target); } catch (IOException e) { thumbDone(id, target, false); }
    }

    private void thumbDone(long id, File f, boolean ok) {
        thumbActive = 0;
        if (ok) {
            try {
                BufferedImage img = ImageIO.read(f);
                if (img != null) {
                    double s = Math.min(1.0, Math.min(240.0 / img.getWidth(), 180.0 / img.getHeight()));
                    Image sc = img.getScaledInstance(Math.max(1, (int) (img.getWidth() * s)), Math.max(1, (int) (img.getHeight() * s)), Image.SCALE_SMOOTH);
                    ImageIcon ic = new ImageIcon(sc);
                    thumbCache.put(id, ic);
                    thumbFiles.put(id, f);
                    for (JLabel l : thumbWaiters.getOrDefault(id, Collections.emptyList())) { l.setIcon(ic); l.setText(null); l.revalidate(); }
                    for (Conversation c : convs.values()) c.messages.revalidate();
                }
            } catch (IOException ignored) { }
        }
        thumbWaiters.remove(id);
        pumpThumbs();
    }

    private void showImagePreview(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return;
            int w = img.getWidth(), h = img.getHeight();
            double s = Math.min(1.0, Math.min(800.0 / w, 560.0 / h));
            Image scaled = img.getScaledInstance((int) (w * s), (int) (h * s), Image.SCALE_SMOOTH);
            JOptionPane.showMessageDialog(this, new JLabel(new ImageIcon(scaled)), "Hình ảnh: " + f.getName(), JOptionPane.PLAIN_MESSAGE);
        } catch (IOException ignored) { }
    }

    // ======================= thong bao =======================
    private TrayIcon tray;

    private void notifyUser(String title, String text) {
        if (!notificationsOn || GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return;
        try {
            if (tray == null) {
                BufferedImage ic = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = ic.createGraphics();
                g.setColor(UiKit.PRIMARY); g.fillOval(0, 0, 15, 15); g.dispose();
                tray = new TrayIcon(ic, "GroupChat");
                tray.setImageAutoSize(true);
                SystemTray.getSystemTray().add(tray);
            }
            tray.displayMessage(title, text, TrayIcon.MessageType.INFO);
        } catch (Exception ignored) { }
    }

    private void notifyIncoming(Conversation c, ClientMsg m) {
        if (m.sender.equalsIgnoreCase(me) || m.system()) return;
        boolean visible = isActive() && cur == c;
        if (visible) return;
        if (!isActive()) { setTitle("(●) " + baseTitle()); Toolkit.getDefaultToolkit().beep(); }
        notifyUser((c.group ? c.title + " • " : "") + dn(m.sender), snippet(m));
    }

    // ======================= dialogs =======================
    void profileDialog() {
        JTextField name = UiKit.styleField(new JTextField(myDisplay, 20));
        final String[] avatarB64 = {null};
        JLabel preview = new JLabel(UiKit.avatar(myDisplay.isEmpty() ? me : myDisplay, avatarImgs.get(me.toLowerCase()), 72));
        JButton choose = UiKit.secondary("Chọn ảnh...");
        choose.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Ảnh (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                String b = UiKit.avatarToBase64(fc.getSelectedFile(), Protocol.MAX_AVATAR_B64);
                if (b == null) { JOptionPane.showMessageDialog(this, "Ảnh quá phức tạp, hãy chọn ảnh khác."); return; }
                avatarB64[0] = b;
                preview.setIcon(UiKit.avatar(me, UiKit.decodeImage(b), 72));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Không đọc được ảnh: " + ex.getMessage());
            }
        });
        JPanel p = new JPanel(new BorderLayout(14, 8));
        JPanel l = new JPanel(new BorderLayout(0, 8));
        l.add(preview, BorderLayout.CENTER);
        l.add(choose, BorderLayout.SOUTH);
        JPanel r = new JPanel(new GridLayout(3, 1, 0, 4));
        r.add(new JLabel("Tên hiển thị (tối đa 30 ký tự):"));
        r.add(name);
        r.add(new JLabel("Tên đăng nhập: @" + me + " (không đổi được)"));
        p.add(l, BorderLayout.WEST);
        p.add(r, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, p, "Hồ sơ cá nhân", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String n = name.getText().trim().replace("|", "_");
        if (!n.equals(myDisplay)) client.trySend(Protocol.join(Protocol.SET_PROFILE, n));
        if (avatarB64[0] != null) client.trySend(Protocol.join(Protocol.SET_AVATAR, avatarB64[0]));
    }

    void passwordDialog() {
        JPasswordField o = new JPasswordField(16), n1 = new JPasswordField(16), n2 = new JPasswordField(16);
        Object[] f = {"Mật khẩu hiện tại:", o, "Mật khẩu mới:", n1, "Nhập lại mật khẩu mới:", n2};
        if (JOptionPane.showConfirmDialog(this, f, "Đổi mật khẩu", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String a = new String(o.getPassword()), b = new String(n1.getPassword());
        if (!b.equals(new String(n2.getPassword()))) { JOptionPane.showMessageDialog(this, "Hai lần nhập mật khẩu mới không khớp."); return; }
        if (a.contains("|") || b.contains("|")) { JOptionPane.showMessageDialog(this, "Mật khẩu không được chứa ký tự |"); return; }
        client.trySend(Protocol.join(Protocol.CHANGE_PASSWORD, a, b));
    }

    void openAdmin() {
        if (!isAdmin) return;
        if (adminFrame == null || !adminFrame.isDisplayable()) adminFrame = new AdminFrame(client, this);
        adminFrame.setVisible(true);
        adminFrame.toFront();
    }

    // ---------- tim kiem ----------
    void openSearch() {
        if (cur == null) { status("Hãy chọn một cuộc trò chuyện trước khi tìm kiếm", true); return; }
        if (searchDialog == null || !searchDialog.isDisplayable()) searchDialog = new SearchDialog();
        searchDialog.bind(cur);
        searchDialog.setVisible(true);
        searchDialog.toFront();
    }

    class SearchDialog extends JDialog {
        final JTextField kw = UiKit.styleField(new JTextField(16)), sender = UiKit.styleField(new JTextField(8));
        final JCheckBox strict = new JCheckBox("Phân biệt dấu");
        final DefaultListModel<ClientMsg> model = new DefaultListModel<>();
        final JList<ClientMsg> list = new JList<>(model);
        final JLabel info = new JLabel(" ");
        final JButton more = UiKit.ghost("Tải thêm kết quả");
        Conversation conv; int page = 0; boolean hasMore; List<ClientMsg> buf;

        SearchDialog() {
            super(MainFrame.this, "Tìm kiếm tin nhắn", false);
            JPanel top = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(3, 4, 3, 4); g.fill = GridBagConstraints.HORIZONTAL;
            g.gridx = 0; top.add(new JLabel("Từ khóa:"), g);
            g.gridx = 1; g.weightx = 1; top.add(kw, g);
            g.gridx = 2; g.weightx = 0; top.add(new JLabel("Người gửi:"), g);
            g.gridx = 3; top.add(sender, g);
            g.gridx = 4; top.add(strict, g);
            JButton go = UiKit.primary("Tìm");
            g.gridx = 5; top.add(go, g);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                    ClientMsg m = (ClientMsg) v;
                    c.setText("<html><b>" + esc(dn(m.sender)) + "</b> <font size=2 color='gray'>" + timeFmt.format(new Date(m.ts)) + "</font><br>"
                            + esc(snippet(m)) + "</html>");
                    c.setBorder(new EmptyBorder(4, 6, 4, 6));
                    return c;
                }
            });
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) gotoSelected(); }
            });
            JPanel bottom = new JPanel(new BorderLayout(8, 0));
            JButton gt = UiKit.secondary("Đi tới tin nhắn");
            gt.addActionListener(e -> gotoSelected());
            bottom.add(info, BorderLayout.CENTER);
            JPanel bb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            bb.add(more); bb.add(gt);
            bottom.add(bb, BorderLayout.EAST);
            JPanel root = new JPanel(new BorderLayout(0, 8));
            root.setBorder(new EmptyBorder(10, 10, 10, 10));
            root.add(top, BorderLayout.NORTH);
            root.add(new JScrollPane(list), BorderLayout.CENTER);
            root.add(bottom, BorderLayout.SOUTH);
            setContentPane(root);
            setSize(720, 460);
            setLocationRelativeTo(MainFrame.this);
            go.addActionListener(e -> run(0));
            kw.addActionListener(e -> run(0));
            sender.addActionListener(e -> run(0));
            more.addActionListener(e -> run(page + 1));
            getRootPane().registerKeyboardAction(e -> setVisible(false), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        void bind(Conversation c) {
            if (conv != c) { model.clear(); info.setText(" "); more.setEnabled(false); }
            conv = c;
            setTitle("Tìm kiếm trong: " + (c.group ? "phòng " : "chat riêng với ") + c.title);
            kw.requestFocusInWindow();
        }

        void run(int p) {
            if (conv == null) return;
            if (kw.getText().trim().isEmpty() && sender.getText().trim().isEmpty()) { info.setText("Hãy nhập từ khóa hoặc người gửi"); return; }
            if (kw.getText().contains("|") || sender.getText().contains("|")) { info.setText("Không dùng ký tự |"); return; }
            if (p == 0) model.clear();
            info.setText("Đang tìm ...");
            more.setEnabled(false);
            client.trySend(Protocol.join(Protocol.SEARCH_MSG, conv.scope, conv.skey, kw.getText().trim(), sender.getText().trim(), p, strict.isSelected() ? 1 : 0));
        }

        void begin(int p) { page = p; buf = new ArrayList<>(); }
        void item(ClientMsg m) { if (buf != null) buf.add(m); }
        void end(boolean more) {
            if (buf != null) for (ClientMsg m : buf) model.addElement(m);
            hasMore = more;
            this.more.setEnabled(more);
            info.setText(model.isEmpty() ? "Không tìm thấy kết quả nào" : model.size() + " kết quả" + (more ? " (còn nữa)" : ""));
        }

        void gotoSelected() {
            ClientMsg m = list.getSelectedValue();
            if (m != null && conv != null) { openConversation(conv); scrollToMessage(conv, m.id); }
        }
    }

    private void scrollToMessage(Conversation c, long id) {
        JPanel row = c.rows.get(id);
        if (row == null) {
            if (c.hasMore) { c.jumpTarget = id; status("Đang tải lịch sử cũ để tìm tin nhắn...", false); c.loadMore(); }
            else status("Không tìm thấy tin nhắn trong lịch sử đã tải", true);
            return;
        }
        c.jumpTarget = 0;
        SwingUtilities.invokeLater(() -> {
            Rectangle r = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), c.scroll.getViewport().getView());
            c.scroll.getViewport().setViewPosition(new Point(0, Math.max(0, r.y - 60)));
            Color old = row.getBackground();
            row.setOpaque(true);
            row.setBackground(new Color(0xFEF9C3));
            javax.swing.Timer t = new javax.swing.Timer(1800, e -> { row.setOpaque(false); row.setBackground(old); row.repaint(); });
            t.setRepeats(false);
            t.start();
        });
    }

    // ---------- ghim ----------
    private void updatePinBanner(Conversation c) {
        if (c != cur) return;
        if (c.pins.isEmpty()) { pinBanner.setVisible(false); return; }
        ClientMsg last = c.pins.get(0);
        pinBanner.setText(UiKit.pinGlyph() + " " + c.pins.size() + " tin đã ghim — mới nhất của " + dn(last.sender) + ": " + snippet(last) + "  (nhấn để xem)");
        pinBanner.setVisible(true);
    }

    void openPins() {
        if (cur == null) return;
        final Conversation c = cur;
        client.trySend(Protocol.join(Protocol.LIST_PINS, c.scope, c.skey));
        DefaultListModel<ClientMsg> model = new DefaultListModel<>();
        for (ClientMsg m : c.pins) model.addElement(m);
        JList<ClientMsg> list = new JList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel x = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                ClientMsg m = (ClientMsg) v;
                x.setText("<html><b>" + esc(dn(m.sender)) + "</b> <font size=2 color='gray'>" + timeFmt.format(new Date(m.ts)) + "</font><br>" + esc(snippet(m)) + "</html>");
                x.setBorder(new EmptyBorder(4, 6, 4, 6));
                return x;
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(520, 280));
        JButton goBtn = UiKit.secondary("Đi tới tin"), unpin = UiKit.ghost("Bỏ ghim");
        unpin.setEnabled(c.group ? iAmMod(c) : true);
        JPanel bt = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bt.add(goBtn); bt.add(unpin);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel(c.pins.isEmpty() ? "Chưa có tin nhắn nào được ghim." : "Tin nhắn đã ghim (" + c.pins.size() + ")"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(bt, BorderLayout.SOUTH);
        JDialog d = new JDialog(this, "Tin nhắn đã ghim", false);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(this);
        goBtn.addActionListener(e -> { ClientMsg m = list.getSelectedValue(); if (m != null) { d.dispose(); scrollToMessage(c, m.id); } });
        unpin.addActionListener(e -> { ClientMsg m = list.getSelectedValue(); if (m != null) { client.trySend(Protocol.join(Protocol.PIN_MSG, m.id, 0)); model.removeElement(m); } });
        d.setVisible(true);
    }

    // ---------- xuat lich su ----------
    void exportHistory() {
        if (cur == null) { status("Hãy chọn một cuộc trò chuyện để xuất", true); return; }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Xuất lịch sử trò chuyện");
        fc.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Văn bản (*.txt)", "txt"));
        javax.swing.filechooser.FileNameExtensionFilter csvF = new javax.swing.filechooser.FileNameExtensionFilter("Bảng CSV (*.csv)", "csv");
        fc.addChoosableFileFilter(csvF);
        fc.setSelectedFile(new File("lichsu-" + cur.title.replaceAll("[^\\p{L}\\p{N}_-]", "_") + ".txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        boolean csv = f.getName().toLowerCase().endsWith(".csv") || fc.getFileFilter() == csvF;
        if (csv && !f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getPath() + ".csv");
        if (!csv && !f.getName().toLowerCase().contains(".")) f = new File(f.getPath() + ".txt");
        cur.exportBuf = new ArrayList<>();
        cur.exportFile = f;
        cur.exportCsv = csv;
        cur.exportPage = 0;
        status("Đang tải toàn bộ lịch sử để xuất...", false);
        cur.requestPage(0);
    }

    private void exportPage(Conversation c, List<ClientMsg> list, boolean hasMore) {
        c.exportBuf.addAll(0, list);                       // cac trang di tu moi den cu -> chen len dau
        if (hasMore && c.exportPage < 2000) { c.exportPage++; c.requestPage(c.exportPage); status("Đang tải lịch sử để xuất... " + c.exportBuf.size() + " tin", false); return; }
        List<ClientMsg> all = c.exportBuf;
        File f = c.exportFile;
        boolean csv = c.exportCsv;
        c.exportBuf = null;
        c.exportFile = null;
        try {
            if (csv) ClientMsg.exportCsv(all, f, this::dn);
            else ClientMsg.exportTxt(all, f, (c.group ? "phòng " : "chat riêng với ") + c.title, this::dn);
            status("Đã xuất " + all.size() + " tin nhắn ra " + f.getAbsolutePath(), false);
            notifyUser("Xuất lịch sử thành công", f.getName() + " • " + all.size() + " tin");
            JOptionPane.showMessageDialog(this, "Đã xuất " + all.size() + " tin nhắn ra:\n" + f.getAbsolutePath(), "Xuất lịch sử", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            status("Xuất lịch sử thất bại: " + e.getMessage(), true);
            JOptionPane.showMessageDialog(this, "Không ghi được file: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void logoutAndExit() {
        closing = true;
        try { client.send(Protocol.LOGOUT); } catch (IOException ignored) { }
        client.close();
        dispose();
        System.exit(0);
    }

    private void backToLogin(String notice) {
        if (closing) return;
        closing = true;
        client.close();
        bg.shutdownNow();
        if (adminFrame != null) adminFrame.dispose();
        dispose();
        Properties c = cfg.isEmpty() ? common.Config.load("config/client.properties") : cfg;
        new LoginFrame(c, notice).setVisible(true);
    }

    // ======================= ket noi lai =======================
    @Override
    public void onReconnecting(int attempt, int delaySec, String reason) {
        banner.setText("⚠ Mất kết nối — đang tự kết nối lại (lần " + attempt + ", thử tiếp sau " + delaySec + " giây)");
        banner.setToolTipText(reason);
        banner.setVisible(true);
        status("Mất kết nối, đang tự động kết nối lại...", true);
        rttLabel.setText("RTT: —");
    }

    @Override
    public void onReconnected() {
        banner.setVisible(false);
        status("Đã kết nối lại thành công", false);
        client.trySend(Protocol.LIST_ROOMS);
        client.trySend(Protocol.LIST_USERS);
        for (Conversation c : convs.values()) {
            if (!c.initialDone) continue;
            c.resync = true;                       // tai lai trang moi nhat va gop vao (khong trung)
            c.requestPage(0);
            client.trySend(Protocol.join(Protocol.LIST_PINS, c.scope, c.skey));
        }
        if (cur != null && cur.group) client.trySend(Protocol.join(Protocol.LIST_MEMBERS, cur.roomId));
        for (String frame : new ArrayList<>(pendingAck.values())) client.trySend(frame);   // gui lai tin chua duoc xac nhan (server chong trung)
        bg.schedule(() -> SwingUtilities.invokeLater(pendingAck::clear), 8, TimeUnit.SECONDS);
        notifyUser("Đã kết nối lại", "GroupChat đã khôi phục kết nối tới server");
    }

    @Override
    public void onDisconnected(String reason) {
        backToLogin(reason);
    }

    // ======================= xu ly goi tin tu server =======================
    @Override
    public void onFrame(String[] p) {
        if (p[0].startsWith("ADMIN_")) { if (adminFrame != null) adminFrame.onFrame(p); return; }
        switch (p[0]) {
            case Protocol.SERVER_INFO: applyServerInfo(p); break;
            case Protocol.ROOM_LIST: applyRoomList(p); break;
            case Protocol.USER_LIST: applyUserList(p); break;
            case Protocol.UNREAD: applyUnread(p); break;
            case Protocol.ROOMS_CHANGED: client.trySend(Protocol.LIST_ROOMS); break;
            case Protocol.MEMBERS_CHANGED:
                if (cur != null && cur.group && String.valueOf(cur.roomId).equals(p[1])) client.trySend(Protocol.join(Protocol.LIST_MEMBERS, cur.roomId));
                break;
            case Protocol.MEMBER_LIST: applyMembers(p); break;
            case Protocol.CREATE_OK: pendingSelectRoom = Integer.parseInt(p[1]); client.trySend(Protocol.LIST_ROOMS); break;
            case Protocol.CREATE_FAIL:
            case Protocol.JOIN_FAIL:
                pendingSelectRoom = -1;
                JOptionPane.showMessageDialog(this, p[1], "Không thực hiện được", JOptionPane.WARNING_MESSAGE);
                break;
            case Protocol.JOIN_OK: pendingSelectRoom = Integer.parseInt(p[1]); client.trySend(Protocol.LIST_ROOMS); break;
            case Protocol.LEAVE_OK: removeConversation("G:" + p[1]); client.trySend(Protocol.LIST_ROOMS); break;
            case Protocol.KICKED:
                removeConversation("G:" + p[1]);
                JOptionPane.showMessageDialog(this, "Bạn đã bị xóa khỏi phòng" + (p.length > 2 && !p[2].isEmpty() ? " \"" + p[2] + "\"" : "") + ".", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
                client.trySend(Protocol.LIST_ROOMS);
                break;
            case Protocol.ERROR: status("⚠ " + p[1], true); break;
            case Protocol.MUTED: status("⚠ " + (p.length > 2 ? p[2] : "Bạn bị hạn chế gửi tin"), true); banner(p.length > 2 ? p[2] : "Bạn bị hạn chế do spam", 6000); break;
            case Protocol.UPLOAD_DONE: break;
            case Protocol.UPLOAD_CANCELLED: status("Đã hủy gửi file", false); break;
            case Protocol.SESSION_EXPIRED:
            case Protocol.SESSION_REPLACED:
                backToLogin(p.length > 1 ? p[1] : "Phiên đăng nhập đã kết thúc");
                break;
            case Protocol.PASSWORD_OK: status("Đã đổi mật khẩu thành công", false); JOptionPane.showMessageDialog(this, "Đổi mật khẩu thành công.", "Mật khẩu", JOptionPane.INFORMATION_MESSAGE); break;
            case Protocol.PASSWORD_FAIL: JOptionPane.showMessageDialog(this, p[1], "Đổi mật khẩu thất bại", JOptionPane.WARNING_MESSAGE); break;
            case Protocol.PROFILE_UPDATE: applyProfile(p[1], Protocol.dec(p[2]), Integer.parseInt(p[3])); break;
            case Protocol.AVATAR: applyAvatar(p); break;
            case Protocol.SYS: onSystem(Integer.parseInt(p[1]), p[2]); break;
            case Protocol.MSG_GROUP: {
                ClientMsg m = ClientMsg.parse(Long.parseLong(p[2]), p[3], Long.parseLong(p[4]), p[5], p[6], p, 7);
                incoming("G:" + p[1], m);
                break;
            }
            case Protocol.MSG_PRIVATE: {
                ClientMsg m = ClientMsg.parse(Long.parseLong(p[1]), p[2], Long.parseLong(p[4]), p[5], p[6], p, 7);
                String peer = p[2].equalsIgnoreCase(me) ? p[3] : p[2];
                incoming("P:" + peer.toLowerCase(), m);
                break;
            }
            case Protocol.HISTORY_BEGIN: histBuf.put(p[1] + ":" + p[2].toLowerCase(), new ArrayList<>()); break;
            case Protocol.HISTORY_ITEM: {
                List<ClientMsg> l = histBuf.get(p[1] + ":" + p[2].toLowerCase());
                if (l != null) l.add(ClientMsg.parse(Long.parseLong(p[3]), p[4], Long.parseLong(p[5]), p[6], p[7], p, 8));
                break;
            }
            case Protocol.HISTORY_END: {
                String key = p[1] + ":" + p[2].toLowerCase();
                List<ClientMsg> l = histBuf.remove(key);
                Conversation c = convs.get(key);
                if (c != null && l != null) {
                    if (c.exportBuf != null) exportPage(c, l, p[4].equals("1"));
                    else applyHistory(c, Integer.parseInt(p[3]), l, p[4].equals("1"));
                }
                break;
            }
            case Protocol.READ_STATE: applyReadState(p); break;
            case Protocol.READ_UPDATE: {
                Conversation c = convs.get(p[1] + ":" + p[2].toLowerCase());
                if (c != null) { c.reads.merge(p[3].toLowerCase(), Long.parseLong(p[4]), Math::max); updateSeen(c); }
                break;
            }
            case Protocol.TYPING_UPDATE: {
                Conversation c = convs.get(p[1] + ":" + p[2].toLowerCase());
                if (c != null && !p[3].equalsIgnoreCase(me)) {
                    if (p[4].equals("1")) c.typing.put(p[3], System.currentTimeMillis() + 5000); else c.typing.remove(p[3]);
                    if (c == cur) updateTypingLabel();
                }
                break;
            }
            case Protocol.MSG_EDITED: msgChanged(p[1], p[2], Long.parseLong(p[3]), m -> {
                m.edited = true; m.setContent(CryptoUtil.decrypt(p[4]));
            }); break;
            case Protocol.MSG_RECALLED: msgChanged(p[1], p[2], Long.parseLong(p[3]), m -> {
                m.deleted = true; m.text = ""; m.pinned = false; m.reactions.clear();
            }); break;
            case Protocol.REACTION_UPDATE: msgChanged(p[1], p[2], Long.parseLong(p[3]), m -> ClientMsg.parseReactions(m, p[4])); break;
            case Protocol.PIN_UPDATE: {
                boolean on = p[4].equals("1");
                msgChanged(p[1], p[2], Long.parseLong(p[3]), m -> m.pinned = on);
                Conversation c = convs.get(p[1] + ":" + p[2].toLowerCase());
                if (c != null) client.trySend(Protocol.join(Protocol.LIST_PINS, c.scope, c.skey));
                break;
            }
            case Protocol.PINS_BEGIN: histBuf.put("PIN:" + p[1] + ":" + p[2].toLowerCase(), new ArrayList<>()); break;
            case Protocol.PINS_ITEM: {
                List<ClientMsg> l = histBuf.get("PIN:" + p[1] + ":" + p[2].toLowerCase());
                if (l != null) l.add(ClientMsg.parse(Long.parseLong(p[3]), p[4], Long.parseLong(p[5]), p[6], p[7], p, 8));
                break;
            }
            case Protocol.PINS_END: {
                List<ClientMsg> l = histBuf.remove("PIN:" + p[1] + ":" + p[2].toLowerCase());
                Conversation c = convs.get(p[1] + ":" + p[2].toLowerCase());
                if (c != null && l != null) { c.pins.clear(); c.pins.addAll(l); updatePinBanner(c); }
                break;
            }
            case Protocol.SEARCH_BEGIN: if (searchDialog != null) searchDialog.begin(Integer.parseInt(p[3])); break;
            case Protocol.SEARCH_ITEM: if (searchDialog != null) searchDialog.item(ClientMsg.parse(Long.parseLong(p[3]), p[4], Long.parseLong(p[5]), p[6], p[7], p, 8)); break;
            case Protocol.SEARCH_END: if (searchDialog != null) searchDialog.end(p[4].equals("1")); break;
            default:
        }
    }

    private void banner(String text, int ms) {
        banner.setText("⚠ " + text);
        banner.setVisible(true);
        javax.swing.Timer t = new javax.swing.Timer(ms, e -> { if (!client.isReconnecting()) banner.setVisible(false); });
        t.setRepeats(false);
        t.start();
    }

    private void applyServerInfo(String[] p) {
        try { maxFileMB = Integer.parseInt(p[1]); } catch (NumberFormatException ignored) { }
        blockedExt = new HashSet<>(); allowedExt = new HashSet<>();
        for (String s : p[2].split(",")) if (!s.isEmpty()) blockedExt.add(s);
        for (String s : p[3].split(",")) if (!s.isEmpty()) allowedExt.add(s);
        isAdmin = p.length > 5 && p[5].equals("1");
        myDisplay = p.length > 6 ? Protocol.dec(p[6]) : "";
        if (!myDisplay.isEmpty()) displayNames.put(me.toLowerCase(), myDisplay);
        adminMenu.setVisible(isAdmin);
        refreshMyHeader();
        client.trySend(Protocol.join(Protocol.GET_AVATAR, me));
    }

    private void applyUnread(String[] p) {
        unread.clear();
        for (int i = 1; i < p.length; i++) {
            int eq = p[i].indexOf('=');
            if (eq < 0) continue;
            String k = p[i].substring(0, eq);
            if (k.startsWith("P:")) k = "P:" + k.substring(2).toLowerCase();
            try { unread.put(k, Integer.parseInt(p[i].substring(eq + 1))); } catch (NumberFormatException ignored) { }
        }
        roomList.repaint(); userList.repaint();
    }

    private void applyProfile(String user, String display, int ver) {
        displayNames.put(user.toLowerCase(), display);
        Integer old = avatarVers.get(user.toLowerCase());
        if (ver > 0 && (old == null || old != ver)) { avatarVers.put(user.toLowerCase(), ver); requestAvatar(user, true); }
        if (user.equalsIgnoreCase(me)) { myDisplay = display; refreshMyHeader(); }
        refreshAll();
    }

    private void requestAvatar(String user, boolean force) {
        String k = user.toLowerCase();
        if (!force && (avatarRequested.contains(k) || avatarImgs.containsKey(k))) return;
        avatarRequested.add(k);
        client.trySend(Protocol.join(Protocol.GET_AVATAR, user));
    }

    private void applyAvatar(String[] p) {
        if (p.length < 4 || p[3].isEmpty()) return;
        BufferedImage img = UiKit.decodeImage(p[3]);
        if (img == null) return;
        avatarImgs.put(p[1].toLowerCase(), img);
        if (p[1].equalsIgnoreCase(me)) refreshMyHeader();
        refreshAll();
    }

    /** Ve lai danh sach + cac dong tin sau khi ten/anh doi. */
    private void refreshAll() {
        roomList.repaint(); userList.repaint(); memberList.repaint();
        for (Conversation c : convs.values()) {
            for (Map.Entry<Long, JPanel> e : c.rows.entrySet()) {
                ClientMsg m = c.msgs.get(e.getKey());
                if (m != null) rebuildRow(c, m);
            }
        }
        if (cur != null && !cur.group) titleLabel.setText("<html><font color='#6D28D9'>Chat riêng với " + esc(dn(cur.peer)) + "</font></html>");
    }

    private void applyRoomList(String[] p) {
        int prevSel = roomList.getSelectedValue() == null ? -1 : roomList.getSelectedValue().id;
        suppressSel = true;
        roomModel.clear();
        Set<Integer> joined = new HashSet<>();
        for (int i = 1; i < p.length; i++) {
            String[] f = p[i].split(",", -1);
            if (f.length < 6) continue;
            RoomItem r = new RoomItem();
            r.id = Integer.parseInt(f[0]); r.name = f[1]; r.locked = f[2].equals("1"); r.owner = f[3];
            r.count = Integer.parseInt(f[4]); r.role = f[5];
            roomModel.addElement(r);
            if (r.joined()) joined.add(r.id);
        }
        for (String k : new ArrayList<>(convs.keySet())) {
            if (k.startsWith("G:") && !joined.contains(Integer.parseInt(k.substring(2)))) removeConversation(k);
        }
        int want = pendingSelectRoom > 0 ? pendingSelectRoom : prevSel;
        RoomItem target = want > 0 ? roomById(want) : null;
        if (target != null && roomList.getSelectedValue() != target) roomList.setSelectedValue(target, true);
        suppressSel = false;
        if (pendingSelectRoom > 0 && target != null) {
            pendingSelectRoom = -1;
            if (target.joined()) { userList.clearSelection(); onRoomSelected(); }
        } else if (cur != null && cur.group) {
            RoomItem r = roomById(cur.roomId);
            if (r != null) subLabel.setText("Chủ phòng: " + dn(r.owner) + " • " + r.count + " thành viên • Vai trò của bạn: " + roleVi(r.role));
        }
        roomList.repaint();
        updateButtons();
    }

    private void applyUserList(String[] p) {
        String selName = userList.getSelectedValue() == null ? null : userList.getSelectedValue().name;
        List<UserItem> items = new ArrayList<>();
        for (int i = 1; i < p.length; i++) {
            String[] f = p[i].split(",", -1);
            UserItem u = new UserItem();
            u.name = f[0]; u.online = f.length > 1 && f[1].equals("1");
            try { u.lastSeen = f.length > 2 ? Long.parseLong(f[2]) : 0; } catch (NumberFormatException e) { u.lastSeen = 0; }
            if (f.length > 3) displayNames.put(u.name.toLowerCase(), Protocol.dec(f[3]));
            int ver = 0;
            try { ver = f.length > 4 ? Integer.parseInt(f[4]) : 0; } catch (NumberFormatException ignored) { }
            if (ver > 0) {
                Integer old = avatarVers.get(u.name.toLowerCase());
                if (old == null || old != ver) { avatarVers.put(u.name.toLowerCase(), ver); requestAvatar(u.name, true); }
            }
            if (!u.name.equalsIgnoreCase(me)) items.add(u);
        }
        items.sort((a, b) -> a.online != b.online ? (a.online ? -1 : 1) : dn(a.name).compareToIgnoreCase(dn(b.name)));
        suppressSel = true;
        userModel.clear();
        for (UserItem u : items) {
            userModel.addElement(u);
            if (u.name.equals(selName)) userList.setSelectedValue(u, false);
        }
        suppressSel = false;
    }

    private void applyMembers(String[] p) {
        if (cur == null || !cur.group || !String.valueOf(cur.roomId).equals(p[1])) return;
        String sel = memberList.getSelectedValue() == null ? null : memberList.getSelectedValue().name;
        memberModel.clear();
        for (int i = 2; i < p.length; i++) {
            String[] f = p[i].split(",", -1);
            MemberItem m = new MemberItem();
            m.name = f[0]; m.role = f[1]; m.online = f[2].equals("1");
            memberModel.addElement(m);
            requestAvatar(m.name, false);
            if (m.name.equals(sel)) memberList.setSelectedIndex(memberModel.size() - 1);
        }
        updateButtons();
    }

    private void onSystem(int roomId, String text) {
        Conversation c = convs.get("G:" + roomId);
        if (c == null) return;
        addLive(c, ClientMsg.system(text));
    }

    private void applyReadState(String[] p) {
        Conversation c = convs.get(p[1] + ":" + p[2].toLowerCase());
        if (c == null || p.length < 4) return;
        for (String part : p[3].split(",")) {
            int i = part.lastIndexOf(':');
            if (i <= 0) continue;
            try { c.reads.merge(part.substring(0, i).toLowerCase(), Long.parseLong(part.substring(i + 1)), Math::max); } catch (NumberFormatException ignored) { }
        }
        updateSeen(c);
    }

    /** "Da xem" duoi tin cuoi cung cua minh: chat rieng -> da xem/da gui; nhom -> danh sach nguoi da xem. */
    private void updateSeen(Conversation c) {
        long lastMine = 0;
        for (ClientMsg m : c.msgs.values()) if (m.sender.equalsIgnoreCase(me) && !m.deleted && m.id > lastMine) lastMine = m.id;
        if (lastMine == 0) { c.seenLabel.setText(" "); return; }
        if (c.group) {
            List<String> who = new ArrayList<>();
            for (Map.Entry<String, Long> e : c.reads.entrySet())
                if (!e.getKey().equalsIgnoreCase(me) && e.getValue() >= lastMine) who.add(dn(e.getKey()));
            Collections.sort(who);
            c.seenLabel.setText(who.isEmpty() ? "Đã gửi" : "Đã xem bởi " + String.join(", ", who));
        } else {
            long peerRead = c.reads.getOrDefault(c.peer.toLowerCase(), 0L);
            c.seenLabel.setText(peerRead >= lastMine ? "✓✓ Đã xem" : "✓ Đã gửi");
        }
    }

    private interface MsgChange { void apply(ClientMsg m); }

    private void msgChanged(String scope, String key, long id, MsgChange ch) {
        Conversation c = convs.get(scope + ":" + key.toLowerCase());
        if (c == null) return;
        ClientMsg m = c.msgs.get(id);
        if (m == null) return;
        ch.apply(m);
        rebuildRow(c, m);
        updateSeen(c);
        if (!m.pinned || m.deleted) c.pins.removeIf(x -> x.id == id);
        updatePinBanner(c);
    }

    /** Tin moi toi: them vao cuoc tro chuyen neu da mo, nguoc lai danh dau chua doc. */
    private void incoming(String key, ClientMsg m) {
        if (!m.cid.isEmpty()) pendingAck.remove(m.cid);   // server da nhan tin nay
        Conversation c = convs.get(key);
        if (c != null) addLive(c, m);
        if (c == null || cur != c || !isActive()) {
            if (!m.sender.equalsIgnoreCase(me)) {
                if (c == null || cur != c) unread.merge(key, 1, Integer::sum);
                roomList.repaint(); userList.repaint();
            }
        }
        if (c != null) notifyIncoming(c, m);
        else if (!m.sender.equalsIgnoreCase(me)) {
            if (!isActive()) setTitle("(●) " + baseTitle());
            notifyUser(dn(m.sender), snippet(m));
        }
    }

    private void addLive(Conversation c, ClientMsg m) {
        if (!c.initialDone) { c.pendingLive.add(m); return; }
        if (m.id > 0 && !c.seen.add(m.id)) return;
        JScrollBar bar = c.scroll.getVerticalScrollBar();
        boolean nearBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 80;
        c.messages.add(makeRow(c, m));
        c.messages.revalidate();
        if (m.id > 0) { c.msgs.put(m.id, m); c.lastMsgId = Math.max(c.lastMsgId, m.id); }
        if (!m.sender.equalsIgnoreCase(me)) c.typing.remove(m.sender);
        updateSeen(c);
        if (nearBottom || m.sender.equalsIgnoreCase(me)) scrollToBottom(c);
        markReadIfNeeded(c);
    }

    private void scrollToBottom(Conversation c) {
        JScrollBar bar = c.scroll.getVerticalScrollBar();
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum())));
    }

    private void applyHistory(Conversation c, int page, List<ClientMsg> list, boolean hasMore) {
        JScrollBar bar = c.scroll.getVerticalScrollBar();
        if (c.resync && page == 0) {                       // ket noi lai: gop tin moi/ cap nhat tin cu
            c.resync = false;
            for (ClientMsg m : list) {
                if (c.seen.add(m.id)) { c.messages.add(makeRow(c, m)); c.msgs.put(m.id, m); c.lastMsgId = Math.max(c.lastMsgId, m.id); }
                else { c.msgs.put(m.id, m); rebuildRow(c, m); }
            }
            c.messages.revalidate();
            updateSeen(c);
            markReadIfNeeded(c);
            return;
        }
        if (!c.initialDone && page == 0) {
            for (ClientMsg m : list) if (c.seen.add(m.id)) { c.messages.add(makeRow(c, m)); c.msgs.put(m.id, m); c.lastMsgId = Math.max(c.lastMsgId, m.id); }
            c.hasMore = hasMore;
            c.nextPage = 1;
            c.topLabel.setText(hasMore ? "↑ Cuộn lên đầu để tải thêm 50 tin cũ" : "— Đầu cuộc trò chuyện —");
            c.messages.revalidate();
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
                bar.setValue(bar.getMaximum());
                c.initialDone = true;
                c.loading = false;
                for (ClientMsg m : c.pendingLive) addLive(c, m);
                c.pendingLive.clear();
                updateSeen(c);
                markReadIfNeeded(c);
                if (c.hasMore && bar.getMaximum() <= bar.getVisibleAmount()) c.loadMore();
            }));
        } else {
            int oldMax = bar.getMaximum(), oldVal = bar.getValue();
            int idx = 0;
            for (ClientMsg m : list) if (c.seen.add(m.id)) { c.messages.add(makeRow(c, m), idx++); c.msgs.put(m.id, m); }
            c.nextPage = page + 1;
            c.hasMore = hasMore;
            c.topLabel.setText(hasMore ? "↑ Cuộn lên đầu để tải thêm 50 tin cũ" : "— Đã tải hết lịch sử —");
            c.messages.revalidate();
            // giu nguyen vi tri dang doc sau khi chen tin cu len tren
            SwingUtilities.invokeLater(() -> {
                if (c.jumpTarget == 0) bar.setValue(bar.getMaximum() - oldMax + oldVal);
                c.loading = false;
                if (c.jumpTarget != 0) {
                    long t = c.jumpTarget;
                    if (c.rows.containsKey(t) || !c.hasMore) { c.jumpTarget = 0; scrollToMessage(c, t); }
                    else c.loadMore();
                }
            });
        }
    }

    // ======================= ve bong bong chat =======================
    static class Bubble extends JPanel {
        private final Color bg;
        Bubble(Color bg) {
            this.bg = bg;
            setOpaque(false);
            setLayout(new BorderLayout(0, 3));
            setBorder(new EmptyBorder(7, 11, 7, 11));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g2.setColor(new Color(0, 0, 0, 22));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private JTextArea textArea(String text, Color fg) {
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setOpaque(false);
        ta.setBackground(new Color(0, 0, 0, 0)); // Nimbus bo qua setOpaque(false) neu khong dat nen trong suot
        ta.setFont(MSG_FONT);
        ta.setForeground(fg);
        ta.setBorder(null);
        Dimension d = ta.getPreferredSize();
        if (d.width > BUBBLE_MAX_W) {
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setSize(BUBBLE_MAX_W, Short.MAX_VALUE);
            d = new Dimension(BUBBLE_MAX_W, ta.getPreferredSize().height);
        }
        ta.setPreferredSize(d);
        return ta;
    }

    private void rebuildRow(Conversation c, ClientMsg m) {
        JPanel wrap = c.rows.get(m.id);
        if (wrap == null) return;
        Container parent = wrap.getParent();
        if (parent == null) return;
        int idx = -1;
        for (int i = 0; i < parent.getComponentCount(); i++) if (parent.getComponent(i) == wrap) { idx = i; break; }
        if (idx < 0) return;
        parent.remove(idx);
        c.rows.remove(m.id);
        parent.add(makeRow(c, m), idx);
        parent.revalidate();
        parent.repaint();
    }

    private Component makeRow(Conversation c, ClientMsg m) {
        if (m.system()) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4)) {
                @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            };
            JLabel l = new JLabel("— " + m.text + " —");
            l.setForeground(UiKit.MUTED);
            l.setFont(l.getFont().deriveFont(Font.ITALIC, 12f));
            row.add(l);
            row.setOpaque(false);
            return row;
        }
        boolean mine = m.sender.equalsIgnoreCase(me);
        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 3)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        row.setOpaque(false);
        if (!mine) {
            JLabel av = new JLabel(UiKit.avatar(dn(m.sender), avatarImgs.get(m.sender.toLowerCase()), 30));
            av.setVerticalAlignment(SwingConstants.TOP);
            av.setToolTipText("@" + m.sender);
            requestAvatar(m.sender, false);
            row.add(av);
        }
        Bubble b = makeBubble(c, m, mine);
        row.add(b);
        if (m.id > 0) c.rows.put(m.id, row);
        if (m.id > 0 && !m.deleted) attachMenu(b, c, m);
        return row;
    }

    private Bubble makeBubble(Conversation c, ClientMsg m, boolean mine) {
        boolean file = m.file();
        Bubble b = new Bubble(m.deleted ? new Color(0xF1F5F9) : file ? C_FILE : mine ? C_ME : C_OTHER);
        String tag = c.group ? "" : "  [riêng]";
        JLabel head = new JLabel((mine ? "Bạn" : dn(m.sender)) + " • " + timeFmt.format(new Date(m.ts)) + tag
                + (m.edited && !m.deleted ? " • đã sửa" : "") + (m.pinned ? "  " + UiKit.pinGlyph() : ""));
        head.setFont(UiKit.FONT_SMALL);
        head.setForeground(mine ? new Color(0x1E40AF) : UiKit.colorFor(m.sender).darker());
        b.add(head, BorderLayout.NORTH);
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        if (m.deleted) {
            JLabel d = new JLabel("Tin nhắn đã được thu hồi");
            d.setFont(MSG_FONT.deriveFont(Font.ITALIC));
            d.setForeground(UiKit.MUTED);
            d.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(d);
        } else {
            if (m.replyTo > 0) body.add(replyQuote(c, m));
            if (file) body.add(fileBody(m, mine));
            else {
                JTextArea ta = textArea(m.text, UiKit.TEXT);
                ta.setAlignmentX(Component.LEFT_ALIGNMENT);
                body.add(ta);
            }
        }
        b.add(body, BorderLayout.CENTER);
        if (!m.reactions.isEmpty() && !m.deleted) b.add(reactionBar(m), BorderLayout.SOUTH);
        return b;
    }

    private JComponent replyQuote(Conversation c, ClientMsg m) {
        JPanel q = new JPanel(new BorderLayout());
        q.setOpaque(true);
        q.setBackground(new Color(0, 0, 0, 14));
        q.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, UiKit.PRIMARY), new EmptyBorder(2, 6, 2, 6)));
        JLabel l = new JLabel("<html><b>" + esc(dn(m.replySender)) + "</b><br><font size=2>" + esc(m.replyText.isEmpty() ? "(tin nhắn gốc)" : m.replyText) + "</font></html>");
        l.setForeground(UiKit.MUTED);
        q.add(l, BorderLayout.CENTER);
        q.setAlignmentX(Component.LEFT_ALIGNMENT);
        q.setMaximumSize(new Dimension(BUBBLE_MAX_W, 60));
        q.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        final long target = m.replyTo;
        q.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { scrollToMessage(c, target); } });
        return q;
    }

    private JComponent fileBody(ClientMsg m, boolean mine) {
        JPanel p = new JPanel(new BorderLayout(8, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean img = TextUtil.isImageName(m.fileName);
        if (img && m.fileSize <= THUMB_MAX_BYTES) {
            JLabel thumb = new JLabel("Đang tải ảnh...", SwingConstants.CENTER);
            thumb.setForeground(UiKit.MUTED);
            thumb.setPreferredSize(new Dimension(160, 90));
            thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final ClientMsg fm = m;
            thumb.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { File f = thumbFiles.get(fm.fileId); if (f != null) showImagePreview(f); }
            });
            p.add(thumb, BorderLayout.NORTH);
            requestThumb(m, thumb);
        }
        JLabel info = new JLabel("<html><b>" + (img ? "[Hình ảnh] " : "[File] ") + esc(m.fileName) + "</b><br><font size=2 color='#555555'>"
                + sizeStr(m.fileSize) + (mine ? " • đã gửi" : " • file nhận được") + "</font></html>");
        JButton dl = UiKit.ghost("Tải về");
        dl.addActionListener(e -> downloadFile(m.fileId, m.fileName));
        p.add(info, BorderLayout.CENTER);
        p.add(dl, BorderLayout.EAST);
        return p;
    }

    private JComponent reactionBar(ClientMsg m) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.setOpaque(false);
        for (Map.Entry<String, List<String>> e : m.reactions.entrySet()) {
            boolean mineR = false;
            List<String> names = new ArrayList<>();
            for (String u : e.getValue()) { if (u.equalsIgnoreCase(me)) mineR = true; names.add(dn(u)); }
            JLabel chip = new JLabel(UiKit.reactionGlyph(e.getKey()) + " " + e.getValue().size());
            chip.setOpaque(true);
            chip.setBackground(mineR ? UiKit.PRIMARY_SOFT : new Color(0xF1F5F9));
            chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(mineR ? UiKit.PRIMARY : UiKit.BORDER), new EmptyBorder(0, 6, 0, 6)));
            chip.setToolTipText(String.join(", ", names));
            chip.setFont(UiKit.FONT_SMALL);
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final String code = e.getKey();
            chip.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent ev) { client.trySend(Protocol.join(Protocol.REACT, m.id, code)); }
            });
            bar.add(chip);
        }
        return bar;
    }

    /** Menu chuot phai tren bong bong: tra loi, cam xuc, sao chep, sua, thu hoi, ghim. */
    private void attachMenu(Component root, Conversation c, ClientMsg m) {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { pop(e); }
            @Override public void mouseReleased(MouseEvent e) { pop(e); }
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && !(e.getSource() instanceof JButton)) startReply(m); }
            void pop(MouseEvent e) { if (e.isPopupTrigger()) buildMenu(c, m).show(e.getComponent(), e.getX(), e.getY()); }
        };
        attachRec(root, ma);
    }

    private void attachRec(Component c, MouseAdapter ma) {
        if (!(c instanceof JButton)) c.addMouseListener(ma);
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) attachRec(k, ma);
    }

    private JPopupMenu buildMenu(Conversation c, ClientMsg m) {
        JPopupMenu pm = new JPopupMenu();
        pm.add(item("Trả lời", null, e -> startReply(m)));
        JMenu react = new JMenu("Thả cảm xúc");
        for (String code : Protocol.REACTIONS) react.add(item(UiKit.reactionGlyph(code) + "  " + code, null, e -> client.trySend(Protocol.join(Protocol.REACT, m.id, code))));
        pm.add(react);
        if (Protocol.TYPE_TEXT.equals(m.type)) pm.add(item("Sao chép nội dung", null, e ->
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(m.text), null)));
        boolean mine = m.sender.equalsIgnoreCase(me);
        if (mine && Protocol.TYPE_TEXT.equals(m.type)) pm.add(item("Sửa tin nhắn", null, e -> startEdit(m)));
        if (!c.group || iAmMod(c)) pm.add(item(m.pinned ? "Bỏ ghim" : "Ghim tin nhắn", null, e -> client.trySend(Protocol.join(Protocol.PIN_MSG, m.id, m.pinned ? 0 : 1))));
        if (mine || iAmMod(c)) pm.add(item(mine ? "Thu hồi tin nhắn" : "Thu hồi (quyền quản lý phòng)", null, e -> {
            if (JOptionPane.showConfirmDialog(this, "Thu hồi tin nhắn này? Nội dung sẽ bị xóa với mọi người.", "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                client.trySend(Protocol.join(Protocol.RECALL_MSG, m.id));
        }));
        return pm;
    }

    // dung cho test giao dien
    Map<String, Conversation> conversations() { return convs; }
    Conversation currentConversation() { return cur; }
    JList<RoomItem> roomListForTest() { return roomList; }
}
