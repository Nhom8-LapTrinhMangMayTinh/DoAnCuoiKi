package client;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

/** Bo ve giao dien hien dai dung chung: mau sac, nut phang, o nhap bo goc, anh dai dien tron, bieu tuong cam xuc. */
public final class UiKit {
    private UiKit() {}

    public static final Color PRIMARY = new Color(0x2563EB);
    public static final Color PRIMARY_DARK = new Color(0x1D4ED8);
    public static final Color PRIMARY_SOFT = new Color(0xDBEAFE);
    public static final Color BG = new Color(0xF1F5F9);
    public static final Color SURFACE = Color.WHITE;
    public static final Color BORDER = new Color(0xE2E8F0);
    public static final Color TEXT = new Color(0x0F172A);
    public static final Color MUTED = new Color(0x64748B);
    public static final Color DANGER = new Color(0xDC2626);
    public static final Color OK = new Color(0x16A34A);
    public static final Color WARN = new Color(0xD97706);
    public static final Font FONT = new Font("SansSerif", Font.PLAIN, 14);
    public static final Font FONT_SMALL = new Font("SansSerif", Font.PLAIN, 12);
    public static final Font FONT_BOLD = new Font("SansSerif", Font.BOLD, 14);

    public static void setupLookAndFeel() {
        try {
            Class<?> c = Class.forName("com.formdev.flatlaf.FlatLightLaf"); // dung FlatLaf neu co trong lib/
            c.getMethod("setup").invoke(null);
            return;
        } catch (Throwable ignored) { }
        try {
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(i.getName())) { UIManager.setLookAndFeel(i.getClassName()); break; }
            }
        } catch (Exception ignored) { }
        UIManager.put("control", BG);
        UIManager.put("nimbusBase", new Color(0x1E40AF));
        UIManager.put("nimbusFocus", PRIMARY);
        UIManager.put("nimbusSelectionBackground", PRIMARY);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List[Selected].textForeground", Color.WHITE);
        UIManager.put("defaultFont", FONT);
    }

    // ------------------------------------------------------------------ nut phang bo goc
    public static class FlatButton extends JButton {
        private Color bg, fg;
        private boolean outline;
        public FlatButton(String text, Color bg, Color fg, boolean outline) {
            super(text);
            this.bg = bg; this.fg = fg; this.outline = outline;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(fg);
            setFont(FONT_BOLD.deriveFont(13f));
            setBorder(new EmptyBorder(7, 14, 7, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? new Color(0xCBD5E1) : getModel().isPressed() ? bg.darker() : getModel().isRollover() ? bg.brighter() : bg;
            if (outline) {
                g2.setColor(SURFACE);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                g2.setColor(isEnabled() ? c : new Color(0xCBD5E1));
                g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                setForeground(isEnabled() ? c : new Color(0x94A3B8));
            } else {
                g2.setColor(c);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                setForeground(isEnabled() ? fg : Color.WHITE);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static JButton primary(String t) { return new FlatButton(t, PRIMARY, Color.WHITE, false); }
    public static JButton secondary(String t) { return new FlatButton(t, PRIMARY, PRIMARY, true); }
    public static JButton danger(String t) { return new FlatButton(t, DANGER, Color.WHITE, false); }
    public static JButton ghost(String t) {
        FlatButton b = new FlatButton(t, new Color(0xE2E8F0), TEXT, false);
        b.setBorder(new EmptyBorder(6, 10, 6, 10));
        return b;
    }

    // ------------------------------------------------------------------ o nhap bo goc
    public static class RoundBorder extends AbstractBorder {
        private final int r; private final Color color;
        public RoundBorder(int r, Color c) { this.r = r; this.color = c; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.hasFocus() ? PRIMARY : color);
            g2.draw(new RoundRectangle2D.Float(x, y, w - 1, h - 1, r, r));
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(7, 12, 7, 12); }
    }

    public static <T extends JTextField> T styleField(T f) {
        f.setBorder(new RoundBorder(12, BORDER));
        f.setBackground(SURFACE);
        f.setFont(FONT);
        f.setMargin(new Insets(2, 4, 2, 4));
        return f;
    }

    // ------------------------------------------------------------------ anh dai dien
    private static final Color[] PALETTE = {
        new Color(0x2563EB), new Color(0x7C3AED), new Color(0xDB2777), new Color(0xEA580C),
        new Color(0x059669), new Color(0x0891B2), new Color(0xCA8A04), new Color(0x4F46E5)};

    public static Color colorFor(String name) {
        return PALETTE[Math.abs(name.toLowerCase().hashCode()) % PALETTE.length];
    }

    /** Anh dai dien tron: dung anh that neu co, khong thi chu cai dau tren nen mau theo ten. */
    public static ImageIcon avatar(String name, BufferedImage img, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setClip(new Ellipse2D.Float(0, 0, size, size));
        if (img != null) {
            g.drawImage(img, 0, 0, size, size, null);
        } else {
            g.setColor(colorFor(name));
            g.fillRect(0, 0, size, size);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, size * 5 / 11)));
            String s = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, (size - fm.stringWidth(s)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        }
        g.dispose();
        return new ImageIcon(out);
    }

    /** Thu nho + cat vuong anh nguoi dung chon thanh PNG/JPEG nho, tra ve base64 vua gioi han giao thuc (hoac null). */
    public static String avatarToBase64(File f, int maxB64) throws IOException {
        BufferedImage src = ImageIO.read(f);
        if (src == null) throw new IOException("Không đọc được ảnh");
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage sq = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        for (int size : new int[]{96, 80, 64, 48}) {
            BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(sq, 0, 0, size, size, Color.WHITE, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            String b64 = Base64.getEncoder().encodeToString(bos.toByteArray());
            if (b64.length() <= maxB64) return b64;
        }
        return null;
    }

    public static BufferedImage decodeImage(String b64) {
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ bieu tuong cam xuc
    private static final boolean EMOJI_OK = new Font("Dialog", Font.PLAIN, 14).canDisplay(0x1F44D);

    public static String reactionGlyph(String code) {
        switch (code) {
            case "like": return EMOJI_OK ? "\uD83D\uDC4D" : "+1";
            case "love": return EMOJI_OK ? "\u2764\uFE0F" : "\u2665";
            case "haha": return EMOJI_OK ? "\uD83D\uDE02" : "\u263A";
            case "wow": return EMOJI_OK ? "\uD83D\uDE2E" : "!?";
            case "sad": return EMOJI_OK ? "\uD83D\uDE22" : "\u2639";
            case "angry": return EMOJI_OK ? "\uD83D\uDE21" : "\u2716";
            default: return code;
        }
    }

    public static String pinGlyph() { return EMOJI_OK ? "\uD83D\uDCCC" : "[ghim]"; }

    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static JPanel card(LayoutManager lm) {
        JPanel p = new JPanel(lm) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 18, 18));
                g2.setColor(BORDER);
                g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 18, 18));
                g2.dispose();
            }
        };
        p.setOpaque(false);
        return p;
    }
}
