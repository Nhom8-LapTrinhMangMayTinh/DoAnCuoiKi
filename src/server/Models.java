package server;

import java.util.ArrayList;
import java.util.List;

/** Cac lop du lieu don gian dung giua DAO va ClientHandler. */
public final class Models {
    private Models() {}

    public static class User {
        public int id;
        public String username;
        public String displayName = "";
        public String serverRole = "USER";
        public boolean disabled;
        public int avatarVer;
        public long lastSeen;
        public boolean admin() { return "ADMIN".equals(serverRole); }
    }

    public static class RoomInfo {
        public int id;
        public String name;
        public boolean locked;
        public int ownerId;
        public String ownerName;
        public int members;
        public String myRole = "";
    }

    public static class MemberInfo {
        public String username;
        public String role;
    }

    public static class LeaveResult {
        public boolean roomDeleted;
        public String newOwner;
    }

    public static class Msg {
        public long id;
        public String sender;
        public int senderId;
        public int receiverId;      // 0 = tin nhom
        public String receiverName = "";
        public int roomId;          // 0 = tin rieng
        public long ts;
        public String type;
        public String content;      // da ma hoa AES ("" neu da thu hoi)
        public long replyTo;
        public String replySender = "";
        public String replyEnc = ""; // doan trich cua tin duoc tra loi (da ma hoa AES)
        public long editedAt;
        public boolean deleted;
        public String reactions = "";
        public boolean pinned;
        public String cid = "";     // clientId do client sinh (chi dung khi phat lai tin vua gui)

        /** Phan duoi chung cua moi frame tin nhan:  replyTo|replySender|replyAES|edited|deleted|reactions|pinned */
        public String tail() {
            return replyTo + "|" + replySender + "|" + replyEnc + "|" + (editedAt > 0 ? 1 : 0) + "|" + (deleted ? 1 : 0)
                    + "|" + reactions + "|" + (pinned ? 1 : 0);
        }
    }

    public static class Page {
        public List<Msg> items = new ArrayList<>();
        public boolean hasMore;
    }

    public static class FileRec {
        public long id;
        public String name;
        public long size;
        public String sha256 = "";
        public int uploaderId;
        public int roomId;     // 0 = khong phai file phong
        public int receiverId; // 0 = khong phai file rieng
    }
}
