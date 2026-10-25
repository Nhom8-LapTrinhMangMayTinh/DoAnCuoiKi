package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Orchestrates group-call signaling, end-to-end key sharing, and group media UI. */
final class GroupCallController {
    private final JFrame owner;
    private final String username;
    private final Function<String, String> displayName;
    private final Predicate<String> sendCommand;
    private final BooleanSupplier oneToOneBusy;
    private final Runnable onStateChanged;
    private final BiConsumer<Integer, String> roomNotice;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "Chat-GroupCall-Control"); t.setDaemon(true); return t;
    });
    private final Map<Integer, RoomSummary> roomStates = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile GroupSession active;

    GroupCallController(JFrame owner, String username, Function<String, String> displayName,
                        Predicate<String> sendCommand, BooleanSupplier oneToOneBusy,
                        Runnable onStateChanged, BiConsumer<Integer, String> roomNotice) {
        this.owner = owner; this.username = username; this.displayName = displayName;
        this.sendCommand = sendCommand; this.oneToOneBusy = oneToOneBusy;
        this.onStateChanged = onStateChanged; this.roomNotice = roomNotice;
    }

    static boolean isGroupControlPacket(String command) {
        return command != null && command.startsWith("GROUP_CALL_")
                && !Protocol.GROUP_CALL_AUDIO.equals(command) && !Protocol.GROUP_CALL_VIDEO.equals(command);
    }
    static boolean isGroupMediaPacket(String command) {
        return Protocol.GROUP_CALL_AUDIO.equals(command) || Protocol.GROUP_CALL_VIDEO.equals(command);
    }

    void requestRoomState(int roomId) {
        if (roomId > 0) sendCommand.test(Protocol.join(Protocol.GROUP_CALL_STATE_REQUEST, roomId));
    }

    boolean isRoomCallActive(int roomId) { return roomStates.containsKey(roomId); }
    String getCallIdForRoom(int roomId) { RoomSummary s = roomStates.get(roomId); return s == null ? "" : s.callId; }
    String getBannerText(int roomId) {
        RoomSummary s = roomStates.get(roomId);
        if (s == null) return "";
        List<String> names = new ArrayList<>();
        for (String username : s.participants) names.add(safeDisplay(username));
        String joined = String.join(", ", names);
        if (joined.length() > 80) joined = joined.substring(0, 77) + "…";
        return "📞 Cuộc gọi nhóm đang diễn ra" + (joined.isBlank() ? "" : " • " + joined) + " — nhấn để tham gia";
    }
    boolean isInCall() { return active != null; }

    void startOrJoinCurrentRoom(int roomId, String roomName) {
        if (roomId <= 0) {
            JOptionPane.showMessageDialog(owner, "Hãy mở một phòng chat trước khi gọi nhóm.", "Gọi nhóm", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (oneToOneBusy.getAsBoolean()) {
            JOptionPane.showMessageDialog(owner, "Bạn đang có cuộc gọi 1-1. Hãy kết thúc cuộc gọi đó trước.", "Gọi nhóm", JOptionPane.WARNING_MESSAGE);
            return;
        }
        GroupSession current = active;
        if (current != null) {
            if (current.roomId == roomId) {
                if (current.crypto.isReady()) JOptionPane.showMessageDialog(owner, "Bạn đã ở trong cuộc gọi nhóm này.", "Gọi nhóm", JOptionPane.INFORMATION_MESSAGE);
                else current.window.setStatus("Đang kết nối cuộc gọi nhóm…");
            } else {
                JOptionPane.showMessageDialog(owner, "Bạn đang tham gia cuộc gọi nhóm ở phòng khác. Hãy rời cuộc gọi đó trước.", "Gọi nhóm", JOptionPane.WARNING_MESSAGE);
            }
            return;
        }
        RoomSummary summary = roomStates.get(roomId);
        if (summary != null) join(roomId, summary.callId, summary.keyOwner, summary.participants);
        else start(roomId, roomName);
    }

    private void start(int roomId, String roomName) {
        String id = UUID.randomUUID().toString();
        try {
            GroupSession session = new GroupSession(roomId, id, username, username, true);
            session.participants.add(username);
            active = session;
            createWindow(session, "Đang bắt đầu cuộc gọi nhóm…");
            session.window.showCallWindow();
            if (!sendCommand.test(Protocol.join(Protocol.GROUP_CALL_START, roomId, id))) {
                closeLocal(session, "Không thể gửi yêu cầu gọi nhóm.", false); return;
            }
            onStateChanged.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Không khởi tạo được khóa cuộc gọi nhóm: " + ex.getMessage(), "Gọi nhóm", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void join(int roomId, String callId, String keyOwner, Set<String> participants) {
        if (oneToOneBusy.getAsBoolean()) {
            JOptionPane.showMessageDialog(owner, "Bạn đang có cuộc gọi 1-1. Hãy kết thúc cuộc gọi đó trước.", "Gọi nhóm", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            GroupSession session = new GroupSession(roomId, callId, username, keyOwner, false);
            if (participants != null) session.participants.addAll(participants);
            session.participants.add(username);
            active = session;
            createWindow(session, "Đang tham gia · chờ khóa media bảo mật…");
            session.window.showCallWindow();
            if (!sendCommand.test(Protocol.join(Protocol.GROUP_CALL_JOIN, roomId, callId))) {
                closeLocal(session, "Không thể tham gia cuộc gọi nhóm.", false); return;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner, "Không khởi tạo được cuộc gọi nhóm: " + ex.getMessage(), "Gọi nhóm", JOptionPane.ERROR_MESSAGE);
        }
    }

    void handleServerMessage(String[] p) {
        if (p == null || p.length == 0) return;
        String cmd = p[0];
        switch (cmd) {
            case Protocol.GROUP_CALL_STARTED: onStarted(p); break;
            case Protocol.GROUP_CALL_ALREADY_ACTIVE: onAlreadyActive(p); break;
            case Protocol.GROUP_CALL_ANNOUNCE: onAnnounce(p); break;
            case Protocol.GROUP_CALL_STATE: onState(p); break;
            case Protocol.GROUP_CALL_IDLE: onIdle(p); break;
            case Protocol.GROUP_CALL_JOINED: onJoined(p); break;
            case Protocol.GROUP_CALL_LEFT: onLeft(p); break;
            case Protocol.GROUP_CALL_ENDED: onEnded(p); break;
            case Protocol.GROUP_CALL_ERROR: onError(p); break;
            case Protocol.GROUP_CALL_KEY_PUBLIC: onPublicKey(p); break;
            case Protocol.GROUP_CALL_KEY_OFFER: onKeyOffer(p); break;
            case Protocol.GROUP_CALL_KEY_READY: onKeyReady(p); break;
            case Protocol.GROUP_CALL_KEY_OWNER: onKeyOwner(p); break;
            case Protocol.GROUP_CALL_MEDIA_STATE: onMediaState(p); break;
            case Protocol.GROUP_CALL_PONG: onPong(p); break;
            default: break;
        }
    }

    void handleMediaPacket(String[] p) {
        GroupSession session = active;
        if (session == null || !session.crypto.isReady() || p == null || p.length < 4 || !session.id.equals(p[1])) return;
        String sender = p[2];
        if (sender.equals(username)) return;
        try {
            byte[] encrypted = Base64.getDecoder().decode(p[p.length - 1]);
            byte[] plain = session.crypto.decrypt(encrypted);
            if (Protocol.GROUP_CALL_AUDIO.equals(p[0])) session.window.acceptRemoteAudio(sender, plain);
            else if (Protocol.GROUP_CALL_VIDEO.equals(p[0])) session.window.acceptRemoteVideo(sender, plain);
        } catch (Exception ignored) {
            // Bad/tampered ciphertext is dropped without blocking the socket reader.
        }
    }

    void onConnectionLost() {
        timer.shutdownNow();
        SwingUtilities.invokeLater(() -> {
            GroupSession session = active;
            if (session != null) closeLocal(session, "Mất kết nối với server.", false);
            roomStates.clear(); onStateChanged.run();
        });
    }

    private void onStarted(String[] p) {
        if (p.length < 3) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[2]) || session.roomId != parseInt(p[1], -1)) return;
        session.serverAccepted = true;
        session.keyOwner = p.length > 3 ? p[3] : username;
        try {
            sendPublicKey(session);
            session.window.markActive();
            startPing(session);
        } catch (Exception ex) { closeLocal(session, "Không thể thiết lập khóa cuộc gọi nhóm.", true); }
    }

    private void onAlreadyActive(String[] p) {
        if (p.length < 3) return;
        int roomId = parseInt(p[1], -1); String callId = p[2]; String ownerName = p.length > 3 ? p[3] : "";
        GroupSession session = active;
        if (session != null && session.roomId == roomId && !session.id.equals(callId)) closeLocal(session, "Đang chuyển vào cuộc gọi nhóm hiện có…", false);
        RoomSummary summary = roomStates.computeIfAbsent(roomId, k -> new RoomSummary(roomId, callId, ownerName));
        summary.callId = callId; if (!ownerName.isBlank()) summary.keyOwner = ownerName;
        requestRoomState(roomId);
        join(roomId, callId, summary.keyOwner, summary.participants);
    }

    private void onAnnounce(String[] p) {
        if (p.length < 4) return;
        int roomId = parseInt(p[1], -1); String starter = p[3];
        roomNotice.accept(roomId, "📞 " + safeDisplay(starter) + " đã bắt đầu cuộc gọi nhóm.");
        try { Toolkit.getDefaultToolkit().beep(); } catch (Exception ignored) { }
    }

    private void onState(String[] p) {
        if (p.length < 6) return;
        int roomId = parseInt(p[1], -1); if (roomId <= 0) return;
        String callId = p[2], starter = p[3], keyOwner = p[4];
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            String list = new String(Base64.getUrlDecoder().decode(p[5]), java.nio.charset.StandardCharsets.UTF_8);
            for (String item : list.split("\\n")) if (!item.isBlank()) names.add(item.trim());
        } catch (Exception ignored) { }
        RoomSummary summary = roomStates.computeIfAbsent(roomId, k -> new RoomSummary(roomId, callId, keyOwner));
        if (!summary.callId.equals(callId)) { summary.callId = callId; summary.participants.clear(); }
        summary.starter = starter; summary.keyOwner = keyOwner; summary.participants.clear(); summary.participants.addAll(names);
        GroupSession session = active;
        if (session != null && session.roomId == roomId && session.id.equals(callId)) {
            session.keyOwner = keyOwner; session.participants.clear(); session.participants.addAll(names); session.window.setParticipants(new ArrayList<>(session.participants));
            if (session.crypto.isReady() && same(keyOwner, username)) sendPendingOffers(session);
        }
        onStateChanged.run();
    }

    private void onIdle(String[] p) {
        if (p.length < 2) return;
        int roomId = parseInt(p[1], -1); roomStates.remove(roomId); onStateChanged.run();
    }

    private void onJoined(String[] p) {
        if (p.length < 4) return;
        int roomId = parseInt(p[1], -1); String callId = p[2], member = p[3];
        GroupSession session = active;
        if (session == null || session.roomId != roomId || !session.id.equals(callId)) return;
        boolean added = session.participants.add(member);
        session.window.setParticipants(new ArrayList<>(session.participants));
        if (added && !member.equals(username)) session.window.participantJoined(member);
        if (member.equals(username)) session.serverAccepted = true;
        if (member.equals(username) && !session.publicKeySent) {
            try { sendPublicKey(session); } catch (Exception ex) { closeLocal(session, "Không thể gửi khóa công khai cuộc gọi nhóm.", true); }
        }
        onStateChanged.run();
    }

    private void onLeft(String[] p) {
        if (p.length < 5) return;
        int roomId = parseInt(p[1], -1); String id = p[2], member = p[3], reason = p[4];
        GroupSession session = active;
        if (session != null && session.roomId == roomId && session.id.equals(id)) {
            session.participants.remove(member);
            if (member.equals(username)) {
                closeLocal(session, "Bạn đã rời cuộc gọi nhóm.", false);
                if ("Rời phòng chat".equalsIgnoreCase(reason) || "Bị xóa khỏi phòng".equalsIgnoreCase(reason)) {
                    roomStates.remove(roomId);
                }
            } else {
                session.window.participantLeft(member, reason);
                session.window.setParticipants(new ArrayList<>(session.participants));
            }
        }
        onStateChanged.run();
    }

    private void onEnded(String[] p) {
        if (p.length < 4) return;
        int roomId = parseInt(p[1], -1); String id = p[2], reason = p[3];
        roomStates.remove(roomId);
        GroupSession session = active;
        if (session != null && session.roomId == roomId && session.id.equals(id)) closeLocal(session, "Cuộc gọi nhóm đã kết thúc · " + reason, false);
        roomNotice.accept(roomId, "Cuộc gọi nhóm đã kết thúc. " + reason);
        onStateChanged.run();
    }

    private void onError(String[] p) {
        String id = p.length > 1 ? p[1] : "";
        String message = p.length > 2 ? p[2] : "Yêu cầu gọi nhóm thất bại.";
        GroupSession session = active;
        if (session != null && (id.isBlank() || session.id.equals(id))) {
            closeLocal(session, message, false);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(owner, message, "Gọi nhóm", JOptionPane.WARNING_MESSAGE));
        } else if (id.isBlank()) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(owner, message, "Gọi nhóm", JOptionPane.WARNING_MESSAGE));
        }
    }

    private void onPublicKey(String[] p) {
        if (p.length < 4) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[1])) return;
        String target = p[2], publicKey = p[3];
        session.publicKeys.put(target, publicKey);
        if (session.crypto.isReady() && same(session.keyOwner, username) && !target.equals(username)
                && !session.keyReady.contains(target)) sendKeyOffer(session, target, publicKey);
    }

    private void onKeyOffer(String[] p) {
        if (p.length < 4) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[1]) || session.crypto.isReady()) return;
        try {
            session.crypto.acceptGroupKeyOffer(p[3]);
            sendCommand.test(Protocol.join(Protocol.GROUP_CALL_KEY_READY, session.id));
            session.window.markActive();
            startPing(session);
        } catch (Exception ex) {
            closeLocal(session, "Không thể mở khóa media nhóm. Hãy rời và tham gia lại cuộc gọi.", true);
        }
    }

    private void onKeyReady(String[] p) {
        if (p.length < 3) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[1])) return;
        session.keyReady.add(p[2]);
    }

    private void onKeyOwner(String[] p) {
        if (p.length < 3) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[1])) return;
        session.keyOwner = p[2];
        if (same(session.keyOwner, username) && session.crypto.isReady()) sendPendingOffers(session);
    }

    private void onMediaState(String[] p) {
        if (p.length < 5) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[1])) return;
        String user = p[2];
        session.window.setParticipantMediaState(user, "1".equals(p[3]), "1".equals(p[4]));
    }

    private void onPong(String[] p) {
        if (p.length < 3) return;
        GroupSession session = active;
        if (session == null || !session.id.equals(p[1])) return;
        long then = parseLong(p[2], 0);
        if (then > 0) { int rtt = (int) Math.max(0, Math.min(5000, System.currentTimeMillis() - then)); session.window.setRtt(rtt); }
    }

    private void sendPublicKey(GroupSession session) throws Exception {
        if (session.publicKeySent || active != session) return;
        String token = session.crypto.createPublicKey();
        session.publicKeySent = sendCommand.test(Protocol.join(Protocol.GROUP_CALL_KEY_PUBLIC, session.id, token));
    }

    private void sendPendingOffers(GroupSession session) {
        if (!session.crypto.isReady() || !same(session.keyOwner, username)) return;
        for (Map.Entry<String, String> e : session.publicKeys.entrySet()) {
            if (!e.getKey().equals(username) && !session.keyReady.contains(e.getKey())) {
                String target = participantName(session, e.getKey());
                sendKeyOffer(session, target, e.getValue());
            }
        }
    }

    private void sendKeyOffer(GroupSession session, String target, String publicKey) {
        if (active != session || !session.crypto.isReady() || !same(session.keyOwner, username)) return;
        try {
            String wrapped = session.crypto.wrapGroupKeyFor(publicKey);
            sendCommand.test(Protocol.join(Protocol.GROUP_CALL_KEY_OFFER, session.id, target, wrapped));
        } catch (Exception ex) { session.window.setStatus("Không gửi được khóa cho " + target + "."); }
    }

    private void createWindow(GroupSession session, String status) {
        session.window = new GroupCallWindow(owner, username, new GroupCallWindow.MediaSink() {
            @Override public void sendAudio(byte[] ulaw) {
                if (active != session || !session.crypto.isReady() || !session.serverAccepted) return;
                try { sendCommand.test(Protocol.join(Protocol.GROUP_CALL_AUDIO, session.id, Base64.getEncoder().encodeToString(session.crypto.encrypt(ulaw)))); } catch (Exception ignored) { }
            }
            @Override public void sendVideo(byte[] jpeg, int quality) {
                if (active != session || !session.crypto.isReady() || !session.serverAccepted) return;
                try { sendCommand.test(Protocol.join(Protocol.GROUP_CALL_VIDEO, session.id, quality, Base64.getEncoder().encodeToString(session.crypto.encrypt(jpeg)))); } catch (Exception ignored) { }
            }
            @Override public void sendMediaState(boolean mic, boolean camera) {
                if (active == session && session.crypto.isReady() && session.serverAccepted) sendCommand.test(Protocol.join(Protocol.GROUP_CALL_MEDIA_STATE, session.id, mic ? "1" : "0", camera ? "1" : "0"));
            }
            @Override public void sendPing(long sentAt) {
                if (active == session && session.crypto.isReady() && session.serverAccepted) sendCommand.test(Protocol.join(Protocol.GROUP_CALL_PING, session.id, sentAt));
            }
        }, () -> leave(session));
        session.window.setStatus(status);
        session.window.setParticipants(new ArrayList<>(session.participants));
    }

    private void startPing(GroupSession session) {
        if (session.pingTask != null || active != session) return;
        session.pingTask = timer.scheduleAtFixedRate(() -> {
            if (active == session && session.crypto.isReady() && session.serverAccepted) {
                session.window.getClass(); // keep captured session strongly reachable while scheduled
                sendCommand.test(Protocol.join(Protocol.GROUP_CALL_PING, session.id, System.currentTimeMillis()));
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private void leave(GroupSession session) {
        if (active != session) return;
        sendCommand.test(Protocol.join(Protocol.GROUP_CALL_LEAVE, session.id));
        closeLocal(session, "Bạn đã rời cuộc gọi nhóm.", false);
    }

    private void closeLocal(GroupSession session, String reason, boolean sendLeave) {
        if (session == null || active != session) return;
        if (sendLeave) sendCommand.test(Protocol.join(Protocol.GROUP_CALL_LEAVE, session.id));
        active = null;
        if (session.pingTask != null) session.pingTask.cancel(true);
        if (session.window != null) session.window.closeFromController(reason);
        session.crypto.clear();
        onStateChanged.run();
    }

    private String participantName(GroupSession s, String lowerName) {
        for (String value : s.participants) if (value.equals(lowerName)) return value;
        return lowerName;
    }
    private String safeDisplay(String value) {
        if (value == null || value.isBlank()) return "thành viên";
        try { String d = displayName.apply(value); return d == null || d.isBlank() ? value : d; }
        catch (Exception ex) { return value; }
    }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ex) { return fallback; } }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (Exception ex) { return fallback; } }
    private static boolean same(String a, String b) { return a != null && b != null && a.equals(b); }

    private static final class RoomSummary {
        final int roomId; volatile String callId; volatile String keyOwner; volatile String starter = "";
        final Set<String> participants = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        RoomSummary(int roomId, String callId, String keyOwner) { this.roomId = roomId; this.callId = callId; this.keyOwner = keyOwner; }
    }
    private static final class GroupSession {
        final int roomId; final String id; final String starter; final GroupCallCrypto crypto;
        final Set<String> participants = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        final Map<String, String> publicKeys = new java.util.concurrent.ConcurrentHashMap<>();
        final Set<String> keyReady = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        volatile String keyOwner; volatile GroupCallWindow window; volatile boolean publicKeySent, serverAccepted;
        volatile ScheduledFuture<?> pingTask;
        GroupSession(int roomId, String id, String starter, String keyOwner, boolean owner) throws Exception {
            this.roomId = roomId; this.id = id; this.starter = starter; this.keyOwner = keyOwner; this.crypto = new GroupCallCrypto(owner);
            if (owner) keyReady.add(starter);
        }
    }
}
