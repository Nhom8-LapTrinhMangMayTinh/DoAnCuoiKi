package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Group-call signaling and encrypted media fan-out. Media and keys are never persisted. */
public final class GroupCallService {
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<Integer, GroupCall> BY_ROOM = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, GroupCall> BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, GroupCall> BY_USER = new ConcurrentHashMap<>();

    private GroupCallService() { }

    public static boolean isGroupCallPacket(String command) {
        return command != null && command.startsWith("GROUP_CALL_");
    }

    public static boolean isGroupMediaPacket(String command) {
        return Protocol.GROUP_CALL_AUDIO.equals(command) || Protocol.GROUP_CALL_VIDEO.equals(command);
    }

    public static void handle(ClientHandler client, String[] p) {
        if (client == null || p == null || p.length == 0 || !client.requireLogin()) return;
        try {
            switch (p[0]) {
                case Protocol.GROUP_CALL_START: start(client, p); break;
                case Protocol.GROUP_CALL_JOIN: join(client, p); break;
                case Protocol.GROUP_CALL_LEAVE: leaveById(client, p.length > 1 ? p[1] : "", "Rời cuộc gọi"); break;
                case Protocol.GROUP_CALL_STATE_REQUEST: sendState(client, parseRoomId(p.length > 1 ? p[1] : "")); break;
                case Protocol.GROUP_CALL_KEY_PUBLIC: receivePublicKey(client, p); break;
                case Protocol.GROUP_CALL_KEY_OFFER: relayKeyOffer(client, p); break;
                case Protocol.GROUP_CALL_KEY_READY: keyReady(client, p); break;
                case Protocol.GROUP_CALL_MEDIA_STATE: mediaState(client, p); break;
                case Protocol.GROUP_CALL_AUDIO: relayAudio(client, p); break;
                case Protocol.GROUP_CALL_VIDEO: relayVideo(client, p); break;
                case Protocol.GROUP_CALL_PING: ping(client, p); break;
                default: error(client, p.length > 1 ? p[1] : "", "Lệnh gọi nhóm không hợp lệ.");
            }
        } catch (Exception ex) {
            System.err.println("[GroupCall] " + p[0] + ": " + ex.getMessage());
            error(client, p.length > 1 ? p[1] : "", "Không thể xử lý yêu cầu gọi nhóm.");
        }
    }

    private static void start(ClientHandler client, String[] p) throws Exception {
        if (p.length < 3) { error(client, "", "Thiếu ID phòng hoặc mã cuộc gọi."); return; }
        int roomId = parseRoomId(p[1]);
        String id = p[2].trim();
        String username = client.getCurrentUsername();
        if (!validId(id) || !isCurrent(client) || !isRoomMember(roomId, username)) {
            error(client, id, "Bạn phải là thành viên đang đăng nhập của phòng để bắt đầu gọi nhóm."); return;
        }
        GroupCall created;
        synchronized (LOCK) {
            GroupCall existing = BY_ROOM.get(roomId);
            if (existing != null) {
                client.sendMessage(Protocol.join(Protocol.GROUP_CALL_ALREADY_ACTIVE, roomId,
                        existing.id, Protocol.clean(existing.keyOwner)));
                sendState(client, roomId);
                return;
            }
            if (BY_ID.containsKey(id)) { error(client, id, "Mã cuộc gọi bị trùng. Hãy thử lại."); return; }
            if (!CallService.reserveGroupCallUser(username, id)) {
                error(client, id, "Bạn đang tham gia một cuộc gọi khác. Hãy kết thúc cuộc gọi đó trước."); return;
            }
            created = new GroupCall(roomId, id, username);
            created.participants.put(key(username), new Participant(username, client));
            created.keyReady.add(key(username));
            BY_ROOM.put(roomId, created);
            BY_ID.put(id, created);
            BY_USER.put(key(username), created);
        }
        client.sendMessage(Protocol.join(Protocol.GROUP_CALL_STARTED, roomId, id, Protocol.clean(username)));
        AdminService.recordCallEvent("GROUP_CALL_STARTED", "ROOM", String.valueOf(roomId),
                username + " bắt đầu cuộc gọi nhóm " + id + ".");
        broadcastRoom(roomId, Protocol.join(Protocol.GROUP_CALL_ANNOUNCE, roomId, id, Protocol.clean(username)));
        broadcastState(created);
    }

    private static void join(ClientHandler client, String[] p) throws Exception {
        if (p.length < 3) { error(client, p.length > 2 ? p[2] : "", "Thiếu ID phòng hoặc mã cuộc gọi."); return; }
        int roomId = parseRoomId(p[1]);
        String id = p[2].trim();
        String username = client.getCurrentUsername();
        if (!isCurrent(client) || !isRoomMember(roomId, username)) {
            error(client, id, "Bạn phải là thành viên của phòng để tham gia cuộc gọi."); return;
        }
        GroupCall call;
        boolean newlyJoined = false;
        synchronized (LOCK) {
            call = BY_ROOM.get(roomId);
            if (call == null || !call.id.equals(id)) {
                client.sendMessage(Protocol.join(Protocol.GROUP_CALL_IDLE, roomId));
                error(client, id, "Cuộc gọi nhóm không còn hoạt động."); return;
            }
            if (call.participants.containsKey(key(username))) {
                sendState(client, roomId);
                return;
            }
            if (call.participants.size() >= maxParticipants()) {
                error(client, id, "Cuộc gọi nhóm đã đủ " + maxParticipants() + " người."); return;
            }
            if (!CallService.reserveGroupCallUser(username, id)) {
                error(client, id, "Bạn đang ở một cuộc gọi khác. Hãy kết thúc cuộc gọi đó trước."); return;
            }
            call.participants.put(key(username), new Participant(username, client));
            BY_USER.put(key(username), call);
            newlyJoined = true;
        }
        if (newlyJoined) {
            broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_JOINED, roomId, id, Protocol.clean(username)));
            broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_KEY_OWNER, id, Protocol.clean(call.keyOwner)));
            broadcastState(call);
            AdminService.recordCallEvent("GROUP_CALL_JOINED", "ROOM", String.valueOf(roomId),
                    username + " tham gia cuộc gọi nhóm " + id + ".");
        }
    }

    private static void receivePublicKey(ClientHandler client, String[] p) {
        if (p.length != 3 || p[2].length() > 6000) return;
        GroupCall call = participantCall(client, p[1]);
        if (call == null) return;
        String username = client.getCurrentUsername();
        String token = p[2];
        try { Base64.getDecoder().decode(token); } catch (IllegalArgumentException ex) { return; }
        synchronized (LOCK) {
            if (BY_ID.get(call.id) != call || !call.participants.containsKey(key(username))) return;
            call.publicKeys.put(key(username), token);
        }
        broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_KEY_PUBLIC, call.id,
                Protocol.clean(username), token));
    }

    private static void relayKeyOffer(ClientHandler client, String[] p) {
        if (p.length != 4 || p[3].length() > 6000) return;
        GroupCall call = participantCall(client, p[1]);
        if (call == null) return;
        String sender = client.getCurrentUsername();
        String targetName = p[2].trim();
        ClientHandler target;
        synchronized (LOCK) {
            if (BY_ID.get(call.id) != call || !same(sender, call.keyOwner)) return;
            Participant targetParticipant = call.participants.get(key(targetName));
            if (targetParticipant == null || key(sender).equals(key(targetName))) return;
            target = targetParticipant.handler;
        }
        try { Base64.getDecoder().decode(p[3]); } catch (IllegalArgumentException ex) { return; }
        if (target != null && isCurrent(target)) {
            // The server relays only the RSA-wrapped AES group key, never the key in clear.
            target.sendMessage(Protocol.join(Protocol.GROUP_CALL_KEY_OFFER, call.id,
                    Protocol.clean(sender), p[3]));
        }
    }

    private static void keyReady(ClientHandler client, String[] p) {
        if (p.length < 2) return;
        GroupCall call = participantCall(client, p[1]);
        if (call == null) return;
        String user = client.getCurrentUsername();
        synchronized (LOCK) {
            if (BY_ID.get(call.id) != call || !call.participants.containsKey(key(user))) return;
            call.keyReady.add(key(user));
        }
        broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_KEY_READY, call.id, Protocol.clean(user)));
    }

    private static void mediaState(ClientHandler client, String[] p) {
        if (p.length != 4 || !("0".equals(p[2]) || "1".equals(p[2])) || !("0".equals(p[3]) || "1".equals(p[3]))) return;
        GroupCall call = participantCall(client, p[1]);
        if (call == null) return;
        String user = client.getCurrentUsername();
        synchronized (LOCK) {
            Participant participant = call.participants.get(key(user));
            if (participant == null || !call.keyReady.contains(key(user))) return;
            participant.micOn = "1".equals(p[2]); participant.cameraOn = "1".equals(p[3]);
        }
        broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_MEDIA_STATE, call.id,
                Protocol.clean(user), p[2], p[3]));
    }

    private static void relayAudio(ClientHandler client, String[] p) {
        if (p.length != 3 || p[2].length() > 1800) return;
        relayMedia(client, p[1], Protocol.GROUP_CALL_AUDIO, -1, p[2]);
    }

    private static void relayVideo(ClientHandler client, String[] p) {
        if (p.length != 4 || p[3].length() > Protocol.MAX_CALL_VIDEO_FRAME_CHARS) return;
        int quality;
        try { quality = Integer.parseInt(p[2]); } catch (NumberFormatException ex) { return; }
        if (quality < 25 || quality > 78) return;
        relayMedia(client, p[1], Protocol.GROUP_CALL_VIDEO, quality, p[3]);
    }

    private static void relayMedia(ClientHandler sender, String callId, String command, int quality, String token) {
        GroupCall call = participantCall(sender, callId);
        if (call == null) return; // late media during teardown is expected; drop without a modal error.
        String username = sender.getCurrentUsername();
        Set<ClientHandler> targets = new LinkedHashSet<>();
        synchronized (LOCK) {
            if (BY_ID.get(call.id) != call || !call.keyReady.contains(key(username))) return;
            for (Map.Entry<String, Participant> entry : call.participants.entrySet()) {
                if (!entry.getKey().equals(key(username)) && call.keyReady.contains(entry.getKey())) {
                    ClientHandler target = entry.getValue().handler;
                    if (target != null && isCurrent(target)) targets.add(target);
                }
            }
        }
        String packet = Protocol.GROUP_CALL_AUDIO.equals(command)
                ? Protocol.join(command, call.id, Protocol.clean(username), token)
                : Protocol.join(command, call.id, Protocol.clean(username), quality, token);
        // Fan out one inbound stream to each other ready member. No media bytes are decoded or saved here.
        for (ClientHandler target : targets) target.sendMessage(packet);
    }

    private static void ping(ClientHandler client, String[] p) {
        if (p.length < 3 || participantCall(client, p[1]) == null) return;
        client.sendMessage(Protocol.join(Protocol.GROUP_CALL_PONG, p[1], p[2]));
    }

    private static void leaveById(ClientHandler client, String callId, String reason) {
        GroupCall call = participantCall(client, callId);
        if (call == null) return;
        removeParticipant(call, client.getCurrentUsername(), client, reason);
    }

    private static void removeParticipant(GroupCall call, String username, ClientHandler expectedHandler, String reason) {
        if (call == null || username == null) return;
        Participant removed;
        boolean ended;
        boolean ownerChanged = false;
        String newOwner = null;
        synchronized (LOCK) {
            if (BY_ID.get(call.id) != call) return;
            removed = call.participants.get(key(username));
            if (removed == null || (expectedHandler != null && removed.handler != expectedHandler)) return;
            call.participants.remove(key(username));
            call.publicKeys.remove(key(username));
            call.keyReady.remove(key(username));
            BY_USER.remove(key(username), call);
            CallService.releaseGroupCallUser(username, call.id);
            if (call.keyOwner.equals(username) && !call.participants.isEmpty()) {
                call.keyOwner = call.participants.values().iterator().next().username;
                ownerChanged = true;
                newOwner = call.keyOwner;
            }
            ended = call.participants.isEmpty();
            if (ended) {
                BY_ROOM.remove(call.roomId, call);
                BY_ID.remove(call.id, call);
            }
        }
        String leftPacket = Protocol.join(Protocol.GROUP_CALL_LEFT, call.roomId, call.id,
                Protocol.clean(username), Protocol.clean(reason == null ? "Rời cuộc gọi" : reason));
        if (removed.handler != null) removed.handler.sendMessage(leftPacket);
        broadcastParticipants(call, leftPacket);
        AdminService.recordCallEvent("GROUP_CALL_LEFT", "ROOM", String.valueOf(call.roomId),
                username + " rời cuộc gọi nhóm " + call.id + ". " + (reason == null ? "" : reason));
        if (ended) {
            broadcastRoom(call.roomId, Protocol.join(Protocol.GROUP_CALL_ENDED, call.roomId, call.id, "Không còn người tham gia"));
            AdminService.recordCallEvent("GROUP_CALL_ENDED", "ROOM", String.valueOf(call.roomId),
                    "Cuộc gọi nhóm " + call.id + " kết thúc vì không còn người tham gia.");
            broadcastIdle(call.roomId);
        } else {
            if (ownerChanged) {
                broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_KEY_OWNER, call.id, Protocol.clean(newOwner)));
                // Send currently-known public keys to the new key owner so it can supply the existing AES key to future joiners.
                for (Map.Entry<String, String> entry : snapshotPublicKeys(call).entrySet()) {
                    Participant owner = findParticipant(call, newOwner);
                    if (owner != null && owner.handler != null) {
                        owner.handler.sendMessage(Protocol.join(Protocol.GROUP_CALL_KEY_PUBLIC, call.id,
                                displayFromKey(call, entry.getKey()), entry.getValue()));
                    }
                }
            }
            broadcastState(call);
        }
    }

    public static void onClientDisconnected(ClientHandler client) {
        if (client == null) return;
        String username = client.getCurrentUsername();
        if (username == null) return;
        GroupCall call = BY_USER.get(key(username));
        if (call != null) removeParticipant(call, username, client, "Mất kết nối");
    }

    public static void onRoomLeave(ClientHandler client, int roomId) {
        if (client == null || client.getCurrentUsername() == null) return;
        GroupCall call = BY_USER.get(key(client.getCurrentUsername()));
        if (call != null && call.roomId == roomId) removeParticipant(call, client.getCurrentUsername(), client, "Rời phòng chat");
    }

    public static void onRoomMemberRemoved(int roomId, String username, String reason) {
        GroupCall call = BY_ROOM.get(roomId);
        if (call == null || username == null) return;
        Participant participant = findParticipant(call, username);
        if (participant != null) removeParticipant(call, username, participant.handler, reason == null ? "Bị xóa khỏi phòng" : reason);
    }

    public static void onRoomDissolved(int roomId) {
        GroupCall call = BY_ROOM.get(roomId);
        if (call == null) { broadcastIdle(roomId); return; }
        synchronized (LOCK) {
            if (BY_ID.get(call.id) != call) return;
            BY_ROOM.remove(roomId, call);
            BY_ID.remove(call.id, call);
            for (Participant participant : call.participants.values()) {
                BY_USER.remove(key(participant.username), call);
                CallService.releaseGroupCallUser(participant.username, call.id);
            }
        }
        broadcastParticipants(call, Protocol.join(Protocol.GROUP_CALL_ENDED, roomId, call.id, "Phòng đã bị giải tán"));
        broadcastRoom(roomId, Protocol.join(Protocol.GROUP_CALL_ENDED, roomId, call.id, "Phòng đã bị giải tán"));
        broadcastIdle(roomId);
        AdminService.recordCallEvent("GROUP_CALL_ENDED", "ROOM", String.valueOf(roomId),
                "Cuộc gọi nhóm " + call.id + " kết thúc do phòng bị giải tán.");
    }

    private static void sendState(ClientHandler client, int roomId) {
        if (roomId <= 0 || client == null || client.getCurrentUsername() == null) return;
        if (!isRoomMember(roomId, client.getCurrentUsername())) {
            client.sendMessage(Protocol.join(Protocol.GROUP_CALL_IDLE, roomId));
            return;
        }
        GroupCall call = BY_ROOM.get(roomId);
        if (call == null) client.sendMessage(Protocol.join(Protocol.GROUP_CALL_IDLE, roomId));
        else client.sendMessage(statePacket(call));
    }

    private static void broadcastState(GroupCall call) {
        broadcastRoom(call.roomId, statePacket(call));
        broadcastParticipants(call, statePacket(call));
    }

    private static String statePacket(GroupCall call) {
        StringBuilder names = new StringBuilder();
        synchronized (LOCK) {
            for (Participant p : call.participants.values()) {
                if (names.length() > 0) names.append('\n');
                names.append(p.username);
            }
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(names.toString().getBytes(StandardCharsets.UTF_8));
        return Protocol.join(Protocol.GROUP_CALL_STATE, call.roomId, call.id,
                Protocol.clean(call.starter), Protocol.clean(call.keyOwner), token);
    }

    private static void broadcastIdle(int roomId) {
        broadcastRoom(roomId, Protocol.join(Protocol.GROUP_CALL_IDLE, roomId));
    }

    private static void broadcastRoom(int roomId, String packet) {
        Set<ClientHandler> clients = new LinkedHashSet<>();
        CopyOnWriteArrayList<ClientHandler> onlineMembers = ChatServer.roomMembers.get(roomId);
        if (onlineMembers != null) clients.addAll(onlineMembers);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT username FROM room_members WHERE room_id=?")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ClientHandler online = findOnline(rs.getString(1));
                    if (online != null) clients.add(online);
                }
            }
        } catch (Exception ignored) { }
        for (ClientHandler client : clients) if (client != null && isCurrent(client)) client.sendMessage(packet);
    }

    private static void broadcastParticipants(GroupCall call, String packet) {
        Set<ClientHandler> clients = new LinkedHashSet<>();
        synchronized (LOCK) {
            if (BY_ID.get(call.id) == call) {
                for (Participant participant : call.participants.values()) {
                    if (participant.handler != null) clients.add(participant.handler);
                }
            }
        }
        for (ClientHandler client : clients) if (isCurrent(client)) client.sendMessage(packet);
    }

    private static GroupCall participantCall(ClientHandler client, String id) {
        if (client == null || id == null || id.isBlank() || !isCurrent(client)) return null;
        synchronized (LOCK) {
            GroupCall call = BY_ID.get(id);
            if (call == null) return null;
            Participant p = call.participants.get(key(client.getCurrentUsername()));
            return p != null && p.handler == client ? call : null;
        }
    }

    private static boolean isRoomMember(int roomId, String username) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM room_members WHERE room_id=? AND username COLLATE BINARY=? COLLATE BINARY LIMIT 1")) {
            ps.setInt(1, roomId); ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception ex) { return false; }
    }

    private static int parseRoomId(String s) {
        try { int n = Integer.parseInt(s); return n > 0 ? n : -1; }
        catch (Exception ex) { return -1; }
    }
    private static boolean validId(String id) { return id != null && id.matches("[A-Fa-f0-9-]{16,64}"); }
    private static boolean isCurrent(ClientHandler c) {
        String name = c == null ? null : c.getCurrentUsername();
        if (name == null) return false;
        for (Map.Entry<String, ClientHandler> e : ChatServer.onlineClients.entrySet()) if (e.getKey().equals(name)) return e.getValue() == c;
        return false;
    }
    private static ClientHandler findOnline(String username) {
        if (username == null) return null;
        for (Map.Entry<String, ClientHandler> e : ChatServer.onlineClients.entrySet()) if (e.getKey().equals(username)) return e.getValue();
        return null;
    }
    private static int maxParticipants() {
        int configured = Integer.getInteger("call.group.max", 6);
        return Math.max(2, Math.min(6, configured));
    }
    private static String key(String s) { return s == null ? "" : s; }
    private static boolean same(String a, String b) { return a != null && b != null && a.equals(b); }
    private static void error(ClientHandler client, String id, String message) {
        if (client != null) client.sendMessage(Protocol.join(Protocol.GROUP_CALL_ERROR,
                id == null ? "" : Protocol.clean(id), Protocol.clean(message)));
    }
    private static Participant findParticipant(GroupCall call, String username) {
        synchronized (LOCK) { return call.participants.get(key(username)); }
    }
    private static Map<String, String> snapshotPublicKeys(GroupCall call) {
        synchronized (LOCK) { return new LinkedHashMap<>(call.publicKeys); }
    }
    private static String displayFromKey(GroupCall call, String key) {
        synchronized (LOCK) { Participant p = call.participants.get(key); return p == null ? key : p.username; }
    }

    private static final class GroupCall {
        final int roomId;
        final String id;
        final String starter;
        volatile String keyOwner;
        final long startedAt = System.currentTimeMillis();
        final LinkedHashMap<String, Participant> participants = new LinkedHashMap<>();
        final Map<String, String> publicKeys = new LinkedHashMap<>();
        final Set<String> keyReady = new LinkedHashSet<>();
        GroupCall(int roomId, String id, String starter) { this.roomId = roomId; this.id = id; this.starter = starter; this.keyOwner = starter; }
    }
    private static final class Participant {
        final String username;
        final ClientHandler handler;
        volatile boolean micOn = true;
        volatile boolean cameraOn = true;
        Participant(String username, ClientHandler handler) { this.username = username; this.handler = handler; }
    }
}
