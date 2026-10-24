package com.chatapp.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Ghi âm WAV PCM 16 kHz, mono, 16-bit little-endian; tối đa 120 giây. */
public final class VoiceRecorderDialog extends JDialog {
    public static final int SAMPLE_RATE = 16_000;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int CHANNELS = 1;
    public static final long MAX_DURATION_MS = 120_000L;
    public static final long MIN_DURATION_MS = 600L;
    private static final int FRAME_SIZE = 2;
    private static final int MAX_PCM_BYTES = SAMPLE_RATE * FRAME_SIZE * 120;
    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, BITS_PER_SAMPLE,
            CHANNELS, FRAME_SIZE, SAMPLE_RATE, false);

    private final Consumer<Path> recordedCallback;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream(MAX_PCM_BYTES);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final AtomicInteger level = new AtomicInteger(0);
    private final JLabel clockLabel = new JLabel("00:00 / 02:00", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("Đang ghi âm…", SwingConstants.CENTER);
    private final VoiceWaveformView volumeWaveform = VoiceWaveformView.liveMeter();
    private final JButton cancelButton = new JButton("Hủy");
    private final JButton stopSendButton = new JButton("Dừng & gửi");
    private final Timer uiTimer;
    private volatile TargetDataLine line;
    private volatile Thread captureThread;
    private volatile long startedNanos;
    private volatile IOException captureFailure;

    private VoiceRecorderDialog(JFrame owner, Consumer<Path> recordedCallback) {
        super(owner, "Ghi âm tin nhắn thoại", ModalityType.APPLICATION_MODAL);
        this.recordedCallback = recordedCallback;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setResizable(false);

        JPanel content = new JPanel(new BorderLayout(10, 14));
        content.setBackground(new Color(247, 250, 255));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 20, 18, 20));
        JLabel title = new JLabel("●  Đang ghi âm", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(new Color(185, 28, 28));
        clockLabel.setFont(new Font("Segoe UI", Font.BOLD, 25));
        clockLabel.setForeground(new Color(30, 41, 59));
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(71, 85, 105));

        volumeWaveform.setPreferredSize(new Dimension(360, 48));
        JPanel waveformCard = new JPanel(new BorderLayout());
        waveformCard.setBackground(new Color(229, 240, 255));
        waveformCard.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(new Color(201, 218, 239)),
                javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        waveformCard.add(volumeWaveform, BorderLayout.CENTER);

        JPanel middle = new JPanel(new GridLayout(0, 1, 0, 10));
        middle.setOpaque(false);
        middle.add(title);
        middle.add(clockLabel);
        middle.add(waveformCard);
        middle.add(statusLabel);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        cancelButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        cancelButton.setFocusPainted(false);
        cancelButton.setBackground(new Color(235, 240, 247));
        cancelButton.setForeground(new Color(51, 65, 85));
        cancelButton.setOpaque(true);
        cancelButton.addActionListener(e -> cancelRecording());
        stopSendButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        stopSendButton.setForeground(Color.WHITE);
        stopSendButton.setBackground(new Color(0, 101, 255));
        stopSendButton.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 16, 8, 16));
        stopSendButton.setOpaque(true);
        stopSendButton.setFocusPainted(false);
        stopSendButton.addActionListener(e -> finishRecording(true));
        buttons.add(cancelButton);
        buttons.add(stopSendButton);
        content.add(middle, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cancelRecording(); }
        });

        uiTimer = new Timer(100, e -> updateMeterAndClock());
        setMinimumSize(new Dimension(440, 305));
        pack();
        setLocationRelativeTo(owner);
        startCapture();
    }

    public static void showRecorder(JFrame owner, Consumer<Path> recordedCallback) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showRecorder(owner, recordedCallback));
            return;
        }
        VoiceRecorderDialog dialog = new VoiceRecorderDialog(owner, recordedCallback);
        if (dialog.isDisplayable()) dialog.setVisible(true);
    }

    private void startCapture() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT, 8192);
            line.start();
            startedNanos = System.nanoTime();
            captureThread = new Thread(this::captureLoop, "ChatApp-VoiceCapture");
            captureThread.setDaemon(true);
            captureThread.start();
            uiTimer.start();
        } catch (Exception ex) {
            statusLabel.setText("Không mở được micro.");
            stopSendButton.setEnabled(false);
            JOptionPane.showMessageDialog(this,
                    "Không thể truy cập micro. Hãy kiểm tra thiết bị ghi âm và quyền micro của Windows.\n"
                            + ex.getMessage(),
                    "Không ghi âm được", JOptionPane.ERROR_MESSAGE);
            dispose();
        }
    }

    private void captureLoop() {
        byte[] buffer = new byte[3200]; // khoảng 100 ms PCM
        try {
            while (!stopRequested.get()) {
                int currentSize;
                synchronized (pcm) { currentSize = pcm.size(); }
                int remaining = MAX_PCM_BYTES - currentSize;
                if (remaining <= 0) break;
                TargetDataLine currentLine = line;
                if (currentLine == null || !currentLine.isOpen()) break;
                int n = currentLine.read(buffer, 0, Math.min(buffer.length, remaining));
                if (n <= 0) continue;
                synchronized (pcm) { pcm.write(buffer, 0, n); }
                level.set(calculatePeak(buffer, n));
            }
        } catch (RuntimeException ex) {
            if (!stopRequested.get()) captureFailure = new IOException("Lỗi đọc dữ liệu micro.", ex);
        } finally {
            if (!stopRequested.get()) {
                SwingUtilities.invokeLater(() -> {
                    if (!finishing.get()) finishRecording(true);
                });
            }
        }
    }

    private static int calculatePeak(byte[] bytes, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
            peak = Math.max(peak, Math.abs(sample == Short.MIN_VALUE ? 32767 : sample));
        }
        return Math.min(100, (int) Math.round(peak * 100.0 / 32768.0));
    }

    private void updateMeterAndClock() {
        if (!isDisplayable() || startedNanos == 0L) return;
        long elapsedMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        clockLabel.setText(formatTime(elapsedMs) + " / 02:00");
        int shownLevel = level.get();
        volumeWaveform.pushLevel(shownLevel);
        statusLabel.setText("Âm lượng micro: " + shownLevel + "%  •  Tối đa 02:00");
        if (elapsedMs >= MAX_DURATION_MS) finishRecording(true);
    }

    private void cancelRecording() {
        if (!finishing.compareAndSet(false, true)) return;
        stopRequested.set(true);
        uiTimer.stop();
        closeLine();
        statusLabel.setText("Đã hủy bản ghi.");
        dispose();
    }

    private void finishRecording(boolean send) {
        if (!finishing.compareAndSet(false, true)) return;
        stopRequested.set(true);
        uiTimer.stop();
        closeLine();
        cancelButton.setEnabled(false);
        stopSendButton.setEnabled(false);
        statusLabel.setText(send ? "Đang hoàn tất tệp WAV…" : "Đã hủy.");
        Thread worker = new Thread(() -> finalizeRecording(send), "ChatApp-VoiceFinalize");
        worker.setDaemon(true);
        worker.start();
    }

    private void closeLine() {
        TargetDataLine active = line;
        line = null;
        if (active != null) {
            try { active.stop(); } catch (RuntimeException ignored) { }
            try { active.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void finalizeRecording(boolean send) {
        try {
            Thread reader = captureThread;
            if (reader != null && reader != Thread.currentThread()) reader.join(1500L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        byte[] raw;
        synchronized (pcm) { raw = pcm.toByteArray(); }
        long frameCount = raw.length / FRAME_SIZE;
        long durationMs = Math.round(frameCount * 1000.0 / SAMPLE_RATE);
        if (!send) {
            SwingUtilities.invokeLater(this::dispose);
            return;
        }
        if (captureFailure != null) {
            showFailure("Không thể đọc âm thanh từ micro: " + captureFailure.getMessage());
            return;
        }
        if (frameCount * 1000L < (long) SAMPLE_RATE * MIN_DURATION_MS) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "Bản ghi ngắn hơn 0,6 giây nên đã được bỏ, không gửi đi.",
                        "Bản ghi quá ngắn", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            });
            return;
        }
        if (raw.length == 0 || raw.length > MAX_PCM_BYTES) {
            showFailure("Bản ghi âm không hợp lệ hoặc vượt quá 120 giây.");
            return;
        }
        Path created = null;
        try {
            Path directory = Path.of(System.getProperty("user.home", "."), ".chatapp", "voice-outgoing");
            Files.createDirectories(directory);
            String prefix = "voice-" + durationMs + "ms-";
            created = Files.createTempFile(directory, prefix, ".wav");
            long frames = raw.length / FRAME_SIZE;
            try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(raw), FORMAT, frames)) {
                int written = AudioSystem.write(audio, AudioFileFormat.Type.WAVE, created.toFile());
                if (written <= 0 || Files.size(created) <= 44L) {
                    throw new IOException("Không tạo được dữ liệu WAV hợp lệ.");
                }
            }
            Path ready = created;
            SwingUtilities.invokeLater(() -> {
                dispose();
                if (recordedCallback != null) recordedCallback.accept(ready);
            });
        } catch (Exception ex) {
            if (created != null) {
                try { Files.deleteIfExists(created); } catch (IOException ignored) { }
            }
            showFailure("Không thể lưu bản ghi WAV: " + ex.getMessage());
        }
    }

    private void showFailure(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "Ghi âm thất bại", JOptionPane.ERROR_MESSAGE);
            dispose();
        });
    }

    static String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
}
