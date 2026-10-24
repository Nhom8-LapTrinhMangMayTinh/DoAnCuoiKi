package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Server-side call signaling and allow-listed media relay. Media packets are never written to disk. */
public final class CallService {
    private static final long RING_SECONDS = 30L;
    private static final long RATE_WINDOW_MS = 10_000L;
    private static final int INVITES_PER_WINDOW = 8;
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<String, CallSession> CALLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> USER_CALL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Deque<Long>> INVITE_TIMES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Chat-Call-Timers");
        t.setDaemon(true);
        return t;
    });

    private CallService() { }

    /** Atomically reserve a user against both 1:1 and group calls. */
    public static boolean reserveGroupCallUser(String username, String groupCallId) {
        if (username == null || username.isBlank() || groupCallId == null || groupCallId.isBlank()) return false;
        synchronized (LOCK) {
            String userKey = key(username);
            if (USER_CALL.containsKey(userKey)) return false;
            USER_CALL.put(userKey, "GROUP:" + groupCallId);
            return true;
        }
    }

    /** Release only a reservation belonging to the supplied group call. */
    public static void releaseGroupCallUser(String username, String groupCallId) {
        if (username == null || groupCallId == null) return;
        synchronized (LOCK) {
            USER_CALL.remove(key(username), "GROUP:" + groupCallId);
        }
    }

    public static boolean isUserInCall(String username) {
        if (username == null || username.isBlank()) return false;
        synchronized (LOCK) { return USER_CALL.containsKey(key(username)); }
    }

    public static boolean isCallPacket(String command) {
        return command != null && command.startsWith("CALL_");
    }

    public static void handle(ClientHandler client, String[] p, String rawLine) {
        if (p == null || p.length == 0 || !client.requireLogin()) return;
        String command = p[0];
        try {
            switch (command) {
                case Protocol.CALL_INVITE: invite(client, p); break;
                case Protocol.CALL_ACCEPT: accept(client, p); break;
                case Protocol.CALL_REJECT: reject(client, p); break;
                case Protocol.CALL_CANCEL: cancel(client, p); break;
                case Protocol.CALL_END: end(client, p); break;
                case Protocol.CALL_KEY_PUBLIC:
                case Protocol.CALL_KEY_OFFER:
                case Protocol.CALL_KEY_READY:
                case Protocol.CALL_MEDIA_STATE:
                case Protocol.CALL_AUDIO:
                case Protocol.CALL_VIDEO:
                case Protocol.CALL_PING:
                case Protocol.CALL_PONG:
                    relayActiveMedia(client, p, rawLine);
                    break;
                default:
                    client.sendError("Lệnh gọi video không hợp lệ.");
            }
        } catch (Exception ex) {
            System.err.println("[Call] " + command + ": " + ex.getMessage());
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, p.length > 1 ? p[1] : "", "Không thể xử lý yêu cầu gọi video."));
        }
    }

    private static void invite(ClientHandler client, String[] p) throws Exception {
        if (p.length < 3) { client.sendMessage(Protocol.join(Protocol.CALL_ERROR, "", "Thiếu người nhận cuộc gọi.")); return; }
        String caller = client.getCurrentUsername();
        String target = p[1].trim();
        String id = p[2].trim();
        if (target.isEmpty() || target.equals(caller) || !isValidCallId(id)) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, id, "Người nhận hoặc mã cuộc gọi không hợp lệ.")); return;
        }
        if (ChatServer.onlineClients.get(caller) != client) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, id, "Phiên đăng nhập không còn hiện hành.")); return;
        }
        String canonicalTarget = findCanonicalUsername(target);
        if (canonicalTarget == null) {
            client.sendMessage(Protocol.join(Protocol.CALL_OFFLINE, id, target));
            return;
        }
        target = canonicalTarget;
        long now = System.currentTimeMillis();
        if (!allowInvite(caller, now)) {
            client.sendMessage(Protocol.join(Protocol.CALL_RATE_LIMIT, id, "Bạn chỉ được mời tối đa 8 cuộc gọi trong 10 giây."));
            recordStandalone(caller, target, "Cuộc gọi video • Bị giới hạn mời gọi");
            return;
        }
        ClientHandler recipient = ChatServer.onlineClients.get(target);
        if (recipient == null || recipient == client || recipient.getCurrentUsername() == null) {
            client.sendMessage(Protocol.join(Protocol.CALL_OFFLINE, id, target));
            recordStandalone(caller, target, "Cuộc gọi video • Người nhận không trực tuyến");
            return;
        }

        CallSession call = null;
        boolean busy;
        boolean duplicateId;
        synchronized (LOCK) {
            busy = USER_CALL.containsKey(key(caller)) || USER_CALL.containsKey(key(target));
            duplicateId = CALLS.containsKey(id);
            if (!busy && !duplicateId) {
                call = new CallSession(id, caller, target, client, recipient);
                CALLS.put(id, call);
                USER_CALL.put(key(caller), id);
                USER_CALL.put(key(target), id);
                CallSession scheduledCall = call;
                call.timeout = TIMER.schedule(() -> timeout(scheduledCall), RING_SECONDS, TimeUnit.SECONDS);
            }
        }
        if (busy) {
            client.sendMessage(Protocol.join(Protocol.CALL_BUSY, id, target));
            recordStandalone(caller, target, "Cuộc gọi video • Máy bận");
            return;
        }
        if (duplicateId || call == null) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, id, "Mã cuộc gọi bị trùng. Hãy thử lại."));
            return;
        }
        recipient.sendMessage(Protocol.join(Protocol.CALL_INCOMING, id, caller, RING_SECONDS));
        client.sendMessage(Protocol.join(Protocol.CALL_RINGING, id, target, RING_SECONDS));
    }

    private static void accept(ClientHandler client, String[] p) {
        CallSession call = session(p);
        if (call == null) { client.sendMessage(Protocol.join(Protocol.CALL_ERROR, p.length > 1 ? p[1] : "", "Cuộc gọi không còn tồn tại.")); return; }
        synchronized (LOCK) {
            if (CALLS.get(call.id) != call || call.state != State.RINGING
                    || call.calleeHandler != client || !isCurrentSession(client)) {
                client.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Không thể nhận cuộc gọi này.")); return;
            }
            call.state = State.ACTIVE;
            call.activeAt = System.currentTimeMillis();
            if (call.timeout != null) call.timeout.cancel(false);
        }
        call.callerHandler.sendMessage(Protocol.join(Protocol.CALL_ACCEPTED, call.id, call.callee));
        call.calleeHandler.sendMessage(Protocol.join(Protocol.CALL_ACCEPTED, call.id, call.caller));
    }

    private static void reject(ClientHandler client, String[] p) {
        CallSession call = session(p);
        if (call == null) return;
        if (call.calleeHandler != client || !isCurrentSession(client)) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Chỉ người nhận đang đổ chuông mới có thể từ chối.")); return;
        }
        if (!finishRinging(call, "REJECTED", "Cuộc gọi video • Bị từ chối")) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Cuộc gọi đã được nhận hoặc kết thúc.")); return;
        }
        call.callerHandler.sendMessage(Protocol.join(Protocol.CALL_REJECTED, call.id, call.callee));
        call.calleeHandler.sendMessage(Protocol.join(Protocol.CALL_REJECTED, call.id, call.caller));
    }

    private static void cancel(ClientHandler client, String[] p) {
        CallSession call = session(p);
        if (call == null) return;
        if (call.callerHandler != client || !isCurrentSession(client)) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Chỉ người gọi mới có thể hủy lúc đang đổ chuông.")); return;
        }
        if (!finishRinging(call, "CANCELED", "Cuộc gọi video • Đã hủy khi đang đổ chuông")) return;
        notifyBoth(call, Protocol.join(Protocol.CALL_CANCELED, call.id, call.caller));
    }

    private static void end(ClientHandler client, String[] p) {
        CallSession call = session(p);
        if (call == null) return;
        if (!isParticipant(call, client) || !isCurrentSession(client)) {
            client.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Bạn không thuộc cuộc gọi này.")); return;
        }
        String actor = client.getCurrentUsername();
        String status = call.state == State.RINGING ? "CANCELED" : "ENDED";
        String log = call.state == State.RINGING
                ? "Cuộc gọi video • Đã hủy khi đang đổ chuông"
                : "Cuộc gọi video • Đã kết thúc • " + formatDuration(System.currentTimeMillis() - call.activeAt);
        if (!finish(call, status, log, true)) return;
        notifyBoth(call, Protocol.join(Protocol.CALL_ENDED, call.id, actor, status));
    }

    private static void timeout(CallSession call) {
        if (!finishRinging(call, "TIMEOUT", "Cuộc gọi video • Không có phản hồi trong 30 giây")) return;
        call.callerHandler.sendMessage(Protocol.join(Protocol.CALL_TIMEOUT, call.id, call.callee));
        call.calleeHandler.sendMessage(Protocol.join(Protocol.CALL_MISSED, call.id, call.caller));
    }

    private static void relayActiveMedia(ClientHandler sender, String[] p, String rawLine) {
        if (p.length < 2) return;

        // Capture threads may already have queued one or two audio/video frames when a user
        // hangs up. CALL_END removes the session before those frames reach this method.
        // A stale/late packet is normal during teardown: silently drop it instead of sending
        // CALL_ERROR, which used to pop a modal warning on the already-closed client window.
        CallSession call = CALLS.get(p[1]);
        if (call == null || call.state != State.ACTIVE || !isParticipant(call, sender)
                || !isCurrentSession(sender)) {
            return;
        }

        // Validate sizes and fields before relay. The server never decodes or persists media payloads.
        if (Protocol.CALL_AUDIO.equals(p[0])) {
            if (p.length != 4 || p[3].length() > 12_000) return;
        } else if (Protocol.CALL_VIDEO.equals(p[0])) {
            if (p.length != 5 || p[4].length() > 450_000) return;
            try {
                int quality = Integer.parseInt(p[3]);
                if (quality < 25 || quality > 78) return;
            } catch (NumberFormatException ex) { return; }
        } else if (Protocol.CALL_KEY_PUBLIC.equals(p[0])) {
            if (p.length != 3 || p[2].length() > 8_000) return;
        } else if (Protocol.CALL_KEY_OFFER.equals(p[0])) {
            if (p.length != 3 || p[2].length() > 2_000) return;
        } else if (Protocol.CALL_MEDIA_STATE.equals(p[0])) {
            if (p.length != 4 || !("0".equals(p[2]) || "1".equals(p[2]))
                    || !("0".equals(p[3]) || "1".equals(p[3]))) return;
        } else if (p.length > 3) {
            return;
        }
        if (Protocol.CALL_KEY_OFFER.equals(p[0]) && sender != call.callerHandler) {
            sender.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Chỉ người khởi tạo cuộc gọi mới được gửi khóa media."));
            return;
        }
        if (Protocol.CALL_KEY_READY.equals(p[0]) && sender != call.calleeHandler) {
            sender.sendMessage(Protocol.join(Protocol.CALL_ERROR, call.id, "Phản hồi khóa media không hợp lệ."));
            return;
        }

        ClientHandler target = peerHandler(call, sender);
        if (target == null || !isCurrentSession(target)) {
            if (finish(call, "DISCONNECTED", "Cuộc gọi video • Mất kết nối", true)) {
                notifyBoth(call, Protocol.join(Protocol.CALL_ENDED, call.id, sender.getCurrentUsername(), "DISCONNECTED"));
            }
            return;
        }

        // The call can end after validation but before forwarding. Re-check the map and state
        // immediately before relay; if the end wins the race, drop the queued frame silently.
        if (CALLS.get(call.id) != call || call.state != State.ACTIVE
                || !isParticipant(call, sender) || !isCurrentSession(sender)
                || !isCurrentSession(target)) {
            return;
        }
        target.sendMessage(rawLine);
    }

    public static void onClientDisconnected(ClientHandler client) {
        if (client == null || client.getCurrentUsername() == null) return;
        String id = USER_CALL.get(key(client.getCurrentUsername()));
        if (id == null) return;
        CallSession call = CALLS.get(id);
        if (call == null || !isParticipant(call, client)) return;
        String log = call.state == State.RINGING
                ? "Cuộc gọi video • Bị gián đoạn do mất kết nối"
                : "Cuộc gọi video • Kết thúc do một bên mất kết nối • " + formatDuration(System.currentTimeMillis() - call.activeAt);
        if (finish(call, "DISCONNECTED", log, true)) {
            notifyBoth(call, Protocol.join(Protocol.CALL_ENDED, call.id, client.getCurrentUsername(), "DISCONNECTED"));
        }
    }

    private static boolean finishRinging(CallSession call, String status, String logText) {
        synchronized (LOCK) {
            if (CALLS.get(call.id) != call || call.state != State.RINGING) return false;
            CALLS.remove(call.id, call);
            USER_CALL.remove(key(call.caller), call.id);
            USER_CALL.remove(key(call.callee), call.id);
            if (call.timeout != null) call.timeout.cancel(false);
        }
        recordCallLog(call.caller, call.callee, logText, call.callerHandler, call.calleeHandler);
        return true;
    }

    private static boolean finish(CallSession call, String status, String logText, boolean writeLog) {
        synchronized (LOCK) {
            if (!CALLS.remove(call.id, call)) return false;
            USER_CALL.remove(key(call.caller), call.id);
            USER_CALL.remove(key(call.callee), call.id);
            if (call.timeout != null) call.timeout.cancel(false);
        }
        if (writeLog) recordCallLog(call.caller, call.callee, logText, call.callerHandler, call.calleeHandler);
        return true;
    }

    private static void notifyBoth(CallSession call, String message) {
        if (call.callerHandler != null) call.callerHandler.sendMessage(message);
        if (call.calleeHandler != null && call.calleeHandler != call.callerHandler) call.calleeHandler.sendMessage(message);
    }

    private static void recordStandalone(String caller, String target, String text) {
        recordCallLog(caller, target, text, ChatServer.onlineClients.get(caller), findOnline(target));
    }

    private static void recordCallLog(String caller, String callee, String text,
                                      ClientHandler callerHandler, ClientHandler calleeHandler) {
        if (caller == null || callee == null || caller.equals(callee)) return;
        try {
            String encrypted = AESUtils.encrypt(text);
            if (encrypted == null) return;
            long id;
            long timestamp = System.currentTimeMillis();
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO messages(sender, receiver, content, msg_type) VALUES(?, ?, ?, ?)")) {
                ps.setString(1, caller);
                ps.setString(2, callee);
                ps.setString(3, encrypted);
                ps.setString(4, Protocol.TYPE_CALL);
                ps.executeUpdate();
                try (java.sql.Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    if (!rs.next()) return;
                    id = rs.getLong(1);
                }
            }
            String packet = Protocol.join(Protocol.MSG_PRIVATE, id, Protocol.clean(caller),
                    Protocol.clean(callee), timestamp, Protocol.TYPE_CALL, encrypted);
            if (callerHandler != null && isCurrentSession(callerHandler)) callerHandler.sendMessage(packet);
            else {
                ClientHandler current = ChatServer.onlineClients.get(caller);
                if (current != null) current.sendMessage(packet);
            }
            if (calleeHandler != null && isCurrentSession(calleeHandler)) calleeHandler.sendMessage(packet);
            else {
                ClientHandler current = ChatServer.onlineClients.get(callee);
                if (current != null) current.sendMessage(packet);
            }
        } catch (Exception ex) {
            System.err.println("[Call] Không ghi được nhật ký cuộc gọi: " + ex.getMessage());
        }
    }

    private static String findCanonicalUsername(String value) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT username FROM users WHERE username COLLATE BINARY=? COLLATE BINARY LIMIT 1")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (Exception ex) { return null; }
    }

    private static boolean allowInvite(String user, long now) {
        Deque<Long> times = INVITE_TIMES.computeIfAbsent(key(user), ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= RATE_WINDOW_MS) times.removeFirst();
            if (times.size() >= INVITES_PER_WINDOW) return false;
            times.addLast(now);
            return true;
        }
    }

    private static boolean isValidCallId(String id) {
        return id != null && id.matches("[A-Fa-f0-9-]{16,64}");
    }

    private static CallSession session(String[] p) {
        return p.length > 1 ? CALLS.get(p[1]) : null;
    }

    private static boolean isParticipant(CallSession c, ClientHandler client) {
        return c.callerHandler == client || c.calleeHandler == client;
    }

    private static ClientHandler peerHandler(CallSession c, ClientHandler client) {
        if (c.callerHandler == client) return c.calleeHandler;
        if (c.calleeHandler == client) return c.callerHandler;
        return null;
    }

    private static boolean isCurrentSession(ClientHandler client) {
        String name = client.getCurrentUsername();
        return name != null && ChatServer.onlineClients.get(name) == client;
    }

    private static ClientHandler findOnline(String name) {
        if (name == null) return null;
        for (Map.Entry<String, ClientHandler> e : ChatServer.onlineClients.entrySet()) {
            if (e.getKey().equals(name)) return e.getValue();
        }
        return null;
    }

    private static String key(String username) {
        return username == null ? "" : username;
    }

    private static String formatDuration(long millis) {
        long total = Math.max(0, millis / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    private enum State { RINGING, ACTIVE }
    private static final class CallSession {
        final String id, caller, callee;
        final ClientHandler callerHandler, calleeHandler;
        volatile State state = State.RINGING;
        volatile long activeAt;
        volatile ScheduledFuture<?> timeout;
        CallSession(String id, String caller, String callee, ClientHandler callerHandler, ClientHandler calleeHandler) {
            this.id = id; this.caller = caller; this.callee = callee;
            this.callerHandler = callerHandler; this.calleeHandler = calleeHandler;
        }
    }
}
