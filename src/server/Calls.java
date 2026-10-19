package server;

import common.Protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Quan ly cuoc goi video 1-1. Server chi DINH TUYEN: bao hieu (moi/nhan/tu choi/ket thuc/ban/het gio) va chuyen tiep goi
 * hinh-tieng giua hai ben cua cuoc goi - khong luu tru. Moi nguoi chi o toi da 1 cuoc goi.
 */
public final class Calls {
    private Calls() {}

    static final class Call {
        final String id = common.CryptoUtil.randomToken().substring(0, 12);
        final ClientHandler caller, callee;
        final boolean video;
        volatile boolean active;
        volatile boolean ended;
        long startedAt;
        final java.util.concurrent.atomic.LongAdder bytes = new java.util.concurrent.atomic.LongAdder();
        ScheduledFuture<?> ringTimer;
        Call(ClientHandler a, ClientHandler b, boolean v) { caller = a; callee = b; video = v; }
        ClientHandler peerOf(ClientHandler h) { return h == caller ? callee : h == callee ? caller : null; }
        boolean involves(ClientHandler h) { return h == caller || h == callee; }
    }

    private static final Map<String, Call> byId = new ConcurrentHashMap<>();
    private static final Map<String, Call> byUser = new ConcurrentHashMap<>();   // username (lower) -> cuoc goi

    static Call of(String id) { return byId.get(id); }

    static boolean inCall(String user) { return byUser.containsKey(user.toLowerCase()); }

    /** Moi goi. Tra ve null va tu bao loi cho nguoi goi neu khong the. */
    static synchronized void invite(ClientHandler caller, String calleeName, boolean video) {
        ClientHandler callee = ChatServer.online.get(calleeName);
        if (callee == null || callee.isClosed()) { caller.send(Protocol.join(Protocol.CALL_REJECTED, "-", "Người nhận không trực tuyến")); return; }
        if (callee == caller) { caller.send(Protocol.join(Protocol.CALL_REJECTED, "-", "Không thể tự gọi chính mình")); return; }
        if (inCall(caller.username())) { caller.send(Protocol.join(Protocol.CALL_REJECTED, "-", "Bạn đang trong một cuộc gọi khác")); return; }
        if (inCall(callee.username())) { caller.send(Protocol.join(Protocol.CALL_REJECTED, "-", callee.username() + " đang bận (trong cuộc gọi khác)")); return; }
        Call c = new Call(caller, callee, video);
        byId.put(c.id, c);
        byUser.put(caller.username().toLowerCase(), c);
        byUser.put(callee.username().toLowerCase(), c);
        caller.send(Protocol.join(Protocol.CALL_RINGING, c.id, callee.username()));
        callee.send(Protocol.join(Protocol.CALL_INCOMING, c.id, caller.username(), video ? 1 : 0));
        c.ringTimer = ChatServer.schedule(() -> {
            if (!c.active && !c.ended) end(c, Protocol.CALL_REJECTED, "Không có người trả lời");
        }, ChatServer.callRingMs);
        Audit.log(caller.username(), "CALL_INVITE", callee.username(), video ? "video" : "thoai", caller.ip());
    }

    static synchronized void accept(ClientHandler h, String id) {
        Call c = byId.get(id);
        if (c == null || c.ended || c.callee != h || c.active) { h.send(Protocol.ERROR + "|Cuộc gọi không còn hiệu lực"); return; }
        c.active = true;
        c.startedAt = System.currentTimeMillis();
        if (c.ringTimer != null) c.ringTimer.cancel(false);
        Stats.callsActive.incrementAndGet();
        Stats.callsTotal.increment();
        Audit.log(c.caller.username(), "CALL_START", c.callee.username(), c.video ? "video" : "thoai", c.caller.ip());
        c.caller.send(Protocol.join(Protocol.CALL_ACCEPTED, c.id));
        c.callee.send(Protocol.join(Protocol.CALL_ACCEPTED, c.id));
    }

    static synchronized void reject(ClientHandler h, String id) {
        Call c = byId.get(id);
        if (c == null || c.ended || c.callee != h || c.active) return;
        end(c, Protocol.CALL_REJECTED, h.username() + " đã từ chối cuộc gọi");
    }

    /** Ket thuc / huy do chuong boi 1 trong 2 ben. */
    static synchronized void hangup(ClientHandler h, String id) {
        Call c = byId.get(id);
        if (c == null || c.ended || !c.involves(h)) return;
        if (!c.active && h == c.caller) end(c, Protocol.CALL_ENDED, "Người gọi đã hủy cuộc gọi");
        else end(c, Protocol.CALL_ENDED, h.username() + " đã kết thúc cuộc gọi");
    }

    /** Ket noi cua 1 ben dong -> cuoc goi ket thuc. */
    static synchronized void onDisconnect(ClientHandler h) {
        String u = h.username();
        if (u == null) return;
        Call c = byUser.get(u.toLowerCase());
        if (c != null && c.involves(h)) end(c, Protocol.CALL_ENDED, u + " mất kết nối");
    }

    private static void end(Call c, String frameCmd, String reason) {
        if (c.ended) return;
        c.ended = true;
        if (c.ringTimer != null) c.ringTimer.cancel(false);
        byId.remove(c.id);
        byUser.remove(c.caller.username().toLowerCase(), c);
        byUser.remove(c.callee.username().toLowerCase(), c);
        String frame = Protocol.join(frameCmd, c.id, reason);
        if (c.active) {
            Stats.callsActive.decrementAndGet();
            long secs = (System.currentTimeMillis() - c.startedAt) / 1000;
            Audit.log(c.caller.username(), "CALL_END", c.callee.username(), secs + " giay, " + (c.bytes.sum() / 1024) + " KB chuyen tiep", c.caller.ip());
        }
        c.caller.send(frame);
        c.callee.send(frame);
    }

    public static int activeCountForTest() { return activeCount(); }

    /** Dem so cuoc goi dang dien ra (test/dashboard). */
    static int activeCount() { return (int) byId.values().stream().filter(c -> c.active).count(); }

    static void clearAll() { byId.clear(); byUser.clear(); }
}
