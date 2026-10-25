package com.chatapp.client;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Grid call window. Remote audio is mixed locally; images use latest-frame-wins buffers. */
final class GroupCallWindow extends JDialog {
    interface MediaSink {
        void sendAudio(byte[] ulaw);
        void sendVideo(byte[] jpeg, int quality);
        void sendMediaState(boolean mic, boolean camera);
        void sendPing(long sentAt);
    }

    private static final int PCM_RATE = 8000;
    private static final int PCM_BYTES = 640; // 40ms: 320 mono samples x 16-bit
    private static final int ULAW_BYTES = 320;
    private static final AudioFormat PCM_FORMAT = new AudioFormat(PCM_RATE, 16, 1, true, false);

    private final String selfName;
    private final MediaSink sink;
    private final Runnable onLeave;
    private final JPanel grid = new JPanel();
    private final JLabel lblClock = new JLabel("00:00");
    private final JLabel lblStatus = new JLabel("Đang thiết lập cuộc gọi nhóm…");
    private final JLabel lblStats = new JLabel("FPS gửi/nhận 0/0 · 0 kbps · RTT -- ms");
    private final JButton btnMic = new JButton("🎙 Tắt mic");
    private final JButton btnCamera = new JButton("📷 Tắt camera");
    private final JButton btnLeave = new JButton("Rời cuộc gọi");
    private final JComboBox<CameraChoice> cameraSelector = new JComboBox<>();
    private final JTextArea eventLog = new JTextArea(3, 40);
    private final CameraCapture cameraCapture = new CameraCapture();
    private final Map<String, Tile> tiles = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<byte[]>> audioQueues = new ConcurrentHashMap<>();
    private final Map<String, byte[]> latestJpegs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService decodeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GroupCall-VideoDecode"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean decodeStarted = new AtomicBoolean(false);
    private final AtomicBoolean mediaStarted = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean microphoneOn = new AtomicBoolean(true);
    private final AtomicBoolean cameraOn = new AtomicBoolean(true);
    private final AtomicLong txBytes = new AtomicLong();
    private final AtomicLong rxBytes = new AtomicLong();
    private final AtomicLong txFrames = new AtomicLong();
    private final AtomicLong rxFrames = new AtomicLong();
    private volatile long callStartedAt;
    private volatile int rttMillis = -1;
    private volatile TargetDataLine microphoneLine;
    private volatile Thread micThread, speakerThread, cameraThread;
    private Timer statsTimer;
    private volatile boolean leaveButtonCanClose = true;

    GroupCallWindow(Frame owner, String selfName, MediaSink sink, Runnable onLeave) {
        super(owner, "Cuộc gọi video nhóm", false);
        this.selfName = selfName;
        this.sink = sink;
        this.onLeave = onLeave;
        buildUi();
        refreshCameraChoices();
        setSize(1040, 760);
        setMinimumSize(new Dimension(780, 560));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (leaveButtonCanClose) onLeave.run(); }
        });
        statsTimer = new Timer(1000, e -> refreshStats());
        statsTimer.start();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(new Color(15, 23, 42));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.setOpaque(false);
        JLabel title = new JLabel("📞 Cuộc gọi video nhóm");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblClock.setForeground(Color.WHITE);
        lblClock.setFont(new Font("Segoe UI", Font.BOLD, 18));
        top.add(title, BorderLayout.WEST);
        top.add(lblClock, BorderLayout.EAST);
        JPanel status = new JPanel(new BorderLayout(0, 3)); status.setOpaque(false);
        lblStatus.setForeground(new Color(226, 232, 240));
        lblStats.setForeground(new Color(148, 163, 184));
        lblStats.setFont(new Font("Consolas", Font.PLAIN, 12));
        status.add(lblStatus, BorderLayout.NORTH); status.add(lblStats, BorderLayout.SOUTH);
        top.add(status, BorderLayout.SOUTH);

        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        JScrollPane gridScroll = new JScrollPane(grid);
        gridScroll.setBorder(BorderFactory.createEmptyBorder());
        gridScroll.getViewport().setBackground(new Color(15, 23, 42));
        addParticipant(selfName, true);

        eventLog.setEditable(false);
        eventLog.setLineWrap(true);
        eventLog.setWrapStyleWord(true);
        eventLog.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        eventLog.setBackground(new Color(30, 41, 59));
        eventLog.setForeground(new Color(203, 213, 225));
        JScrollPane logScroll = new JScrollPane(eventLog);
        logScroll.setPreferredSize(new Dimension(100, 75));
        logScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(51, 65, 85)), "Hoạt động thành viên"));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 9, 2)); controls.setOpaque(false);
        styleButton(btnMic, new Color(51, 65, 85));
        styleButton(btnCamera, new Color(51, 65, 85));
        styleButton(btnLeave, new Color(220, 38, 38));
        btnMic.addActionListener(e -> toggleMic());
        btnCamera.addActionListener(e -> toggleCamera());
        btnLeave.addActionListener(e -> onLeave.run());
        cameraSelector.setToolTipText("Đổi nguồn camera trong cuộc gọi");
        cameraSelector.addActionListener(e -> {
            CameraChoice choice = (CameraChoice) cameraSelector.getSelectedItem();
            if (choice != null && choice.index >= 0 && mediaStarted.get()) cameraCapture.select(choice.index, cameraOn.get());
        });
        controls.add(btnMic); controls.add(btnCamera); controls.add(cameraSelector); controls.add(btnLeave);

        JPanel bottom = new JPanel(new BorderLayout(0, 5)); bottom.setOpaque(false);
        bottom.add(logScroll, BorderLayout.CENTER); bottom.add(controls, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH); root.add(gridScroll, BorderLayout.CENTER); root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void styleButton(JButton button, Color color) {
        button.setBackground(color); button.setForeground(Color.WHITE); button.setOpaque(true);
        button.setFocusPainted(false); button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
    }

    void showCallWindow() { if (!isVisible()) setVisible(true); }
    void setStatus(String value) { SwingUtilities.invokeLater(() -> lblStatus.setText(value == null ? " " : value)); }
    void markActive() {
        if (callStartedAt == 0) callStartedAt = System.currentTimeMillis();
        setStatus("Đã kết nối · Âm thanh được ghép tại máy nhận");
        startMedia();
    }
    void setRtt(int value) { rttMillis = value; }
    boolean isMicOn() { return microphoneOn.get(); }
    boolean isCameraOn() { return cameraOn.get(); }

    void setParticipants(List<String> names) {
        if (names == null) return;
        SwingUtilities.invokeLater(() -> {
            LinkedHashSet<String> desired = new LinkedHashSet<>(); desired.add(selfName);
            for (String n : names) if (n != null && !n.isBlank()) desired.add(n);
            for (String existing : new ArrayList<>(tiles.keySet())) if (!desired.contains(existing)) removeParticipantUi(existing, false);
            for (String n : desired) addParticipant(n, n.equals(selfName));
            rebuildGrid();
        });
    }

    void participantJoined(String username) {
        if (username == null || username.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            if (!tiles.containsKey(username)) addParticipant(username, username.equals(selfName));
            addEvent(username + " đã tham gia cuộc gọi.");
            rebuildGrid();
        });
    }

    void participantLeft(String username, String reason) {
        if (username == null || username.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            removeParticipantUi(username, true);
            addEvent(username + " đã rời cuộc gọi" + ((reason == null || reason.isBlank()) ? "." : " · " + reason + "."));
            rebuildGrid();
        });
    }

    void setParticipantMediaState(String username, boolean mic, boolean camera) {
        Tile tile = tiles.get(username); if (tile == null) return;
        tile.setMediaState(mic, camera);
    }

    void acceptRemoteAudio(String username, byte[] ulaw) {
        if (stopping.get() || username == null || username.equals(selfName) || ulaw == null || ulaw.length != ULAW_BYTES) return;
        BlockingQueue<byte[]> q = audioQueues.computeIfAbsent(username, k -> new ArrayBlockingQueue<>(8));
        if (!q.offer(ulaw)) { q.poll(); q.offer(ulaw); }
        rxBytes.addAndGet(ulaw.length);
        if (rmsUlaw(ulaw) > 650) setSpeaking(username, true);
    }

    void acceptRemoteVideo(String username, byte[] jpeg) {
        if (stopping.get() || username == null || username.equals(selfName) || jpeg == null || jpeg.length == 0) return;
        latestJpegs.put(username, jpeg);
        rxBytes.addAndGet(jpeg.length);
    }

    void closeFromController(String reason) {
        if (!stopping.compareAndSet(false, true)) return;
        leaveButtonCanClose = false;
        if (statsTimer != null) statsTimer.stop();
        setStatus(reason);
        stopMedia();
        decodeExecutor.shutdownNow();
        dispose();
    }

    private void addParticipant(String username, boolean self) {
        if (username == null || username.isBlank() || tiles.containsKey(username)) return;
        Tile tile = new Tile(username, self);
        tiles.put(username, tile);
        if (!self) audioQueues.computeIfAbsent(username, k -> new ArrayBlockingQueue<>(8));
    }

    private void removeParticipantUi(String username, boolean log) {
        Tile tile = tiles.remove(username);
        audioQueues.remove(username); latestJpegs.remove(username);
        if (tile != null && log) { /* log is appended by the caller */ }
    }

    private void rebuildGrid() {
        grid.removeAll();
        List<Tile> ordered = new ArrayList<>(tiles.values());
        ordered.sort((a, b) -> {
            if (a.username.equals(selfName)) return b.username.equals(selfName) ? 0 : -1;
            if (b.username.equals(selfName)) return 1;
            return a.username.compareToIgnoreCase(b.username);
        });
        int count = Math.max(1, ordered.size());
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / cols);
        grid.setLayout(new GridLayout(rows, cols, 8, 8));
        for (Tile tile : ordered) grid.add(tile);
        for (int i = count; i < rows * cols; i++) grid.add(new JPanel());
        grid.revalidate(); grid.repaint();
    }

    private void addEvent(String text) {
        eventLog.append(String.format("%tT  %s%n", System.currentTimeMillis(), text));
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    private void setSpeaking(String username, boolean speaking) {
        Tile tile = tiles.get(username);
        if (tile != null) tile.setSpeaking(speaking);
    }

    private void toggleMic() {
        boolean value = !microphoneOn.get(); microphoneOn.set(value);
        btnMic.setText(value ? "🎙 Tắt mic" : "🔇 Bật mic");
        Tile self = tiles.get(selfName);
        if (self != null) self.setMediaState(value, cameraOn.get());
        // Capture loop closes the TargetDataLine as soon as mute is observed, then reopens
        // it when the user unmutes. Do not close it here while line.read() is blocked.
        sink.sendMediaState(value, cameraOn.get());
    }

    private void toggleCamera() {
        boolean value = !cameraOn.get(); cameraOn.set(value);
        if (!value) {
            cameraCapture.close();
            Tile self = tiles.get(selfName); if (self != null) { self.setImage(null, "Camera đã tắt"); self.setMediaState(microphoneOn.get(), false); }
        } else if (!cameraCapture.openSelected()) {
            cameraOn.set(false); value = false;
            Tile self = tiles.get(selfName); if (self != null) self.setMediaState(microphoneOn.get(), false);
            setStatus("Không mở được camera. Kiểm tra webcam-capture và quyền truy cập.");
        } else {
            Tile self = tiles.get(selfName); if (self != null) self.setMediaState(microphoneOn.get(), true);
        }
        btnCamera.setText(value ? "📷 Tắt camera" : "🚫 Bật camera");
        sink.sendMediaState(microphoneOn.get(), value);
    }

    private void refreshCameraChoices() {
        List<CameraChoice> choices = cameraCapture.listChoices();
        cameraSelector.removeAllItems();
        for (CameraChoice choice : choices) cameraSelector.addItem(choice);
        if (choices.isEmpty()) cameraSelector.addItem(new CameraChoice(-1, "Không tìm thấy camera"));
    }

    private void startMedia() {
        if (!mediaStarted.compareAndSet(false, true) || stopping.get()) return;
        cameraOn.set(cameraCapture.openSelected());
        if (!cameraOn.get()) {
            btnCamera.setText("🚫 Bật camera");
            Tile self = tiles.get(selfName); if (self != null) { self.setImage(null, "Camera đã tắt"); self.setMediaState(microphoneOn.get(), false); }
        }
        sink.sendMediaState(microphoneOn.get(), cameraOn.get());
        micThread = daemon("GroupCall-Microphone", this::captureAudioLoop);
        speakerThread = daemon("GroupCall-Mixer", this::playMixedAudioLoop);
        cameraThread = daemon("GroupCall-Camera", this::captureVideoLoop);
        micThread.start(); speakerThread.start(); cameraThread.start();
        if (decodeStarted.compareAndSet(false, true)) {
            decodeExecutor.scheduleAtFixedRate(this::decodeLatestFrames, 0, 33, TimeUnit.MILLISECONDS);
        }
    }

    private Thread daemon(String name, Runnable task) {
        Thread t = new Thread(task, name + "-" + selfName); t.setDaemon(true); return t;
    }

    private void captureAudioLoop() {
        TargetDataLine line = null;
        try {
            byte[] pcm = new byte[PCM_BYTES];
            while (!stopping.get()) {
                if (!microphoneOn.get()) {
                    if (line != null) {
                        if (microphoneLine == line) microphoneLine = null;
                        try { line.stop(); } catch (Exception ignored) { }
                        try { line.close(); } catch (Exception ignored) { }
                        line = null;
                    }
                    Thread.sleep(35);
                    continue;
                }
                if (line == null) {
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, PCM_FORMAT);
                    line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(PCM_FORMAT, PCM_BYTES * 8);
                    line.start();
                    microphoneLine = line;
                }
                int offset = 0;
                while (offset < pcm.length && !stopping.get() && microphoneOn.get()) {
                    int read = line.read(pcm, offset, pcm.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                if (stopping.get()) break;
                if (!microphoneOn.get() || offset != pcm.length) continue;
                byte[] ulaw = pcmToUlaw(pcm);
                if (rmsPcm(pcm) > 650) setSpeaking(selfName, true);
                sink.sendAudio(ulaw); txBytes.addAndGet(ulaw.length);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Exception ex) {
            if (!stopping.get() && microphoneOn.get()) {
                microphoneOn.set(false); SwingUtilities.invokeLater(() -> btnMic.setText("🔇 Bật mic"));
                sink.sendMediaState(false, cameraOn.get()); setStatus("Không mở được micro. Kiểm tra thiết bị/quyền micro.");
            }
        } finally {
            if (line != null) { if (microphoneLine == line) microphoneLine = null; try { line.stop(); } catch (Exception ignored) { } try { line.close(); } catch (Exception ignored) { } }
        }
    }

    private void closeMicrophoneLine() {
        TargetDataLine line = microphoneLine; microphoneLine = null;
        if (line != null) { try { line.stop(); } catch (Exception ignored) { } try { line.close(); } catch (Exception ignored) { } }
    }

    private void playMixedAudioLoop() {
        SourceDataLine line = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, PCM_FORMAT);
            line = (SourceDataLine) AudioSystem.getLine(info); line.open(PCM_FORMAT, PCM_BYTES * 12); line.start();
            byte[] silence = new byte[ULAW_BYTES];
            while (!stopping.get()) {
                int[] mixed = new int[ULAW_BYTES];
                for (Map.Entry<String, BlockingQueue<byte[]>> e : audioQueues.entrySet()) {
                    byte[] frame = e.getValue().poll();
                    if (frame == null) continue;
                    for (int i = 0; i < Math.min(ULAW_BYTES, frame.length); i++) mixed[i] += ulawToLinear(frame[i]);
                }
                byte[] pcm = new byte[PCM_BYTES];
                boolean hasAudio = false;
                for (int i = 0; i < mixed.length; i++) {
                    int sample = Math.max(-32768, Math.min(32767, mixed[i])); // saturating mix
                    if (sample != 0) hasAudio = true;
                    pcm[i * 2] = (byte) (sample & 0xff); pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
                }
                line.write(pcm, 0, pcm.length);
                // Keep the mixer paced at 40 ms per G.711 frame to avoid filling
                // the speaker buffer with startup silence and increasing latency.
                if (!hasAudio) Thread.yield();
                Thread.sleep(40);
            }
        } catch (Exception ex) { if (!stopping.get()) setStatus("Không mở được loa. Kiểm tra thiết bị âm thanh."); }
        finally { if (line != null) { try { line.stop(); } catch (Exception ignored) { } try { line.close(); } catch (Exception ignored) { } } }
    }

    private void captureVideoLoop() {
        long lastFrameAt = 0;
        while (!stopping.get()) {
            try {
                if (!cameraOn.get()) { Thread.sleep(100); continue; }
                if (!cameraCapture.isOpen() && !cameraCapture.openSelected()) { cameraOn.set(false); sink.sendMediaState(microphoneOn.get(), false); continue; }
                BufferedImage source = cameraCapture.getImage();
                if (source == null) { Thread.sleep(25); continue; }
                BufferedImage normalized = normalize(source);
                Tile self = tiles.get(selfName); if (self != null) self.setImage(normalized, null);
                long now = System.nanoTime();
                int fps = adaptiveFps(rttMillis); long interval = 1_000_000_000L / fps;
                if (lastFrameAt == 0 || now - lastFrameAt >= interval) {
                    int quality = adaptiveQuality(rttMillis); byte[] jpeg = encodeJpeg(normalized, quality);
                    if (jpeg.length < 300_000) { sink.sendVideo(jpeg, quality); txFrames.incrementAndGet(); txBytes.addAndGet(jpeg.length); }
                    lastFrameAt = now;
                } else Thread.sleep(Math.max(1, (interval - (now - lastFrameAt)) / 1_000_000L));
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            catch (Exception ex) { if (!stopping.get()) { setStatus("Lỗi đọc camera: " + ex.getClass().getSimpleName()); try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; } } }
        }
    }

    private void decodeLatestFrames() {
        if (stopping.get()) return;
        for (Map.Entry<String, byte[]> e : new ArrayList<>(latestJpegs.entrySet())) {
            String username = e.getKey(); byte[] token = latestJpegs.remove(username);
            if (token == null) continue;
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(token));
                if (image != null) {
                    Tile tile = tiles.get(username);
                    if (tile != null) tile.setImage(image, null);
                    rxFrames.incrementAndGet();
                }
            } catch (Exception ignored) { }
        }
    }

    private void refreshStats() {
        if (callStartedAt > 0) {
            long seconds = Math.max(0, (System.currentTimeMillis() - callStartedAt) / 1000);
            lblClock.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
        }
        long tx = txBytes.getAndSet(0), rx = rxBytes.getAndSet(0);
        long txF = txFrames.getAndSet(0), rxF = rxFrames.getAndSet(0);
        lblStats.setText(String.format(Locale.ROOT, "FPS gửi/nhận %d/%d · %d kbps · RTT %s ms",
                txF, rxF, Math.round((tx + rx) * 8f / 1000f), rttMillis < 0 ? "--" : String.valueOf(rttMillis)));
        // Speaker edges decay on each UI tick unless new audio arrives.
        for (Tile tile : tiles.values()) tile.decaySpeaking();
    }

    private void stopMedia() {
        stopping.set(true); mediaStarted.set(false); audioQueues.clear(); latestJpegs.clear();
        cameraCapture.close(); closeMicrophoneLine();
        if (micThread != null) micThread.interrupt(); if (speakerThread != null) speakerThread.interrupt(); if (cameraThread != null) cameraThread.interrupt();
    }

    private static int adaptiveFps(int rtt) { if (rtt < 0 || rtt <= 120) return 12; if (rtt <= 220) return 9; if (rtt <= 350) return 6; return 4; }
    private static int adaptiveQuality(int rtt) { if (rtt < 0 || rtt <= 120) return 78; if (rtt <= 220) return 62; if (rtt <= 350) return 42; return 25; }

    private static BufferedImage normalize(BufferedImage source) {
        int w = 480, h = 270; BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double scale = Math.max((double) w / source.getWidth(), (double) h / source.getHeight());
        int sw = (int) Math.ceil(source.getWidth() * scale), sh = (int) Math.ceil(source.getHeight() * scale);
        g.drawImage(source, (w - sw) / 2, (h - sh) / 2, sw, sh, null); g.dispose(); return result;
    }

    private static byte[] encodeJpeg(BufferedImage image, int quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg"); if (!writers.hasNext()) throw new IllegalStateException("Không có JPEG writer.");
        ImageWriter writer = writers.next(); ByteArrayOutputStream out = new ByteArrayOutputStream(12000);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios); ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) { param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); param.setCompressionQuality(Math.max(.25f, Math.min(.78f, quality / 100f))); }
            writer.write(null, new IIOImage(image, null, null), param); ios.flush(); return out.toByteArray();
        } finally { writer.dispose(); }
    }

    private static byte[] pcmToUlaw(byte[] pcm) {
        byte[] out = new byte[ULAW_BYTES];
        for (int i = 0; i < out.length; i++) { short sample = (short) (((pcm[i * 2 + 1]) << 8) | (pcm[i * 2] & 0xff)); out[i] = linearToUlaw(sample); }
        return out;
    }
    private static byte[] ulawToPcm(byte[] ulaw) {
        byte[] out = new byte[ulaw.length * 2]; for (int i = 0; i < ulaw.length; i++) { short v = ulawToLinear(ulaw[i]); out[i * 2] = (byte) (v & 0xff); out[i * 2 + 1] = (byte) ((v >>> 8) & 0xff); } return out;
    }
    private static byte linearToUlaw(short sample) {
        final int BIAS = 0x84, CLIP = 32635; int pcm = sample, mask;
        if (pcm < 0) { pcm = BIAS - pcm; mask = 0x7f; } else { pcm += BIAS; mask = 0xff; }
        if (pcm > CLIP) pcm = CLIP; int segment = 0; for (int v = pcm >> 7; v > 1; v >>= 1) segment++; if (segment > 7) segment = 7;
        int mantissa = (pcm >> (segment + 3)) & 0x0f; return (byte) (((segment << 4) | mantissa) ^ mask);
    }
    private static short ulawToLinear(byte value) {
        int u = (~value) & 0xff; int t = ((u & 0x0f) << 3) + 0x84; t <<= (u & 0x70) >> 4; return (short) ((u & 0x80) != 0 ? 0x84 - t : t - 0x84);
    }
    private static int rmsPcm(byte[] pcm) { long sum = 0; int n = pcm.length / 2; for (int i = 0; i < n; i++) { int s = (short) (((pcm[i * 2 + 1]) << 8) | (pcm[i * 2] & 0xff)); sum += (long) s * s; } return n == 0 ? 0 : (int) Math.sqrt(sum / n); }
    private static int rmsUlaw(byte[] ulaw) { long sum = 0; for (byte b : ulaw) { short s = ulawToLinear(b); sum += (long) s * s; } return ulaw.length == 0 ? 0 : (int) Math.sqrt(sum / ulaw.length); }

    private final class Tile extends JPanel {
        final String username; final boolean self; final JLabel title = new JLabel(); final Surface surface = new Surface(); final JLabel media = new JLabel();
        volatile long lastSpeakingAt;
        volatile boolean micOn = true, cameraOn = true;
        Tile(String username, boolean self) {
            super(new BorderLayout(0, 4)); this.username = username; this.self = self;
            setBackground(new Color(30, 41, 59)); setBorder(BorderFactory.createLineBorder(new Color(71, 85, 105), 2));
            title.setForeground(Color.WHITE); title.setFont(new Font("Segoe UI", Font.BOLD, 12)); title.setBorder(BorderFactory.createEmptyBorder(5, 7, 2, 7));
            media.setForeground(new Color(203, 213, 225)); media.setFont(new Font("Segoe UI", Font.PLAIN, 10)); media.setBorder(BorderFactory.createEmptyBorder(0, 7, 5, 7));
            add(title, BorderLayout.NORTH); add(surface, BorderLayout.CENTER); add(media, BorderLayout.SOUTH); refreshTitle();
            surface.setPlaceholder(self && !GroupCallWindow.this.cameraOn.get() ? "Camera đã tắt" : (self ? "Đang mở camera…" : "Đang chờ hình ảnh…"));
            setPreferredSize(new Dimension(300, 190));
        }
        void setImage(BufferedImage image, String placeholder) { surface.setImage(image, placeholder); if (image != null) cameraOn = true; }
        void setMediaState(boolean mic, boolean cam) { micOn = mic; cameraOn = cam; SwingUtilities.invokeLater(() -> { refreshTitle(); if (!cam) surface.setPlaceholder("Camera đã tắt"); }); }
        void setSpeaking(boolean active) {
            if (active) lastSpeakingAt = System.currentTimeMillis();
            SwingUtilities.invokeLater(this::repaintSpeaking);
        }
        void decaySpeaking() { if (System.currentTimeMillis() - lastSpeakingAt > 600) repaintSpeaking(); }
        void repaintSpeaking() {
            boolean speaking = System.currentTimeMillis() - lastSpeakingAt <= 600;
            setBorder(BorderFactory.createLineBorder(speaking ? new Color(34, 197, 94) : new Color(71, 85, 105), speaking ? 3 : 2));
        }
        void refreshTitle() {
            title.setText(username + (!micOn ? "  [mic tắt]" : ""));
            media.setText((micOn ? "Mic đang bật" : "Mic tắt") + "  ·  " + (cameraOn ? "Camera đang bật" : "Camera đã tắt"));
        }
    }

    private static final class Surface extends JPanel {
        private volatile BufferedImage image; private volatile String placeholder = "";
        Surface() { setBackground(new Color(2, 6, 23)); setPreferredSize(new Dimension(300, 180)); }
        void setImage(BufferedImage img, String text) { image = img; placeholder = text == null ? "" : text; repaint(); }
        void setPlaceholder(String text) { image = null; placeholder = text == null ? "" : text; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            BufferedImage current = image;
            if (current != null) { double scale = Math.min((double) getWidth() / current.getWidth(), (double) getHeight() / current.getHeight()); int w = (int) (current.getWidth() * scale), h = (int) (current.getHeight() * scale); g.drawImage(current, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null); }
            else { String text = placeholder == null || placeholder.isBlank() ? "Đang chờ hình ảnh…" : placeholder; g.setColor(new Color(148, 163, 184)); g.setFont(new Font("Segoe UI", Font.PLAIN, 12)); int sw = g.getFontMetrics().stringWidth(text); g.drawString(text, Math.max(8, (getWidth() - sw) / 2), getHeight() / 2); }
            g.dispose();
        }
    }

    private static final class CameraChoice { final int index; final String name; CameraChoice(int i, String n) { index = i; name = n; } @Override public String toString() { return name; } }

    private static final class CameraCapture {
        private Class<?> webcamClass; private List<Object> webcams = Collections.emptyList(); private Object active; private int selectedIndex;
        CameraCapture() { try { webcamClass = Class.forName("com.github.sarxos.webcam.Webcam"); Object result = webcamClass.getMethod("getWebcams").invoke(null); if (result instanceof List<?>) webcams = new ArrayList<>((List<?>) result); } catch (Throwable ignored) { webcamClass = null; webcams = Collections.emptyList(); } }
        List<CameraChoice> listChoices() { List<CameraChoice> out = new ArrayList<>(); for (int i = 0; i < webcams.size(); i++) { Object d = webcams.get(i); String n; try { n = String.valueOf(d.getClass().getMethod("getName").invoke(d)); } catch (Exception ex) { n = "Camera " + (i + 1); } out.add(new CameraChoice(i, n)); } if (out.isEmpty()) out.add(new CameraChoice(-1, "Cần thêm thư viện webcam-capture")); return out; }
        synchronized void select(int i, boolean open) { selectedIndex = i; close(); if (open) openSelected(); }
        synchronized boolean openSelected() { if (active != null) return true; if (webcamClass == null || webcams.isEmpty() || selectedIndex < 0 || selectedIndex >= webcams.size()) return false; try { active = webcams.get(selectedIndex); try { active.getClass().getMethod("setViewSize", Dimension.class).invoke(active, new Dimension(640, 480)); } catch (Exception ignored) { } active.getClass().getMethod("open").invoke(active); return true; } catch (Throwable ex) { active = null; return false; } }
        synchronized boolean isOpen() { if (active == null) return false; try { return Boolean.TRUE.equals(active.getClass().getMethod("isOpen").invoke(active)); } catch (Exception ex) { return true; } }
        synchronized BufferedImage getImage() { if (active == null) return null; try { Object img = active.getClass().getMethod("getImage").invoke(active); return img instanceof BufferedImage ? (BufferedImage) img : null; } catch (Throwable ex) { return null; } }
        synchronized void close() { Object old = active; active = null; if (old != null) try { old.getClass().getMethod("close").invoke(old); } catch (Throwable ignored) { } }
    }
}
