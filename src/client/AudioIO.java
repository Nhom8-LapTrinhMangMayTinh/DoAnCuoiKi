package client;

import javax.sound.sampled.*;
import java.io.Closeable;
import java.io.IOException;

/**
 * Lop am thanh dung chung (ghi am, goi video): micro, loa, nen G.711 mu-law.
 * Dinh dang: PCM 16 kHz, 16-bit, mono, little-endian (32 KB/s; mu-law con 16 KB/s).
 * Khi chay -Dgc.fakeAudio=true (hoac may khong co micro/loa khi kiem thu) dung nguon "tieng bip" tong hop va loa gia lap.
 */
public final class AudioIO {
    private AudioIO() {}

    public static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
    public static final int BYTES_PER_SEC = 32000;

    public interface In extends Closeable {
        /** Doc toi da len byte (chan, theo thoi gian thuc). Tra ve so byte, -1 neu da dong. */
        int read(byte[] b, int off, int len) throws IOException;
        boolean isFake();
    }

    public interface Out extends Closeable {
        void write(byte[] b, int off, int len);
        boolean isFake();
    }

    public static boolean fake() { return Boolean.getBoolean("gc.fakeAudio"); }

    public static boolean micAvailable() {
        if (fake()) return true;
        try { return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT)); } catch (Throwable t) { return false; }
    }

    public static boolean speakerAvailable() {
        try { return AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, FORMAT)); } catch (Throwable t) { return false; }
    }

    /** Mo micro. Nem LineUnavailableException neu khong co micro / bi ung dung khac chiem. */
    public static In openMic() throws LineUnavailableException {
        if (fake()) return new ToneIn();
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
        line.open(FORMAT, BYTES_PER_SEC / 5);
        line.start();
        return new In() {
            @Override public int read(byte[] b, int off, int len) { return line.read(b, off, len); }
            @Override public boolean isFake() { return false; }
            @Override public void close() { line.stop(); line.close(); }
        };
    }

    /** Mo loa. Khong co thiet bi -> tra ve loa gia lap (bo qua am thanh) de cuoc goi van chay. */
    public static Out openSpeaker() {
        if (!fake()) {
            try {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, FORMAT));
                line.open(FORMAT, BYTES_PER_SEC / 4);
                line.start();
                return new Out() {
                    @Override public void write(byte[] b, int off, int len) {
                        // chong tre tich luy: neu bo dem phat day (>~250ms) thi bo bot de khong bi lech tieng
                        if (line.available() < len) return;
                        line.write(b, off, len);
                    }
                    @Override public boolean isFake() { return false; }
                    @Override public void close() { line.drain(); line.stop(); line.close(); }
                };
            } catch (Throwable ignored) { }
        }
        return new Out() {
            @Override public void write(byte[] b, int off, int len) { }
            @Override public boolean isFake() { return true; }
            @Override public void close() { }
        };
    }

    /** Nguon am gia lap: tieng bip 440 Hz theo thoi gian thuc (de kiem thu/chup anh khi khong co micro). */
    static final class ToneIn implements In {
        private long samples = 0;
        private final long t0 = System.nanoTime();
        private volatile boolean closed;
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (closed) return -1;
            len = len & ~1;
            long due = t0 + (samples + len / 2) * 1_000_000_000L / 16000;   // pha theo dong ho de dung toc do that
            long wait = due - System.nanoTime();
            if (wait > 0) try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); } catch (InterruptedException e) { return -1; }
            for (int i = 0; i < len; i += 2) {
                double env = 0.55 + 0.45 * Math.sin(2 * Math.PI * 1.5 * samples / 16000.0);      // "nhip noi" 1.5 Hz
                short v = (short) (Math.sin(2 * Math.PI * 440 * samples / 16000.0) * 9000 * env);
                b[off + i] = (byte) v;
                b[off + i + 1] = (byte) (v >> 8);
                samples++;
            }
            return len;
        }
        @Override public boolean isFake() { return true; }
        @Override public void close() { closed = true; }
    }

    // ------------------------------------------------------------------ G.711 mu-law
    private static final int BIAS = 0x84, CLIP = 32635;

    public static byte[] muLawEncode(byte[] pcm, int off, int len) {
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            int s = (short) ((pcm[off + 2 * i] & 0xFF) | (pcm[off + 2 * i + 1] << 8));
            int sign = (s >> 8) & 0x80;
            if (sign != 0) s = -s;
            if (s > CLIP) s = CLIP;
            s += BIAS;
            int exp = 7;
            for (int mask = 0x4000; (s & mask) == 0 && exp > 0; exp--, mask >>= 1) { }
            int mant = (s >> (exp + 3)) & 0x0F;
            out[i] = (byte) ~(sign | (exp << 4) | mant);
        }
        return out;
    }

    public static byte[] muLawDecode(byte[] ulaw, int off, int len) {
        byte[] pcm = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            int u = ~ulaw[off + i] & 0xFF;
            int sign = u & 0x80, exp = (u >> 4) & 7, mant = u & 0x0F;
            int s = ((mant << 3) + BIAS) << exp;
            s -= BIAS;
            if (sign != 0) s = -s;
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    /** Muc am luong (RMS) 0..1 cua doan PCM 16-bit LE - ve thanh do muc tren giao dien. */
    public static double level(byte[] pcm, int off, int len) {
        long sum = 0;
        int n = len / 2;
        if (n == 0) return 0;
        for (int i = 0; i < n; i++) {
            int s = (short) ((pcm[off + 2 * i] & 0xFF) | (pcm[off + 2 * i + 1] << 8));
            sum += (long) s * s;
        }
        return Math.min(1.0, Math.sqrt(sum / (double) n) / 12000.0);
    }
}
