package client;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Nguon hinh cho cuoc goi. JDK KHONG co API webcam, nen:
 *  - Webcam: dung thu vien tuy chon sarxos/webcam-capture neu cac jar nam trong lib/ (nap bang reflection, khong bat buoc);
 *  - Chia se man hinh: thuan JDK (java.awt.Robot) - luon dung duoc khi co man hinh;
 *  - Mau thu: hinh dong tong hop (kiem thu / may khong man hinh, hoac -Dgc.fakeVideo=true).
 */
public final class VideoSources {
    private VideoSources() {}

    public static final int W = 480, H = 270;

    public interface Source extends Closeable {
        String name();
        /** Lay 1 khung hinh (da thu nho ve toi da W x H). null neu tam thoi khong co. */
        BufferedImage grab();
        @Override void close();
    }

    public static boolean webcamLibraryPresent() {
        try { Class.forName("com.github.sarxos.webcam.Webcam"); return true; } catch (Throwable t) { return false; }
    }

    /** Danh sach nguon kha dung theo thu tu uu tien. */
    public static List<String> available() {
        List<String> l = new ArrayList<>();
        if (Boolean.getBoolean("gc.fakeVideo")) { l.add("Hình mẫu (kiểm thử)"); return l; }
        if (webcamLibraryPresent() && webcamCount() > 0) l.add("Webcam");
        if (!GraphicsEnvironment.isHeadless()) l.add("Chia sẻ màn hình");
        l.add("Hình mẫu (kiểm thử)");
        return l;
    }

    private static int webcamCount() {
        try {
            Class<?> c = Class.forName("com.github.sarxos.webcam.Webcam");
            return ((List<?>) c.getMethod("getWebcams").invoke(null)).size();
        } catch (Throwable t) { return 0; }
    }

    public static Source open(String name) throws IOException {
        if (name.startsWith("Webcam")) return new WebcamSource();
        if (name.startsWith("Chia sẻ")) return new ScreenSource();
        return new PatternSource();
    }

    public static BufferedImage fit(BufferedImage src) {
        if (src == null) return null;
        double s = Math.min((double) W / src.getWidth(), (double) H / src.getHeight());
        if (s >= 1) return src;
        int w = Math.max(1, (int) (src.getWidth() * s)), h = Math.max(1, (int) (src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ nguon
    static final class ScreenSource implements Source {
        private final Robot robot;
        private final Rectangle area;
        ScreenSource() throws IOException {
            try {
                robot = new Robot();
                area = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            } catch (AWTException | HeadlessException | SecurityException e) {
                throw new IOException("Không chụp được màn hình: " + e.getMessage(), e);
            }
        }
        @Override public String name() { return "Chia sẻ màn hình"; }
        @Override public BufferedImage grab() { return fit(robot.createScreenCapture(area)); }
        @Override public void close() { }
    }

    /** Webcam qua reflection de khong phu thuoc luc bien dich/chay khi khong co thu vien. */
    static final class WebcamSource implements Source {
        private final Object cam;
        private final Method getImage, close;
        WebcamSource() throws IOException {
            try {
                Class<?> wc = Class.forName("com.github.sarxos.webcam.Webcam");
                cam = wc.getMethod("getDefault").invoke(null);
                if (cam == null) throw new IOException("Không tìm thấy webcam");
                try { wc.getMethod("setViewSize", Dimension.class).invoke(cam, new Dimension(640, 480)); } catch (Throwable ignored) { }
                wc.getMethod("open").invoke(cam);
                getImage = wc.getMethod("getImage");
                close = wc.getMethod("close");
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("Không mở được webcam (cần jar webcam-capture trong lib/ và quyền camera): " + t, t);
            }
        }
        @Override public String name() { return "Webcam"; }
        @Override public BufferedImage grab() {
            try { return fit((BufferedImage) getImage.invoke(cam)); } catch (Throwable t) { return null; }
        }
        @Override public void close() { try { close.invoke(cam); } catch (Throwable ignored) { } }
    }

    /** Hinh dong tong hop co dong ho: nhin la biet video co dang chay va dung toc do. */
    static final class PatternSource implements Source {
        private final long t0 = System.currentTimeMillis();
        private final String who;
        PatternSource() { this("Hình mẫu"); }
        PatternSource(String who) { this.who = who; }
        @Override public String name() { return "Hình mẫu (kiểm thử)"; }
        @Override public BufferedImage grab() {
            long t = System.currentTimeMillis() - t0;
            BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = im.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float hue = (t % 8000) / 8000f;
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 0.6f, 0.9f), W, H, Color.getHSBColor((hue + 0.35f) % 1f, 0.7f, 0.5f)));
            g.fillRect(0, 0, W, H);
            int x = (int) ((Math.sin(t / 700.0) * 0.5 + 0.5) * (W - 90));
            int y = (int) ((Math.cos(t / 900.0) * 0.5 + 0.5) * (H - 90));
            g.setColor(new Color(255, 255, 255, 190));
            g.fillOval(x, y, 80, 80);
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRoundRect(10, H - 54, 270, 44, 14, 14);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.drawString(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()), 22, H - 24);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString("khung #" + (t / 66), 175, H - 24);
            g.dispose();
            return im;
        }
        @Override public void close() { }
    }

    // ------------------------------------------------------------------ JPEG
    /** Nen JPEG voi chat luong 0..1; tra ve byte. */
    public static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB && img.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam pr = w.getDefaultWriteParam();
        pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        pr.setCompressionQuality(quality);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
            w.setOutput(out);
            w.write(null, new IIOImage(rgb, null, null), pr);
        } finally { w.dispose(); }
        return bos.toByteArray();
    }

    public static BufferedImage decode(byte[] data) {
        try { return ImageIO.read(new ByteArrayInputStream(data)); } catch (IOException e) { return null; }
    }
}
