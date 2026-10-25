package com.chatapp.client;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bong bóng tin thoại theo kiểu trình phát nhỏ gọn: nút tròn, sóng âm và nút lưu. */
public final class VoiceMessageBubble extends JPanel {
    private static final Pattern VOICE_NAME = Pattern.compile(
            "(?i)^voice-(\\d{1,6})ms-[a-z0-9-]+\\.wav$");
    private static final ConcurrentHashMap<Long, WeakReference<VoiceMessageBubble>> LATEST =
            new ConcurrentHashMap<>();
    private static final Object PLAYER_LOCK = new Object();
    private static volatile Clip activeClip;
    private static volatile Timer activeTimer;
    private static volatile long activeFileId = -1L;
    private static volatile long pendingFileId = -1L;
    private static volatile long generation;

    private static final Color SURFACE = new Color(229, 240, 255);
    private static final Color OUTLINE = new Color(201, 218, 239);
    private static final Color BLUE = new Color(0, 101, 255);

    private final long fileId;
    private final String fileName;
    private final long durationMs;
    private final Function<Long, Path> cachedPathProvider;
    private final BiConsumer<Long, String> playRequest;
    private final BiConsumer<Long, String> saveAction;
    private final PlayButton playButton = new PlayButton();
    private final DownloadButton saveButton = new DownloadButton();
    private final VoiceWaveformView waveform = new VoiceWaveformView();
    private final JLabel timeLabel;

    public VoiceMessageBubble(long fileId, String fileName,
                              Function<Long, Path> cachedPathProvider,
                              BiConsumer<Long, String> playRequest,
                              BiConsumer<Long, String> saveAction,
                              Consumer<JButton> secondaryButtonStyler) {
        super(new BorderLayout(12, 0));
        this.fileId = fileId;
        this.fileName = fileName == null ? "" : fileName;
        this.durationMs = durationMillis(this.fileName);
        this.cachedPathProvider = cachedPathProvider;
        this.playRequest = playRequest;
        this.saveAction = saveAction;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 12));
        setPreferredSize(new Dimension(365, 106));
        setMinimumSize(new Dimension(280, 106));
        setMaximumSize(new Dimension(470, 106));
        getAccessibleContext().setAccessibleName("Tin nhắn thoại, thời lượng " + formatTime(durationMs));

        playButton.setToolTipText("Phát tin nhắn thoại");
        playButton.getAccessibleContext().setAccessibleName("Phát tin nhắn thoại");
        playButton.addActionListener(e -> togglePlayback());

        waveform.setProgress(0.0);
        JPanel middle = new JPanel(new BorderLayout(0, 1));
        middle.setOpaque(false);
        middle.add(waveform, BorderLayout.CENTER);
        timeLabel = new JLabel(formatTime(durationMs));
        timeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        timeLabel.setForeground(new Color(83, 101, 128));
        timeLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        timeLabel.setToolTipText("Thời lượng: " + formatTime(durationMs));
        middle.add(timeLabel, BorderLayout.SOUTH);

        saveButton.setToolTipText("Lưu bản ghi WAV về máy (có tiến trình và kiểm tra SHA-256)");
        saveButton.getAccessibleContext().setAccessibleName("Lưu bản ghi WAV");
        if (secondaryButtonStyler != null) {
            // The control has its own painted style; don't let the generic toolbar style override it.
        }
        saveButton.addActionListener(e -> {
            if (saveAction != null) saveAction.accept(fileId, fileName);
        });

        add(playButton, BorderLayout.WEST);
        add(middle, BorderLayout.CENTER);
        add(saveButton, BorderLayout.EAST);

        LATEST.put(fileId, new WeakReference<>(this));
        if (pendingFileId == fileId) setPendingUi(true);
        if (activeFileId == fileId) setPlayingUi(true);
        else applyProgress(0L, false);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int height = getHeight();
        g.setColor(SURFACE);
        g.fillRoundRect(0, 0, width - 1, height - 1, 14, 14);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(0, 0, width - 2, height - 2, 14, 14);
        g.dispose();
        super.paintComponent(graphics);
    }

    public static boolean isVoiceMessageName(String name) {
        return name != null && VOICE_NAME.matcher(name.trim()).matches();
    }

    public static long durationMillis(String name) {
        if (name == null) return 0L;
        Matcher matcher = VOICE_NAME.matcher(name.trim());
        if (!matcher.matches()) return 0L;
        try {
            long value = Long.parseLong(matcher.group(1));
            return Math.max(0L, Math.min(120_000L, value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public static String userFacingName(String name) {
        return isVoiceMessageName(name) ? "Tin nhắn thoại.wav" : (name == null ? "" : name);
    }

    private void togglePlayback() {
        long oldPending = pendingFileId;
        if (oldPending > 0L && oldPending != fileId) {
            pendingFileId = -1L;
            VoiceMessageBubble previousPending = latest(oldPending);
            if (previousPending != null) previousPending.setPendingUi(false);
        }
        if (activeFileId == fileId && activeClip != null) {
            stopCurrent(false);
            return;
        }
        if (pendingFileId == fileId) {
            pendingFileId = -1L;
            setPendingUi(false);
            return;
        }
        Path cached = null;
        try { if (cachedPathProvider != null) cached = cachedPathProvider.apply(fileId); }
        catch (RuntimeException ignored) { }
        if (cached != null && Files.isRegularFile(cached)) {
            startPlayback(cached);
            return;
        }
        stopCurrent(false);
        pendingFileId = fileId;
        setPendingUi(true);
        if (playRequest != null) playRequest.accept(fileId, fileName);
        else onAudioFailure(fileId);
    }

    /** Gọi sau khi FileTransferController đã tải file và xác minh SHA-256 thành công. */
    public static void audioFileReady(long fileId, Path path) {
        SwingUtilities.invokeLater(() -> {
            if (pendingFileId != fileId) return;
            WeakReference<VoiceMessageBubble> ref = LATEST.get(fileId);
            VoiceMessageBubble bubble = ref == null ? null : ref.get();
            pendingFileId = -1L;
            if (bubble == null || path == null || !Files.isRegularFile(path)) return;
            bubble.setPendingUi(false);
            bubble.startPlayback(path);
        });
    }

    public static void onAudioFailure(long fileId) {
        SwingUtilities.invokeLater(() -> {
            if (pendingFileId == fileId) pendingFileId = -1L;
            WeakReference<VoiceMessageBubble> ref = LATEST.get(fileId);
            VoiceMessageBubble bubble = ref == null ? null : ref.get();
            if (bubble != null) bubble.setPendingUi(false);
        });
    }

    /** Dừng mọi âm thanh khi cửa sổ chat bị đóng hoặc đăng xuất. */
    public static void stopAllPlayback() {
        Runnable action = () -> {
            long oldPending = pendingFileId;
            pendingFileId = -1L;
            VoiceMessageBubble pending = latest(oldPending);
            if (pending != null) pending.setPendingUi(false);
            stopCurrent(false);
        };
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    private void startPlayback(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            onAudioFailure(fileId);
            return;
        }
        stopCurrent(false);
        final long token;
        synchronized (PLAYER_LOCK) {
            token = ++generation;
            activeFileId = fileId;
        }
        setPendingUi(true);
        Thread worker = new Thread(() -> {
            Clip prepared = null;
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
                prepared = AudioSystem.getClip();
                prepared.open(stream);
                Clip readyClip = prepared;
                SwingUtilities.invokeLater(() -> {
                    synchronized (PLAYER_LOCK) {
                        if (token != generation || activeFileId != fileId) {
                            readyClip.close();
                            return;
                        }
                        activeClip = readyClip;
                    }
                    VoiceMessageBubble bubble = latest(fileId);
                    if (bubble != null) {
                        bubble.setPendingUi(false);
                        bubble.setPlayingUi(true);
                        bubble.applyProgress(0L, true);
                    }
                    Timer ticker = new Timer(80, e -> updatePlaybackProgress());
                    synchronized (PLAYER_LOCK) { activeTimer = ticker; }
                    ticker.start();
                    readyClip.start();
                });
            } catch (Exception ex) {
                if (prepared != null) try { prepared.close(); } catch (Exception ignored) { }
                SwingUtilities.invokeLater(() -> {
                    synchronized (PLAYER_LOCK) {
                        if (token != generation) return;
                    }
                    stopCurrent(false);
                    VoiceMessageBubble bubble = latest(fileId);
                    if (bubble != null) {
                        bubble.setPendingUi(false);
                        javax.swing.JOptionPane.showMessageDialog(bubble,
                                "Không thể phát bản ghi WAV. Hãy kiểm tra thiết bị âm thanh.\n" + ex.getMessage(),
                                "Phát tin nhắn thoại", javax.swing.JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        }, "ChatApp-VoicePlayback-" + fileId);
        worker.setDaemon(true);
        worker.start();
    }

    private static void updatePlaybackProgress() {
        Clip clip = activeClip;
        long id = activeFileId;
        if (clip == null || id <= 0) return;
        long position = clip.getMicrosecondPosition() / 1000L;
        long length = clip.getMicrosecondLength() / 1000L;
        VoiceMessageBubble bubble = latest(id);
        if (bubble != null) bubble.applyProgress(position, true);
        if (!clip.isRunning() || (length > 0 && position >= length)) stopCurrent(true);
    }

    private static void stopCurrent(boolean completed) {
        Clip clip;
        Timer timer;
        long oldId;
        synchronized (PLAYER_LOCK) {
            generation++;
            clip = activeClip;
            timer = activeTimer;
            oldId = activeFileId;
            activeClip = null;
            activeTimer = null;
            activeFileId = -1L;
        }
        if (timer != null) timer.stop();
        if (clip != null) {
            try { clip.stop(); } catch (RuntimeException ignored) { }
            try { clip.close(); } catch (RuntimeException ignored) { }
        }
        VoiceMessageBubble bubble = latest(oldId);
        if (bubble != null) {
            bubble.setPlayingUi(false);
            bubble.applyProgress(completed ? bubble.durationMs : 0L, false);
        }
    }

    private static VoiceMessageBubble latest(long id) {
        WeakReference<VoiceMessageBubble> ref = LATEST.get(id);
        return ref == null ? null : ref.get();
    }

    private void setPendingUi(boolean pending) {
        playButton.setEnabled(!pending);
        playButton.setPending(pending);
        if (pending) {
            timeLabel.setText("Đang tải…");
        } else {
            timeLabel.setText(formatTime(durationMs));
        }
    }

    private void setPlayingUi(boolean playing) {
        playButton.setPlaying(playing);
        playButton.setEnabled(true);
        playButton.setToolTipText(playing ? "Dừng phát" : "Phát tin nhắn thoại");
        playButton.getAccessibleContext().setAccessibleName(playing ? "Dừng phát" : "Phát tin nhắn thoại");
    }

    private void applyProgress(long positionMs, boolean playing) {
        long safeDuration = Math.max(1L, durationMs);
        long capped = Math.max(0L, Math.min(safeDuration, positionMs));
        double ratio = capped / (double) safeDuration;
        waveform.setProgress(playing ? ratio : 0.0);
        timeLabel.setText(playing ? formatTime(capped) : formatTime(durationMs));
        if (playing) setPlayingUi(true);
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private static final class PlayButton extends JButton {
        private boolean playing;
        private boolean pending;
        PlayButton() {
            setPreferredSize(new Dimension(50, 50));
            setMinimumSize(new Dimension(50, 50));
            setMaximumSize(new Dimension(50, 50));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(true);
        }
        void setPlaying(boolean value) { playing = value; pending = false; repaint(); }
        void setPending(boolean value) { pending = value; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
            g.setColor(isEnabled() ? BLUE : new Color(119, 166, 237));
            g.fillOval(x, y, d, d);
            g.setColor(Color.WHITE);
            if (pending) {
                g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(x + 15, y + 15, d - 30, d - 30, 45, 265);
            } else if (playing) {
                int side = 13;
                g.fillRoundRect(getWidth() / 2 - side / 2, getHeight() / 2 - side / 2, side, side, 3, 3);
            } else {
                int cx = getWidth() / 2 + 2, cy = getHeight() / 2;
                Polygon triangle = new Polygon(
                        new int[]{cx - 6, cx - 6, cx + 8},
                        new int[]{cy - 9, cy + 9, cy}, 3);
                g.fillPolygon(triangle);
            }
            g.dispose();
        }
    }

    private static final class DownloadButton extends JButton {
        DownloadButton() {
            setPreferredSize(new Dimension(40, 40));
            setMinimumSize(new Dimension(40, 40));
            setMaximumSize(new Dimension(40, 40));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = 1, y = 1, size = Math.min(getWidth(), getHeight()) - 2;
            g.setColor(getModel().isPressed() ? new Color(185, 213, 252)
                    : getModel().isRollover() ? new Color(202, 224, 255) : new Color(211, 229, 255));
            g.fillRoundRect(x, y, size, size, 7, 7);
            g.setColor(new Color(21, 74, 158));
            g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            g.drawLine(cx, 9, cx, 23);
            g.drawLine(cx - 5, 18, cx, 23);
            g.drawLine(cx + 5, 18, cx, 23);
            g.drawLine(10, 29, getWidth() - 10, 29);
            g.dispose();
        }
    }
}
