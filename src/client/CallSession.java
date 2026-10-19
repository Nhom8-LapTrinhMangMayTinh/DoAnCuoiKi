package client;

import common.Protocol;

import javax.sound.sampled.LineUnavailableException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dong co cuoc goi video 1-1 (khong co giao dien nen test duoc):
 *  - thu mic (16 kHz) -> nen mu-law -> goi 'A' moi 40 ms;
 *  - thu khung hinh -> JPEG thich ung chat luong/toc do khung (4-12 fps) -> goi 'V';
 *  - nhan: giai ma mu-law, dem chong giat 120 ms roi phat ra loa; JPEG giai ma tren luong rieng, chi giu khung moi nhat
 *    (nguoi nhan cham thi bo khung cu thay vi tre tich luy).
 */
public class CallSession implements ChatClient.MediaListener {

    public interface Ui {
        void onLocalFrame(BufferedImage img);
        void onRemoteFrame(BufferedImage img);
        void onStats(Stats s);
        void onError(String message);
    }

    public static class Stats {
        public double fpsOut, fpsIn, kbpsOut, kbpsIn;
        public int quality;
        public long framesSent, framesRecv, audioSent, audioRecv, audioDropped;
        public long sendMsMax;
    }

    private static final int MAX_FPS = 12, MIN_FPS = 4;
    private final ChatClient client;
    private final String callId;
    private final Ui ui;
    private volatile boolean running, micMuted, cameraOn;
    private volatile AudioIO.In mic;
    private volatile AudioIO.Out speaker;
    private volatile VideoSources.Source video;
    private final BlockingQueue<byte[]> playQ = new ArrayBlockingQueue<>(30);
    private volatile byte[] latestJpeg;
    private final Object jpegLock = new Object();
    private volatile float quality = 0.6f;
    private volatile int fps = MAX_FPS;
    private final AtomicLong bytesOut = new AtomicLong(), bytesIn = new AtomicLong();
    private final AtomicLong framesOut = new AtomicLong(), framesIn = new AtomicLong(), aOut = new AtomicLong(), aIn = new AtomicLong(), aDrop = new AtomicLong();
    private final AtomicLong sendMsMax = new AtomicLong();
    public volatile long lastRemoteFrameAt, lastRemoteAudioAt;
    private volatile long lastSwitch;
    private volatile String sourceName;

    public CallSession(ChatClient client, String callId, Ui ui) {
        this.client = client; this.callId = callId; this.ui = ui;
    }

    public String callId() { return callId; }

    public synchronized void start(String videoSource, boolean cameraOn) {
        if (running) return;
        running = true;
        this.cameraOn = cameraOn;
        this.sourceName = videoSource == null ? VideoSources.available().get(0) : videoSource;   // phai gan TRUOC khi chay cac luong
        client.setMediaListener(this);
        speaker = AudioIO.openSpeaker();
        thread("call-audio-send", this::audioSendLoop);
        thread("call-audio-play", this::audioPlayLoop);
        thread("call-video-send", this::videoSendLoop);
        thread("call-video-decode", this::decodeLoop);
        thread("call-stats", this::statsLoop);
    }

    private void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    public void setMicMuted(boolean m) { micMuted = m; announceState(); }
    public void setCameraOn(boolean on) { cameraOn = on; announceState(); }
    public boolean isMicMuted() { return micMuted; }
    public boolean isCameraOn() { return cameraOn; }

    private void announceState() { client.trySend(Protocol.join(Protocol.CALL_STATE, callId, cameraOn ? 1 : 0, micMuted ? 0 : 1)); }

    /** Doi nguon hinh (webcam <-> chia se man hinh) giua cuoc goi. */
    public void switchSource(String name) {
        VideoSources.Source old = video;
        sourceName = name;
        video = null;
        if (old != null) old.close();
        lastSwitch = System.currentTimeMillis();
    }

    public void stop() {
        running = false;
        if (client != null) client.setMediaListener(null);
        try { if (mic != null) mic.close(); } catch (IOException ignored) { }
        if (video != null) video.close();
        AudioIO.Out sp = speaker;
        if (sp != null) try { sp.close(); } catch (IOException ignored) { }
        synchronized (jpegLock) { jpegLock.notifyAll(); }
    }

    private void fail(String msg) {
        if (!running) return;
        running = false;
        ui.onError(msg);
    }

    // ------------------------------------------------------------------ gui
    private void audioSendLoop() {
        try {
            mic = AudioIO.openMic();
        } catch (LineUnavailableException | RuntimeException e) {
            ui.onError("Không mở được micro (" + e.getMessage() + ") - cuộc gọi tiếp tục không có tiếng của bạn");
            return;
        }
        byte[] buf = new byte[1280];
        try {
            while (running) {
                int n = mic.read(buf, 0, buf.length);
                if (n <= 0) break;
                if (micMuted) continue;
                byte[] enc = AudioIO.muLawEncode(buf, 0, n);
                client.sendMedia(callId, 'A', enc, enc.length);
                aOut.incrementAndGet();
                bytesOut.addAndGet(enc.length);
            }
        } catch (IOException e) { fail("Mất kết nối khi đang gọi"); }
    }

    private void videoSendLoop() {
        long next = System.nanoTime();
        while (running) {
            try {
                if (video == null) {
                    try { video = VideoSources.open(sourceName); }
                    catch (IOException e) { ui.onError(e.getMessage()); sourceName = VideoSources.available().get(VideoSources.available().size() - 1); Thread.sleep(300); continue; }
                }
                BufferedImage img = video.grab();
                if (img == null) { Thread.sleep(50); continue; }
                ui.onLocalFrame(img);
                if (cameraOn) {
                    byte[] jpg = VideoSources.jpeg(img, quality);
                    // thich ung chat luong theo kich thuoc khung (muc tieu 20-45 KB)
                    if (jpg.length > 45_000 && quality > 0.25f) quality -= 0.08f;
                    else if (jpg.length < 18_000 && quality < 0.75f) quality += 0.03f;
                    long t0 = System.nanoTime();
                    client.sendMedia(callId, 'V', jpg, jpg.length);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    sendMsMax.accumulateAndGet(ms, Math::max);
                    // thich ung toc do khung: ghi cham (mang yeu/VPN) -> giam fps; nhanh -> tang lai
                    if (ms > 120 && fps > MIN_FPS) fps--;
                    else if (ms < 25 && fps < MAX_FPS && Math.random() < 0.1) fps++;
                    framesOut.incrementAndGet();
                    bytesOut.addAndGet(jpg.length);
                }
                next += 1_000_000_000L / fps;
                long wait = next - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); else next = System.nanoTime();
            } catch (IOException e) { fail("Mất kết nối khi đang gọi"); return; }
            catch (InterruptedException e) { return; }
            catch (RuntimeException e) { ui.onError("Lỗi video: " + e); try { Thread.sleep(500); } catch (InterruptedException ie) { return; } }
        }
    }

    // ------------------------------------------------------------------ nhan (goi tu luong doc cua ChatClient)
    @Override
    public void onMedia(String id, char kind, byte[] data) {
        if (!running || !callId.equals(id)) return;
        bytesIn.addAndGet(data.length);
        if (kind == 'A') {
            lastRemoteAudioAt = System.currentTimeMillis();
            aIn.incrementAndGet();
            byte[] pcm = AudioIO.muLawDecode(data, 0, data.length);
            if (!playQ.offer(pcm)) { playQ.poll(); playQ.offer(pcm); aDrop.incrementAndGet(); }   // day -> bo goi cu nhat
        } else if (kind == 'V') {
            synchronized (jpegLock) { latestJpeg = data; jpegLock.notifyAll(); }
        }
    }

    private void audioPlayLoop() {
        try {
            while (running && playQ.size() < 3) { Thread.sleep(10); if (!running) return; }   // dem truoc ~120 ms chong giat
            while (running) {
                byte[] pcm = playQ.poll(200, TimeUnit.MILLISECONDS);
                if (pcm == null) continue;
                AudioIO.Out sp = speaker;
                if (sp != null) sp.write(pcm, 0, pcm.length);
            }
        } catch (InterruptedException ignored) { }
    }

    private void decodeLoop() {
        while (running) {
            byte[] data;
            synchronized (jpegLock) {
                while (running && latestJpeg == null) { try { jpegLock.wait(500); } catch (InterruptedException e) { return; } }
                data = latestJpeg;
                latestJpeg = null;
            }
            if (data == null) continue;
            BufferedImage img = VideoSources.decode(data);
            if (img != null) {
                framesIn.incrementAndGet();
                lastRemoteFrameAt = System.currentTimeMillis();
                ui.onRemoteFrame(img);
            }
        }
    }

    // ------------------------------------------------------------------ thong ke
    private void statsLoop() {
        long fo = 0, fi = 0, bo = 0, bi = 0, t0 = System.nanoTime();
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long now = System.nanoTime();
            double dt = (now - t0) / 1e9;
            t0 = now;
            Stats s = snapshot();
            s.fpsOut = (s.framesSent - fo) / dt; s.fpsIn = (s.framesRecv - fi) / dt;
            s.kbpsOut = (bytesOut.get() - bo) * 8 / 1000.0 / dt; s.kbpsIn = (bytesIn.get() - bi) * 8 / 1000.0 / dt;
            fo = s.framesSent; fi = s.framesRecv; bo = bytesOut.get(); bi = bytesIn.get();
            ui.onStats(s);
        }
    }

    public Stats snapshot() {
        Stats s = new Stats();
        s.framesSent = framesOut.get(); s.framesRecv = framesIn.get();
        s.audioSent = aOut.get(); s.audioRecv = aIn.get(); s.audioDropped = aDrop.get();
        s.quality = Math.round(quality * 100);
        s.sendMsMax = sendMsMax.get();
        return s;
    }

    public long bytesSent() { return bytesOut.get(); }
    public long bytesReceived() { return bytesIn.get(); }
}
