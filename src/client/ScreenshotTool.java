package client;

import common.CryptoUtil;
import common.Protocol;
import server.ChatServer;
import tests.TestKit.TC;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chup anh minh chung giao dien THAT (Swing that, server that, du lieu that): chay duoi Xvfb khi khong co man hinh:
 *   xvfb-run -a java -cp "out:lib/*" client.ScreenshotTool docs/screenshots
 */
public class ScreenshotTool {
    static final String HOST = "127.0.0.1";
    static int PORT;
    static File outDir;
    static final String PW = "Matkhau#123";

    static void snap(Window w, String name) throws Exception {
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            img[0] = new BufferedImage(Math.max(1, w.getWidth()), Math.max(1, w.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img[0].getWidth(), img[0].getHeight());
            w.paintAll(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", new File(outDir, name));
        System.out.println("Da chup " + name);
    }

    static void ui(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    /** Dang nhap that va mo MainFrame (giong LoginFrame). */
    static MainFrame openMain(String user, Properties cfg, int x, int y) throws Exception {
        final AtomicReference<MainFrame> ref = new AtomicReference<>();
        final ChatClient c = new ChatClient();
        c.setListener(new ChatClient.Listener() {
            @Override public void onFrame(String[] p) {
                if (p[0].equals("AUTH_OK")) {
                    c.enableAutoReconnect(user, PW, 20);
                    MainFrame mf = new MainFrame(c, user, HOST, PORT, cfg);
                    c.setListener(mf);
                    mf.setLocation(x, y);
                    mf.setVisible(true);
                    ref.set(mf);
                }
            }
            @Override public void onDisconnected(String r) { System.out.println("disconnected " + r); }
        });
        c.connect(HOST, PORT, 5000);
        c.send(Protocol.join(Protocol.AUTH_LOGIN, user, PW));
        for (int i = 0; i < 100 && ref.get() == null; i++) sleep(100);
        if (ref.get() == null) throw new IllegalStateException("khong dang nhap duoc " + user);
        return ref.get();
    }

    static String png(Color a, Color b, int w, int h) throws IOException {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setPaint(new GradientPaint(0, 0, a, w, h, b));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 170));
        g.fillOval(w / 6, h / 5, w / 3, w / 3);
        g.fillRect(w / 2, h / 2, w / 3, h / 5);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(im, "png", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("gc.fakeAudio", "true");
        System.setProperty("gc.fakeVideo", "true");
        outDir = new File(args.length > 0 ? args[0] : "docs/screenshots");
        outDir.mkdirs();
        File tmp = Files.createTempDirectory("gc-shots").toFile();
        Properties s = new Properties();
        s.setProperty("port", "0");
        s.setProperty("db.url", "jdbc:sqlite:" + new File(tmp, "chat.db").getPath());
        s.setProperty("files.dir", new File(tmp, "files").getPath());
        s.setProperty("log.file", new File(tmp, "server.log").getPath());
        s.setProperty("tls.keystore", new File(tmp, "server.p12").getPath());
        s.setProperty("admin.users", "admin");
        s.setProperty("admin.password", PW);
        s.setProperty("admin.credentials.file", new File(tmp, "adm.txt").getPath());
        s.setProperty("spam.max.msgs", "100000");
        s.setProperty("spam.max.repeat", "100000");
        ChatServer.start(s);
        PORT = ChatServer.port();
        ChatClient.configureTls(true, "", new File(tmp, "known.properties"));
        Properties cfg = new Properties();
        System.setProperty("java.awt.headless", "false");
        SwingUtilities.invokeAndWait(UiKit::setupLookAndFeel);

        // ---- man hinh dang nhap
        final LoginFrame[] lf = new LoginFrame[1];
        ui(() -> { lf[0] = new LoginFrame(cfg, "Mật khẩu hoặc tên đăng nhập sai sẽ hiện ở đây"); lf[0].setLocation(40, 40); lf[0].setVisible(true); });
        sleep(500);
        snap(lf[0], "01-dang-nhap.png");
        ui(() -> lf[0].dispose());

        // ---- du lieu
        TC alice = new TC("alice").connect(HOST, PORT), bob = new TC("bob").connect(HOST, PORT), carol = new TC("carol").connect(HOST, PORT);
        alice.registerAndLogin(PW); bob.registerAndLogin(PW); carol.registerAndLogin(PW);
        bob.send(Protocol.join(Protocol.SET_PROFILE, "Bảo Trần"));
        carol.send(Protocol.join(Protocol.SET_PROFILE, "Cẩm Linh"));
        bob.send(Protocol.join(Protocol.SET_AVATAR, png(new Color(0xF97316), new Color(0xEC4899), 64, 64)));
        carol.send(Protocol.join(Protocol.SET_AVATAR, png(new Color(0x10B981), new Color(0x3B82F6), 64, 64)));
        alice.send(Protocol.join(Protocol.CREATE_ROOM, "Nhóm Đồ Án Mạng", ""));
        int rid = Integer.parseInt(alice.expect("CREATE_OK", 3000)[1]);
        alice.send(Protocol.join(Protocol.CREATE_ROOM, "Cả lớp 22TCLC", ""));
        alice.expect("CREATE_OK", 3000);
        for (TC t : new TC[]{bob, carol}) { t.clear(); t.send(Protocol.join(Protocol.JOIN_ROOM, rid, "")); t.expect("JOIN_OK", 3000); }
        long first = say(alice, rid, "Chào cả nhóm! Hôm nay họp lúc 14h nhé 👋", 0);
        long m2 = say(bob, rid, "Ok, mình sẽ chuẩn bị phần báo cáo bảo mật bằng Tiếng Việt", 0);
        long m3 = say(carol, rid, "Mình gửi slide trước 13h30 để cả nhóm xem trước", m2);
        long m4 = say(bob, rid, "Mình vừa dựng xong dashboard quản trị server, số liệu cập nhật mỗi giây", 0);
        long gone = say(carol, rid, "tin này sẽ bị thu hồi", 0);
        say(alice, rid, "Nhớ kiểm thử với nhiều client cùng lúc và đo độ trễ qua Tailscale nhé", 0);
        alice.send(Protocol.join(Protocol.PIN_MSG, first, 1));
        bob.send(Protocol.join(Protocol.REACT, first, "like")); carol.send(Protocol.join(Protocol.REACT, first, "like"));
        carol.send(Protocol.join(Protocol.REACT, m2, "love")); alice.send(Protocol.join(Protocol.REACT, m2, "love")); alice.send(Protocol.join(Protocol.REACT, m3, "haha"));
        bob.send(Protocol.join(Protocol.EDIT_MSG, m4, CryptoUtil.encrypt("Mình vừa dựng xong dashboard quản trị, số liệu cập nhật mỗi giây (đã sửa)")));
        carol.send(Protocol.join(Protocol.RECALL_MSG, gone));
        File img = new File(tmp, "so-do-kien-truc.png");
        ImageIO.write(ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(png(new Color(0x2563EB), new Color(0x7C3AED), 480, 300)))), "png", img);
        bob.c.uploadFile("GROUP", "" + rid, img, null);
        File pdf = new File(tmp, "BaoCao-Tuan3.pdf");
        Files.write(pdf.toPath(), new byte[1_258_000]);
        carol.c.uploadFile("GROUP", "" + rid, pdf, null);
        bob.send(Protocol.join(Protocol.SEND_MSG_PRIVATE, "alice", CryptoUtil.encrypt("Alice ơi, chiều nay mình test VPN Tailscale nhé?"), 0, "x1"));
        sleep(600);
        bob.send(Protocol.join(Protocol.MARK_READ, "G", rid, 999999)); carol.send(Protocol.join(Protocol.MARK_READ, "G", rid, 999999));

        alice.close();
        sleep(1200);
        // ---- giao dien alice
        MainFrame mf = openMain("alice", cfg, 20, 20);
        sleep(1500);
        ui(() -> { mf.setSize(1240, 780); mf.validate(); });
        selectRoom(mf, "Nhóm Đồ Án Mạng");
        sleep(2500);
        bob.send(Protocol.join(Protocol.TYPING, "G", rid, 1));
        sleep(700);
        snap(mf, "02-chat-nhom-reply-reaction-ghim.png");

        // chat rieng
        ui(() -> { try { selectUser(mf, "bob"); } catch (Exception e) { throw new RuntimeException(e); } });
        sleep(1500);
        snap(mf, "03-chat-rieng-da-doc.png");
        selectRoom(mf, "Nhóm Đồ Án Mạng");
        sleep(800);

        // tim kiem
        ui(mf::openSearch);
        sleep(400);
        SearchView sv = new SearchView(mf);
        sv.search("tieng viet");
        sleep(1200);
        snap(sv.dialog(), "04-tim-kiem-khong-dau.png");
        ui(() -> sv.dialog().dispose());

        // tin ghim
        ui(mf::openPins);
        sleep(900);
        Window pins = topDialog("Tin nhắn đã ghim");
        if (pins != null) { snap(pins, "05-tin-nhan-ghim.png"); ui(pins::dispose); }

        // truyen file voi tien trinh / toc do / nut huy
        File big = new File(tmp, "video-demo.mp4");
        try (RandomAccessFile r = new RandomAccessFile(big, "rw")) { r.setLength(700L * 1024 * 1024); }
        ui(() -> mf.startUpload(big));
        sleep(900);
        snap(mf, "06-truyen-file-tien-trinh-toc-do.png");
        sleep(6000);

        // dashboard quan tri
        MainFrame adminMf = openMain("admin", cfg, 60, 60);
        sleep(1200);
        List<TC> crowd = new ArrayList<>();
        for (int i = 0; i < 12; i++) { TC t = new TC("user" + i).connect(HOST, PORT); if (t.registerAndLogin(PW)) { crowd.add(t); t.send(Protocol.join(Protocol.JOIN_ROOM, rid, "")); } }
        Thread chat = new Thread(() -> { for (int k = 0; k < 40; k++) { for (TC t : crowd) t.send(Protocol.join(Protocol.SEND_MSG_GROUP, rid, CryptoUtil.encrypt("tai " + k + " " + System.nanoTime()))); sleep(300); } });
        chat.start();
        ui(adminMf::openAdmin);
        sleep(6500);
        AdminFrame af = adminFrame(adminMf);
        af.setSize(1040, 700);
        sleep(500);
        snap(af, "07-dashboard-tong-quan.png");
        selectTab(af, 1);
        sleep(1200);
        snap(af, "08-dashboard-tai-khoan.png");
        selectTab(af, 3);
        sleep(1200);
        snap(af, "09-dashboard-nhat-ky.png");
        ui(af::dispose);

        // mat ket noi -> banner + tu ket noi lai
        mf.client.dropConnectionForTest();
        sleep(500);
        snap(mf, "10-mat-ket-noi-tu-ket-noi-lai.png");
        sleep(3500);
        snap(mf, "11-da-ket-noi-lai.png");

        // dialog ho so + phim tat
        ui(() -> new Thread(() -> mf.showHelp()).start());
        sleep(900);
        Window help = topDialog("Phím tắt");
        if (help != null) { snap(help, "12-phim-tat.png"); ui(help::dispose); }
        ui(() -> new Thread(() -> mf.profileDialog()).start());
        sleep(900);
        Window prof = topDialog("Hồ sơ cá nhân");
        if (prof != null) { snap(prof, "13-ho-so-anh-dai-dien.png"); ui(prof::dispose); }

        // ================= tinh nang moi: giai tan phong, ghi am, goi video =================
        selectRoom(mf, "Nhóm Đồ Án Mạng");
        sleep(800);
        ui(() -> new Thread(mf::dissolveSelected).start());
        sleep(900);
        Window dis = topDialog("Giải tán phòng");
        if (dis != null) { snap(dis, "14-giai-tan-phong-xac-nhan.png"); ui(dis::dispose); }
        sleep(300);

        ui(mf::toggleRecording);
        sleep(2600);
        snap(mf, "15-ghi-am-dang-ghi.png");
        ui(mf::finishRecording);
        sleep(2500);
        ui(mf::playLastVoiceForTest);
        sleep(1300);
        snap(mf, "16-tin-nhan-thoai-dang-phat.png");
        sleep(2500);

        bob.close();
        carol.close();
        sleep(1200);
        MainFrame bobMf = openMain("bob", cfg, 100, 100);
        sleep(1500);
        selectUser(mf, "bob");
        sleep(900);
        ui(mf::startCallWithCurrent);
        sleep(1200);
        IncomingCallDialog inc = null;
        for (int i = 0; i < 30 && inc == null; i++) { inc = incomingOf(bobMf); sleep(100); }
        if (inc != null) {
            snap(inc, "17-cuoc-goi-den.png");
            CallWindow aw0 = callWinOf(mf);
            if (aw0 != null) snap(aw0, "18-dang-do-chuong.png");
            final IncomingCallDialog fi = inc;
            ui(() -> { fi.dismiss(); bobMf.acceptCall(fi.callId, "alice"); });
        }
        sleep(4500);
        CallWindow aw = callWinOf(mf), bw = callWinOf(bobMf);
        if (aw != null) snap(aw, "19-goi-video-may-alice.png");
        if (bw != null) snap(bw, "20-goi-video-may-bob.png");
        if (bw != null && bw.session() != null) { bw.session().setCameraOn(false); sleep(1500); if (aw != null) snap(aw, "21-doi-phuong-tat-camera.png"); }
        if (aw != null) ui(aw::hangup);
        sleep(2500);
        snap(mf, "22-sau-cuoc-goi.png");
        System.out.println("Xong. Anh luu o " + outDir.getAbsolutePath());
        System.exit(0);
    }

    static long say(TC t, int rid, String text, long reply) {
        t.clear();
        t.send(Protocol.join(Protocol.SEND_MSG_GROUP, rid, CryptoUtil.encrypt(text), reply, "c" + System.nanoTime()));
        String[] r = t.expect("MSG_GROUP", 3000);
        sleep(120);
        return r == null ? -1 : Long.parseLong(r[2]);
    }

    @SuppressWarnings("unchecked")
    static void selectRoom(MainFrame mf, String name) throws Exception {
        ui(() -> {
            JList<MainFrame.RoomItem> l = mf.roomListForTest();
            for (int i = 0; i < l.getModel().getSize(); i++) if (l.getModel().getElementAt(i).name.equals(name)) { l.setSelectedIndex(i); break; }
        });
    }

    @SuppressWarnings("unchecked")
    static void selectUser(MainFrame mf, String name) throws Exception {
        Field f = MainFrame.class.getDeclaredField("userList");
        f.setAccessible(true);
        JList<MainFrame.UserItem> l = (JList<MainFrame.UserItem>) f.get(mf);
        for (int i = 0; i < l.getModel().getSize(); i++) if (l.getModel().getElementAt(i).name.equals(name)) { l.setSelectedIndex(i); break; }
    }

    static CallWindow callWinOf(MainFrame mf) throws Exception {
        Field f = MainFrame.class.getDeclaredField("callWin");
        f.setAccessible(true);
        return (CallWindow) f.get(mf);
    }

    static IncomingCallDialog incomingOf(MainFrame mf) throws Exception {
        Field f = MainFrame.class.getDeclaredField("incoming");
        f.setAccessible(true);
        return (IncomingCallDialog) f.get(mf);
    }

    static Window topDialog(String title) {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof Dialog && title.equals(((Dialog) w).getTitle())) return w;
        }
        return null;
    }

    static AdminFrame adminFrame(MainFrame mf) throws Exception {
        Field f = MainFrame.class.getDeclaredField("adminFrame");
        f.setAccessible(true);
        return (AdminFrame) f.get(mf);
    }

    static void selectTab(AdminFrame af, int i) throws Exception {
        ui(() -> {
            for (Component c : ((JRootPane) af.getRootPane()).getContentPane().getComponents())
                if (c instanceof JTabbedPane) ((JTabbedPane) c).setSelectedIndex(i);
        });
    }

    /** Dieu khien hop thoai tim kiem qua reflection (lop noi bo khong public). */
    static class SearchView {
        final MainFrame mf;
        SearchView(MainFrame mf) { this.mf = mf; }
        JDialog dialog() {
            try { Field f = MainFrame.class.getDeclaredField("searchDialog"); f.setAccessible(true); return (JDialog) f.get(mf); } catch (Exception e) { throw new RuntimeException(e); }
        }
        void search(String kw) throws Exception {
            JDialog d = dialog();
            Field f = d.getClass().getDeclaredField("kw");
            f.setAccessible(true);
            JTextField t = (JTextField) f.get(d);
            ui(() -> { t.setText(kw); t.postActionEvent(); });
        }
    }
}
