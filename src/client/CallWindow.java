package client;

import common.Protocol;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** Cua so cuoc goi video: video doi phuong, xem truoc may minh (PiP), tat/bat mic - camera, chon nguon hinh, thong ke, ket thuc. */
public class CallWindow extends JFrame implements CallSession.Ui {
    enum State { CALLING, CONNECTING, ACTIVE, ENDED }

    private final MainFrame owner;
    private final ChatClient client;
    final String peer;
    private final String peerDisplay;
    volatile String callId;
    private final boolean outgoing;
    private volatile State state;
    private CallSession session;
    private volatile BufferedImage remoteImg, localImg;
    private volatile boolean remoteCamOff, remoteMicOff;
    private final JLabel title = new JLabel(), timer = new JLabel("00:00"), statsLabel = new JLabel(" "), notice = new JLabel(" ", SwingConstants.CENTER);
    private final JToggleButton micBtn = new JToggleButton("Mic: BẬT"), camBtn = new JToggleButton("Camera: BẬT");
    private final JComboBox<String> sourceBox = new JComboBox<>();
    private final JButton hangBtn = UiKit.danger("Kết thúc");
    private final JPanel stage;
    private long activeSince;
    private javax.swing.Timer clock;
    private boolean logged;

    CallWindow(MainFrame owner, ChatClient client, String peer, String peerDisplay, String callId, boolean outgoing) {
        super("Cuộc gọi video với " + peerDisplay);
        this.owner = owner; this.client = client; this.peer = peer; this.peerDisplay = peerDisplay; this.callId = callId; this.outgoing = outgoing;
        this.state = outgoing ? State.CALLING : State.CONNECTING;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { hangup(); } });

        stage = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int w = getWidth(), h = getHeight();
                g.setColor(new Color(0x0B1220));
                g.fillRect(0, 0, w, h);
                BufferedImage r = remoteImg;
                if (r != null && !remoteCamOff && state == State.ACTIVE) {
                    double s = Math.min((double) w / r.getWidth(), (double) h / r.getHeight());
                    int iw = (int) (r.getWidth() * s), ih = (int) (r.getHeight() * s);
                    g.drawImage(r, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
                } else {
                    g.drawImage(UiKit.avatar(peerDisplay, null, 120).getImage(), w / 2 - 60, h / 2 - 110, null);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.BOLD, 20));
                    String t1 = peerDisplay;
                    g.drawString(t1, (w - g.getFontMetrics().stringWidth(t1)) / 2, h / 2 + 40);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 15));
                    g.setColor(new Color(0xCBD5E1));
                    String t2 = state == State.CALLING ? "Đang đổ chuông..." : state == State.CONNECTING ? "Đang kết nối..." : state == State.ENDED ? "Cuộc gọi đã kết thúc"
                            : remoteCamOff ? "Đối phương đã tắt camera" : "Đang chờ hình từ đối phương...";
                    g.drawString(t2, (w - g.getFontMetrics().stringWidth(t2)) / 2, h / 2 + 68);
                }
                if (remoteMicOff && state == State.ACTIVE) {
                    g.setColor(new Color(0, 0, 0, 150));
                    g.fill(new RoundRectangle2D.Float(14, 14, 190, 28, 12, 12));
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
                    g.drawString("Đối phương đã tắt mic", 24, 33);
                }
                BufferedImage l = localImg;
                if (l != null && camBtn.isSelected() == false && state != State.ENDED) {
                    int pw = Math.max(140, w / 5), ph = pw * 9 / 16;
                    int px = w - pw - 14, py = h - ph - 14;
                    g.setClip(new RoundRectangle2D.Float(px, py, pw, ph, 12, 12));
                    g.drawImage(l, px, py, pw, ph, null);
                    g.setClip(null);
                    g.setColor(new Color(255, 255, 255, 200));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new RoundRectangle2D.Float(px, py, pw, ph, 12, 12));
                    g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                    g.drawString("Bạn" + (sourceBox.getSelectedItem() == null ? "" : " • " + sourceBox.getSelectedItem()), px + 6, py + ph - 6);
                }
                g.dispose();
            }
        };
        stage.setPreferredSize(new Dimension(760, 460));

        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setForeground(Color.WHITE);
        timer.setForeground(new Color(0x86EFAC));
        timer.setFont(timer.getFont().deriveFont(Font.BOLD, 15f));
        statsLabel.setForeground(new Color(0x94A3B8));
        statsLabel.setFont(UiKit.FONT_SMALL);
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBackground(new Color(0x111827));
        top.setBorder(new EmptyBorder(8, 14, 8, 14));
        top.add(title, BorderLayout.WEST);
        top.add(statsLabel, BorderLayout.CENTER);
        top.add(timer, BorderLayout.EAST);

        notice.setForeground(new Color(0xFCD34D));
        notice.setFont(UiKit.FONT_SMALL);
        notice.setBorder(new EmptyBorder(3, 6, 3, 6));
        for (String s : VideoSources.available()) sourceBox.addItem(s);
        sourceBox.setToolTipText("Nguồn hình gửi cho đối phương");
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        controls.setBackground(new Color(0x111827));
        micBtn.setFocusPainted(false);
        camBtn.setFocusPainted(false);
        controls.add(micBtn); controls.add(camBtn);
        JLabel lbl = new JLabel("Nguồn hình:");
        lbl.setForeground(new Color(0xCBD5E1));
        controls.add(lbl); controls.add(sourceBox);
        controls.add(hangBtn);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(new Color(0x111827));
        south.add(notice, BorderLayout.NORTH);
        south.add(controls, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(stage, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);
        title.setText((outgoing ? "Gọi " : "Cuộc gọi từ ") + peerDisplay);

        micBtn.addActionListener(e -> {
            boolean muted = micBtn.isSelected();
            micBtn.setText(muted ? "Mic: TẮT" : "Mic: BẬT");
            if (session != null) session.setMicMuted(muted);
        });
        camBtn.addActionListener(e -> {
            boolean off = camBtn.isSelected();
            camBtn.setText(off ? "Camera: TẮT" : "Camera: BẬT");
            if (session != null) session.setCameraOn(!off);
            stage.repaint();
        });
        sourceBox.addActionListener(e -> { if (session != null && sourceBox.getSelectedItem() != null) session.switchSource((String) sourceBox.getSelectedItem()); });
        hangBtn.addActionListener(e -> hangup());
        micBtn.setEnabled(false); camBtn.setEnabled(false);
        clock = new javax.swing.Timer(1000, e -> {
            if (state == State.ACTIVE) {
                long s = (System.currentTimeMillis() - activeSince) / 1000;
                timer.setText(String.format("%02d:%02d", s / 60, s % 60));
            }
        });
        clock.start();
    }

    String callId() { return callId; }
    boolean isEnded() { return state == State.ENDED; }

    /** Bao ben kia da nhan may: bat dau gui/nhan hinh-tieng. */
    void onAccepted() {
        if (state == State.ACTIVE || state == State.ENDED) return;
        state = State.ACTIVE;
        activeSince = System.currentTimeMillis();
        micBtn.setEnabled(true);
        camBtn.setEnabled(true);
        title.setText("Đang gọi video với " + peerDisplay);
        session = new CallSession(client, callId, this);
        List<String> src = VideoSources.available();
        String chosen = sourceBox.getSelectedItem() == null ? src.get(0) : (String) sourceBox.getSelectedItem();
        session.start(chosen, true);
        stage.repaint();
    }

    void onRemoteState(boolean cam, boolean mic) {
        remoteCamOff = !cam;
        remoteMicOff = !mic;
        stage.repaint();
    }

    /** Cuoc goi ket thuc (do ben kia / het gio / tu choi / mat ket noi). */
    void onEnded(String reason) {
        if (state == State.ENDED) return;
        boolean wasActive = state == State.ACTIVE;
        long secs = wasActive ? (System.currentTimeMillis() - activeSince) / 1000 : 0;
        state = State.ENDED;
        if (session != null) session.stop();
        micBtn.setEnabled(false); camBtn.setEnabled(false); sourceBox.setEnabled(false); hangBtn.setEnabled(false);
        notice.setText(reason);
        title.setText("Cuộc gọi đã kết thúc");
        stage.repaint();
        if (!logged) {
            logged = true;
            owner.logCall(peer, wasActive ? "Cuộc gọi video với " + peerDisplay + " đã kết thúc (" + Voice.mmss((int) secs) + ")"
                    : (outgoing ? "Cuộc gọi tới " + peerDisplay + " không thành công: " + reason : "Cuộc gọi nhỡ từ " + peerDisplay + ": " + reason));
        }
        javax.swing.Timer t = new javax.swing.Timer(wasActive ? 1500 : 2500, e -> { clock.stop(); dispose(); owner.callClosed(this); });
        t.setRepeats(false);
        t.start();
    }

    /** Nguoi dung bam Ket thuc / dong cua so. */
    void hangup() {
        if (state == State.ENDED) return;
        if (callId != null) client.trySend(Protocol.join(Protocol.CALL_END, callId));
        onEnded(state == State.ACTIVE ? "Bạn đã kết thúc cuộc gọi" : "Bạn đã hủy cuộc gọi");
    }

    CallSession session() { return session; }

    // ---- CallSession.Ui (goi tu luong nen) ----
    @Override public void onLocalFrame(BufferedImage img) { localImg = img; stage.repaint(); }
    @Override public void onRemoteFrame(BufferedImage img) { remoteImg = img; stage.repaint(); }
    @Override public void onStats(CallSession.Stats s) {
        SwingUtilities.invokeLater(() -> statsLabel.setText(String.format("↑ %.0f fps %.0f kbps  |  ↓ %.0f fps %.0f kbps  |  chất lượng %d%%",
                s.fpsOut, s.kbpsOut, s.fpsIn, s.kbpsIn, s.quality)));
    }
    @Override public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
            if (message.startsWith("Mất kết nối")) { client.trySend(Protocol.join(Protocol.CALL_END, callId == null ? "-" : callId)); onEnded(message); }
            else notice.setText(message);
        });
    }
}
