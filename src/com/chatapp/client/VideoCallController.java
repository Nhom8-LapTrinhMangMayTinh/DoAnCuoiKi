package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Client call signaling and encrypted media coordinator. All signaling is validated again by the server. */
final class VideoCallController {
    private final JFrame owner;
    private final String username;
    private final Supplier<String> currentPrivatePeer;
    private final Function<String, String> displayName;
    private final Predicate<String> sendCommand;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "Chat-VideoCall-Control");
        t.setDaemon(true);
        return t;
    });
    private volatile CallState active;
    private IncomingPrompt incomingPrompt;

    VideoCallController(JFrame owner, String username, Supplier<String> currentPrivatePeer,
                        Function<String, String> displayName, Predicate<String> sendCommand) {
        this.owner = owner;
        this.username = username;
        this.currentPrivatePeer = currentPrivatePeer;
        this.displayName = displayName;
        this.sendCommand = sendCommand;
    }

    static boolean isCallControlPacket(String command) {
        if (command == null || !command.startsWith("CALL_")) return false;
        return !(Protocol.CALL_AUDIO.equals(command) || Protocol.CALL_VIDEO.equals(command));
    }

    static boolean isMediaPacket(String command) {
        return Protocol.CALL_AUDIO.equals(command) || Protocol.CALL_VIDEO.equals(command);
    }

    boolean isInCall() { return active != null; }

    void startOutgoingCall() {
        String peer = currentPrivatePeer.get();
        if (peer == null || peer.isBlank() || peer.equals(username)) {
            javax.swing.JOptionPane.showMessageDialog(owner,
                    "Hãy mở một cuộc trò chuyện riêng với người nhận trước khi gọi video.",
                    "Gọi video", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (active != null) {
            javax.swing.JOptionPane.showMessageDialog(owner, "Bạn đang có một cuộc gọi khác.",
                    "Gọi video", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        String callId = UUID.randomUUID().toString();
        CallState state = new CallState(callId, peer, true);
        active = state;
        createWindow(state, "Đang gửi lời mời…");
        state.window.showCallWindow();
        if (!sendCommand.test(Protocol.join(Protocol.CALL_INVITE, peer, callId))) {
            closeLocal(state, "Không thể gửi lời mời gọi.");
        }
    }

    void handleServerMessage(String[] p) {
        if (p == null || p.length == 0) return;
        String cmd = p[0];
        switch (cmd) {
            case Protocol.CALL_INCOMING: onIncoming(p); break;
            case Protocol.CALL_RINGING: onRinging(p); break;
            case Protocol.CALL_ACCEPTED: onAccepted(p); break;
            case Protocol.CALL_REJECTED: onRejected(p); break;
            case Protocol.CALL_CANCELED: onCanceled(p); break;
            case Protocol.CALL_BUSY: onTerminalMessage(p, "Người nhận đang bận."); break;
            case Protocol.CALL_OFFLINE: onTerminalMessage(p, "Người nhận hiện không trực tuyến."); break;
            case Protocol.CALL_RATE_LIMIT: onTerminalMessage(p, p.length > 2 ? p[2] : "Bạn đang mời gọi quá nhanh."); break;
            case Protocol.CALL_TIMEOUT: onTerminalMessage(p, "Hết thời gian đổ chuông (30 giây)."); break;
            case Protocol.CALL_MISSED: onTerminalMessage(p, "Cuộc gọi nhỡ từ " + safeDisplay(p.length > 2 ? p[2] : "người dùng") + "."); break;
            case Protocol.CALL_ENDED: onEnded(p); break;
            case Protocol.CALL_ERROR: onCallError(p); break;
            case Protocol.CALL_KEY_PUBLIC: onPublicKey(p); break;
            case Protocol.CALL_KEY_OFFER: onKeyOffer(p); break;
            case Protocol.CALL_KEY_READY: onKeyReady(p); break;
            case Protocol.CALL_MEDIA_STATE: onPeerMediaState(p); break;
            case Protocol.CALL_PING: onPing(p); break;
            case Protocol.CALL_PONG: onPong(p); break;
            default: break;
        }
    }

    /** Must be called directly from the socket reader thread; video is coalesced in the window. */
    void handleMediaPacket(String[] p) {
        CallState state = active;
        if (state == null || p.length < 4 || !state.id.equals(p[1]) || !state.keyReady) return;
        try {
            byte[] encrypted = Base64.getDecoder().decode(p[p.length - 1]);
            byte[] content = state.crypto.decrypt(encrypted);
            if (Protocol.CALL_AUDIO.equals(p[0])) {
                VideoCallWindow window = state.window;
                if (window != null) window.acceptRemoteAudio(content);
            } else if (Protocol.CALL_VIDEO.equals(p[0])) {
                VideoCallWindow window = state.window;
                if (window != null) window.acceptRemoteVideo(content);
            }
        } catch (Exception ignored) {
            // Do not show cryptographic parse detail to the other users. Bad/tampered packets are dropped.
        }
    }

    void onConnectionLost() {
        timer.shutdownNow();
        SwingUtilities.invokeLater(() -> {
            CallState state = active;
            if (state != null) closeLocal(state, "Kết nối với server bị mất.");
            closePrompt();
        });
    }

    private void onIncoming(String[] p) {
        if (p.length < 3) return;
        String callId = p[1];
        String caller = p[2];
        if (active != null && !active.id.equals(callId)) {
            sendCommand.test(Protocol.join(Protocol.CALL_REJECT, callId));
            return;
        }
        CallState state = active;
        if (state == null) {
            state = new CallState(callId, caller, false);
            active = state;
        }
        if (state.outgoing || !state.id.equals(callId)) return;
        showIncomingPrompt(state, caller);
    }

    private void showIncomingPrompt(CallState state, String caller) {
        closePrompt();
        JDialog dialog = new JDialog(owner, "Cuộc gọi video đến", false);
        JPanel root = new JPanel(new BorderLayout(10, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        JLabel icon = new JLabel("▣", SwingConstants.CENTER);
        icon.setForeground(new Color(37, 99, 235));
        icon.setFont(new Font("Segoe UI", Font.BOLD, 36));
        JLabel title = new JLabel(safeDisplay(caller) + " đang gọi video cho bạn", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 16));
        JLabel sub = new JLabel("Đổ chuông tối đa 30 giây", SwingConstants.CENTER);
        sub.setForeground(new Color(100, 116, 139));
        JPanel text = new JPanel(new BorderLayout(0, 5));
        text.add(title, BorderLayout.CENTER);
        text.add(sub, BorderLayout.SOUTH);
        incomingPrompt = new IncomingPrompt(state, dialog);
        text.add(incomingPrompt.countdown, BorderLayout.NORTH);
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(icon, BorderLayout.NORTH);
        top.add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        JButton reject = new JButton("Từ chối");
        JButton accept = new JButton("Nhận cuộc gọi");
        reject.setBackground(new Color(220, 38, 38));
        reject.setForeground(Color.WHITE);
        accept.setBackground(new Color(22, 163, 74));
        accept.setForeground(Color.WHITE);
        reject.addActionListener(e -> {
            sendCommand.test(Protocol.join(Protocol.CALL_REJECT, state.id));
            closePrompt();
        });
        accept.addActionListener(e -> {
            sendCommand.test(Protocol.join(Protocol.CALL_ACCEPT, state.id));
            closePrompt();
            createWindow(state, "Đang kết nối cuộc gọi…");
            state.window.showCallWindow();
        });
        buttons.add(reject);
        buttons.add(accept);
        root.add(top, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(420, 220);
        dialog.setLocationRelativeTo(owner);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                sendCommand.test(Protocol.join(Protocol.CALL_REJECT, state.id));
                closePrompt();
            }
        });
        incomingPrompt.start();
        dialog.setVisible(true);
    }

    private void onRinging(String[] p) {
        CallState state = match(p);
        if (state != null && state.window != null) state.window.setStatus("Đang đổ chuông cho " + safeDisplay(state.peer) + "…");
    }

    private void onAccepted(String[] p) {
        CallState state = match(p);
        if (state == null) return;
        state.ringing = false;
        if (state.window == null) createWindow(state, "Đang thiết lập kênh bảo mật…");
        state.window.showCallWindow();
        state.window.setStatus("Đang thiết lập mã hóa đầu cuối…");
        try {
            String publicKey = state.crypto.createPublicKey();
            sendCommand.test(Protocol.join(Protocol.CALL_KEY_PUBLIC, state.id, publicKey));
            state.publicKeySent = true;
        } catch (Exception ex) {
            failCall(state, "Không thể tạo khóa bảo mật cho cuộc gọi.");
        }
    }

    private void onPublicKey(String[] p) {
        CallState state = match(p);
        if (state == null || !state.outgoing || p.length < 3 || state.keyOfferSent) return;
        try {
            String wrapped = state.crypto.createKeyOffer(p[2]);
            state.keyOfferSent = true;
            sendCommand.test(Protocol.join(Protocol.CALL_KEY_OFFER, state.id, wrapped));
        } catch (Exception ex) {
            failCall(state, "Không thể thiết lập khóa media an toàn.");
        }
    }

    private void onKeyOffer(String[] p) {
        CallState state = match(p);
        if (state == null || state.outgoing || p.length < 3 || state.keyReady) return;
        try {
            state.crypto.acceptKeyOffer(p[2]);
            state.keyReady = true;
            sendCommand.test(Protocol.join(Protocol.CALL_KEY_READY, state.id));
            activateMedia(state);
        } catch (Exception ex) {
            failCall(state, "Không thể xác thực khóa media của cuộc gọi.");
        }
    }

    private void onKeyReady(String[] p) {
        CallState state = match(p);
        if (state == null || !state.outgoing || state.keyReady) return;
        state.keyReady = true;
        activateMedia(state);
    }

    private void activateMedia(CallState state) {
        if (active != state || state.window == null || state.mediaActivated) return;
        state.mediaActivated = true;
        state.window.markActive();
        sendCommand.test(Protocol.join(Protocol.CALL_MEDIA_STATE, state.id,
                state.window.isMicrophoneOn() ? "1" : "0", state.window.isCameraOn() ? "1" : "0"));
        state.pingTask = timer.scheduleAtFixedRate(() -> {
            if (active == state && state.keyReady) {
                long now = System.currentTimeMillis();
                state.lastPing = now;
                sendCommand.test(Protocol.join(Protocol.CALL_PING, state.id, now));
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void onPeerMediaState(String[] p) {
        CallState state = match(p);
        if (state == null || state.window == null || p.length < 4) return;
        state.window.updatePeerMediaState("1".equals(p[2]), "1".equals(p[3]));
    }

    private void onPing(String[] p) {
        if (p.length >= 3) sendCommand.test(Protocol.join(Protocol.CALL_PONG, p[1], p[2]));
    }

    private void onPong(String[] p) {
        CallState state = match(p);
        if (state == null || p.length < 3) return;
        try {
            long sent = Long.parseLong(p[2]);
            int rtt = (int) Math.min(Integer.MAX_VALUE, Math.max(0, System.currentTimeMillis() - sent));
            state.rttMillis = rtt;
            if (state.window != null) state.window.updateRtt(rtt);
        } catch (NumberFormatException ignored) { }
    }

    private void onRejected(String[] p) {
        CallState state = match(p);
        if (state != null) closeLocal(state, "Cuộc gọi đã bị từ chối.");
    }

    private void onCanceled(String[] p) {
        CallState state = match(p);
        if (state != null) closeLocal(state, "Cuộc gọi đã bị hủy.");
    }

    private void onEnded(String[] p) {
        CallState state = match(p);
        if (state == null) return;
        String reason = p.length > 3 ? p[3] : "ENDED";
        String message;
        switch (reason) {
            case "DISCONNECTED": message = "Cuộc gọi kết thúc do một bên mất kết nối."; break;
            case "CANCELED": message = "Cuộc gọi đã bị hủy."; break;
            default: message = "Cuộc gọi đã kết thúc."; break;
        }
        closeLocal(state, message);
    }

    private void onTerminalMessage(String[] p, String status) {
        CallState state = match(p);
        if (state == null) return;
        if (state.window != null) state.window.setStatus(status);
        if (incomingPrompt != null && incomingPrompt.state == state) closePrompt();
        closeLocal(state, status);
        if (!Protocol.CALL_MISSED.equals(p[0])) {
            javax.swing.JOptionPane.showMessageDialog(owner, status, "Cuộc gọi video", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void onCallError(String[] p) {
        String id = p.length > 1 ? p[1] : "";
        CallState state = active;
        String error = p.length > 2 ? p[2] : "Không thể xử lý cuộc gọi.";

        // Backward compatibility with a server which may still answer a last in-flight frame
        // with CALL_ERROR after CALL_ENDED has already closed this call on the client.
        // This is a teardown race, not a user-facing error: never put a modal dialog on top
        // of chat for a call ID that is no longer active.
        if (isStaleCallTrafficError(error)) {
            if (state != null && (id.isEmpty() || state.id.equals(id))) {
                closeLocal(state, "Cuộc gọi đã kết thúc.");
            }
            return;
        }

        if (state != null && (id.isEmpty() || state.id.equals(id))) {
            if (state.window != null) state.window.setStatus(error);
            closeLocal(state, error);
        } else {
            // Responses for an old call may arrive after the user has already started
            // returning to chat. Ignore them instead of interrupting the next UI action.
            if (!id.isEmpty()) return;
            javax.swing.JOptionPane.showMessageDialog(owner, error, "Gọi video", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    private static boolean isStaleCallTrafficError(String error) {
        if (error == null) return false;
        String value = error.toLowerCase(Locale.ROOT);
        return value.contains("gói dữ liệu bị từ chối")
                || value.contains("không thuộc cuộc gọi đang hoạt động")
                || value.contains("cuộc gọi không còn tồn tại");
    }

    private void createWindow(CallState state, String status) {
        if (state.window != null) return;
        state.window = new VideoCallWindow(owner, username, safeDisplay(state.peer), new VideoCallWindow.MediaSink() {
            @Override public void sendAudio(byte[] ulawFrame) {
                if (active != state || !state.keyReady) return;
                try {
                    byte[] encrypted = state.crypto.encrypt(ulawFrame);
                    sendCommand.test(Protocol.join(Protocol.CALL_AUDIO, state.id,
                            state.audioSequence.getAndIncrement(), Base64.getEncoder().encodeToString(encrypted)));
                } catch (Exception ignored) { }
            }
            @Override public void sendVideo(byte[] jpeg, int quality) {
                if (active != state || !state.keyReady) return;
                try {
                    byte[] encrypted = state.crypto.encrypt(jpeg);
                    sendCommand.test(Protocol.join(Protocol.CALL_VIDEO, state.id,
                            state.videoSequence.getAndIncrement(), quality,
                            Base64.getEncoder().encodeToString(encrypted)));
                } catch (Exception ignored) { }
            }
            @Override public void sendMediaState(boolean mic, boolean camera) {
                if (active == state && state.keyReady) {
                    sendCommand.test(Protocol.join(Protocol.CALL_MEDIA_STATE, state.id,
                            mic ? "1" : "0", camera ? "1" : "0"));
                }
            }
        }, () -> hangUp(state), () -> state.rttMillis);
        state.window.setStatus(status);
    }

    private void hangUp(CallState state) {
        if (active != state) return;
        if (state.ringing) {
            sendCommand.test(Protocol.join(state.outgoing ? Protocol.CALL_CANCEL : Protocol.CALL_REJECT, state.id));
        } else {
            sendCommand.test(Protocol.join(Protocol.CALL_END, state.id));
        }
        closeLocal(state, "Đang kết thúc cuộc gọi…");
    }

    private void failCall(CallState state, String reason) {
        sendCommand.test(Protocol.join(state.ringing ? (state.outgoing ? Protocol.CALL_CANCEL : Protocol.CALL_REJECT)
                : Protocol.CALL_END, state.id));
        closeLocal(state, reason);
        javax.swing.JOptionPane.showMessageDialog(owner, reason, "Gọi video", javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    private void closeLocal(CallState state, String reason) {
        if (active != state) return;
        active = null;
        closePrompt();
        if (state.pingTask != null) state.pingTask.cancel(true);
        if (state.window != null) state.window.closeFromController(reason);
        state.crypto.clear();
    }

    private void closePrompt() {
        IncomingPrompt prompt = incomingPrompt;
        incomingPrompt = null;
        if (prompt != null) prompt.stop();
    }

    private CallState match(String[] p) {
        if (p.length < 2) return null;
        CallState state = active;
        return state != null && state.id.equals(p[1]) ? state : null;
    }

    private String safeDisplay(String peer) {
        if (peer == null || peer.isBlank()) return "người dùng";
        try {
            String value = displayName.apply(peer);
            return value == null || value.isBlank() ? peer : value;
        } catch (Exception ignored) { return peer; }
    }

    private final class IncomingPrompt {
        final CallState state;
        final JDialog dialog;
        final long startedAt = System.currentTimeMillis();
        final JLabel countdown;
        final ScheduledFuture<?> beeper;
        final Timer uiTimer;
        IncomingPrompt(CallState state, JDialog dialog) {
            this.state = state;
            this.dialog = dialog;
            this.countdown = new JLabel("30 giây còn lại", SwingConstants.CENTER);
            this.beeper = timer.scheduleAtFixedRate(() -> {
                try { Toolkit.getDefaultToolkit().beep(); } catch (Exception ignored) { }
            }, 0, 1400, TimeUnit.MILLISECONDS);
            this.uiTimer = new Timer(250, e -> {
                long left = Math.max(0, 30 - (System.currentTimeMillis() - startedAt) / 1000);
                countdown.setText(left + " giây còn lại");
            });
            uiTimer.start();
        }
        void start() { }
        void stop() {
            beeper.cancel(true);
            uiTimer.stop();
            dialog.setVisible(false);
            dialog.dispose();
        }
    }

    private static final class CallState {
        final String id;
        final String peer;
        final boolean outgoing;
        final CallCrypto crypto = new CallCrypto();
        final AtomicLong audioSequence = new AtomicLong();
        final AtomicLong videoSequence = new AtomicLong();
        volatile VideoCallWindow window;
        volatile boolean ringing = true;
        volatile boolean keyReady;
        volatile boolean keyOfferSent;
        volatile boolean publicKeySent;
        volatile boolean mediaActivated;
        volatile int rttMillis = -1;
        volatile long lastPing;
        volatile ScheduledFuture<?> pingTask;
        CallState(String id, String peer, boolean outgoing) { this.id = id; this.peer = peer; this.outgoing = outgoing; }
    }
}
