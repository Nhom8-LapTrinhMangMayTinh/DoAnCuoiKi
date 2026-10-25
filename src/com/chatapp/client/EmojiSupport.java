package com.chatapp.client;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hiển thị emoji bằng PNG màu, không phụ thuộc khả năng render emoji của font Swing.
 * Các ký tự Unicode vẫn được lưu/gửi như văn bản bình thường.
 */
public final class EmojiSupport {
    private static final String RESOURCE_ROOT = "/com/chatapp/client/emoji/assets/";
    private static final Map<String, BufferedImage> IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISSING_IMAGES = ConcurrentHashMap.newKeySet();
    private static volatile List<String> EMOJI_MATCH_ORDER;

    private EmojiSupport() { }

    public static String normalizeEmoji(String emoji) {
        if (emoji == null) return "";
        StringBuilder out = new StringBuilder();
        emoji.codePoints().filter(cp -> cp != 0xFE0E && cp != 0xFE0F)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    public static String resourceName(String emoji) {
        String value = normalizeEmoji(emoji);
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (out.length() > 0) out.append('-');
            out.append(Integer.toHexString(cp));
        });
        return out + ".png";
    }

    /**
     * Load emoji art from the classpath first. Some NetBeans Ant projects do not
     * copy non-Java files from src into build/classes in every custom setup, so
     * also fall back to the project's source/resources directories.
     */
    private static volatile Path discoveredAssetsDirectory;
    private static volatile boolean searchedAssetsDirectory;

    private static BufferedImage loadSourceImage(String resource) {
        // Luôn ưu tiên atlas nhúng trong Java. Đây là đường dẫn chính, không phụ thuộc
        // thư mục hiện hành hoặc việc Ant có copy PNG ra build/classes hay không.
        BufferedImage embedded = EmbeddedEmojiAssets.getImage(resource);
        if (embedded != null) return embedded;
        // 1) Preferred: classpath resource. In a normal Ant build the PNGs are
        // copied from src/ into build/classes/ using the same package path.
        String classpathResource = RESOURCE_ROOT + resource;
        try {
            URL url = EmojiSupport.class.getResource(classpathResource);
            if (url != null) {
                BufferedImage image = ImageIO.read(url);
                if (image != null) return image;
            }
        } catch (Exception ignored) { }

        // 2) Find the assets directory once and reuse it. NetBeans can launch a
        // project with a user.dir different from the project root, so check the
        // current folder, its parents, and the compiled class location.
        Path assets = findAssetsDirectory();
        if (assets != null) {
            try {
                Path candidate = assets.resolve(resource);
                if (Files.isRegularFile(candidate)) {
                    BufferedImage image = ImageIO.read(candidate.toFile());
                    if (image != null) return image;
                }
            } catch (IOException | RuntimeException ignored) { }
        }

        // 3) Direct candidates for non-standard NetBeans/Ant build layouts.
        String userDir = System.getProperty("user.dir", ".");
        String relative = "com/chatapp/client/emoji/assets/" + resource;
        Path[] candidates = new Path[] {
            Path.of(userDir, "src", "com", "chatapp", "client", "emoji", "assets", resource),
            Path.of(userDir, "build", "classes", "com", "chatapp", "client", "emoji", "assets", resource),
            Path.of(userDir, "src", "resources", "com", "chatapp", "client", "emoji", "assets", resource),
            Path.of(userDir, "src\\com\\chatapp\\client\\emoji\\assets", resource)
        };
        for (Path candidate : candidates) {
            try {
                if (Files.isRegularFile(candidate)) {
                    BufferedImage image = ImageIO.read(candidate.toFile());
                    if (image != null) return image;
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        return null;
    }

    private static Path findAssetsDirectory() {
        if (searchedAssetsDirectory) return discoveredAssetsDirectory;
        synchronized (EmojiSupport.class) {
            if (searchedAssetsDirectory) return discoveredAssetsDirectory;
            LinkedHashSet<Path> roots = new LinkedHashSet<>();
            try { roots.add(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()); }
            catch (Exception ignored) { }
            try {
                URL location = EmojiSupport.class.getProtectionDomain().getCodeSource().getLocation();
                if (location != null && "file".equalsIgnoreCase(location.getProtocol())) {
                    Path codeRoot = Path.of(location.toURI()).toAbsolutePath().normalize();
                    roots.add(codeRoot);
                    if (codeRoot.getParent() != null) roots.add(codeRoot.getParent());
                }
            } catch (Exception ignored) { }

            // Check each root and up to 5 parents. This covers the usual
            // D:\\...\\ChatApp_Core, build/classes, and NetBeans run layouts.
            List<Path> initial = new ArrayList<>(roots);
            for (Path root : initial) {
                Path cursor = root;
                for (int i = 0; cursor != null && i < 6; i++, cursor = cursor.getParent()) {
                    roots.add(cursor);
                }
            }

            for (Path root : roots) {
                Path[] possible = new Path[] {
                    root.resolve("src/com/chatapp/client/emoji/assets"),
                    root.resolve("com/chatapp/client/emoji/assets"),
                    root.resolve("build/classes/com/chatapp/client/emoji/assets"),
                    root.resolve("src/resources/com/chatapp/client/emoji/assets"),
                    root.resolve("ChatApp_Core/src/com/chatapp/client/emoji/assets")
                };
                for (Path possibleDir : possible) {
                    try {
                        if (Files.isDirectory(possibleDir)
                                && Files.isRegularFile(possibleDir.resolve("1f600.png"))
                                && Files.isRegularFile(possibleDir.resolve("2764.png"))) {
                            discoveredAssetsDirectory = possibleDir;
                            searchedAssetsDirectory = true;
                            return discoveredAssetsDirectory;
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
            searchedAssetsDirectory = true;
            return null;
        }
    }

    public static ImageIcon iconFor(String emoji, int size) {
        if (emoji == null || emoji.isEmpty()) return null;
        String resource = resourceName(emoji);
        BufferedImage source = IMAGE_CACHE.get(resource);
        if (source == null && !MISSING_IMAGES.contains(resource)) {
            source = loadSourceImage(resource);
            if (source != null) IMAGE_CACHE.put(resource, source);
            else {
                MISSING_IMAGES.add(resource);
                // Warn once so a mis-copied assets folder is diagnosable instead of silently blank.
                if (MISSING_IMAGES.size() == 1) {
                    System.err.println("[EmojiSupport] Không tìm thấy PNG emoji: " + resource
                            + ". Hãy chép src/com/chatapp/client/emoji/assets vào đúng project ChatApp.");
                }
            }
        }
        if (source == null) return null;
        java.awt.Image scaled = source.getScaledInstance(
                Math.max(8, size), Math.max(8, size), java.awt.Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    /** Kiểm tra nhanh để bảng chọn không mở ra một vùng trống nếu thiếu ảnh. */
    public static boolean assetsAvailable() {
        return EmbeddedEmojiAssets.isAvailable()
                && iconFor("😀", 24) != null
                && iconFor("❤️", 24) != null;
    }

    public static List<String> allEmojisLongestFirst() {
        List<String> cached = EMOJI_MATCH_ORDER;
        if (cached != null) return cached;
        synchronized (EmojiSupport.class) {
            if (EMOJI_MATCH_ORDER == null) {
                Set<String> unique = new LinkedHashSet<>();
                for (String[] group : EmojiPicker.getCategories().values()) {
                    for (String emoji : group) unique.add(emoji);
                }
                List<String> values = new ArrayList<>(unique);
                values.sort(Comparator.comparingInt(String::length).reversed());
                EMOJI_MATCH_ORDER = List.copyOf(values);
            }
            return EMOJI_MATCH_ORDER;
        }
    }

    public static String emojiAt(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) return null;
        for (String emoji : allEmojisLongestFirst()) {
            if (text.startsWith(emoji, index)) return emoji;
            // Make matching tolerant of U+FE0F variation selectors.
            String normalized = normalizeEmoji(emoji);
            if (normalized.length() != emoji.length() && text.startsWith(normalized, index)) return normalized;
        }
        return null;
    }

    /** Paints bundled color PNG emojis at text-line height over unsupported Unicode glyphs in Swing text. */
    public static void paintEmojiOverlays(JTextComponent component, Graphics graphics, Color coverColor) {
        if (component == null || graphics == null) return;
        String text = component.getText();
        if (text == null || text.isEmpty()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        try {
            for (int i = 0; i < text.length();) {
                String emoji = emojiAt(text, i);
                if (emoji == null) {
                    i += Character.charCount(text.codePointAt(i));
                    continue;
                }
                // For a normalized variation-selector-free match, advance over the matching normalized range.
                int endIndex = i + emoji.length();
                if (endIndex > text.length()) break;
                Rectangle2D start = component.modelToView2D(i);
                Rectangle2D end = component.modelToView2D(endIndex);
                if (start != null && end != null) {
                    int x = (int) Math.round(start.getX());
                    int y = (int) Math.round(start.getY());
                    int lineHeight = Math.max(14, (int) Math.round(start.getHeight()));
                    java.awt.FontMetrics metrics = component.getFontMetrics(component.getFont());
                    int spanWidth;
                    if (Math.abs(end.getY() - start.getY()) < 1.0) {
                        spanWidth = (int) Math.round(end.getX() - start.getX());
                    } else {
                        spanWidth = metrics.stringWidth(text.substring(i, endIndex));
                    }
                    if (spanWidth <= 0) spanWidth = metrics.stringWidth(emoji);
                    spanWidth = Math.max(12, Math.min(120, spanWidth));

                    // Emoji PNGs contain a few transparent pixels around the artwork.
                    // Use the font's complete line height (plus a small optical correction)
                    // rather than capping the icon to the Unicode glyph's advance width.
                    // The old spanWidth cap made emoji visibly smaller than adjacent text.
                    int textLineHeight = Math.max(lineHeight, metrics.getHeight());
                    int iconSize = Math.max(16, Math.min(30, textLineHeight + 2));
                    ImageIcon icon = iconFor(emoji, iconSize);
                    if (icon != null) {
                        g.setColor(coverColor == null ? Color.WHITE : coverColor);
                        g.fillRect(x - 1, y, spanWidth + 2, lineHeight + 1);

                        // Keep the Unicode advance area intact, but center a full line-height
                        // icon over it. Its transparent PNG margins let adjacent letters remain
                        // readable while the emoji itself becomes comparable in size to the text.
                        int drawX = x + (spanWidth - iconSize) / 2;
                        int drawY = y + (lineHeight - iconSize) / 2;
                        g.drawImage(icon.getImage(), drawX, drawY, iconSize, iconSize, component);
                    }
                }
                i = endIndex;
            }
        } catch (Exception ignored) {
            // Không làm hỏng việc vẽ ô nhập/chat nếu gặp offset Unicode không hợp lệ.
        } finally {
            g.dispose();
        }
    }

    /** Thay thế JTextField thường bằng lớp này để emoji có màu và kích thước ngang chiều cao dòng chữ trong lúc soạn. */
    public static final class EmojiTextField extends JTextField {
        public EmojiTextField() {
            setBackground(Color.WHITE);
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            paintEmojiOverlays(this, g, Color.WHITE);
        }
    }
}
