package client;

import common.Config;
import common.CryptoUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

/** Man hinh Dang nhap / Dang ky + nhap Server IP, Port. Diem khoi dong cua Client. */
public class LoginFrame extends JFrame implements ChatClient.Listener {
    private final JTextField hostField = UiKit.styleField(new JTextField(16));
    private final JTextField portField = UiKit.styleField(new JTextField("5555", 6));
    private final JTextField userField = UiKit.styleField(new JTextField(16));
    private final JPasswordField passField = UiKit.styleField(new JPasswordField(16));
    private final JButton loginBtn = UiKit.primary("Đăng nhập");
    private final JButton registerBtn = UiKit.secondary("Tạo tài khoản");
    private final JLabel status = new JLabel(" ");
    private volatile ChatClient client;
    private final Properties cfg;
    private String host, user, pass;
    private int port;

    public LoginFrame(Properties cfg) {
        this(cfg, null);
    }

    public LoginFrame(Properties cfg, String notice) {
        super("GroupChat - Đăng nhập");
        this.cfg = cfg;
        hostField.setText(cfg.getProperty("server.host", "127.0.0.1"));
        portField.setText(cfg.getProperty("server.port", "5555"));
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiKit.BG);

        JPanel hero = new JPanel(new GridLayout(3, 1, 0, 2)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x1E3A8A), getWidth(), getHeight(), new Color(0x2563EB)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        hero.setBorder(new EmptyBorder(26, 30, 22, 30));
        JLabel logo = new JLabel(UiKit.avatar("G", null, 54));
        JLabel title = new JLabel("GroupChat");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setForeground(Color.WHITE);
        JLabel sub = new JLabel("Chat nhóm bảo mật • TLS • PBKDF2 • SQLite");
        sub.setForeground(new Color(0xBFDBFE));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        top.setOpaque(false);
        top.add(logo);
        top.add(title);
        hero.add(top);
        hero.add(sub);
        hero.add(new JLabel(" "));
        root.add(hero, BorderLayout.NORTH);

        JPanel card = UiKit.card(new GridBagLayout());
        card.setBorder(new EmptyBorder(18, 22, 18, 22));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 4, 5, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.weightx = 1; g.gridwidth = 2;
        addRow(card, g, 0, "Địa chỉ Server (IP / Tailscale / ZeroTier)", hostField);
        g.gridwidth = 1;
        g.gridy = 1; g.gridx = 0; g.weightx = 0;
        card.add(label("Cổng"), g);
        g.gridx = 1; g.weightx = 1;
        card.add(portField, g);
        g.gridwidth = 2; g.gridx = 0;
        addRow(card, g, 2, "Tên đăng nhập", userField);
        addRow(card, g, 4, "Mật khẩu", passField);
        JPanel btns = new JPanel(new GridLayout(1, 2, 10, 0));
        btns.setOpaque(false);
        btns.add(loginBtn);
        btns.add(registerBtn);
        g.gridy = 6; g.insets = new Insets(14, 4, 4, 4);
        card.add(btns, g);
        status.setHorizontalAlignment(SwingConstants.CENTER);
        g.gridy = 7; g.insets = new Insets(6, 4, 0, 4);
        card.add(status, g);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(18, 22, 8, 22));
        wrap.add(card, BorderLayout.CENTER);
        root.add(wrap, BorderLayout.CENTER);
        JLabel foot = new JLabel("Kênh truyền được mã hóa TLS • lần đầu kết nối sẽ ghi nhớ vân tay chứng chỉ Server", SwingConstants.CENTER);
        foot.setFont(UiKit.FONT_SMALL);
        foot.setForeground(UiKit.MUTED);
        foot.setBorder(new EmptyBorder(0, 10, 12, 10));
        root.add(foot, BorderLayout.SOUTH);

        setContentPane(root);
        loginBtn.addActionListener(e -> doAuth(false));
        registerBtn.addActionListener(e -> doAuth(true));
        passField.addActionListener(e -> doAuth(false));
        userField.addActionListener(e -> passField.requestFocus());
        getRootPane().setDefaultButton(loginBtn);
        setSize(440, 560);
        setMinimumSize(new Dimension(420, 540));
        setLocationRelativeTo(null);
        if (notice != null) setStatus(notice, true);
    }

    private static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(UiKit.MUTED);
        l.setFont(UiKit.FONT_SMALL);
        return l;
    }

    private void addRow(JPanel p, GridBagConstraints g, int row, String label, JComponent c) {
        g.gridy = row; g.gridx = 0;
        p.add(label(label), g);
        g.gridy = row + 1;
        p.add(c, g);
    }

    private void setStatus(String msg, boolean error) {
        status.setForeground(error ? UiKit.DANGER : UiKit.OK);
        status.setText("<html><div style='text-align:center;width:320px'>" + UiKit.esc(msg) + "</div></html>");
    }

    private void setBusy(boolean busy) {
        loginBtn.setEnabled(!busy);
        registerBtn.setEnabled(!busy);
    }

    private void doAuth(boolean register) {
        host = hostField.getText().trim();
        user = userField.getText().trim();
        pass = new String(passField.getPassword());
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            setStatus("Cổng không hợp lệ", true);
            return;
        }
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            setStatus("Vui lòng nhập đầy đủ địa chỉ Server, tên đăng nhập và mật khẩu", true);
            return;
        }
        if (user.contains("|") || pass.contains("|")) {
            setStatus("Không được dùng ký tự | trong tên đăng nhập / mật khẩu", true);
            return;
        }
        setBusy(true);
        setStatus("Đang kết nối " + host + ":" + port + " ...", false);
        final String cmd = (register ? "AUTH_REGISTER|" : "AUTH_LOGIN|") + user + "|" + pass;
        new Thread(() -> {
            ChatClient c = new ChatClient();
            try {
                c.setListener(this);
                client = c;
                c.connect(host, port, 5000);
                c.send(cmd);
            } catch (IOException e) {
                c.close();
                SwingUtilities.invokeLater(() -> {
                    setStatus("Không kết nối được tới " + host + ":" + port + " — " + e.getMessage(), true);
                    setBusy(false);
                });
            }
        }, "login-connect").start();
    }

    @Override
    public void onFrame(String[] p) {
        switch (p[0]) {
            case "AUTH_OK": {
                if (Boolean.parseBoolean(cfg.getProperty("reconnect.enabled", "true")))
                    client.enableAutoReconnect(user, pass, Integer.parseInt(cfg.getProperty("reconnect.max.attempts", "40")));
                MainFrame mf = new MainFrame(client, p[1], host, port, cfg);
                client.setListener(mf);
                mf.setVisible(true);
                dispose();
                break;
            }
            case "AUTH_FAIL":
            case "REGISTER_FAIL":
                setStatus(p.length > 1 ? p[1] : "Thất bại", true);
                client.close();
                setBusy(false);
                break;
            case "REGISTER_OK":
                setStatus("Đăng ký thành công! Hãy bấm Đăng nhập.", false);
                client.close();
                setBusy(false);
                break;
            default:
        }
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus(reason, true);
        setBusy(false);
    }

    public static void setupLookAndFeel() { UiKit.setupLookAndFeel(); }

    /** Doc cau hinh TLS/AES tu client.properties va ap dung cho ChatClient. */
    public static void applyConfig(Properties cfg) {
        CryptoUtil.setSecret(cfg.getProperty("aes.secret", CryptoUtil.DEFAULT_SECRET));
        ChatClient.configureTls(Boolean.parseBoolean(cfg.getProperty("tls.enabled", "true")),
                cfg.getProperty("server.fingerprint", ""), new File("config/known_servers.properties"));
    }

    public static void main(String[] args) {
        Properties cfg = Config.load("config/client.properties");
        if (args.length > 0) cfg.setProperty("server.host", args[0]);
        if (args.length > 1) cfg.setProperty("server.port", args[1]);
        applyConfig(cfg);
        SwingUtilities.invokeLater(() -> {
            UiKit.setupLookAndFeel();
            new LoginFrame(cfg).setVisible(true);
        });
    }
}
