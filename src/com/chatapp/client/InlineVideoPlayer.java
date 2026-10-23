package com.chatapp.client;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bộ phát video nhúng vào bong bóng chat.
 * JavaFX được nạp bằng reflection để project Swing vẫn biên dịch khi JavaFX chưa được thêm.
 * Để phát ngay trong cửa sổ cần thêm các JAR OpenJFX (javafx-media, javafx-graphics,
 * javafx-base, javafx-swing) cùng các thư viện đi kèm của đúng hệ điều hành.
 */
public final class InlineVideoPlayer extends JPanel {
    private final Path videoPath;
    private final JLabel status = new JLabel("Đang khởi tạo trình phát video...", SwingConstants.CENTER);
    private final JButton playPause = new JButton("Tạm dừng");
    private final AtomicReference<Object> mediaPlayer = new AtomicReference<>();
    private volatile boolean paused;

    public InlineVideoPlayer(Path videoPath) {
        this.videoPath = videoPath;
        setLayout(new BorderLayout(0, 5));
        setOpaque(true);
        setBackground(new Color(15, 23, 42));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        status.setForeground(Color.WHITE);
        status.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        status.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));

        if (videoPath == null || !Files.isRegularFile(videoPath)) {
            showFallback("Không tìm thấy tệp video trong bộ nhớ đệm.");
            return;
        }

        try {
            Class<?> jfxPanelType = Class.forName("javafx.embed.swing.JFXPanel");
            Component fxPanel = (Component) jfxPanelType.getConstructor().newInstance();
            fxPanel.setPreferredSize(new Dimension(400, 225));
            fxPanel.setMinimumSize(new Dimension(300, 170));
            add(fxPanel, BorderLayout.CENTER);

            JPanel controls = new JPanel(new BorderLayout(8, 0));
            controls.setBackground(new Color(15, 23, 42));
            playPause.setFocusPainted(false);
            playPause.setForeground(Color.WHITE);
            playPause.setBackground(new Color(37, 99, 235));
            playPause.setOpaque(true);
            playPause.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            playPause.addActionListener(e -> togglePlayback());
            controls.add(playPause, BorderLayout.WEST);
            status.setHorizontalAlignment(SwingConstants.LEFT);
            controls.add(status, BorderLayout.CENTER);
            add(controls, BorderLayout.SOUTH);

            status.setText("Đang tải video...");
            runOnFxThread(() -> initializePlayer(jfxPanelType, fxPanel));
        } catch (Throwable unavailable) {
            showFallback("Chưa có JavaFX Media trong project. Có thể mở bằng trình phát ngoài.");
        }
    }

    private void initializePlayer(Class<?> jfxPanelType, Component fxPanel) {
        try {
            String uri = videoPath.toUri().toURL().toExternalForm();
            Class<?> mediaType = Class.forName("javafx.scene.media.Media");
            Class<?> playerType = Class.forName("javafx.scene.media.MediaPlayer");
            Class<?> viewType = Class.forName("javafx.scene.media.MediaView");
            Class<?> parentType = Class.forName("javafx.scene.Parent");
            Class<?> sceneType = Class.forName("javafx.scene.Scene");
            Class<?> stackPaneType = Class.forName("javafx.scene.layout.StackPane");

            Object media = mediaType.getConstructor(String.class).newInstance(uri);
            Object player = playerType.getConstructor(mediaType).newInstance(media);
            mediaPlayer.set(player);
            Object view = viewType.getConstructor(playerType).newInstance(player);
            viewType.getMethod("setPreserveRatio", boolean.class).invoke(view, true);
            viewType.getMethod("setFitWidth", double.class).invoke(view, 400.0);
            viewType.getMethod("setFitHeight", double.class).invoke(view, 225.0);

            Object stack = stackPaneType.getConstructor().newInstance();
            Object children = stackPaneType.getMethod("getChildren").invoke(stack);
            children.getClass().getMethod("add", Object.class).invoke(children, view);
            Object scene = sceneType.getConstructor(parentType).newInstance(stack);
            jfxPanelType.getMethod("setScene", sceneType).invoke(fxPanel, scene);
            playerType.getMethod("play").invoke(player);
            SwingUtilities.invokeLater(() -> status.setText(videoPath.getFileName().toString()));
        } catch (Throwable ex) {
            mediaPlayer.set(null);
            SwingUtilities.invokeLater(() -> {
                status.setText("Không phát được định dạng video này.");
                playPause.setText("Mở ngoài");
            });
        }
    }

    private void togglePlayback() {
        Object player = mediaPlayer.get();
        if (player == null) {
            openWithSystemPlayer();
            return;
        }
        paused = !paused;
        playPause.setText(paused ? "▶ Tiếp tục" : "⏸ Tạm dừng");
        runOnFxThread(() -> {
            try {
                player.getClass().getMethod(paused ? "pause" : "play").invoke(player);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> status.setText("Không điều khiển được video."));
            }
        });
    }

    private void showFallback(String message) {
        removeAll();
        setBackground(new Color(248, 250, 252));
        JLabel notice = new JLabel("▶  " + message, SwingConstants.CENTER);
        notice.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        notice.setForeground(new Color(71, 85, 105));
        notice.setBorder(BorderFactory.createEmptyBorder(12, 8, 8, 8));
        JButton open = new JButton("Mở bằng trình phát ngoài");
        open.setFocusPainted(false);
        open.setForeground(Color.WHITE);
        open.setBackground(new Color(37, 99, 235));
        open.setOpaque(true);
        open.addActionListener(e -> openWithSystemPlayer());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bottom.setOpaque(false);
        bottom.add(open);
        add(notice, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    private void openWithSystemPlayer() {
        try {
            if (videoPath != null && Files.isRegularFile(videoPath) && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(videoPath.toFile());
            } else {
                JOptionPane.showMessageDialog(this, "Không thể mở tệp video.",
                        "Phát video", JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Không mở được video: " + ex.getMessage(),
                    "Phát video", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void runOnFxThread(Runnable action) {
        try {
            Class<?> platform = Class.forName("javafx.application.Platform");
            Method runLater = platform.getMethod("runLater", Runnable.class);
            runLater.invoke(null, action);
        } catch (Throwable ex) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "JavaFX chưa được cấu hình đúng.", "Phát video", JOptionPane.WARNING_MESSAGE));
        }
    }
}
