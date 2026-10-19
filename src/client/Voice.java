package client;

import javax.sound.sampled.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tin nhan thoai: ghi am ra WAV, nhan dang theo ten file voice_yyyyMMdd_HHmmss_<giay>s.wav, phat lai. */
public final class Voice {
    private Voice() {}

    public static final int MAX_SECONDS = 120;
    public static final int MIN_MILLIS = 600;
    private static final Pattern NAME = Pattern.compile("^voice_\\d{8}_\\d{6}_(\\d{1,3})s\\.wav$");

    public static boolean isVoice(String fileName) { return fileName != null && NAME.matcher(fileName).matches(); }

    /** Thoi luong (giay) ghi trong ten file; -1 neu khong phai tin thoai. */
    public static int durationOf(String fileName) {
        if (fileName == null) return -1;
        Matcher m = NAME.matcher(fileName);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    public static String mmss(int sec) { return String.format("%d:%02d", sec / 60, sec % 60); }

    /** Ghi PCM ra file WAV chuan. */
    public static void writeWav(byte[] pcm, int len, File f) throws IOException {
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm, 0, len), AudioIO.FORMAT, len / 2)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, f);
        }
    }

    /** Thoi luong that (giay, lam tron) cua 1 file WAV. */
    public static double wavSeconds(File f) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            return in.getFrameLength() / in.getFormat().getFrameRate();
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Không phải file WAV hợp lệ", e);
        }
    }

    // ------------------------------------------------------------------ ghi am
    public interface LevelListener { void onLevel(double level, long elapsedMs); }

    /** Phien ghi am: start() -> stop() tra ve file WAV (hoac null neu qua ngan/da huy). */
    public static class Recorder {
        private final LevelListener listener;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream(AudioIO.BYTES_PER_SEC * 10);
        private volatile boolean running, cancelled;
        private Thread thread;
        private AudioIO.In in;
        private volatile long startMs;
        private volatile boolean autoStopped;
        private Runnable onAutoStop;

        public Recorder(LevelListener l) { this.listener = l; }

        public void setOnAutoStop(Runnable r) { onAutoStop = r; }

        public void start() throws LineUnavailableException {
            in = AudioIO.openMic();
            running = true;
            startMs = System.currentTimeMillis();
            thread = new Thread(() -> {
                byte[] buf = new byte[1280];   // 40 ms
                try {
                    while (running) {
                        int n = in.read(buf, 0, buf.length);
                        if (n <= 0) break;
                        synchronized (pcm) { pcm.write(buf, 0, n); }
                        long el = System.currentTimeMillis() - startMs;
                        if (listener != null) listener.onLevel(AudioIO.level(buf, 0, n), el);
                        if (el >= MAX_SECONDS * 1000L) { autoStopped = true; running = false; if (onAutoStop != null) onAutoStop.run(); }
                    }
                } catch (IOException ignored) { }
            }, "voice-recorder");
            thread.setDaemon(true);
            thread.start();
        }

        public boolean autoStopped() { return autoStopped; }
        public long elapsedMs() { return System.currentTimeMillis() - startMs; }

        public void cancel() { cancelled = true; running = false; closeIn(); }

        private void closeIn() { try { if (in != null) in.close(); } catch (IOException ignored) { } }

        /** Dung ghi va luu thanh file voice_....wav trong dir. null neu qua ngan hoac da huy. */
        public File stop(File dir) throws IOException {
            running = false;
            closeIn();
            try { if (thread != null) thread.join(1000); } catch (InterruptedException ignored) { }
            byte[] data;
            int len;
            synchronized (pcm) { data = pcm.toByteArray(); len = data.length; }
            long ms = len * 1000L / AudioIO.BYTES_PER_SEC;
            if (cancelled || ms < MIN_MILLIS) return null;
            int sec = (int) Math.max(1, Math.round(ms / 1000.0));
            dir.mkdirs();
            File f = new File(dir, "voice_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_" + sec + "s.wav");
            writeWav(data, len, f);
            return f;
        }
    }

    // ------------------------------------------------------------------ phat lai
    public interface PlayListener { void onProgress(double fraction); void onFinished(boolean completed, String error); }

    /** Phat 1 file WAV tren luong rieng; chi 1 tin thoai phat tai 1 thoi diem (phat cai moi se dung cai cu). */
    public static class Player {
        private static volatile Player current;
        private volatile boolean stop;
        private final File file;
        private final PlayListener l;

        public Player(File f, PlayListener l) { this.file = f; this.l = l; }

        public static void stopAll() { Player p = current; if (p != null) p.stop = true; }

        public void play() {
            Player prev = current;
            if (prev != null) prev.stop = true;
            current = this;
            Thread t = new Thread(() -> {
                String err = null;
                boolean done = false;
                try (AudioInputStream raw = AudioSystem.getAudioInputStream(file)) {
                    AudioInputStream in = AudioSystem.getAudioInputStream(AudioIO.FORMAT, raw);
                    long total = Math.max(1, raw.getFrameLength() * 2);
                    AudioIO.Out out = AudioIO.openSpeaker();
                    byte[] buf = new byte[3200];
                    long played = 0;
                    long t0 = System.nanoTime();
                    int n;
                    try {
                        while (!stop && (n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            played += n;
                            if (out.isFake()) {   // khong co loa: tu giu nhip thoi gian thuc de thanh tien do chay dung toc do
                                long due = t0 + played * 1_000_000_000L / AudioIO.BYTES_PER_SEC;
                                long wait = due - System.nanoTime();
                                if (wait > 0) Thread.sleep(wait / 1_000_000);
                            }
                            if (l != null) l.onProgress(Math.min(1.0, played / (double) total));
                        }
                        done = !stop;
                    } finally { out.close(); }
                } catch (UnsupportedAudioFileException | IOException | InterruptedException | IllegalArgumentException e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    if (current == this) current = null;
                    if (l != null) l.onFinished(done, err);
                }
            }, "voice-player");
            t.setDaemon(true);
            t.start();
        }

        public void stop() { stop = true; }
    }
}
