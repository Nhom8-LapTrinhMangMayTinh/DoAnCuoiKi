package com.chatapp.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/** Active call window; media is captured locally and encrypted by VideoCallController before it is sent. */
final class VideoCallWindow extends JDialog {
    interface MediaSink {
        void sendAudio(byte[] ulawFrame);
        void sendVideo(byte[] jpeg, int quality);
        void sendMediaState(boolean microphoneOn, boolean cameraOn);
    }

    private static final int PCM_RATE = 8000;
    private static final int PCM_BYTES_PER_FRAME = 640; // 40 ms, 8 kHz mono, 16-bit PCM
    private static final int ULAW_SAMPLES_PER_FRAME = 320;
    private static final AudioFormat PCM_FORMAT = new AudioFormat(PCM_RATE, 16, 1, true, false);

    private final String selfName;
    private final String peerName;
    private final MediaSink sink;
    private final Runnable onHangup;
    private final IntSupplier rttSupplier;
    private final VideoSurface remoteSurface = new VideoSurface("Đang chờ hình ảnh đối phương…");
    private final VideoSurface localSurface = new VideoSurface("Camera của bạn đang tắt");
    private final JLabel lblStatus = new JLabel("Đang thiết lập cuộc gọi…");
    private final JLabel lblClock = new JLabel("00:00");
    private final JLabel lblStats = new JLabel("FPS 0 · 0 kbps · RTT -- ms");
    private final JLabel lblRemoteMedia = new JLabel("Mic: chưa rõ  ·  Camera: chưa rõ");
    private final JButton btnMic = new JButton("🎙 Tắt mic");
    private final JButton btnCamera = new JButton("📷 Tắt camera");
    private final JButton btnHangup = new JButton("Kết thúc");
    private final JComboBox<CameraChoice> cameraSelector = new JComboBox<>();
    private final CameraCapture cameraCapture = new CameraCapture();
    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(12);
    private final AtomicBoolean mediaStarted = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean microphoneOn = new AtomicBoolean(true);
    private final AtomicBoolean cameraOn = new AtomicBoolean(true);
    private final AtomicBoolean uiUpdateQueued = new AtomicBoolean(false);
    private final AtomicLong txBytes = new AtomicLong();
    private final AtomicLong rxBytes = new AtomicLong();
    private final AtomicInteger txFrames = new AtomicInteger();
    private final AtomicInteger rxFrames = new AtomicInteger();
    private volatile BufferedImage latestRemoteImage;
    private volatile boolean callActive;
    private volatile long callStartMillis;
    private volatile int lastRttMillis = -1;
    private volatile String peerMicStatus = "Chưa rõ";
    private volatile String peerCameraStatus = "Chưa rõ";
    private Timer statsTimer;
    private volatile TargetDataLine microphoneLine;
    private volatile Thread micThread;
    private Thread speakerThread;
    private Thread cameraThread;

    VideoCallWindow(Frame owner, String selfName, String peerName,
                    MediaSink sink, Runnable onHangup, IntSupplier rttSupplier) {
        super(owner, "Cuộc gọi video · " + peerName, false);
        this.selfName = selfName;
        this.peerName = peerName;
        this.sink = sink;
        this.onHangup = onHangup;
        this.rttSupplier = rttSupplier;
        buildUi();
        setSize(820, 600);
        setMinimumSize(new Dimension(720, 520));
        setLocationRelativeTo(owner);
        refreshCameraChoices();
        statsTimer = new Timer(1000, e -> refreshStats());
        statsTimer.start();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onHangup.run(); }
        });
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(new Color(15, 23, 42));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        JLabel title = new JLabel("Gọi video với " + peerName);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblStatus.setForeground(new Color(203, 213, 225));
        lblClock.setForeground(Color.WHITE);
        lblClock.setFont(new Font("Segoe UI", Font.BOLD, 18));
        top.add(title, BorderLayout.WEST);
        top.add(lblClock, BorderLayout.EAST);
        JPanel statusStack = new JPanel(new BorderLayout(0, 2));
        statusStack.setOpaque(false);
        statusStack.add(lblStatus, BorderLayout.NORTH);
        lblStats.setForeground(new Color(148, 163, 184));
        lblStats.setFont(new Font("Consolas", Font.PLAIN, 12));
        statusStack.add(lblStats, BorderLayout.SOUTH);
        top.add(statusStack, BorderLayout.SOUTH);

        JPanel videos = new JPanel(new java.awt.GridLayout(1, 2, 10, 0));
        videos.setOpaque(false);
        JPanel remotePanel = new JPanel(new BorderLayout(0, 4));
        remotePanel.setOpaque(false);
        JLabel remoteTitle = surfaceTitle(peerName + " · Đối phương");
        remotePanel.add(remoteTitle, BorderLayout.NORTH);
        remotePanel.add(remoteSurface, BorderLayout.CENTER);
        JPanel localPanel = new JPanel(new BorderLayout(0, 4));
        localPanel.setOpaque(false);
        JLabel localTitle = surfaceTitle(selfName + " · Bạn");
        localPanel.add(localTitle, BorderLayout.NORTH);
        localPanel.add(localSurface, BorderLayout.CENTER);
        JPanel localBottom = new JPanel(new BorderLayout(4, 4));
        localBottom.setOpaque(false);
        lblRemoteMedia.setForeground(new Color(203, 213, 225));
        lblRemoteMedia.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        localBottom.add(lblRemoteMedia, BorderLayout.NORTH);
        cameraSelector.setToolTipText("Đổi nguồn hình ảnh trong cuộc gọi");
        cameraSelector.addActionListener(e -> {
            CameraChoice choice = (CameraChoice) cameraSelector.getSelectedItem();
            if (choice != null && choice.index >= 0 && mediaStarted.get()) {
                cameraCapture.select(choice.index, cameraOn.get());
            }
        });
        localBottom.add(cameraSelector, BorderLayout.CENTER);
        localPanel.add(localBottom, BorderLayout.SOUTH);
        videos.add(remotePanel);
        videos.add(localPanel);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 2));
        controls.setOpaque(false);
        styleButton(btnMic, new Color(51, 65, 85));
        styleButton(btnCamera, new Color(51, 65, 85));
        styleButton(btnHangup, new Color(220, 38, 38));
        btnMic.addActionListener(e -> toggleMicrophone());
        btnCamera.addActionListener(e -> toggleCamera());
        btnHangup.addActionListener(e -> onHangup.run());
        controls.add(btnMic);
        controls.add(btnCamera);
        controls.add(btnHangup);

        root.add(top, BorderLayout.NORTH);
        root.add(videos, BorderLayout.CENTER);
        root.add(controls, BorderLayout.SOUTH);
        setContentPane(root);
        setStatus("Đang đổ chuông…");
        setMediaButtonsEnabled(false);
    }

    private JLabel surfaceTitle(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(226, 232, 240));
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        return l;
    }

    private void styleButton(JButton b, Color color) {
        b.setForeground(Color.WHITE);
        b.setBackground(color);
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setBorder(BorderFactory.createEmptyBorder(11, 16, 11, 16));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    void showCallWindow() { if (!isVisible()) setVisible(true); }

    void setStatus(String status) {
        SwingUtilities.invokeLater(() -> lblStatus.setText(status == null ? " " : status));
    }

    void markActive() {
        callActive = true;
        callStartMillis = System.currentTimeMillis();
        setStatus("Đã kết nối · Media được mã hóa đầu cuối");
        setMediaButtonsEnabled(true);
        startMedia();
    }

    void updatePeerMediaState(boolean mic, boolean camera) {
        peerMicStatus = mic ? "Bật" : "Tắt";
        peerCameraStatus = camera ? "Bật" : "Tắt";
        SwingUtilities.invokeLater(() -> lblRemoteMedia.setText(
                "Mic đối phương: " + peerMicStatus + "  ·  Camera: " + peerCameraStatus));
    }

    void acceptRemoteAudio(byte[] ulaw) {
        if (ulaw == null || stopping.get()) return;
        if (!audioQueue.offer(ulaw)) {
            audioQueue.poll(); // Bound jitter buffer: keep recent audio instead of building a long delay.
            audioQueue.offer(ulaw);
        }
        rxBytes.addAndGet(ulaw.length);
    }

    void acceptRemoteVideo(byte[] jpeg) {
        if (jpeg == null || stopping.get()) return;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
            if (img == null) return;
            latestRemoteImage = img; // Latest-frame strategy: do not enqueue stale video frames on the EDT.
            rxFrames.incrementAndGet();
            rxBytes.addAndGet(jpeg.length);
            if (uiUpdateQueued.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    try { remoteSurface.setImage(latestRemoteImage); }
                    finally { uiUpdateQueued.set(false); }
                });
            }
        } catch (Exception ignored) { }
    }

    void updateRtt(int rttMillis) { lastRttMillis = rttMillis; }
    int getRttMillis() { return lastRttMillis; }
    boolean isMicrophoneOn() { return microphoneOn.get(); }
    boolean isCameraOn() { return cameraOn.get(); }

    void closeFromController(String finalStatus) {
        if (!stopping.compareAndSet(false, true)) return;
        if (finalStatus != null && !finalStatus.isBlank()) lblStatus.setText(finalStatus);
        stopMedia();
        if (statsTimer != null) statsTimer.stop();
        dispose();
    }

    private void setMediaButtonsEnabled(boolean enabled) {
        btnMic.setEnabled(enabled);
        btnCamera.setEnabled(enabled);
        cameraSelector.setEnabled(enabled);
    }

    private void toggleMicrophone() {
        boolean newValue = !microphoneOn.get();
        microphoneOn.set(newValue);
        btnMic.setText(newValue ? "🎙 Tắt mic" : "🔇 Bật mic");
        if (mediaStarted.get()) {
            if (newValue) {
                micThread = daemon("Call-Microphone", this::captureAudioLoop);
                micThread.start();
            } else {
                closeMicrophoneLine();
                if (micThread != null) micThread.interrupt();
            }
        }
        sink.sendMediaState(newValue, cameraOn.get());
        setStatus(newValue ? "Micro đang bật" : "Micro đã tắt");
    }

    private void toggleCamera() {
        boolean newValue = !cameraOn.get();
        cameraOn.set(newValue);
        btnCamera.setText(newValue ? "📷 Tắt camera" : "🚫 Bật camera");
        if (!newValue) {
            cameraCapture.close();
            localSurface.setPlaceholder("Camera của bạn đang tắt");
        } else {
            if (!cameraCapture.openSelected()) {
                cameraOn.set(false);
                btnCamera.setText("🚫 Bật camera");
                setStatus("Không mở được camera. Hãy kiểm tra thư viện webcam-capture và quyền camera.");
                sink.sendMediaState(microphoneOn.get(), false);
                return;
            }
            setStatus("Camera đang bật");
        }
        sink.sendMediaState(microphoneOn.get(), newValue);
    }

    private void refreshCameraChoices() {
        List<CameraChoice> choices = cameraCapture.listChoices();
        DefaultComboBoxModel<CameraChoice> model = new DefaultComboBoxModel<>();
        for (CameraChoice choice : choices) model.addElement(choice);
        cameraSelector.setModel(model);
        if (choices.isEmpty()) {
            model.addElement(new CameraChoice(-1, "Chưa tìm thấy camera"));
        }
    }

    private void startMedia() {
        if (!mediaStarted.compareAndSet(false, true)) return;
        stopping.set(false);
        micThread = daemon("Call-Microphone", this::captureAudioLoop);
        speakerThread = daemon("Call-Speaker", this::playAudioLoop);
        cameraThread = daemon("Call-Camera", this::captureVideoLoop);
        micThread.start();
        speakerThread.start();
        cameraThread.start();
        if (!cameraCapture.openSelected()) {
            cameraOn.set(false);
            btnCamera.setText("🚫 Bật camera");
            sink.sendMediaState(microphoneOn.get(), false);
            localSurface.setPlaceholder("Không mở được camera · Kiểm tra webcam-capture và quyền camera");
        }
    }

    private Thread daemon(String name, Runnable task) {
        Thread t = new Thread(task, name + "-" + selfName);
        t.setDaemon(true);
        return t;
    }

    private void captureAudioLoop() {
        TargetDataLine line = null;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, PCM_FORMAT);
            line = (TargetDataLine) AudioSystem.getLine(info);
            microphoneLine = line;
            line.open(PCM_FORMAT, PCM_BYTES_PER_FRAME * 8);
            line.start();
            byte[] pcm = new byte[PCM_BYTES_PER_FRAME];
            while (!stopping.get() && microphoneOn.get()) {
                int offset = 0;
                while (offset < pcm.length && !stopping.get()) {
                    int n = line.read(pcm, offset, pcm.length - offset);
                    if (n < 0) break;
                    offset += n;
                }
                if (stopping.get()) break;
                if (offset != pcm.length) continue;
                if (!microphoneOn.get()) continue;
                byte[] ulaw = pcmToUlaw(pcm);
                sink.sendAudio(ulaw);
                txBytes.addAndGet(ulaw.length);
            }
        } catch (Exception ex) {
            if (!stopping.get() && microphoneOn.get()) {
                microphoneOn.set(false);
                SwingUtilities.invokeLater(() -> btnMic.setText("🔇 Bật mic"));
                sink.sendMediaState(false, cameraOn.get());
                setStatus("Không mở được micro: kiểm tra thiết bị/quyền micro.");
            }
        } finally {
            if (line != null) {
                if (microphoneLine == line) microphoneLine = null;
                try { line.stop(); } catch (Exception ignored) { }
                try { line.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void closeMicrophoneLine() {
        TargetDataLine line = microphoneLine;
        microphoneLine = null;
        if (line != null) {
            try { line.stop(); } catch (Exception ignored) { }
            try { line.close(); } catch (Exception ignored) { }
        }
    }

    private void playAudioLoop() {
        SourceDataLine line = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, PCM_FORMAT);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(PCM_FORMAT, PCM_BYTES_PER_FRAME * 12);
            line.start();
            while (!stopping.get()) {
                byte[] ulaw = audioQueue.take();
                byte[] pcm = ulawToPcm(ulaw);
                line.write(pcm, 0, pcm.length);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            setStatus("Không mở được loa: kiểm tra thiết bị âm thanh.");
        } finally {
            if (line != null) { try { line.drain(); } catch (Exception ignored) { } try { line.stop(); } catch (Exception ignored) { } try { line.close(); } catch (Exception ignored) { } }
        }
    }

    private void captureVideoLoop() {
        long lastFrameAt = 0;
        while (!stopping.get()) {
            try {
                if (!cameraOn.get()) {
                    Thread.sleep(100);
                    continue;
                }
                if (!cameraCapture.isOpen() && !cameraCapture.openSelected()) {
                    cameraOn.set(false);
                    SwingUtilities.invokeLater(() -> btnCamera.setText("🚫 Bật camera"));
                    sink.sendMediaState(microphoneOn.get(), false);
                    setStatus("Không đọc được camera. Đã tắt camera để bảo vệ quyền riêng tư.");
                    localSurface.setPlaceholder("Không mở được camera");
                    Thread.sleep(250);
                    continue;
                }
                BufferedImage source = cameraCapture.getImage();
                if (source == null) { Thread.sleep(25); continue; }
                BufferedImage normalized = normalizeFrame(source);
                localSurface.setImage(normalized);
                int rtt = rttSupplier.getAsInt();
                int quality = adaptiveQuality(rtt);
                int fps = adaptiveFps(rtt);
                long now = System.nanoTime();
                long interval = 1_000_000_000L / fps;
                long elapsed = now - lastFrameAt;
                if (lastFrameAt == 0 || elapsed >= interval) {
                    byte[] jpeg = encodeJpeg(normalized, quality);
                    if (jpeg.length < 300_000) {
                        sink.sendVideo(jpeg, quality);
                        txFrames.incrementAndGet();
                        txBytes.addAndGet(jpeg.length);
                    }
                    lastFrameAt = now;
                } else {
                    Thread.sleep(Math.max(1, (interval - elapsed) / 1_000_000L));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                setStatus("Lỗi đọc camera: " + ex.getClass().getSimpleName());
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private static int adaptiveFps(int rtt) {
        if (rtt < 0 || rtt <= 120) return 12;
        if (rtt <= 220) return 9;
        if (rtt <= 350) return 6;
        return 4;
    }

    private static int adaptiveQuality(int rtt) {
        if (rtt < 0 || rtt <= 120) return 78;
        if (rtt <= 220) return 62;
        if (rtt <= 350) return 42;
        return 25;
    }

    private static BufferedImage normalizeFrame(BufferedImage source) {
        final int w = 480, h = 270;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double scale = Math.max((double) w / source.getWidth(), (double) h / source.getHeight());
        int scaledW = (int) Math.ceil(source.getWidth() * scale);
        int scaledH = (int) Math.ceil(source.getHeight() * scale);
        int x = (w - scaledW) / 2, y = (h - scaledH) / 2;
        g.drawImage(source, x, y, scaledW, scaledH, null);
        g.dispose();
        return result;
    }

    private static byte[] encodeJpeg(BufferedImage image, int quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("Không có JPEG writer.");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(12_000);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.25f, Math.min(0.78f, quality / 100f)));
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }

    private void refreshStats() {
        long tx = txBytes.getAndSet(0);
        long rx = rxBytes.getAndSet(0);
        int txF = txFrames.getAndSet(0);
        int rxF = rxFrames.getAndSet(0);
        int rtt = rttSupplier.getAsInt();
        String rttText = rtt < 0 ? "--" : Integer.toString(rtt);
        lblStats.setText(String.format(Locale.ROOT, "FPS gửi/nhận %d/%d · %d kbps · RTT %s ms",
                txF, rxF, Math.round((tx + rx) * 8f / 1000f), rttText));
        if (callActive && callStartMillis > 0) {
            long seconds = (System.currentTimeMillis() - callStartMillis) / 1000;
            lblClock.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60));
        }
    }

    private void stopMedia() {
        stopping.set(true);
        mediaStarted.set(false);
        audioQueue.clear();
        cameraCapture.close();
        closeMicrophoneLine();
        if (micThread != null) micThread.interrupt();
        if (speakerThread != null) speakerThread.interrupt();
        if (cameraThread != null) cameraThread.interrupt();
    }

    private static byte[] pcmToUlaw(byte[] pcm) {
        byte[] out = new byte[ULAW_SAMPLES_PER_FRAME];
        for (int i = 0; i < out.length; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1];
            short sample = (short) ((hi << 8) | lo);
            out[i] = linearToUlaw(sample);
        }
        return out;
    }

    private static byte[] ulawToPcm(byte[] ulaw) {
        byte[] out = new byte[ulaw.length * 2];
        for (int i = 0; i < ulaw.length; i++) {
            short value = ulawToLinear(ulaw[i]);
            out[i * 2] = (byte) (value & 0xff);
            out[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return out;
    }

    private static byte linearToUlaw(short sample) {
        final int BIAS = 0x84, CLIP = 32635;
        int pcm = sample;
        int mask;
        if (pcm < 0) { pcm = BIAS - pcm; mask = 0x7f; }
        else { pcm += BIAS; mask = 0xff; }
        if (pcm > CLIP) pcm = CLIP;
        int segment = 0;
        for (int v = pcm >> 7; v > 1; v >>= 1) segment++;
        if (segment > 7) segment = 7;
        int mantissa = (pcm >> (segment + 3)) & 0x0f;
        return (byte) (((segment << 4) | mantissa) ^ mask);
    }

    private static short ulawToLinear(byte value) {
        int u = (~value) & 0xff;
        int t = ((u & 0x0f) << 3) + 0x84;
        t <<= (u & 0x70) >> 4;
        return (short) ((u & 0x80) != 0 ? 0x84 - t : t - 0x84);
    }

    private static final class VideoSurface extends JPanel {
        private volatile BufferedImage image;
        private volatile String placeholder;
        VideoSurface(String placeholder) {
            this.placeholder = placeholder;
            setBackground(new Color(2, 6, 23));
            setPreferredSize(new Dimension(360, 250));
            setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85)));
        }
        void setImage(BufferedImage value) { image = value; placeholder = null; repaint(); }
        void setPlaceholder(String value) { image = null; placeholder = value; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            BufferedImage current = image;
            if (current != null) {
                double scale = Math.min((double) getWidth() / current.getWidth(), (double) getHeight() / current.getHeight());
                int w = (int) (current.getWidth() * scale), h = (int) (current.getHeight() * scale);
                g.drawImage(current, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } else {
                g.setColor(new Color(148, 163, 184));
                g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                String text = placeholder == null ? "" : placeholder;
                java.awt.FontMetrics fm = g.getFontMetrics();
                int lineW = Math.min(getWidth() - 20, fm.stringWidth(text));
                String shown = text;
                while (shown.length() > 10 && fm.stringWidth(shown) > getWidth() - 20) shown = shown.substring(0, shown.length() - 2) + "…";
                g.drawString(shown, Math.max(8, (getWidth() - fm.stringWidth(shown)) / 2), getHeight() / 2);
            }
            g.dispose();
        }
    }

    private static final class CameraChoice {
        final int index;
        final String name;
        CameraChoice(int index, String name) { this.index = index; this.name = name; }
        @Override public String toString() { return name; }
    }

    /** Reflection keeps the project compilable without bundling a webcam driver in source. */
    private static final class CameraCapture {
        private Class<?> webcamClass;
        private List<Object> webcams = Collections.emptyList();
        private Object active;
        private int selectedIndex;
        CameraCapture() {
            try {
                webcamClass = Class.forName("com.github.sarxos.webcam.Webcam");
                Object result = webcamClass.getMethod("getWebcams").invoke(null);
                if (result instanceof List<?>) {
                    webcams = new ArrayList<>((List<?>) result);
                }
            } catch (Throwable ignored) { webcamClass = null; webcams = Collections.emptyList(); }
        }
        List<CameraChoice> listChoices() {
            List<CameraChoice> out = new ArrayList<>();
            for (int i = 0; i < webcams.size(); i++) {
                Object device = webcams.get(i);
                String name;
                try { name = String.valueOf(device.getClass().getMethod("getName").invoke(device)); }
                catch (Exception ex) { name = "Camera " + (i + 1); }
                out.add(new CameraChoice(i, name));
            }
            if (out.isEmpty()) out.add(new CameraChoice(-1, "Cần thêm thư viện webcam-capture"));
            return out;
        }
        synchronized void select(int index, boolean shouldOpen) {
            selectedIndex = index;
            closeActive();
            if (shouldOpen) openSelected();
        }
        synchronized boolean openSelected() {
            if (active != null) return true;
            if (webcamClass == null || webcams.isEmpty() || selectedIndex < 0 || selectedIndex >= webcams.size()) return false;
            try {
                active = webcams.get(selectedIndex);
                try {
                    Method setViewSize = active.getClass().getMethod("setViewSize", Dimension.class);
                    setViewSize.invoke(active, new Dimension(640, 480));
                } catch (Exception ignored) { }
                active.getClass().getMethod("open").invoke(active);
                return true;
            } catch (Throwable ex) { active = null; return false; }
        }
        synchronized boolean isOpen() {
            if (active == null) return false;
            try { return Boolean.TRUE.equals(active.getClass().getMethod("isOpen").invoke(active)); }
            catch (Exception ex) { return active != null; }
        }
        synchronized BufferedImage getImage() {
            if (active == null) return null;
            try {
                Object image = active.getClass().getMethod("getImage").invoke(active);
                return image instanceof BufferedImage ? (BufferedImage) image : null;
            } catch (Throwable ex) { return null; }
        }
        synchronized void close() { closeActive(); }
        private void closeActive() {
            Object old = active;
            active = null;
            if (old != null) try { old.getClass().getMethod("close").invoke(old); } catch (Throwable ignored) { }
        }
    }
}
