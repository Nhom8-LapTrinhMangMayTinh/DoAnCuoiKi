package client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Hop thoai "co cuoc goi den": Tra loi / Tu choi, keu bip, tu dong tat khi het gio hoac nguoi goi huy. */
public class IncomingCallDialog extends JDialog {
    final String callId;
    private final javax.swing.Timer ring;
    private int left = common.Protocol.CALL_RING_SECONDS;

    IncomingCallDialog(MainFrame owner, String callId, String from, String fromDisplay, boolean video) {
        super(owner, "Cuộc gọi đến", false);
        this.callId = callId;
        setAlwaysOnTop(true);
        JPanel p = new JPanel(new BorderLayout(16, 8));
        p.setBorder(new EmptyBorder(18, 22, 16, 22));
        JLabel av = new JLabel(UiKit.avatar(fromDisplay, null, 64));
        JLabel msg = new JLabel("<html><b style='font-size:15px'>" + UiKit.esc(fromDisplay) + "</b><br>đang gọi " + (video ? "video" : "thoại") + " cho bạn...</html>");
        JLabel timeLeft = new JLabel(" ");
        timeLeft.setForeground(UiKit.MUTED);
        timeLeft.setFont(UiKit.FONT_SMALL);
        JPanel mid = new JPanel(new GridLayout(2, 1));
        mid.add(msg);
        mid.add(timeLeft);
        JButton accept = UiKit.primary("Trả lời"), reject = UiKit.danger("Từ chối");
        accept.setBackground(UiKit.OK);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.add(reject); btns.add(accept);
        p.add(av, BorderLayout.WEST);
        p.add(mid, BorderLayout.CENTER);
        p.add(btns, BorderLayout.SOUTH);
        setContentPane(p);
        pack();
        setLocationRelativeTo(owner);
        accept.addActionListener(e -> { stopRing(); dispose(); owner.acceptCall(callId, from); });
        reject.addActionListener(e -> { stopRing(); dispose(); owner.rejectCall(callId); });
        ring = new javax.swing.Timer(1000, e -> {
            left--;
            timeLeft.setText("Tự động từ chối sau " + left + " giây");
            if ((left % 2) == 0) Toolkit.getDefaultToolkit().beep();
            if (left <= 0) { stopRing(); dispose(); owner.rejectCall(callId); }
        });
        ring.start();
        Toolkit.getDefaultToolkit().beep();
    }

    void stopRing() { ring.stop(); }

    void dismiss() { stopRing(); dispose(); }
}
