package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Map;
import java.util.Set;

/**
 * Thành phần giao diện chuyên hiển thị lịch sử chat: notice, bubble, file,
 * trạng thái sửa/ghim, reactions và trạng thái đã xem.
 * Các hành động nghiệp vụ được gọi ngược về MainFrame qua callback.
 */
public final class ChatTranscriptPanel extends JPanel {

    private final String username;
    private final Function<DisplayItem, String> readReceiptProvider;
    private final BiConsumer<Component, DisplayItem> popupInstaller;
    private final BiConsumer<Long, String> downloadAction;
    private final BiConsumer<Long, String> previewAction;
    private final Function<Long, Path> previewPathProvider;
    private final Consumer<JButton> secondaryButtonStyler;
    private final Function<String, byte[]> avatarBytesProvider;
    private final Function<String, String> displayNameProvider;
    private final JPanel messagePanel;
    private final JScrollPane scrollPane;
    private final java.util.Map<Long, Component> renderedMessageComponents = new java.util.HashMap<>();

    public ChatTranscriptPanel(
            String username,
            Function<DisplayItem, String> readReceiptProvider,
            BiConsumer<Component, DisplayItem> popupInstaller,
            BiConsumer<Long, String> downloadAction,
            Consumer<JButton> secondaryButtonStyler) {
        this(username, readReceiptProvider, popupInstaller, downloadAction,
                (fileId, fileName) -> { }, fileId -> null, secondaryButtonStyler, name -> null);
    }

    public ChatTranscriptPanel(
            String username,
            Function<DisplayItem, String> readReceiptProvider,
            BiConsumer<Component, DisplayItem> popupInstaller,
            BiConsumer<Long, String> downloadAction,
            BiConsumer<Long, String> previewAction,
            Function<Long, Path> previewPathProvider,
            Consumer<JButton> secondaryButtonStyler) {
        this(username, readReceiptProvider, popupInstaller, downloadAction,
                previewAction, previewPathProvider, secondaryButtonStyler, name -> null);
    }

    /** Constructor có provider ảnh đại diện người gửi từ MainFrame. */
    public ChatTranscriptPanel(
            String username,
            Function<DisplayItem, String> readReceiptProvider,
            BiConsumer<Component, DisplayItem> popupInstaller,
            BiConsumer<Long, String> downloadAction,
            BiConsumer<Long, String> previewAction,
            Function<Long, Path> previewPathProvider,
            Consumer<JButton> secondaryButtonStyler,
            Function<String, byte[]> avatarBytesProvider) {
        this(username, readReceiptProvider, popupInstaller, downloadAction,
                previewAction, previewPathProvider, secondaryButtonStyler,
                avatarBytesProvider, Function.identity());
    }

    /** Constructor có provider ảnh và tên hiển thị theo username. */
    public ChatTranscriptPanel(
            String username,
            Function<DisplayItem, String> readReceiptProvider,
            BiConsumer<Component, DisplayItem> popupInstaller,
            BiConsumer<Long, String> downloadAction,
            BiConsumer<Long, String> previewAction,
            Function<Long, Path> previewPathProvider,
            Consumer<JButton> secondaryButtonStyler,
            Function<String, byte[]> avatarBytesProvider,
            Function<String, String> displayNameProvider) {
        super(new BorderLayout());
        this.username = username == null ? "" : username;
        this.readReceiptProvider = readReceiptProvider;
        this.popupInstaller = popupInstaller;
        this.downloadAction = downloadAction;
        this.previewAction = previewAction;
        this.previewPathProvider = previewPathProvider;
        this.secondaryButtonStyler = secondaryButtonStyler;
        this.avatarBytesProvider = avatarBytesProvider;
        this.displayNameProvider = displayNameProvider;

        messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(new Color(242, 245, 249));
        messagePanel.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));

        scrollPane = new JScrollPane(messagePanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(new Color(242, 245, 249));
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    /** Vẽ lại lịch sử và cuộn xuống tin mới nhất (dùng khi có tin mới). */
    public void render(List<DisplayItem> items) {
        renderInternal(items, false);
    }

    /** Giữ nguyên vị trí cuộn khi thêm các tin nhắn cũ hơn vào đầu danh sách. */
    public void renderPreservingScroll(List<DisplayItem> items) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> renderPreservingScroll(items));
            return;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        final int oldValue = bar.getValue();
        final int oldMaximum = bar.getMaximum();
        rebuildMessages(items);
        SwingUtilities.invokeLater(() -> {
            int delta = Math.max(0, bar.getMaximum() - oldMaximum);
            int maxValue = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
            bar.setValue(Math.min(maxValue, oldValue + delta));
        });
    }

    private void renderInternal(List<DisplayItem> items, boolean preserveScroll) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> renderInternal(items, preserveScroll));
            return;
        }
        rebuildMessages(items);
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void rebuildMessages(List<DisplayItem> items) {
        messagePanel.removeAll();
        renderedMessageComponents.clear();
        if (items != null) {
            for (DisplayItem item : items) {
                if (item == null) continue;
                Component row = createMessageComponent(item);
                messagePanel.add(row);
                if (item.id > 0) renderedMessageComponents.put(item.id, row);
                messagePanel.add(Box.createVerticalStrut(5));
            }
        }
        messagePanel.revalidate();
        messagePanel.repaint();
    }

    /** Cuộn tới một tin nhắn, ví dụ khi người dùng chọn tin trong danh sách ghim. */
    public void scrollToMessage(long messageId) {
        Component component = renderedMessageComponents.get(messageId);
        if (component == null) return;
        SwingUtilities.invokeLater(() -> {
            Rectangle bounds = SwingUtilities.convertRectangle(
                    component.getParent(), component.getBounds(), messagePanel);
            messagePanel.scrollRectToVisible(bounds);
        });
    }

    private Component createInlinePreview(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) return null;
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) return null;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) { image.flush(); return null; }
            double scale = Math.min(1.0, Math.min(340.0 / width, 195.0 / height));
            int viewW = Math.max(1, (int) Math.round(width * scale));
            int viewH = Math.max(1, (int) Math.round(height * scale));
            BufferedImage scaled = new BufferedImage(viewW, viewH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, viewW, viewH, null);
            g.dispose();
            JLabel imageLabel = new JLabel(new ImageIcon(scaled));
            imageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            imageLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            imageLabel.setToolTipText("Nhấn để xem ảnh lớn");
            imageLabel.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { showImagePreview(path); }
            });
            image.flush();
            return imageLabel;
        } catch (IOException | RuntimeException ex) { return null; }
    }

    private void showImagePreview(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) return;
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) return;
            JLabel imageLabel = new JLabel(new ImageIcon(image));
            JScrollPane scroll = new JScrollPane(imageLabel);
            scroll.setPreferredSize(new Dimension(
                    Math.min(900, Math.max(320, image.getWidth() + 24)),
                    Math.min(700, Math.max(240, image.getHeight() + 24))));
            JOptionPane.showMessageDialog(this, scroll, "Xem trước ảnh", JOptionPane.PLAIN_MESSAGE);
            image.flush();
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Không thể mở ảnh xem trước: " + ex.getMessage(),
                    "Xem trước ảnh", JOptionPane.WARNING_MESSAGE);
        }
    }

    private Component createMessageComponent(DisplayItem item) {
        if (item.id <= 0) {
            JPanel noticeRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
            noticeRow.setOpaque(false);
            JLabel notice = new JLabel(item.notice == null ? "" : item.notice);
            notice.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            notice.setForeground(new Color(100, 116, 139));
            notice.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(218, 226, 236)),
                    BorderFactory.createEmptyBorder(5, 12, 5, 12)));
            notice.setOpaque(true);
            notice.setBackground(new Color(255, 255, 255, 225));
            noticeRow.add(notice);
            noticeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, noticeRow.getPreferredSize().height + 6));
            return noticeRow;
        }

        boolean mine = item.sender != null && item.sender.equals(username);
        Color normal = mine ? new Color(226, 240, 255) : Color.WHITE;
        Color hover = mine ? new Color(215, 233, 255) : new Color(248, 250, 253);
        Color outline = mine ? new Color(174, 207, 250) : new Color(220, 226, 234);

        boolean voiceMessage = !item.deleted && Protocol.TYPE_FILE.equals(item.type)
                && item.fileId > 0 && VoiceMessageBubble.isVoiceMessageName(item.fileName);

        BubblePanel bubble = new BubblePanel(normal, hover);
        if (voiceMessage) {
            // The audio component is the message bubble itself, without an extra nested card or sender header.
            bubble.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            bubble.setPaintSurface(false);
        } else {
            bubble.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(outline),
                    BorderFactory.createEmptyBorder(7, 10, 6, 10)));
        }
        bubble.setMaximumSize(new Dimension(500, Integer.MAX_VALUE));
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (!voiceMessage) {
            JPanel messageHeader = new JPanel(new BorderLayout(12, 0));
            messageHeader.setOpaque(false);
            messageHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
            String senderDisplayName = safeText(item.sender);
            if (!mine && displayNameProvider != null) {
                try {
                    String provided = displayNameProvider.apply(item.sender);
                    if (provided != null && !provided.trim().isEmpty()) {
                        senderDisplayName = provided.trim();
                    }
                } catch (RuntimeException ignored) { }
            }
            JLabel senderLabel = new JLabel(mine ? "Bạn" : senderDisplayName);
            senderLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            senderLabel.setForeground(mine ? new Color(27, 88, 167) : senderColor(item.sender));
            messageHeader.add(senderLabel, BorderLayout.WEST);
            bubble.add(messageHeader);
            bubble.add(Box.createVerticalStrut(4));
        }

        if (item.deleted) {
            JLabel recalled = new JLabel("Tin nhắn đã bị thu hồi");
            recalled.setForeground(new Color(100, 116, 139));
            recalled.setFont(new Font("Segoe UI", Font.ITALIC, 13));
            recalled.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(recalled);
        } else if (Protocol.TYPE_FILE.equals(item.type) && item.fileId > 0
                && VoiceMessageBubble.isVoiceMessageName(item.fileName)) {
            VoiceMessageBubble voice = new VoiceMessageBubble(
                    item.fileId, item.fileName,
                    previewPathProvider,
                    previewAction,
                    downloadAction,
                    secondaryButtonStyler);
            voice.setAlignmentX(Component.LEFT_ALIGNMENT);
            voice.setMaximumSize(new Dimension(470, 106));
            voice.setPreferredSize(new Dimension(365, 106));
            bubble.add(voice);
        } else if (Protocol.TYPE_FILE.equals(item.type) && item.fileId > 0) {
            JPanel fileCard = new JPanel(new BorderLayout(9, 0));
            fileCard.setOpaque(false);
            JComponent icon = new FileTypeIcon(item.fileName);
            icon.setPreferredSize(new Dimension(42, 42));
            icon.setMinimumSize(new Dimension(42, 42));
            JPanel fileText = new JPanel(new GridLayout(2, 1, 0, 3));
            fileText.setOpaque(false);
            JLabel fileNameLabel = new JLabel(escapeHtml(item.fileName));
            fileNameLabel.setToolTipText(item.fileName);
            fileNameLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            fileNameLabel.setForeground(new Color(30, 41, 59));
            JLabel fileSizeLabel = new JLabel(formatFileSize(item.fileSize));
            fileSizeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            fileSizeLabel.setForeground(new Color(100, 116, 139));
            fileText.add(fileNameLabel); fileText.add(fileSizeLabel);
            JButton download = new JButton("Tải xuống");
            download.setFont(new Font("Segoe UI", Font.BOLD, 11));
            if (secondaryButtonStyler != null) secondaryButtonStyler.accept(download);
            download.addActionListener(e -> { if (downloadAction != null) downloadAction.accept(item.fileId, item.fileName); });
            fileCard.add(icon, BorderLayout.WEST); fileCard.add(fileText, BorderLayout.CENTER); fileCard.add(download, BorderLayout.EAST);
            fileCard.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(218, 226, 236)),
                    BorderFactory.createEmptyBorder(5, 6, 5, 6)));
            fileCard.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(fileCard);
            if (FileTransferController.isPreviewableImage(item.fileName)) {
                Path previewPath = previewPathProvider == null ? null : previewPathProvider.apply(item.fileId);
                Component preview = previewPath == null ? null : createInlinePreview(previewPath);
                if (preview != null) {
                    bubble.add(Box.createVerticalStrut(5));
                    bubble.add(preview);
                } else if (previewAction != null) {
                    JButton previewButton = new JButton("▣  Xem ảnh trong chat");
                    if (secondaryButtonStyler != null) secondaryButtonStyler.accept(previewButton);
                    previewButton.setFont(new Font("Segoe UI", Font.BOLD, 11));
                    previewButton.setAlignmentX(Component.LEFT_ALIGNMENT);
                    previewButton.addActionListener(e -> previewAction.accept(item.fileId, item.fileName));
                    bubble.add(Box.createVerticalStrut(4));
                    bubble.add(previewButton);
                }
            } else if (FileTransferController.isPreviewableVideo(item.fileName)) {
                Path videoPath = previewPathProvider == null ? null : previewPathProvider.apply(item.fileId);
                if (videoPath != null && Files.isRegularFile(videoPath)) {
                    bubble.add(Box.createVerticalStrut(5));
                    JComponent player = new InlineVideoPlayer(videoPath);
                    player.setAlignmentX(Component.LEFT_ALIGNMENT);
                    player.setMaximumSize(new Dimension(430, 285));
                    bubble.add(player);
                } else if (previewAction != null) {
                    JButton playButton = new JButton("▶  Tải và phát video trong chat");
                    playButton.setFont(new Font("Segoe UI", Font.BOLD, 11));
                    playButton.setForeground(Color.WHITE);
                    playButton.setBackground(new Color(37, 99, 235));
                    playButton.setOpaque(true);
                    playButton.setFocusPainted(false);
                    playButton.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
                    playButton.setAlignmentX(Component.LEFT_ALIGNMENT);
                    playButton.addActionListener(e -> {
                        playButton.setEnabled(false);
                        playButton.setText("Đang chuẩn bị video...");
                        previewAction.accept(item.fileId, item.fileName);
                    });
                    bubble.add(Box.createVerticalStrut(4));
                    bubble.add(playButton);
                }
            }
        } else {
            JTextArea body = new EmojiAwareTextArea(item.text == null ? "" : item.text, bubble);
            body.setEditable(false); body.setLineWrap(true); body.setWrapStyleWord(true);
            String bodyText = item.text == null ? "" : item.text;
            int longestLine = 1;
            for (String line : bodyText.split("\\n", -1)) longestLine = Math.max(longestLine, line.length());
            body.setColumns(Math.min(32, Math.max(5, longestLine + 1)));
            body.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            body.setForeground(new Color(28, 38, 54));
            body.setOpaque(false); body.setBorder(BorderFactory.createEmptyBorder());
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(body);
        }

        if (item.edited || item.pinned) {
            String meta = (item.edited ? "Đã sửa" : "")
                    + (item.edited && item.pinned ? "  •  " : "")
                    + (item.pinned ? "Đã ghim" : "");
            JLabel metadata = new JLabel(meta);
            metadata.setForeground(item.pinned ? new Color(124, 58, 237) : new Color(100, 116, 139));
            metadata.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(Box.createVerticalStrut(4)); bubble.add(metadata);
        }

        JPanel reactions = createReactionChips(item);
        if (reactions != null) {
            bubble.add(Box.createVerticalStrut(5));
            reactions.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(reactions);
        }

        JPanel footer = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 7, 0));
        footer.setOpaque(false);
        String formattedTimestamp = formatTimestamp(item.timestamp);
        if (!formattedTimestamp.isEmpty()) {
            JLabel timestampLabel = new JLabel(formattedTimestamp);
            timestampLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            timestampLabel.setForeground(new Color(112, 126, 145));
            timestampLabel.setToolTipText(safeText(item.timestamp)); footer.add(timestampLabel);
        }
        String receipt = readReceiptProvider == null ? null : readReceiptProvider.apply(item);
        if (receipt != null && !receipt.isBlank()) {
            JLabel receiptLabel = new JLabel(receipt);
            receiptLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            receiptLabel.setForeground(new Color(64, 105, 161)); footer.add(receiptLabel);
        }
        if (footer.getComponentCount() > 0) {
            footer.setAlignmentX(Component.LEFT_ALIGNMENT); bubble.add(Box.createVerticalStrut(3)); bubble.add(footer);
        }

        if (popupInstaller != null) popupInstaller.accept(bubble, item);
        installBubbleHover(bubble, bubble);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JPanel aligned = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 3));
        aligned.setOpaque(false);
        if (!mine) {
            byte[] avatarBytes = null;
            try { if (avatarBytesProvider != null) avatarBytes = avatarBytesProvider.apply(item.sender); }
            catch (RuntimeException ignored) { }
            aligned.add(new SenderAvatar(item.sender, avatarBytes));
        }
        aligned.add(bubble);
        row.add(aligned, mine ? BorderLayout.EAST : BorderLayout.WEST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(60, row.getPreferredSize().height + 8)));
        return row;
    }

    private JPanel createReactionChips(DisplayItem item) {
        if (item.reactions == null || item.reactions.isEmpty()) return null;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 1));
        row.setOpaque(false);
        int added = 0;
        for (String code : Protocol.REACTIONS) {
            Set<String> users = item.reactions.get(code);
            if (users == null || users.isEmpty()) continue;
            ReactionPalette palette = paletteFor(code);
            boolean selected = false;
            for (String reactedUser : users) {
                if (reactedUser != null && reactedUser.equals(username)) { selected = true; break; }
            }
            JPanel chip = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 1));
            chip.setOpaque(true);
            chip.setBackground(selected ? palette.selected : palette.background);
            chip.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(selected ? palette.foreground : palette.border, selected ? 2 : 1),
                    BorderFactory.createEmptyBorder(1, 5, 1, 5)));
            JLabel emoji = new JLabel(EmojiSupport.iconFor(ReactionController.emoji(code), 19));
            emoji.setPreferredSize(new Dimension(20, 20));
            JLabel count = new JLabel(String.valueOf(users.size()));
            count.setFont(new Font("Segoe UI", Font.BOLD, 11));
            count.setForeground(palette.foreground);
            chip.add(emoji); chip.add(count);
            StringBuilder tooltip = new StringBuilder(ReactionController.label(code));
            tooltip.append(" — ").append(users.size()).append(" người");
            chip.setToolTipText(tooltip.toString());
            row.add(chip);
            added++;
        }
        return added == 0 ? null : row;
    }

    private static final class ReactionPalette {
        final Color background, selected, border, foreground;
        ReactionPalette(Color background, Color selected, Color border, Color foreground) {
            this.background = background; this.selected = selected; this.border = border; this.foreground = foreground;
        }
    }

    private ReactionPalette paletteFor(String code) {
        switch (code) {
            case "LIKE": return new ReactionPalette(new Color(239, 246, 255), new Color(191, 219, 254), new Color(147, 197, 253), new Color(29, 78, 216));
            case "LOVE": return new ReactionPalette(new Color(255, 241, 242), new Color(254, 205, 211), new Color(253, 164, 175), new Color(190, 24, 93));
            case "HAHA": return new ReactionPalette(new Color(255, 251, 235), new Color(253, 230, 138), new Color(252, 211, 77), new Color(180, 83, 9));
            case "WOW": return new ReactionPalette(new Color(240, 249, 255), new Color(186, 230, 253), new Color(125, 211, 252), new Color(3, 105, 161));
            case "SAD": return new ReactionPalette(new Color(239, 246, 255), new Color(191, 219, 254), new Color(147, 197, 253), new Color(30, 64, 175));
            case "ANGRY": return new ReactionPalette(new Color(254, 242, 242), new Color(254, 202, 202), new Color(252, 165, 165), new Color(185, 28, 28));
            default: return new ReactionPalette(new Color(248, 250, 252), new Color(226, 232, 240), new Color(203, 213, 225), new Color(51, 65, 85));
        }
    }

    private static Color senderColor(String sender) {
        int hash = Math.abs((sender == null ? "" : sender.toLowerCase(Locale.ROOT)).hashCode());
        Color[] colors = {new Color(37, 99, 235), new Color(124, 58, 237), new Color(5, 150, 105),
                new Color(194, 65, 12), new Color(190, 24, 93), new Color(3, 105, 161)};
        return colors[hash % colors.length];
    }

    /** Text area renders emoji as bundled color images, even when Swing font fallback shows tofu boxes. */
    private final class EmojiAwareTextArea extends JTextArea {
        private final BubblePanel ownerBubble;
        EmojiAwareTextArea(String text, BubblePanel ownerBubble) {
            super(text);
            this.ownerBubble = ownerBubble;
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            EmojiSupport.paintEmojiOverlays(this, g, ownerBubble.getFillColor());
        }
    }

    private static final class SenderAvatar extends JComponent {
        private final String initials;
        private final Color fill;
        private final BufferedImage image;

        SenderAvatar(String sender, byte[] bytes) {
            String value = sender == null ? "?" : sender.trim();
            initials = value.isEmpty() ? "?" : value.substring(0, 1).toUpperCase(Locale.ROOT);
            int hash = Math.abs(value.toLowerCase(Locale.ROOT).hashCode());
            Color[] colors = {new Color(218, 232, 246), new Color(234, 222, 246),
                    new Color(219, 238, 226), new Color(250, 230, 204), new Color(246, 219, 224)};
            fill = colors[hash % colors.length];
            BufferedImage decoded = null;
            if (bytes != null && bytes.length > 0) {
                try { decoded = ImageIO.read(new ByteArrayInputStream(bytes)); }
                catch (IOException ignored) { }
            }
            image = decoded;
            setPreferredSize(new Dimension(40, 40));
            setMinimumSize(new Dimension(40, 40));
            setMaximumSize(new Dimension(40, 40));
            setToolTipText(value);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int d = Math.min(getWidth(), getHeight()) - 4;
            int x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
            g.setColor(new Color(226, 232, 240)); g.fillOval(x - 1, y - 1, d + 2, d + 2);
            g.setColor(fill); g.fillOval(x, y, d, d);
            if (image != null) {
                java.awt.Shape oldClip = g.getClip();
                g.setClip(new java.awt.geom.Ellipse2D.Double(x, y, d, d));
                int side = Math.min(image.getWidth(), image.getHeight());
                int sx = (image.getWidth() - side) / 2, sy = (image.getHeight() - side) / 2;
                g.drawImage(image, x, y, x + d, y + d, sx, sy, sx + side, sy + side, null);
                g.setClip(oldClip);
                g.setColor(new Color(255, 255, 255, 210)); g.setStroke(new BasicStroke(1.1f));
                g.drawOval(x, y, d - 1, d - 1);
            } else {
                g.setColor(new Color(55, 65, 81));
                g.setFont(new Font("Segoe UI", Font.BOLD, 14));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(initials, (getWidth() - fm.stringWidth(initials)) / 2,
                        (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            }
            g.dispose();
        }
    }

    private void installBubbleHover(BubblePanel bubble, Component component) {
        if (component != bubble) {
            component.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { bubble.setHovered(true); }
                @Override public void mouseExited(MouseEvent e) { bubble.setHovered(false); }
            });
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) installBubbleHover(bubble, child);
        }
    }

    private String formatTimestamp(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String raw = value.trim();
        LocalDateTime parsed = null;
        if (raw.matches("\\d{10,13}")) {
            try {
                long numeric = Long.parseLong(raw);
                long millis = raw.length() == 10 ? numeric * 1000L : numeric;
                parsed = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
            } catch (RuntimeException ignored) { }
        }
        if (parsed == null) {
            DateTimeFormatter[] formats = new DateTimeFormatter[]{
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ISO_LOCAL_DATE_TIME};
            for (DateTimeFormatter format : formats) {
                try { parsed = LocalDateTime.parse(raw, format); break; }
                catch (DateTimeParseException ignored) { }
            }
        }
        if (parsed == null) {
            try { parsed = LocalDateTime.parse(raw.replace(' ', 'T')); }
            catch (DateTimeParseException ignored) { return raw; }
        }
        if (parsed.toLocalDate().equals(LocalDate.now())) return parsed.format(DateTimeFormatter.ofPattern("HH:mm"));
        return parsed.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    private String safeText(String value) { return value == null ? "" : value; }
    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
    /** Icon tệp vẽ bằng Java2D, tránh emoji bị hiển thị thành ô trống trên Windows. */
    private static final class FileTypeIcon extends JComponent {
        private final String extension;
        private final Color accent;
        private final Color pale;
        FileTypeIcon(String name) {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            String ext = dot >= 0 && dot < lower.length() - 1 ? lower.substring(dot + 1) : "FILE";
            extension = ext.length() > 4 ? ext.substring(0, 4).toUpperCase(Locale.ROOT) : ext.toUpperCase(Locale.ROOT);
            if (Set.of("mp4", "m4v", "mov", "mkv", "webm", "avi").contains(ext)) {
                accent = new Color(217, 119, 6); pale = new Color(254, 243, 199);
            } else if (Set.of("png", "jpg", "jpeg", "gif", "bmp").contains(ext)) {
                accent = new Color(22, 163, 74); pale = new Color(220, 252, 231);
            } else if ("pdf".equals(ext)) {
                accent = new Color(220, 38, 38); pale = new Color(254, 226, 226);
            } else if (Set.of("zip", "rar", "7z", "tar", "gz").contains(ext)) {
                accent = new Color(124, 58, 237); pale = new Color(237, 233, 254);
            } else if (Set.of("doc", "docx", "txt", "rtf").contains(ext)) {
                accent = new Color(37, 99, 235); pale = new Color(219, 234, 254);
            } else if (Set.of("xls", "xlsx", "csv").contains(ext)) {
                accent = new Color(5, 150, 105); pale = new Color(209, 250, 229);
            } else if (Set.of("ppt", "pptx").contains(ext)) {
                accent = new Color(234, 88, 12); pale = new Color(255, 237, 213);
            } else if (Set.of("mp3", "wav", "flac", "aac").contains(ext)) {
                accent = new Color(147, 51, 234); pale = new Color(243, 232, 255);
            } else {
                accent = new Color(71, 85, 105); pale = new Color(226, 232, 240);
            }
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(pale); g.fillRoundRect(2, 2, w - 4, h - 4, 12, 12);
            int left = 10, top = 6, dw = w - 20, dh = h - 12;
            g.setColor(Color.WHITE); g.fillRoundRect(left, top, dw, dh, 5, 5);
            g.setColor(accent); g.fillRoundRect(left, top + dh - 13, dw, 13, 4, 4);
            g.setColor(accent); g.setStroke(new BasicStroke(1.4f));
            g.drawRoundRect(left, top, dw, dh, 5, 5);
            if (Set.of("mp4", "m4v", "mov", "mkv", "webm", "avi").contains(extension.toLowerCase(Locale.ROOT))) {
                int cx = w / 2, cy = h / 2 - 2;
                g.setColor(new Color(253, 230, 138));
                g.fillOval(cx - 8, cy - 8, 16, 16);
                g.setColor(new Color(146, 64, 14));
                int[] xs = {cx - 2, cx - 2, cx + 4}; int[] ys = {cy - 4, cy + 4, cy};
                g.fillPolygon(xs, ys, 3);
            } else {
                g.setColor(accent); g.setFont(new Font("Segoe UI", Font.BOLD, 8));
                FontMetrics fm = g.getFontMetrics();
                String text = extension;
                g.drawString(text, Math.max(3, (w - fm.stringWidth(text)) / 2), h / 2 + 3);
            }
            g.dispose();
        }
    }

    private static final class BubblePanel extends JPanel {
        private Color fill;
        private final Color normalFill;
        private final Color hoverFill;
        private boolean paintSurface = true;
        BubblePanel(Color normalFill, Color hoverFill) {
            this.normalFill = normalFill; this.hoverFill = hoverFill; this.fill = normalFill;
            setOpaque(false); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(9, 11, 9, 11));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { setHovered(true); }
                @Override public void mouseExited(MouseEvent e) { setHovered(false); }
            });
        }
        void setHovered(boolean hovered) { fill = hovered ? hoverFill : normalFill; repaint(); }
        void setPaintSurface(boolean value) { paintSurface = value; repaint(); }

        Color getFillColor() { return fill; }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (paintSurface) {
                g2.setColor(fill); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            }
            g2.dispose(); super.paintComponent(g);
        }
    }
}
