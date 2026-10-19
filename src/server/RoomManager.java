package server;

import common.Protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Quan ly cac phien (ClientHandler) dang online cua tung phong. Thread-safe. */
public class RoomManager {
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> sessions = new ConcurrentHashMap<>();

    public void attach(int roomId, ClientHandler h) {
        sessions.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).addIfAbsent(h);
    }

    public void detach(int roomId, ClientHandler h) {
        CopyOnWriteArrayList<ClientHandler> l = sessions.get(roomId);
        if (l != null) l.remove(h);
    }

    public boolean isAttached(int roomId, ClientHandler h) {
        CopyOnWriteArrayList<ClientHandler> l = sessions.get(roomId);
        return l != null && l.contains(h);
    }

    /** Go handler khoi moi phong, tra ve danh sach roomId da go. */
    public List<Integer> detachAll(ClientHandler h) {
        List<Integer> ids = new ArrayList<>();
        for (java.util.Map.Entry<Integer, CopyOnWriteArrayList<ClientHandler>> e : sessions.entrySet()) {
            if (e.getValue().remove(h)) ids.add(e.getKey());
        }
        return ids;
    }

    public void broadcast(int roomId, String frame) {
        CopyOnWriteArrayList<ClientHandler> l = sessions.get(roomId);
        if (l == null) return;
        for (ClientHandler h : l) h.send(frame); // send() khong chan, loi 1 client khong anh huong client khac
    }

    public void membersChanged(int roomId) {
        broadcast(roomId, Protocol.join(Protocol.MEMBERS_CHANGED, roomId));
    }

    public void sys(int roomId, String text) {
        broadcast(roomId, Protocol.join(Protocol.SYS, roomId, text));
    }

    /** So phong dang co it nhat 1 phien online. */
    public int activeRoomCount() {
        int n = 0;
        for (CopyOnWriteArrayList<ClientHandler> l : sessions.values()) if (!l.isEmpty()) n++;
        return n;
    }

    public List<ClientHandler> sessionsOf(int roomId) {
        CopyOnWriteArrayList<ClientHandler> l = sessions.get(roomId);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    public void closeRoom(int roomId) {
        sessions.remove(roomId);
    }
}
