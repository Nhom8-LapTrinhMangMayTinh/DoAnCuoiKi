package server;

import common.CryptoUtil;
import common.Protocol;
import common.TextUtil;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Moi Client = 1 ClientHandler (1 thread doc) + 1 thread ghi rieng (hang doi outbox).
 * Hang doi giup Client cham/mat mang khong lam nghen cac Client khac khi Broadcast.
 * File duoc truyen theo manh 64KB (FILE_CHUNK) nen khong chiem kenh cua cac lenh khac va co the huy giua chung.
 */
public class ClientHandler implements Runnable {
    private static final Object POISON = new Object();
    private static final Object CLOSE_AFTER_FLUSH = new Object();
    private static final Pattern USER_RE = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final Pattern ROOM_RE = Pattern.compile("^[\\p{L}\\p{N}_ .-]{1,30}$");
    static final int MAX_UPLOADS_PER_USER = 3;

    /** Mot luot tai file xuong client: moi lan gui 1 manh roi tra lai cuoi hang doi de frame khac chen vao giua. */
    private static class FileTask {
        long id; String name; File file; long size; String sha;
        InputStream in; long sent; boolean started;
    }

    /** Goi hinh/tieng cua cuoc goi can chuyen tiep cho client nay (header + byte tho). */
    private static final class MediaPacket {
        final String header; final byte[] data; final boolean video;
        MediaPacket(String h, byte[] d, boolean v) { header = h; data = d; video = v; }
    }

    /** Mot luot nhan file tu client (nhieu manh FILE_CHUNK). */
    private static class Upload {
        long fileId; File dest; long size, received; OutputStream out;
        final MessageDigest md = CryptoUtil.newSha256();
        String name; int roomId; int receiverId;
    }

    private final Socket socket;
    private final String remote;
    private final String ip;
    private DataInputStream in;
    private DataOutputStream out;
    private final LinkedBlockingQueue<Object> outbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String username;
    private volatile int userId = -1;
    private volatile boolean admin;
    private volatile String displayName = "";
    private volatile String sessionToken;
    private volatile long lastActivity = System.currentTimeMillis(); // lenh cua nguoi dung (khong tinh PING)
    private volatile long lastInbound = System.currentTimeMillis();  // bat ky du lieu nao (ke ca PING)
    private volatile boolean adminSub;
    private final Map<Long, Upload> uploads = new ConcurrentHashMap<>();
    private final Set<Long> cancelledDownloads = ConcurrentHashMap.newKeySet();
    private final Guards.RateLimiter typingLimiter = new Guards.RateLimiter(12, 2000);
    private final Guards.RateLimiter reactLimiter = new Guards.RateLimiter(20, 5000);
    private final Guards.RateLimiter searchLimiter = new Guards.RateLimiter(10, 5000);
    private final Guards.RateLimiter adminLimiter = new Guards.RateLimiter(60, 5000);

    final UserDAO userDAO = new UserDAO();
    final RoomDAO roomDAO = new RoomDAO();
    final MessageDAO messageDAO = new MessageDAO();

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.remote = String.valueOf(socket.getRemoteSocketAddress());
        String h = remote.startsWith("/") ? remote.substring(1) : remote;
        int c = h.lastIndexOf(':');
        this.ip = c > 0 ? h.substring(0, c) : h;
    }

    // ---------- truy cap cho cac lop khac ----------
    public String username() { return username; }
    public int userId() { return userId; }
    public boolean isAdmin() { return admin; }
    public String ip() { return ip; }
    public long lastActivity() { return lastActivity; }
    public long lastInbound() { return lastInbound; }
    public boolean adminSubscribed() { return adminSub; }
    public boolean isClosed() { return closed.get(); }

    /** Gui 1 frame roi dong ket noi sau khi frame da duoc day di. */
    public void sendAndClose(String frame) {
        if (closed.get()) return;
        outbox.offer(frame);
        outbox.offer(CLOSE_AFTER_FLUSH);
    }

    // ============================== vong doi ==============================
    @Override
    public void run() {
        Log.info("Ket noi moi: " + remote);
        Stats.tcpConnections.incrementAndGet();
        try {
            socket.setSoTimeout(ChatServer.readTimeoutMs);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
            Thread w = new Thread(this::writerLoop, "writer-" + remote);
            w.setDaemon(true);
            w.start();
            while (!closed.get()) {
                String line = in.readUTF();
                lastInbound = System.currentTimeMillis();
                try {
                    handle(line);
                } catch (SQLException e) {
                    Log.error("SQL (" + username + ", lenh " + line.split("\\|", 2)[0] + "): " + e);
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    Log.error(sw.toString().replace("\n", " / ").substring(0, Math.min(600, sw.toString().length())));
                    Stats.errors.increment();
                    Audit.log(username, "ERROR_SQL", null, String.valueOf(e.getMessage()), ip);
                    send(Protocol.ERROR + "|Lỗi cơ sở dữ liệu");
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    send(Protocol.ERROR + "|Dữ liệu gửi lên không hợp lệ");
                } catch (RuntimeException e) {
                    Log.error("Loi xu ly lenh tu " + username + ": " + e);
                    Stats.errors.increment();
                    Audit.log(username, "ERROR_RUNTIME", null, String.valueOf(e), ip);
                    send(Protocol.ERROR + "|Lỗi xử lý trên server");
                }
            }
        } catch (EOFException e) {
            Log.info("Client dong ket noi (EOF): " + who());
        } catch (SocketTimeoutException e) {
            Log.info("Client qua han khong phan hoi: " + who());
        } catch (SocketException e) {
            Log.info("Client mat ket noi (" + e.getMessage() + "): " + who());
        } catch (IOException e) {
            Log.info("Loi IO voi " + who() + ": " + e);
        } finally {
            cleanup();
            Stats.tcpConnections.decrementAndGet();
        }
    }

    private String who() { return username != null ? username : remote; }

    private void writerLoop() {
        try {
            while (true) {
                Object o = outbox.take();
                if (o == POISON) break;
                if (o == CLOSE_AFTER_FLUSH) { out.flush(); forceClose(); break; }
                if (o instanceof String) {
                    out.writeUTF((String) o);
                    if (outbox.isEmpty()) out.flush();
                } else if (o instanceof MediaPacket) {
                    MediaPacket m = (MediaPacket) o;
                    out.writeUTF(m.header);
                    out.write(m.data);
                    if (outbox.isEmpty()) out.flush();
                } else if (o instanceof FileTask) {
                    stepFile((FileTask) o);
                }
            }
            out.flush();
        } catch (InterruptedException | IOException e) {
            forceClose();
        }
    }

    /** Gui 1 manh cua file. Neu chua xong thi dua lai cuoi hang doi -> cac frame khac (chat...) khong bi chan. */
    private void stepFile(FileTask t) throws IOException {
        if (!t.started) {
            out.writeUTF(Protocol.join(Protocol.FILE_DATA, t.id, t.name, t.size, t.sha));
            t.in = new BufferedInputStream(new FileInputStream(t.file), 65536);
            t.started = true;
        }
        if (cancelledDownloads.remove(t.id)) {
            closeQuietly(t.in);
            out.writeUTF(Protocol.join(Protocol.FILE_ABORT, t.id, "Đã hủy tải"));
            out.flush();
            return;
        }
        byte[] buf = new byte[Protocol.CHUNK_SIZE];
        int n;
        try {
            n = t.in.read(buf, 0, (int) Math.min(buf.length, t.size - t.sent));
        } catch (IOException e) {
            n = -1;
        }
        if (n <= 0) {
            closeQuietly(t.in);
            out.writeUTF(Protocol.join(Protocol.FILE_ABORT, t.id, "File trên server bị lỗi hoặc đã bị xóa"));
            out.flush();
            return;
        }
        out.writeUTF(Protocol.join(Protocol.FILE_CHUNK, t.id, n));
        out.write(buf, 0, n);
        t.sent += n;
        Stats.bytesDown.add(n);
        if (t.sent >= t.size) {
            closeQuietly(t.in);
            out.writeUTF(Protocol.join(Protocol.FILE_END, t.id));
            out.flush();
            Stats.filesDownloaded.increment();
        } else {
            out.flush();
            outbox.offer(t);
        }
    }

    private static void closeQuietly(Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) { }
    }

    /** Dong ket noi ngay (khong lam sap server). */
    private void forceClose() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void cleanup() {
        if (!closed.compareAndSet(false, true)) return;
        forceClose();
        outbox.clear();
        outbox.offer(POISON);
        for (Upload u : uploads.values()) abortUpload(u, false);
        uploads.clear();
        Calls.onDisconnect(this);
        List<Integer> roomIds = ChatServer.rooms.detachAll(this);
        String u = username;
        if (u != null) {
            boolean wasRegistered = ChatServer.online.remove(u, this);
            if (wasRegistered) {   // neu phien nay da bi phien moi thay the thi KHONG duoc dat Offline
                try { userDAO.setStatus(userId, false); } catch (Exception e) { Log.error("setStatus: " + e); }
                for (int rid : roomIds) ChatServer.rooms.membersChanged(rid);
                ChatServer.broadcastUserList();
            }
            Log.info("Da giai phong phien cua " + u);
        }
    }

    /** Dua frame vao hang doi gui (khong chan). */
    public void send(String frame) {
        if (closed.get()) return;
        if (frame.length() > Protocol.MAX_FRAME_CHARS) {
            Log.error("Bo qua frame qua lon (" + frame.length() + ") cho " + who());
            return;
        }
        if (outbox.size() > 20000) { // client qua cham -> ngat de bao ve server
            Log.error("Hang doi gui cua " + who() + " day, ngat ket noi");
            forceClose();
            return;
        }
        outbox.offer(frame);
    }

    private void err(String msg) { send(Protocol.ERROR + "|" + msg); }

    // ============================== xu ly lenh ==============================
    private void handle(String line) throws IOException, SQLException {
        String[] p = Protocol.split(line);
        String cmd = p[0];
        if (username == null) {
            switch (cmd) {
                case Protocol.AUTH_LOGIN: login(p); return;
                case Protocol.AUTH_REGISTER: register(p); return;
                case Protocol.AUTH_RESUME: resume(p); return;
                case Protocol.PING: send(Protocol.PONG + (p.length > 1 ? "|" + p[1] : "")); return;
                default: err("Bạn chưa đăng nhập"); return;
            }
        }
        if (!Protocol.PING.equals(cmd)) lastActivity = System.currentTimeMillis();
        if (cmd.startsWith("ADMIN_")) {
            if (!admin) {
                Audit.log(username, "ADMIN_DENIED", cmd, "khong co quyen quan tri", ip);
                err("Bạn không có quyền quản trị");
                return;
            }
            if (!adminLimiter.allow()) { err("Thao tác quản trị quá nhanh"); return; }
            AdminCommands.handle(this, p);
            return;
        }
        switch (cmd) {
            case Protocol.PING:
                send(Protocol.PONG + (p.length > 1 ? "|" + p[1] : ""));
                if (sessionToken != null) ChatServer.sessions.touch(sessionToken);
                break;
            case Protocol.LOGOUT:
                ChatServer.sessions.revoke(sessionToken);
                cleanup();
                break;
            case Protocol.CHANGE_PASSWORD: changePassword(p); break;
            case Protocol.SET_PROFILE: setProfile(p); break;
            case Protocol.SET_AVATAR: setAvatar(p); break;
            case Protocol.GET_AVATAR: getAvatar(p); break;
            case Protocol.LIST_ROOMS: sendRoomList(); break;
            case Protocol.LIST_USERS: ChatServer.sendUserListTo(this); break;
            case Protocol.CREATE_ROOM: createRoom(p); break;
            case Protocol.JOIN_ROOM: joinRoom(p); break;
            case Protocol.LEAVE_ROOM: leaveRoom(p); break;
            case Protocol.LIST_MEMBERS: listMembers(Integer.parseInt(p[1])); break;
            case Protocol.GRANT_ADMIN: changeAdmin(p, true); break;
            case Protocol.REVOKE_ADMIN: changeAdmin(p, false); break;
            case Protocol.KICK_MEMBER: kick(p); break;
            case Protocol.CALL_INVITE: callInvite(p); break;
            case Protocol.CALL_ACCEPT: Calls.accept(this, p[1]); break;
            case Protocol.CALL_REJECT: Calls.reject(this, p[1]); break;
            case Protocol.CALL_END: Calls.hangup(this, p[1]); break;
            case Protocol.CALL_STATE: callState(p); break;
            case Protocol.CALL_MEDIA: callMedia(p); break;
            case Protocol.DISSOLVE_ROOM: dissolveRoom(Integer.parseInt(p[1])); break;
            case Protocol.SEND_MSG_GROUP: sendGroup(p); break;
            case Protocol.SEND_MSG_PRIVATE: sendPrivate(p); break;
            case Protocol.LOAD_HISTORY_PAGED: loadGroupHistory(p); break;
            case Protocol.LOAD_PRIVATE_HISTORY: loadPrivateHistory(p); break;
            case Protocol.SEARCH_MSG: search(p); break;
            case Protocol.EDIT_MSG: editMsg(p); break;
            case Protocol.RECALL_MSG: recallMsg(p); break;
            case Protocol.REACT: react(p); break;
            case Protocol.PIN_MSG: pin(p); break;
            case Protocol.LIST_PINS: listPins(p); break;
            case Protocol.MARK_READ: markRead(p); break;
            case Protocol.TYPING: typing(p); break;
            case Protocol.SEND_FILE: startUpload(p); break;
            case Protocol.FILE_CHUNK: uploadChunk(p); break;
            case Protocol.FILE_END: finishUpload(p); break;
            case Protocol.FILE_CANCEL: cancelUpload(p); break;
            case Protocol.DOWNLOAD_FILE: downloadFile(Long.parseLong(p[1])); break;
            case Protocol.CANCEL_DOWNLOAD: cancelledDownloads.add(Long.parseLong(p[1])); break;
            default: err("Lệnh không hợp lệ: " + Protocol.clean(cmd));
        }
    }

    // ============================== tai khoan / phien ==============================
    private void register(String[] p) throws SQLException {
        String u = p[1].trim();
        String pw = p[2];
        if (!USER_RE.matcher(u).matches()) {
            send(Protocol.REGISTER_FAIL + "|Tên đăng nhập 3-20 ký tự (chữ, số, _)"); return;
        }
        if (pw.length() < ChatServer.minPasswordLen || pw.length() > 64) {
            send(Protocol.REGISTER_FAIL + "|Mật khẩu phải từ " + ChatServer.minPasswordLen + " đến 64 ký tự"); return;
        }
        if (!ChatServer.registerLimiter(ip).allow()) {
            send(Protocol.REGISTER_FAIL + "|Đăng ký quá nhiều lần, hãy thử lại sau"); return;
        }
        if (userDAO.register(u, pw) < 0) {
            send(Protocol.REGISTER_FAIL + "|Tên đăng nhập đã tồn tại"); return;
        }
        Audit.log(u, "REGISTER", u, "", ip);
        send(Protocol.REGISTER_OK);
        ChatServer.broadcastUserList();
    }

    private void login(String[] p) throws SQLException {
        String u = p[1].trim();
        String kU = "u:" + u.toLowerCase(), kI = "ip:" + ip;
        long lock = Math.max(ChatServer.loginGuard.lockedSeconds(kU), ChatServer.ipGuard.lockedSeconds(kI));
        if (lock > 0) {
            Audit.log(u, "LOGIN_LOCKED", u, "bi khoa tam thoi con " + lock + "s", ip);
            send(Protocol.AUTH_FAIL + "|Đăng nhập sai quá nhiều lần. Thử lại sau " + lock + " giây");
            return;
        }
        UserDAO.AuthResult r = userDAO.authenticate(u, p[2]);
        if (r.status == UserDAO.Auth.BAD) {
            ChatServer.loginGuard.fail(kU);
            ChatServer.ipGuard.fail(kI);
            Stats.loginsFail.increment();
            Audit.log(u, "LOGIN_FAIL", u, "sai ten hoac mat khau", ip);
            send(Protocol.AUTH_FAIL + "|Sai tên đăng nhập hoặc mật khẩu");
            return;
        }
        ChatServer.loginGuard.success(kU);
        if (r.status == UserDAO.Auth.DISABLED) {
            Audit.log(u, "LOGIN_DENIED", u, "tai khoan bi khoa", ip);
            send(Protocol.AUTH_FAIL + "|Tài khoản đã bị khóa bởi quản trị viên");
            return;
        }
        completeLogin(r.user, false);
    }

    /** Ket noi lai bang token phien (khong can mat khau). Thay the phien cu neu con ton tai. */
    private void resume(String[] p) throws SQLException {
        String u = p[1].trim();
        if (!ChatServer.sessions.validate(u, p[2])) {
            send(Protocol.AUTH_FAIL + "|Phiên đăng nhập đã hết hạn, hãy đăng nhập lại");
            return;
        }
        Models.User user = userDAO.find(u);
        if (user == null || user.disabled) {
            send(Protocol.AUTH_FAIL + "|Tài khoản không khả dụng");
            return;
        }
        completeLogin(user, true);
    }

    private void completeLogin(Models.User u, boolean resumed) throws SQLException {
        ClientHandler old = ChatServer.online.putIfAbsent(u.username, this);
        if (old != null && old != this) {
            boolean stale = System.currentTimeMillis() - old.lastInbound > ChatServer.staleSessionMs;
            if (resumed || stale) { // phien cu da chet (mat mang) hoac chinh chu ket noi lai -> thay the
                old.sendAndClose(Protocol.SESSION_REPLACED + "|Phiên này đã được thay thế bởi một kết nối mới");
                ChatServer.online.put(u.username, this);
                Audit.log(u.username, "SESSION_REPLACED", u.username, resumed ? "resume" : "phien cu qua han", ip);
            } else {
                Audit.log(u.username, "LOGIN_DUPLICATE", u.username, "dang nhap trung tai khoan", ip);
                send(Protocol.AUTH_FAIL + "|Tài khoản đang đăng nhập ở nơi khác");
                return;
            }
        }
        this.userId = u.id;
        this.username = u.username;
        this.admin = u.admin();
        this.displayName = u.displayName == null ? "" : u.displayName;
        this.sessionToken = ChatServer.sessions.issue(u.username);
        lastActivity = System.currentTimeMillis();
        userDAO.setStatus(userId, true);
        List<Integer> rids = roomDAO.roomIdsOf(userId);
        for (int rid : rids) ChatServer.rooms.attach(rid, this);
        Stats.loginsOk.increment();
        Audit.log(username, resumed ? "LOGIN_RESUME" : "LOGIN_OK", username, "", ip);
        send(Protocol.AUTH_OK + "|" + username + "|" + sessionToken);
        send(Protocol.join(Protocol.SERVER_INFO, ChatServer.maxFileBytes >> 20, String.join(",", ChatServer.blockedExt),
                String.join(",", ChatServer.allowedExt), ChatServer.idleTimeoutMs / 60000.0, admin ? 1 : 0, Protocol.enc(displayName)));
        sendRoomList();
        sendUnread();
        ChatServer.broadcastUserList();
        for (int rid : rids) ChatServer.rooms.membersChanged(rid);
    }

    private void changePassword(String[] p) throws SQLException {
        if (!userDAO.checkPassword(userId, p[1])) {
            Audit.log(username, "PASSWORD_CHANGE_FAIL", username, "sai mat khau cu", ip);
            send(Protocol.PASSWORD_FAIL + "|Mật khẩu hiện tại không đúng"); return;
        }
        if (p[2].length() < ChatServer.minPasswordLen || p[2].length() > 64) {
            send(Protocol.PASSWORD_FAIL + "|Mật khẩu mới phải từ " + ChatServer.minPasswordLen + " đến 64 ký tự"); return;
        }
        userDAO.setPassword(userId, p[2]);
        ChatServer.sessions.revokeUser(username);              // moi token cu mat hieu luc
        sessionToken = ChatServer.sessions.issue(username);    // phien hien tai duoc cap token moi
        Audit.log(username, "PASSWORD_CHANGED", username, "", ip);
        send(Protocol.PASSWORD_OK + "|" + sessionToken);
    }

    private void setProfile(String[] p) throws SQLException {
        String name = Protocol.clean(p[1]).replaceAll("\\p{Cntrl}", "");
        if (name.length() > 30) { err("Tên hiển thị tối đa 30 ký tự"); return; }
        userDAO.setDisplayName(userId, name);
        displayName = name;
        Models.User u = userDAO.findById(userId);
        ChatServer.broadcastAll(Protocol.join(Protocol.PROFILE_UPDATE, username, Protocol.enc(name), u == null ? 0 : u.avatarVer));
    }

    private void setAvatar(String[] p) throws SQLException {
        String b64 = p[1];
        if (b64.length() > Protocol.MAX_AVATAR_B64) { err("Ảnh đại diện quá lớn (tối đa ~10KB sau khi thu nhỏ)"); return; }
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(b64);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null || img.getWidth() > 256 || img.getHeight() > 256) { err("Ảnh đại diện không hợp lệ (tối đa 256x256)"); return; }
        } catch (IllegalArgumentException | IOException e) {
            err("Ảnh đại diện không hợp lệ"); return;
        }
        int ver = userDAO.setAvatar(userId, b64);
        ChatServer.broadcastAll(Protocol.join(Protocol.PROFILE_UPDATE, username, Protocol.enc(displayName), ver));
    }

    private void getAvatar(String[] p) throws SQLException {
        String[] a = userDAO.getAvatar(p[1]);
        if (a == null) send(Protocol.join(Protocol.AVATAR, p[1], 0, ""));
        else send(Protocol.join(Protocol.AVATAR, p[1], a[0], a[1]));
    }

    // ============================== phong ==============================
    private void sendRoomList() throws SQLException {
        StringBuilder sb = new StringBuilder(Protocol.ROOM_LIST);
        for (Models.RoomInfo r : roomDAO.listAll(userId, 400)) {
            sb.append('|').append(r.id).append(',').append(r.name).append(',').append(r.locked ? 1 : 0)
              .append(',').append(r.ownerName).append(',').append(r.members).append(',').append(r.myRole);
        }
        send(sb.toString());
    }

    private void createRoom(String[] p) throws SQLException {
        String name = Protocol.clean(p[1]);
        String pass = p.length > 2 ? p[2] : "";
        if (!ROOM_RE.matcher(name).matches()) {
            send(Protocol.CREATE_FAIL + "|Tên phòng 1-30 ký tự (chữ, số, khoảng trắng, _ . -)"); return;
        }
        int id = roomDAO.create(name, pass, userId);
        if (id < 0) { send(Protocol.CREATE_FAIL + "|Tên phòng đã tồn tại"); return; }
        ChatServer.rooms.attach(id, this);
        Log.info(username + " tao phong #" + id + " '" + name + "'" + (pass.isEmpty() ? "" : " (co mat khau)"));
        send(Protocol.CREATE_OK + "|" + id);
        ChatServer.broadcastAll(Protocol.ROOMS_CHANGED);
    }

    private void joinRoom(String[] p) throws SQLException {
        int rid = Integer.parseInt(p[1]);
        String pass = p.length > 2 ? p[2] : "";
        Models.RoomInfo r = roomDAO.get(rid, userId);
        if (r == null) { send(Protocol.JOIN_FAIL + "|Phòng không tồn tại"); return; }
        if (!r.myRole.isEmpty()) { // da la thanh vien: vao lai khong can mat khau
            ChatServer.rooms.attach(rid, this);
            send(Protocol.join(Protocol.JOIN_OK, rid, r.name, r.myRole));
            return;
        }
        String lockKey = "room:" + userId + ":" + rid;   // chong do mat khau phong
        long lock = ChatServer.loginGuard.lockedSeconds(lockKey);
        if (lock > 0) { send(Protocol.JOIN_FAIL + "|Nhập sai mật khẩu phòng quá nhiều lần. Thử lại sau " + lock + " giây"); return; }
        if (!roomDAO.checkPassword(rid, pass)) {
            ChatServer.loginGuard.fail(lockKey);
            Audit.log(username, "JOIN_ROOM_FAIL", String.valueOf(rid), "sai mat khau phong", ip);
            send(Protocol.JOIN_FAIL + "|Sai mật khẩu phòng"); return;
        }
        ChatServer.loginGuard.success(lockKey);
        roomDAO.addMember(rid, userId, Protocol.ROLE_MEMBER);
        ChatServer.rooms.attach(rid, this);
        send(Protocol.join(Protocol.JOIN_OK, rid, r.name, Protocol.ROLE_MEMBER));
        ChatServer.rooms.sys(rid, username + " đã tham gia phòng");
        ChatServer.rooms.membersChanged(rid);
        ChatServer.broadcastAll(Protocol.ROOMS_CHANGED);
        Log.info(username + " vao phong #" + rid);
    }

    private void leaveRoom(String[] p) throws SQLException {
        int rid = Integer.parseInt(p[1]);
        Models.LeaveResult res = roomDAO.leave(rid, userId);
        if (res == null) { err("Bạn không ở trong phòng này"); return; }
        ChatServer.rooms.detach(rid, this);
        send(Protocol.LEAVE_OK + "|" + rid);
        if (res.roomDeleted) {
            ChatServer.rooms.closeRoom(rid);
        } else {
            ChatServer.rooms.sys(rid, username + " đã rời phòng");
            if (res.newOwner != null) ChatServer.rooms.sys(rid, res.newOwner + " trở thành chủ phòng mới");
            ChatServer.rooms.membersChanged(rid);
        }
        ChatServer.broadcastAll(Protocol.ROOMS_CHANGED);
        Log.info(username + " roi phong #" + rid);
    }

    private void listMembers(int rid) throws SQLException {
        if (!inRoom(rid)) { err("Bạn không ở trong phòng này"); return; }
        StringBuilder sb = new StringBuilder(Protocol.MEMBER_LIST).append('|').append(rid);
        for (Models.MemberInfo m : roomDAO.members(rid)) {
            sb.append('|').append(m.username).append(',').append(m.role).append(',')
              .append(ChatServer.online.containsKey(m.username) ? 1 : 0);
        }
        send(sb.toString());
    }

    private void changeAdmin(String[] p, boolean grant) throws SQLException {
        int rid = Integer.parseInt(p[1]);
        String target = p[2];
        String myRole = roomDAO.getRole(rid, userId);
        if (!Protocol.ROLE_OWNER.equals(myRole)) { err("Chỉ chủ phòng mới được đổi quyền Admin"); return; }
        Models.User t = userDAO.find(target);
        String tRole = t == null ? null : roomDAO.getRole(rid, t.id);
        if (tRole == null) { err("Người dùng không ở trong phòng"); return; }
        if (Protocol.ROLE_OWNER.equals(tRole)) { err("Không thể đổi quyền của chủ phòng"); return; }
        roomDAO.setRole(rid, t.id, grant ? Protocol.ROLE_ADMIN : Protocol.ROLE_MEMBER);
        Audit.log(username, grant ? "ROOM_GRANT_ADMIN" : "ROOM_REVOKE_ADMIN", t.username, "room " + rid, ip);
        ChatServer.rooms.sys(rid, t.username + (grant ? " được cấp quyền Admin" : " bị thu hồi quyền Admin"));
        ChatServer.rooms.membersChanged(rid);
        ChatServer.broadcastAll(Protocol.ROOMS_CHANGED);
    }

    private void kick(String[] p) throws SQLException {
        int rid = Integer.parseInt(p[1]);
        String myRole = roomDAO.getRole(rid, userId);
        if (!Protocol.ROLE_OWNER.equals(myRole) && !Protocol.ROLE_ADMIN.equals(myRole)) {
            err("Bạn không có quyền quản lý thành viên"); return;
        }
        Models.User t = userDAO.find(p[2]);
        String tRole = t == null ? null : roomDAO.getRole(rid, t.id);
        if (tRole == null) { err("Người dùng không ở trong phòng"); return; }
        boolean allowed = Protocol.ROLE_OWNER.equals(myRole) ? !Protocol.ROLE_OWNER.equals(tRole)
                                                              : Protocol.ROLE_MEMBER.equals(tRole);
        if (!allowed) { err("Bạn không thể xóa thành viên này"); return; }
        roomDAO.removeMember(rid, t.id);
        Audit.log(username, "ROOM_KICK", t.username, "room " + rid, ip);
        Models.RoomInfo r = roomDAO.get(rid, userId);
        ClientHandler th = ChatServer.online.get(t.username);
        if (th != null) {
            ChatServer.rooms.detach(rid, th);
            th.send(Protocol.join(Protocol.KICKED, rid, r == null ? "" : r.name));
        }
        ChatServer.rooms.sys(rid, t.username + " đã bị " + username + " xóa khỏi phòng");
        ChatServer.rooms.membersChanged(rid);
        ChatServer.broadcastAll(Protocol.ROOMS_CHANGED);
    }

    // ============================== goi video ==============================
    private final Guards.RateLimiter videoLimiter = new Guards.RateLimiter(60, 1000);
    private final Guards.RateLimiter audioLimiter = new Guards.RateLimiter(120, 1000);
    private final Guards.RateLimiter inviteLimiter = new Guards.RateLimiter(8, 10_000);

    private void callInvite(String[] p) throws SQLException {
        if (!inviteLimiter.allow()) { err("Bạn gọi quá nhiều lần, hãy chờ một chút"); return; }
        Models.User to = userDAO.find(p[1]);
        if (to == null) { send(Protocol.join(Protocol.CALL_REJECTED, "-", "Người dùng không tồn tại")); return; }
        Calls.invite(this, to.username, p.length > 2 && "1".equals(p[2]));
    }

    private void callState(String[] p) {
        Calls.Call c = Calls.of(p[1]);
        if (c == null || !c.active || !c.involves(this)) return;
        ClientHandler peer = c.peerOf(this);
        if (peer != null) peer.send(Protocol.join(Protocol.CALL_STATE_UPDATE, c.id, "1".equals(p[2]) ? 1 : 0, "1".equals(p[3]) ? 1 : 0));
    }

    /** CALL_MEDIA|callId|V/A|len + byte: chi chuyen tiep khi cuoc goi dang dien ra va nguoi gui la 1 trong 2 ben. */
    private void callMedia(String[] p) throws IOException {
        String kind = p[2];
        int len = Integer.parseInt(p[3]);
        if (len <= 0 || len > 1 << 20) throw new IOException("Goi media khong hop le: " + len);   // vi pham giao thuc -> ngat phien
        byte[] buf = new byte[len];
        in.readFully(buf);                                   // luon doc het byte de giu dung khung giao thuc
        Calls.Call c = Calls.of(p[1]);
        if (c == null || !c.active || !c.involves(this)) return;                     // khong thuoc cuoc goi -> bo qua am tham
        boolean video = "V".equals(kind);
        if (!video && !"A".equals(kind)) return;
        if (len > (video ? Protocol.CALL_MAX_VIDEO : Protocol.CALL_MAX_AUDIO)) return;
        if (!(video ? videoLimiter : audioLimiter).allow()) return;
        ClientHandler peer = c.peerOf(this);
        if (peer == null || peer.closed.get()) return;
        // chong tre tich luy: nguoi nhan cham -> bo khung hinh cu (giu am thanh uu tien)
        if (peer.outbox.size() > (video ? 30 : 400)) return;
        peer.outbox.offer(new MediaPacket(Protocol.join(Protocol.CALL_MEDIA, c.id, kind, len), buf, video));
        c.bytes.add(len);
        Stats.callBytes.add(len);
    }

    /**
     * Giai tan phong: CHI chu phong (OWNER). Xoa vinh vien tin nhan, reaction, file (dia + CSDL), thanh vien; bao cho moi
     * thanh vien dang online bang ROOM_DISSOLVED va ghi nhat ky.
     */
    private void dissolveRoom(int rid) throws SQLException {
        Models.RoomInfo r = roomDAO.get(rid, userId);
        if (r == null) { err("Phòng không tồn tại"); return; }
        if (!Protocol.ROLE_OWNER.equals(r.myRole)) {
            Audit.log(username, "DISSOLVE_DENIED", r.name, "room " + rid + " (khong phai chu phong)", ip);
            err("Chỉ chủ phòng mới được giải tán phòng");
            return;
        }
        for (long fid : messageDAO.fileIdsOfRoom(rid)) {
            new File(ChatServer.filesDir, fid + ".bin").delete();
            messageDAO.deleteFile(fid);
        }
        List<ClientHandler> attached = ChatServer.rooms.sessionsOf(rid);
        long msgs = 0;
        roomDAO.delete(rid);
        String frame = Protocol.join(Protocol.ROOM_DISSOLVED, rid, r.name, username);
        for (ClientHandler h : attached) h.send(frame);
        ChatServer.rooms.closeRoom(rid);
        Audit.log(username, "ROOM_DISSOLVE", r.name, "room " + rid + ", " + r.members + " thanh vien", ip);
        ChatServer.broadcastAll(Protocol.ROOMS_CHANGED);
    }

    /** Dang la thanh vien that su (CSDL) va dang gan phien vao phong. */
    private boolean inRoom(int rid) throws SQLException {
        return ChatServer.rooms.isAttached(rid, this) && roomDAO.getRole(rid, userId) != null;
    }

    // ============================== chat ==============================
    private boolean validContent(String enc) {
        if (enc == null || enc.isEmpty() || enc.length() > 16000) { err("Nội dung tin nhắn không hợp lệ"); return false; }
        return true;
    }

    /** Chong spam. true = cho phep gui. */
    private boolean spamOk(String enc) {
        boolean[] muted = {false};
        String reason = ChatServer.spam.check(username, CryptoUtil.decrypt(enc), muted);
        if (reason == null) return true;
        Stats.spamBlocked.increment();
        if (muted[0]) {
            Audit.log(username, "SPAM_MUTE", username, "gui qua nhanh/lap lai -> han che " + (ChatServer.spam.muteMs / 1000) + "s", ip);
            send(Protocol.MUTED + "|" + (ChatServer.spam.muteMs / 1000) + "|" + reason);
        }
        err(reason);
        return false;
    }

    private static long parseReply(String[] p, int idx) {
        if (p.length <= idx || p[idx].isEmpty()) return 0;
        long v = Long.parseLong(p[idx]);
        return Math.max(0, v);
    }

    /** Chong gui trung khi client gui lai sau mat mang (cung clientId). true = tin moi. */
    private boolean freshClientId(String[] p, int idx) {
        if (p.length <= idx || p[idx].isEmpty()) return true;
        return ChatServer.rememberClientId(username + ":" + p[idx]);
    }

    private static String cidOf(String[] p, int idx) {
        if (p.length <= idx) return "";
        String c = Protocol.clean(p[idx]);
        return c.length() > 40 ? c.substring(0, 40) : c;
    }

    private void sendGroup(String[] p) throws SQLException {
        int rid = Integer.parseInt(p[1]);
        if (!validContent(p[2])) return;
        if (!inRoom(rid)) { err("Bạn chưa tham gia phòng này"); return; }
        long replyTo = parseReply(p, 3);
        if (replyTo > 0) {
            Models.Msg r = messageDAO.get(replyTo);
            if (r == null || r.roomId != rid) { err("Tin nhắn được trả lời không tồn tại trong phòng này"); return; }
        }
        if (!spamOk(p[2])) return;
        if (!freshClientId(p, 4)) return;
        Models.Msg m;
        try {
            m = messageDAO.save(userId, username, rid, 0, Protocol.TYPE_TEXT, p[2], replyTo);
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("FOREIGN KEY")) { err("Phòng không còn tồn tại (có thể vừa bị giải tán)"); return; }   // dua nhau voi giai tan phong
            throw e;
        }
        m.cid = cidOf(p, 4);
        Stats.messages.increment();
        ChatServer.rooms.broadcast(rid, groupFrame(rid, m));
    }

    private String groupFrame(int rid, Models.Msg m) {
        return Protocol.join(Protocol.MSG_GROUP, rid, m.id, m.sender, m.ts, m.type, m.content) + "|" + m.tail() + "|" + m.cid;
    }

    private void sendPrivate(String[] p) throws SQLException {
        if (!validContent(p[2])) return;
        Models.User to = userDAO.find(p[1]);
        if (to == null) { err("Người nhận không tồn tại"); return; }
        long replyTo = parseReply(p, 3);
        if (replyTo > 0) {
            Models.Msg r = messageDAO.get(replyTo);
            if (r == null || r.roomId != 0 || !isPair(r, userId, to.id)) { err("Tin nhắn được trả lời không tồn tại trong cuộc trò chuyện này"); return; }
        }
        if (!spamOk(p[2])) return;
        if (!freshClientId(p, 4)) return;
        Models.Msg m = messageDAO.save(userId, username, 0, to.id, Protocol.TYPE_TEXT, p[2], replyTo);
        m.cid = cidOf(p, 4);
        Stats.messages.increment();
        routePrivate(m, to);
    }

    private static boolean isPair(Models.Msg m, int a, int b) {
        return (m.senderId == a && m.receiverId == b) || (m.senderId == b && m.receiverId == a);
    }

    /** Dinh tuyen tin rieng: gui cho nguoi nhan (neu online) va echo lai cho nguoi gui. */
    private void routePrivate(Models.Msg m, Models.User to) {
        String frame = Protocol.join(Protocol.MSG_PRIVATE, m.id, username, to.username, m.ts, m.type, m.content) + "|" + m.tail() + "|" + m.cid;
        send(frame);
        if (!to.username.equalsIgnoreCase(username)) {
            ClientHandler h = ChatServer.online.get(to.username);
            if (h != null) h.send(frame);
        }
    }

    // ---------- loc quyen truy cap vao 1 tin ----------
    private interface FrameBuilder { String build(String scope, String key); }

    private static final String NO_MSG = "Tin nhắn không tồn tại hoặc bạn không có quyền truy cập";

    /** Lay tin neu ton tai VA nguoi dung duoc phep (thanh vien phong that su / 1 trong 2 nguoi cua cuoc chat rieng). */
    private Models.Msg accessibleMsg(long id) throws SQLException {
        Models.Msg m = messageDAO.get(id);
        if (m == null) return null;
        if (m.roomId > 0) return inRoom(m.roomId) ? m : null;
        return (m.senderId == userId || m.receiverId == userId) ? m : null;
    }

    /** Gui frame den tat ca nguoi thuoc cuoc tro chuyen cua tin m. Khoa cuoc chat rieng = ten nguoi con lai. */
    private void notifyAudience(Models.Msg m, FrameBuilder b) {
        if (m.roomId > 0) {
            ChatServer.rooms.broadcast(m.roomId, b.build("G", String.valueOf(m.roomId)));
            return;
        }
        ClientHandler hs = ChatServer.online.get(m.sender);
        if (hs != null) hs.send(b.build("P", m.receiverName));
        if (!m.sender.equalsIgnoreCase(m.receiverName)) {
            ClientHandler hr = ChatServer.online.get(m.receiverName);
            if (hr != null) hr.send(b.build("P", m.sender));
        }
    }

    private void editMsg(String[] p) throws SQLException {
        long id = Long.parseLong(p[1]);
        if (!validContent(p[2])) return;
        Models.Msg m = accessibleMsg(id);
        if (m == null) { err(NO_MSG); return; }
        if (m.senderId != userId) { err("Chỉ người gửi mới được sửa tin nhắn"); return; }
        if (m.deleted) { err("Tin nhắn đã bị thu hồi"); return; }
        if (!Protocol.TYPE_TEXT.equals(m.type)) { err("Chỉ sửa được tin nhắn văn bản"); return; }
        long ts = System.currentTimeMillis();
        if (!messageDAO.edit(id, userId, p[2], ts)) { err("Không sửa được: tin nhắn đã bị thu hồi"); return; }
        notifyAudience(m, (sc, key) -> Protocol.join(Protocol.MSG_EDITED, sc, key, id, p[2], ts));
    }

    private void recallMsg(String[] p) throws SQLException {
        long id = Long.parseLong(p[1]);
        Models.Msg m = accessibleMsg(id);
        if (m == null) { err(NO_MSG); return; }
        boolean mine = m.senderId == userId;
        boolean moderator = false;
        if (!mine && m.roomId > 0) {
            String role = roomDAO.getRole(m.roomId, userId);
            moderator = Protocol.ROLE_OWNER.equals(role) || Protocol.ROLE_ADMIN.equals(role);
        }
        if (!mine && !moderator) { err("Bạn chỉ được thu hồi tin nhắn của mình"); return; }
        if (!messageDAO.recall(id)) { err("Tin nhắn đã được thu hồi trước đó"); return; }
        if (Protocol.TYPE_FILE.equals(m.type)) removeFileOfMessage(m);
        if (!mine) Audit.log(username, "MSG_RECALL_MOD", m.sender, "msg " + id, ip);
        notifyAudience(m, (sc, key) -> Protocol.join(Protocol.MSG_RECALLED, sc, key, id, username));
    }

    private void removeFileOfMessage(Models.Msg m) {
        try {
            String[] f = CryptoUtil.decrypt(m.content).split("\\|", 3);
            long fid = Long.parseLong(f[0]);
            new File(ChatServer.filesDir, fid + ".bin").delete();
            messageDAO.deleteFile(fid);
        } catch (RuntimeException ignored) { }
    }

    private void react(String[] p) throws SQLException {
        long id = Long.parseLong(p[1]);
        if (!Protocol.validReaction(p[2])) { err("Biểu tượng cảm xúc không hợp lệ"); return; }
        if (!reactLimiter.allow()) { err("Bạn thả cảm xúc quá nhanh"); return; }
        Models.Msg m = accessibleMsg(id);
        if (m == null) { err(NO_MSG); return; }
        if (m.deleted) { err("Tin nhắn đã bị thu hồi"); return; }
        String summary = messageDAO.toggleReaction(id, userId, p[2]);
        notifyAudience(m, (sc, key) -> Protocol.join(Protocol.REACTION_UPDATE, sc, key, id, summary));
    }

    private void pin(String[] p) throws SQLException {
        long id = Long.parseLong(p[1]);
        boolean on = "1".equals(p[2]);
        Models.Msg m = accessibleMsg(id);
        if (m == null) { err(NO_MSG); return; }
        if (m.deleted) { err("Tin nhắn đã bị thu hồi"); return; }
        if (m.roomId > 0) {
            String role = roomDAO.getRole(m.roomId, userId);
            if (!Protocol.ROLE_OWNER.equals(role) && !Protocol.ROLE_ADMIN.equals(role)) { err("Chỉ chủ phòng hoặc Admin phòng mới được ghim tin"); return; }
        }
        if (!messageDAO.setPinned(id, on ? userId : 0)) { err("Không ghim được tin nhắn"); return; }
        notifyAudience(m, (sc, key) -> Protocol.join(Protocol.PIN_UPDATE, sc, key, id, on ? 1 : 0, username));
    }

    private void listPins(String[] p) throws SQLException {
        String scope = p[1], key = p[2];
        List<Models.Msg> list;
        if ("G".equals(scope)) {
            int rid = Integer.parseInt(key);
            if (!inRoom(rid)) { err("Bạn chưa tham gia phòng này"); return; }
            list = messageDAO.listPinned(true, rid, 0, 0);
        } else {
            Models.User peer = userDAO.find(key);
            if (peer == null) { err("Người dùng không tồn tại"); return; }
            list = messageDAO.listPinned(false, 0, userId, peer.id);
            key = peer.username;
        }
        send(Protocol.join(Protocol.PINS_BEGIN, scope, key));
        for (Models.Msg m : list) send(itemFrame(Protocol.PINS_ITEM, scope, key, m));
        send(Protocol.join(Protocol.PINS_END, scope, key));
    }

    private String itemFrame(String cmd, String scope, String key, Models.Msg m) {
        return Protocol.join(cmd, scope, key, m.id, m.sender, m.ts, m.type, m.content) + "|" + m.tail();
    }

    private void markRead(String[] p) throws SQLException {
        String scope = p[1], key = p[2];
        long upTo = Long.parseLong(p[3]);
        if (upTo <= 0) return;
        if ("G".equals(scope)) {
            int rid = Integer.parseInt(key);
            if (!inRoom(rid)) { err("Bạn chưa tham gia phòng này"); return; }
            upTo = Math.min(upTo, messageDAO.maxMsgId(true, rid, 0, 0));
            if (upTo <= 0) return;
            long cur = messageDAO.setRead(userId, "G", key, upTo);
            ChatServer.rooms.broadcast(rid, Protocol.join(Protocol.READ_UPDATE, "G", key, username, cur));
        } else {
            Models.User peer = userDAO.find(key);
            if (peer == null) { err("Người dùng không tồn tại"); return; }
            upTo = Math.min(upTo, messageDAO.maxMsgId(false, 0, userId, peer.id));
            if (upTo <= 0) return;
            long cur = messageDAO.setRead(userId, "P", peer.username.toLowerCase(), upTo);
            ClientHandler h = ChatServer.online.get(peer.username);
            if (h != null) h.send(Protocol.join(Protocol.READ_UPDATE, "P", username, username, cur));
        }
    }

    private void typing(String[] p) throws SQLException {
        if (!typingLimiter.allow()) return; // bo qua am tham, khong bao loi
        String scope = p[1], key = p[2], st = "1".equals(p[3]) ? "1" : "0";
        if ("G".equals(scope)) {
            int rid = Integer.parseInt(key);
            if (!inRoom(rid)) return;
            String frame = Protocol.join(Protocol.TYPING_UPDATE, "G", key, username, st);
            for (ClientHandler h : ChatServer.rooms.sessionsOf(rid)) if (h != this) h.send(frame);
        } else {
            ClientHandler h = ChatServer.online.get(key);
            if (h != null && h != this) h.send(Protocol.join(Protocol.TYPING_UPDATE, "P", username, username, st));
        }
    }

    private void sendUnread() throws SQLException {
        StringBuilder sb = new StringBuilder(Protocol.UNREAD);
        for (Map.Entry<Integer, Integer> e : messageDAO.unreadGroups(userId).entrySet())
            sb.append("|G:").append(e.getKey()).append('=').append(e.getValue());
        for (Map.Entry<String, Integer> e : messageDAO.unreadPrivates(userId).entrySet())
            sb.append("|P:").append(e.getKey()).append('=').append(e.getValue());
        send(sb.toString());
    }

    // ---------- lich su / tim kiem ----------
    private void loadGroupHistory(String[] p) throws SQLException {
        int rid = Integer.parseInt(p[1]);
        int page = Math.max(0, Integer.parseInt(p[2]));
        if (!inRoom(rid)) { err("Bạn chưa tham gia phòng này"); return; }
        Models.Page pg = messageDAO.loadGroupPage(rid, page, Protocol.PAGE_SIZE);
        sendHistory("G", String.valueOf(rid), page, pg);
        if (page == 0) {
            StringBuilder sb = new StringBuilder(Protocol.join(Protocol.READ_STATE, "G", rid));
            sb.append('|');
            boolean first = true;
            for (Map.Entry<String, Long> e : messageDAO.readStatesOfRoom(rid).entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append(':').append(e.getValue());
                first = false;
            }
            send(sb.toString());
        }
    }

    private void loadPrivateHistory(String[] p) throws SQLException {
        Models.User peer = userDAO.find(p[1]);
        if (peer == null) { err("Người dùng không tồn tại"); return; }
        int page = Math.max(0, Integer.parseInt(p[2]));
        Models.Page pg = messageDAO.loadPrivatePage(userId, peer.id, page, Protocol.PAGE_SIZE);
        sendHistory("P", peer.username, page, pg);
        if (page == 0) {
            long peerRead = messageDAO.getRead(peer.id, "P", username.toLowerCase());
            send(Protocol.join(Protocol.READ_STATE, "P", peer.username, peer.username + ":" + peerRead));
        }
    }

    private void sendHistory(String scope, String key, int page, Models.Page pg) {
        send(Protocol.join(Protocol.HISTORY_BEGIN, scope, key, page));
        for (Models.Msg m : pg.items) send(itemFrame(Protocol.HISTORY_ITEM, scope, key, m));
        send(Protocol.join(Protocol.HISTORY_END, scope, key, page, pg.hasMore ? 1 : 0));
    }

    /** SEARCH_MSG|G/P|key|keyword|sender|page|strict */
    private void search(String[] p) throws SQLException {
        String scope = p[1], key = p[2];
        String keyword = p[3].trim(), sender = p[4].trim();
        int page = Math.max(0, Integer.parseInt(p[5]));
        boolean strict = p.length > 6 && "1".equals(p[6]);
        if (keyword.length() > 100 || sender.length() > 20) { err("Từ khóa tìm kiếm quá dài"); return; }
        if (keyword.isEmpty() && sender.isEmpty()) { err("Hãy nhập từ khóa hoặc người gửi"); return; }
        if (!searchLimiter.allow()) { err("Bạn tìm kiếm quá nhanh, hãy chờ một chút"); return; }
        Models.Page pg;
        if ("G".equals(scope)) {
            int rid = Integer.parseInt(key);
            if (!inRoom(rid)) { err("Bạn chưa tham gia phòng này"); return; }
            pg = messageDAO.search(true, rid, 0, 0, keyword, sender, strict, page, Protocol.SEARCH_PAGE_SIZE);
        } else {
            Models.User peer = userDAO.find(key);
            if (peer == null) { err("Người dùng không tồn tại"); return; }
            pg = messageDAO.search(false, 0, userId, peer.id, keyword, sender, strict, page, Protocol.SEARCH_PAGE_SIZE);
            key = peer.username;
        }
        send(Protocol.join(Protocol.SEARCH_BEGIN, scope, key, page));
        for (Models.Msg m : pg.items) send(itemFrame(Protocol.SEARCH_ITEM, scope, key, m));
        send(Protocol.join(Protocol.SEARCH_END, scope, key, page, pg.hasMore ? 1 : 0));
    }

    // ============================== file ==============================
    /** SEND_FILE|GROUP|roomID|name|size (hoac PRIVATE|username|name|size). Sau UPLOAD_READY client gui FILE_CHUNK... FILE_END. */
    private void startUpload(String[] p) throws IOException, SQLException {
        String scope = p[1];
        String name = Protocol.clean(new File(p[3].replace('\\', '/')).getName());
        if (name.length() > 120) name = name.substring(name.length() - 120);
        long size;
        try { size = Long.parseLong(p[4]); } catch (NumberFormatException e) { size = -1; }
        int roomId = 0;
        Models.User to = null;
        String reject = null;
        String ext = TextUtil.extOf(name);
        if (name.isEmpty()) reject = "Tên file không hợp lệ";
        else if (size <= 0) reject = "File rỗng hoặc kích thước không hợp lệ";
        else if (size > ChatServer.maxFileBytes) reject = "File vượt quá giới hạn " + (ChatServer.maxFileBytes >> 20) + " MB";
        else if (ChatServer.blockedExt.contains(ext)) reject = "Loại file ." + ext + " không được phép gửi";
        else if (!ChatServer.allowedExt.isEmpty() && !ChatServer.allowedExt.contains(ext)) reject = "Chỉ cho phép gửi các loại file: " + String.join(", ", ChatServer.allowedExt);
        else if (uploads.size() >= MAX_UPLOADS_PER_USER) reject = "Đang gửi quá nhiều file cùng lúc";
        else if ("GROUP".equals(scope)) {
            roomId = Integer.parseInt(p[2]);
            if (!inRoom(roomId)) reject = "Bạn chưa tham gia phòng này";
        } else if ("PRIVATE".equals(scope)) {
            to = userDAO.find(p[2]);
            if (to == null) reject = "Người nhận không tồn tại";
        } else reject = "Loại gửi file không hợp lệ";
        if (reject == null && !spamFileOk()) reject = "Bạn gửi file quá nhanh";
        if (reject != null) { send(Protocol.UPLOAD_REJECT + "|" + reject); return; }

        long fileId = messageDAO.createFile(userId, roomId, to == null ? 0 : to.id, name, size);
        Upload u = new Upload();
        u.fileId = fileId; u.size = size; u.name = name; u.roomId = roomId; u.receiverId = to == null ? 0 : to.id;
        u.dest = new File(ChatServer.filesDir, fileId + ".bin");
        u.out = new BufferedOutputStream(new FileOutputStream(u.dest), 65536);
        uploads.put(fileId, u);
        send(Protocol.UPLOAD_READY + "|" + fileId);
        Log.info(username + " bat dau gui file '" + name + "' " + size + " byte (id " + fileId + ")");
    }

    private final Guards.RateLimiter fileLimiter = new Guards.RateLimiter(30, 60_000);
    private boolean spamFileOk() { return fileLimiter.allow(); }

    private void uploadChunk(String[] p) throws IOException {
        long fid = Long.parseLong(p[1]);
        int len = Integer.parseInt(p[2]);
        if (len <= 0 || len > 1 << 20) throw new IOException("Manh file khong hop le: " + len); // vi pham giao thuc -> ngat
        byte[] buf = new byte[len];
        in.readFully(buf);                         // luon doc het byte de giu dung khung giao thuc
        Upload u = uploads.get(fid);
        if (u == null) return;                     // upload da bi huy/tu choi: bo qua du lieu con sot
        if (u.received + len > u.size) {
            abortUpload(u, true);
            uploads.remove(fid);
            send(Protocol.UPLOAD_REJECT + "|Dữ liệu vượt quá kích thước đã khai báo");
            return;
        }
        u.out.write(buf);
        u.md.update(buf);
        u.received += len;
        Stats.bytesUp.add(len);
    }

    private void finishUpload(String[] p) throws IOException, SQLException {
        long fid = Long.parseLong(p[1]);
        Upload u = uploads.remove(fid);
        if (u == null) { send(Protocol.UPLOAD_REJECT + "|Phiên gửi file không tồn tại"); return; }
        u.out.close();
        String sha = CryptoUtil.bytesToHex(u.md.digest());
        String claimed = p.length > 2 ? p[2].toLowerCase() : "";
        if (u.received != u.size) {
            abortUpload(u, true);
            send(Protocol.UPLOAD_REJECT + "|File nhận được thiếu dữ liệu (" + u.received + "/" + u.size + " byte)");
            return;
        }
        if (!claimed.isEmpty() && !claimed.equals(sha)) {
            abortUpload(u, true);
            Audit.log(username, "UPLOAD_CHECKSUM_FAIL", u.name, "file " + fid, ip);
            send(Protocol.UPLOAD_REJECT + "|Sai checksum SHA-256: file bị hỏng trong quá trình truyền, hãy gửi lại");
            return;
        }
        messageDAO.setFileSha(fid, sha);
        String enc = CryptoUtil.encrypt(fid + "|" + u.name + "|" + u.size);
        Models.Msg m;
        try {
            m = messageDAO.save(userId, username, u.roomId, u.receiverId, Protocol.TYPE_FILE, enc);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("FOREIGN KEY")) throw e;
            new File(ChatServer.filesDir, fid + ".bin").delete();
            messageDAO.deleteFile(fid);
            send(Protocol.UPLOAD_REJECT + "|Phòng không còn tồn tại (có thể vừa bị giải tán)");
            return;
        }
        Stats.filesUploaded.increment();
        Stats.messages.increment();
        if (u.roomId > 0) {
            ChatServer.rooms.broadcast(u.roomId, groupFrame(u.roomId, m));
        } else {
            Models.User to = userDAO.findById(u.receiverId);
            if (to != null) routePrivate(m, to);
        }
        send(Protocol.join(Protocol.UPLOAD_DONE, fid, sha));
    }

    private void cancelUpload(String[] p) {
        long fid = Long.parseLong(p[1]);
        Upload u = uploads.remove(fid);
        if (u != null) {
            abortUpload(u, true);
            Log.info(username + " huy gui file " + fid);
        }
        send(Protocol.UPLOAD_CANCELLED + "|" + fid);
    }

    /** Don dep file do dang: dong luong, xoa file tren dia va ban ghi CSDL. */
    private void abortUpload(Upload u, boolean notify) {
        closeQuietly(u.out);
        u.dest.delete();
        messageDAO.deleteFile(u.fileId);
        if (!notify) Log.info("Huy file do ngat ket noi: " + u.fileId);
    }

    private void downloadFile(long fileId) throws SQLException {
        Models.FileRec f = messageDAO.getFile(fileId);
        File file = new File(ChatServer.filesDir, fileId + ".bin");
        if (f == null || !file.isFile()) { err("File không tồn tại"); return; }
        boolean allowed = f.roomId > 0 ? roomDAO.getRole(f.roomId, userId) != null
                                       : (f.uploaderId == userId || f.receiverId == userId);
        if (!allowed) {
            Audit.log(username, "DOWNLOAD_DENIED", String.valueOf(fileId), "khong co quyen tai file", ip);
            err("Bạn không có quyền tải file này"); return;
        }
        String sha = f.sha256;
        if (sha == null || sha.isEmpty()) {          // file cu (truoc khi nang cap) chua co SHA-256 -> tinh 1 lan roi luu
            sha = sha256Of(file);
            messageDAO.setFileSha(fileId, sha);
        }
        FileTask t = new FileTask();
        t.id = f.id; t.name = f.name; t.file = file; t.size = file.length(); t.sha = sha;
        cancelledDownloads.remove(fileId);
        if (!closed.get()) outbox.offer(t); // writer thread se truyen tung manh - khong chan cac client khac
    }

    static String sha256Of(File f) {
        MessageDigest md = CryptoUtil.newSha256();
        try (InputStream i = new BufferedInputStream(new FileInputStream(f), 65536)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = i.read(b)) > 0) md.update(b, 0, n);
        } catch (IOException e) {
            return "";
        }
        return CryptoUtil.bytesToHex(md.digest());
    }
}
