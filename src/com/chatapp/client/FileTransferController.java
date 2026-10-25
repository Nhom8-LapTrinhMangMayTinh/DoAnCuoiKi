package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Quản lý gửi/tải tệp cho client ChatApp. Hỗ trợ tiến độ, tốc độ, ETA, hủy,
 * xác minh SHA-256 và tải thumbnail an toàn để xem trước ảnh trong hội thoại.
 */
public final class FileTransferController {

    public static final long MAX_FILE_BYTES = 1L * 1024L * 1024L * 1024L;
    private static final long MAX_PREVIEW_BYTES = 2L * 1024L * 1024L;
    private static final int SAFE_FRAME_BYTES = Math.max(1024,
            ((Protocol.MAX_FRAME_CHARS - 256) * 3) / 4);
    private static final int FALLBACK_CHUNK_BYTES = Math.max(1024,
            Math.min(Protocol.FILE_CHUNK_BYTES, SAFE_FRAME_BYTES));
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "dll", "com", "bat", "cmd", "ps1", "psm1", "vbs", "vbe",
            "js", "jse", "msi", "msp", "scr", "pif", "cpl", "jar", "apk",
            "appx", "appxbundle", "application", "appref-ms", "app", "gadget",
            "sh", "bash", "zsh", "csh", "command", "php", "pl", "py", "rb",
            "reg", "lnk", "hta", "wsf", "wsc", "wsh", "sct", "inf", "ins", "isp",
            "ocx", "sys", "drv", "so", "dylib", "deb", "rpm", "chm", "scf", "url",
            "iso", "html", "htm", "xhtml", "shtml", "mht", "mhtml", "svg", "svgz",
            "docm", "xlsm", "pptm", "xlam", "xll", "war", "ear");
    private static final Set<String> PREVIEW_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "mkv", "webm", "avi");

    private final JFrame owner;
    private final Function<String, Boolean> sendCommand;
    private final Consumer<String> notifier;
    private final BiConsumer<Long, Path> previewReady;
    private final Map<Long, String> knownNames = new ConcurrentHashMap<>();
    private final Map<Long, DownloadSession> downloads = new ConcurrentHashMap<>();
    private final Object uploadGuard = new Object();
    private volatile UploadJob pendingUpload;

    public FileTransferController(Function<String, Boolean> sendCommand,
                                  Consumer<String> notifier) {
        this(null, sendCommand, notifier, (id, path) -> { });
    }

    public FileTransferController(JFrame owner,
                                  Function<String, Boolean> sendCommand,
                                  Consumer<String> notifier,
                                  BiConsumer<Long, Path> previewReady) {
        this.owner = owner;
        this.sendCommand = sendCommand;
        this.notifier = notifier;
        this.previewReady = previewReady == null ? (id, path) -> { } : previewReady;
    }

    public void registerFile(long fileId, String fileName) {
        if (fileId > 0 && fileName != null && !fileName.isBlank()) {
            knownNames.put(fileId, safeName(fileName));
        }
    }

    public static boolean isSafeFileName(String name) {
        String value = safeName(name).toLowerCase(Locale.ROOT);
        if (value.isBlank()) return false;
        String[] segments = value.split("\\.");
        for (String segment : segments) {
            if (BLOCKED_EXTENSIONS.contains(segment)) return false;
        }
        String ext = extensionOf(value);
        return !BLOCKED_EXTENSIONS.contains(ext);
    }

    public static boolean isPreviewableImage(String name) {
        return isSafeFileName(name) && PREVIEW_EXTENSIONS.contains(extensionOf(name));
    }

    /** Video có thể tải vào cache rồi phát ngay trong hội thoại bằng JavaFX MediaPlayer. */
    public static boolean isPreviewableVideo(String name) {
        return isSafeFileName(name) && VIDEO_EXTENSIONS.contains(extensionOf(name));
    }

    public void chooseAndUpload(String scope, String target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn tệp hoặc hình ảnh để gửi (tối đa 1 GB)");
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        uploadFile(scope, target, chooser.getSelectedFile(), false);
    }

    /** Gửi bản ghi WAV thông qua đúng luồng upload file thông thường (quyền + SHA-256 + tiến độ). */
    public void uploadRecordedVoice(Path wavPath, String scope, String target) {
        if (wavPath == null || !Files.isRegularFile(wavPath)) {
            notify("Không tìm thấy bản ghi thoại để gửi.");
            return;
        }
        String name = safeName(wavPath.getFileName().toString());
        if (!VoiceMessageBubble.isVoiceMessageName(name)) {
            notify("Tên bản ghi thoại không hợp lệ.");
            try { Files.deleteIfExists(wavPath); } catch (IOException ignored) { }
            return;
        }
        uploadFile(scope, target, wavPath.toFile(), true);
    }

    private void uploadFile(String scope, String target, File file, boolean deleteSourceWhenDone) {
        if (file == null || !file.isFile()) {
            notify("Tệp được chọn không hợp lệ.");
            return;
        }
        String name = safeName(file.getName());
        if (!isSafeFileName(name)) {
            notify("Đã chặn loại tệp nguy hiểm: " + name);
            if (deleteSourceWhenDone) try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
            return;
        }
        long size = file.length();
        if (size <= 0L || size > MAX_FILE_BYTES) {
            notify("Tệp phải lớn hơn 0 byte và không vượt quá 1 GB. Dung lượng: " + formatBytes(size) + ".");
            if (deleteSourceWhenDone) try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
            return;
        }
        try {
            if (hasDangerousSignature(file.toPath())) {
                notify("Tệp có chữ ký nhị phân thực thi, đã bị chặn để bảo vệ người nhận.");
                if (deleteSourceWhenDone) Files.deleteIfExists(file.toPath());
                return;
            }
        } catch (IOException ex) {
            notify("Không thể kiểm tra tệp: " + ex.getMessage());
            if (deleteSourceWhenDone) try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
            return;
        }

        synchronized (uploadGuard) {
            if (pendingUpload != null) {
                notify("Đang có một tệp được truyền. Hãy hoàn tất hoặc hủy tệp đó trước.");
                if (deleteSourceWhenDone) try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
                return;
            }
            UploadJob job = new UploadJob(file, size, deleteSourceWhenDone);
            pendingUpload = job;
            job.ui = createProgressUi("Đang gửi: " + (VoiceMessageBubble.isVoiceMessageName(name)
                    ? "Tin nhắn thoại.wav" : name), () -> cancelUpload(job));
            job.ui.setStatus("Đang chuẩn bị và kiểm tra mã SHA-256...");
            Thread worker = new Thread(() -> uploadInBackground(job, scope, target),
                    "ChatApp-Upload-" + name);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void uploadInBackground(UploadJob job, String scope, String target) {
        boolean cancelled = false;
        try {
            job.sha256 = sha256(job.file.toPath(), job.cancelled::get);
            checkCancelled(job.cancelled);
            if (Files.size(job.file.toPath()) != job.expectedSize) {
                throw new IOException("Tệp đã thay đổi dung lượng trước khi gửi.");
            }
            String encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    job.file.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!Boolean.TRUE.equals(sendCommand.apply(Protocol.join(Protocol.SEND_FILE,
                    scope, target, encodedName, job.expectedSize, job.sha256)))) {
                throw new IOException("Không gửi được yêu cầu upload tới Server.");
            }
            if (!job.ready.await(30, TimeUnit.SECONDS)) {
                throw new IOException("Server không phản hồi yêu cầu gửi tệp.");
            }
            checkCancelled(job.cancelled);
            if (job.error.get() != null) throw new IOException(job.error.get());
            if (job.uploadId == null || job.uploadId.isBlank()) {
                throw new IOException("Server không cấp phiên upload hợp lệ.");
            }

            int chunkSize = job.chunkSize > 0 ? job.chunkSize : FALLBACK_CHUNK_BYTES;
            chunkSize = Math.min(chunkSize, SAFE_FRAME_BYTES);
            long sent = 0;
            long started = System.nanoTime();
            long lastUi = 0;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(job.file.toPath()))) {
                byte[] buffer = new byte[chunkSize];
                int sequence = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    checkCancelled(job.cancelled);
                    byte[] chunk = n == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, n);
                    String encodedChunk = Base64.getEncoder().encodeToString(chunk);
                    if (!Boolean.TRUE.equals(sendCommand.apply(Protocol.join(
                            Protocol.UPLOAD_CHUNK, job.uploadId, sequence++, encodedChunk)))) {
                        throw new IOException("Mất kết nối khi đang gửi tệp.");
                    }
                    sent += n;
                    long now = System.nanoTime();
                    if (now - lastUi >= TimeUnit.MILLISECONDS.toNanos(150)) {
                        job.ui.update("Đang gửi dữ liệu...", sent, job.expectedSize, started);
                        lastUi = now;
                    }
                }
            }
            checkCancelled(job.cancelled);
            if (sent != job.expectedSize) {
                throw new IOException("Số byte đã gửi không khớp kích thước ban đầu của tệp.");
            }
            job.ui.update("Đang xác minh và lưu tệp trên server...", job.expectedSize,
                    job.expectedSize, started);
            if (!Boolean.TRUE.equals(sendCommand.apply(Protocol.join(Protocol.UPLOAD_FINISH, job.uploadId)))) {
                throw new IOException("Không gửi được yêu cầu hoàn tất upload.");
            }
            if (!job.done.await(3, TimeUnit.MINUTES)) {
                throw new IOException("Quá thời gian chờ server kiểm tra SHA-256 và lưu tệp.");
            }
            checkCancelled(job.cancelled);
            if (job.error.get() != null) throw new IOException(job.error.get());
            job.ui.update("Hoàn tất", job.expectedSize, job.expectedSize, started);
            notify(VoiceMessageBubble.isVoiceMessageName(job.file.getName())
                    ? "Đã gửi tin nhắn thoại thành công."
                    : "Đã gửi tệp thành công: " + job.file.getName());
        } catch (CancellationException ex) {
            cancelled = true;
        } catch (Exception ex) {
            if (job.cancelled.get()) {
                cancelled = true;
            } else {
                if (job.uploadId != null && !job.uploadId.isBlank()) {
                    sendQuietly(Protocol.join(Protocol.UPLOAD_CANCEL, job.uploadId));
                }
                String reason = ex.getMessage() == null ? "Lỗi truyền tệp." : ex.getMessage();
                notify("Gửi tệp thất bại: " + reason);
            }
        } finally {
            if (cancelled) notify("Đã hủy gửi tệp: " + job.file.getName());
            job.ui.close();
            synchronized (uploadGuard) {
                if (pendingUpload == job) pendingUpload = null;
            }
            if (job.deleteSourceWhenDone) {
                try { Files.deleteIfExists(job.file.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    public void cancelCurrentUpload() {
        UploadJob job = pendingUpload;
        if (job != null) cancelUpload(job);
    }

    private void cancelUpload(UploadJob job) {
        if (job == null || !job.cancelled.compareAndSet(false, true)) return;
        job.ui.setCancelling();
        String id = job.uploadId;
        if (id != null && !id.isBlank()) sendQuietly(Protocol.join(Protocol.UPLOAD_CANCEL, id));
        job.error.compareAndSet(null, "Đã hủy truyền tệp.");
        job.ready.countDown();
        job.done.countDown();
    }

    /** Tải tệp gốc về đường dẫn do người dùng chọn. */
    public void requestDownload(long fileId) {
        requestDownload(fileId, knownNames.getOrDefault(fileId, "tep-" + fileId));
    }

    public void requestDownload(long fileId, String fileName) {
        if (fileId <= 0) {
            notify("Tệp không hợp lệ.");
            return;
        }
        String safe = safeName(fileName == null ? "tep-" + fileId : fileName);
        String suggestedSaveName = VoiceMessageBubble.isVoiceMessageName(safe)
                ? "Tin_nhan_thoai.wav" : safe;
        if (!isSafeFileName(safe)) {
            notify("Không cho phép tải loại tệp nguy hiểm: " + safe);
            return;
        }
        if (downloads.containsKey(fileId)) {
            notify("Tệp này đang được tải hoặc xem trước.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Lưu tệp về máy");
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), suggestedSaveName));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        File selected = chooser.getSelectedFile();
        if (selected == null) return;
        Path target = selected.toPath().toAbsolutePath().normalize();
        if (Files.isDirectory(target)) {
            notify("Bạn phải chọn tên tệp, không phải thư mục.");
            return;
        }
        if (Files.exists(target)) {
            int answer = JOptionPane.showConfirmDialog(owner,
                    "Tệp đã tồn tại. Bạn có muốn ghi đè không?",
                    "Xác nhận ghi đè", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) return;
        }

        DownloadSession session = new DownloadSession(fileId, target, false, safe);
        session.ui = createProgressUi("Đang tải: " + safe, () -> cancelDownload(fileId));
        if (downloads.putIfAbsent(fileId, session) != null) {
            session.ui.close();
            notify("Tệp này đang được tải hoặc xem trước.");
            return;
        }
        if (!sendCommandSafely(Protocol.join(Protocol.DOWNLOAD_FILE, fileId))) {
            downloads.remove(fileId, session);
            session.abort();
            session.ui.close();
            notify("Không gửi được yêu cầu tải tệp.");
        }
    }

    /** Tải thumbnail nhỏ và đã kiểm tra từ server, không tải toàn bộ ảnh gốc. */
    public void requestPreview(long fileId, String fileName) {
        if (fileId <= 0 || !isPreviewableImage(fileName)) return;
        knownNames.put(fileId, safeName(fileName));
        Path cache = previewPath(fileId);
        if (Files.isRegularFile(cache) && safeSize(cache) > 0) {
            previewReady.accept(fileId, cache);
            return;
        }
        DownloadSession session = new DownloadSession(fileId, cache, true, safeName(fileName));
        if (downloads.putIfAbsent(fileId, session) != null) {
            return;
        }
        try {
            Files.createDirectories(cache.getParent());
        } catch (IOException ex) {
            downloads.remove(fileId, session);
            notify("Không tạo được thư mục xem trước ảnh.");
            return;
        }
        if (!sendCommandSafely(Protocol.join(Protocol.DOWNLOAD_FILE, fileId, "PREVIEW"))) {
            downloads.remove(fileId, session);
            session.abort();
            notify("Không gửi được yêu cầu xem trước ảnh.");
        }
    }

    /** Bắt đầu tải video vào cache riêng để phát trực tiếp trong bubble chat. */
    public void requestInlineVideo(long fileId, String fileName) {
        if (fileId <= 0 || !isPreviewableVideo(fileName)) return;
        String safe = safeName(fileName);
        knownNames.put(fileId, safe);
        Path cache = videoPath(fileId, safe);
        if (Files.isRegularFile(cache) && safeSize(cache) > 0) {
            previewReady.accept(fileId, cache);
            return;
        }
        DownloadSession session = new DownloadSession(fileId, cache, false, safe, true);
        if (downloads.putIfAbsent(fileId, session) != null) {
            notify("Tệp này đang được tải; vui lòng đợi hoàn tất.");
            return;
        }
        try {
            Files.createDirectories(cache.getParent());
            session.ui = createProgressUi("Đang tải video để phát trong chat: " + safe,
                    () -> cancelDownload(fileId));
        } catch (Exception ex) {
            downloads.remove(fileId, session);
            session.abort();
            notify("Không thể chuẩn bị bộ nhớ đệm video.");
            return;
        }
        if (!sendCommandSafely(Protocol.join(Protocol.DOWNLOAD_FILE, fileId))) {
            downloads.remove(fileId, session);
            session.abort();
            if (session.ui != null) session.ui.close();
            notify("Không gửi được yêu cầu tải video.");
        }
    }

    /** Tải WAV vào cache riêng để phát trong bubble; server vẫn xác minh quyền và SHA-256. */
    public void requestInlineAudio(long fileId, String fileName) {
        if (fileId <= 0 || !VoiceMessageBubble.isVoiceMessageName(fileName)) return;
        String safe = safeName(fileName);
        knownNames.put(fileId, safe);
        Path cache = audioPath(fileId);
        if (Files.isRegularFile(cache) && safeSize(cache) > 44L) {
            previewReady.accept(fileId, cache);
            return;
        }
        DownloadSession session = new DownloadSession(fileId, cache, false, safe, true);
        if (downloads.putIfAbsent(fileId, session) != null) return;
        try {
            Files.createDirectories(cache.getParent());
            session.ui = createProgressUi("Đang tải tin nhắn thoại…", () -> cancelDownload(fileId));
        } catch (Exception ex) {
            downloads.remove(fileId, session);
            session.abort();
            VoiceMessageBubble.onAudioFailure(fileId);
            notify("Không thể chuẩn bị bộ nhớ đệm cho tin nhắn thoại.");
            return;
        }
        if (!sendCommandSafely(Protocol.join(Protocol.DOWNLOAD_FILE, fileId))) {
            downloads.remove(fileId, session);
            session.abort();
            if (session.ui != null) session.ui.close();
            VoiceMessageBubble.onAudioFailure(fileId);
            notify("Không gửi được yêu cầu tải tin nhắn thoại.");
        }
    }

    /** Trả đường dẫn file đã cache để phát; file thoại chỉ được dùng sau khi kiểm tra SHA-256. */
    public Path getPreviewPath(long fileId) {
        Path image = previewPath(fileId);
        if (Files.isRegularFile(image) && safeSize(image) > 0) return image;
        String name = knownNames.get(fileId);
        if (isPreviewableVideo(name)) {
            Path video = videoPath(fileId, name);
            if (Files.isRegularFile(video) && safeSize(video) > 0) return video;
        }
        if (VoiceMessageBubble.isVoiceMessageName(name)) {
            Path audio = audioPath(fileId);
            if (Files.isRegularFile(audio) && safeSize(audio) > 44L) return audio;
        }
        return null;
    }

    private Path previewPath(long fileId) {
        return Path.of(System.getProperty("user.home", "."), ".chatapp", "previews",
                "file-" + fileId + ".jpg").toAbsolutePath().normalize();
    }

    private Path videoPath(long fileId, String fileName) {
        String ext = extensionOf(safeName(fileName));
        if (ext.isBlank() || !VIDEO_EXTENSIONS.contains(ext)) ext = "mp4";
        return Path.of(System.getProperty("user.home", "."), ".chatapp", "videos",
                "file-" + fileId + "." + ext).toAbsolutePath().normalize();
    }

    private Path audioPath(long fileId) {
        return Path.of(System.getProperty("user.home", "."), ".chatapp", "audio",
                "file-" + fileId + ".wav").toAbsolutePath().normalize();
    }

    public void cancelDownload(long fileId) {
        DownloadSession session = downloads.remove(fileId);
        if (session == null) return;
        session.cancelled.set(true);
        sendQuietly(Protocol.join(Protocol.DOWNLOAD_CANCEL, fileId));
        session.abort();
        if (session.ui != null) session.ui.close();
        if (VoiceMessageBubble.isVoiceMessageName(session.originalName)) {
            VoiceMessageBubble.onAudioFailure(fileId);
        }
        notify(session.previewOnly ? "Đã hủy xem trước ảnh." : "Đã hủy tải tệp.");
    }

    public boolean isDownloadPacket(String command) {
        return Protocol.DOWNLOAD_BEGIN.equals(command)
                || Protocol.DOWNLOAD_CHUNK.equals(command)
                || Protocol.DOWNLOAD_DONE.equals(command)
                || Protocol.DOWNLOAD_FAIL.equals(command);
    }

    /** Xử lý các gói transfer trên reader thread để giữ đúng thứ tự chunk. */
    public void handleServerMessage(String[] p) {
        if (p == null || p.length == 0) return;
        try {
            switch (p[0]) {
                case Protocol.UPLOAD_READY:
                    handleUploadReady(p);
                    break;
                case Protocol.UPLOAD_REJECT:
                    handleUploadReject(p);
                    break;
                case Protocol.UPLOAD_DONE:
                    handleUploadDone(p);
                    break;
                case Protocol.DOWNLOAD_BEGIN:
                    handleDownloadBegin(p);
                    break;
                case Protocol.DOWNLOAD_CHUNK:
                    handleDownloadChunk(p);
                    break;
                case Protocol.DOWNLOAD_DONE:
                    handleDownloadDone(p);
                    break;
                case Protocol.DOWNLOAD_FAIL:
                    handleDownloadFail(p);
                    break;
                default:
                    break;
            }
        } catch (Exception ex) {
            long id = p.length > 1 ? parseLongOrZero(p[1]) : 0;
            DownloadSession session = downloads.remove(id);
            if (session != null) {
                session.cancelled.set(true);
                sendQuietly(Protocol.join(Protocol.DOWNLOAD_CANCEL, id));
                session.abort();
                if (session.ui != null) session.ui.close();
                if (VoiceMessageBubble.isVoiceMessageName(session.originalName)) {
                    VoiceMessageBubble.onAudioFailure(id);
                }
            }
            notify("Lỗi xử lý truyền tệp: "
                    + (ex.getMessage() == null ? "Dữ liệu không hợp lệ." : ex.getMessage()));
        }
    }

    private void handleUploadReady(String[] p) {
        UploadJob job = pendingUpload;
        if (job == null) return;
        if (p.length < 3) {
            job.error.set("Phản hồi upload từ server không hợp lệ.");
            job.ready.countDown();
            return;
        }
        job.uploadId = p[1];
        try { job.chunkSize = Integer.parseInt(p[2]); }
        catch (NumberFormatException ex) { job.chunkSize = FALLBACK_CHUNK_BYTES; }
        if (job.chunkSize <= 0 || job.chunkSize > SAFE_FRAME_BYTES) job.chunkSize = FALLBACK_CHUNK_BYTES;
        if (job.cancelled.get()) sendQuietly(Protocol.join(Protocol.UPLOAD_CANCEL, job.uploadId));
        job.ready.countDown();
    }

    private void handleUploadReject(String[] p) {
        UploadJob job = pendingUpload;
        if (job == null) {
            notify("Server từ chối tệp: " + (p.length > 2 ? p[2] : "Không rõ nguyên nhân."));
            return;
        }
        String id = p.length > 1 ? p[1] : "";
        if (!id.isBlank() && job.uploadId != null && !job.uploadId.equals(id)) return;
        String reason = p.length > 2 ? p[2] : "Server từ chối tệp.";
        job.error.set(reason);
        job.ready.countDown();
        job.done.countDown();
    }

    private void handleUploadDone(String[] p) {
        UploadJob job = pendingUpload;
        if (job == null) return;
        if (p.length >= 2 && job.uploadId != null && !job.uploadId.equals(p[1])) return;
        job.done.countDown();
    }

    private void handleDownloadBegin(String[] p) throws Exception {
        if (p.length < 5) throw new IOException("Gói DOWNLOAD_BEGIN thiếu trường.");
        long id = Long.parseLong(p[1]);
        DownloadSession session = downloads.get(id);
        if (session == null || session.cancelled.get()) return;

        String name = safeName(new String(Base64.getDecoder().decode(p[2]),
                java.nio.charset.StandardCharsets.UTF_8));
        long size = Long.parseLong(p[3]);
        String sha = p[4].toLowerCase(Locale.ROOT);
        boolean previewFlag = p.length > 5 && "PREVIEW".equalsIgnoreCase(p[5]);
        if (session.previewOnly != previewFlag) {
            throw new IOException("Loại truyền tải không khớp (tệp gốc/xem trước).");
        }
        long maxSize = session.previewOnly ? MAX_PREVIEW_BYTES : MAX_FILE_BYTES;
        if (size < 0 || size > maxSize || !sha.matches("[0-9a-f]{64}")) {
            throw new IOException("Metadata hoặc kích thước tệp tải xuống không hợp lệ.");
        }
        if (!session.previewOnly && !isSafeFileName(name)) {
            throw new IOException("Server trả về loại tệp bị chặn.");
        }
        session.fileName = name;
        session.expectedSize = size;
        session.expectedSha = sha;
        Path parent = session.target.getParent();
        if (parent != null) Files.createDirectories(parent);
        session.part = session.target.resolveSibling(session.target.getFileName().toString() + ".part");
        session.output = new BufferedOutputStream(Files.newOutputStream(session.part,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        session.started = true;
        session.startedNanos = System.nanoTime();
        if (session.ui != null) session.ui.update("Đang nhận dữ liệu...", 0, size, session.startedNanos);
    }

    private void handleDownloadChunk(String[] p) throws Exception {
        if (p.length < 4) throw new IOException("Gói DOWNLOAD_CHUNK thiếu trường.");
        long id = Long.parseLong(p[1]);
        DownloadSession session = downloads.get(id);
        if (session == null || session.cancelled.get()) return;
        byte[] bytes = Base64.getDecoder().decode(p[3]);
        if (bytes.length > Protocol.FILE_CHUNK_BYTES) throw new IOException("Chunk tải xuống quá lớn.");
        synchronized (session) {
            if (session.cancelled.get()) return;
            if (session.output == null) throw new IOException("Nhận chunk trước DOWNLOAD_BEGIN.");
            int sequence = Integer.parseInt(p[2]);
            if (sequence != session.nextSequence++) throw new IOException("Thứ tự chunk tải xuống không hợp lệ.");
            if (session.received + bytes.length > session.expectedSize) throw new IOException("Dữ liệu vượt kích thước tệp.");
            session.output.write(bytes);
            session.received += bytes.length;
            long now = System.nanoTime();
            if (session.ui != null && (now - session.lastUiNanos >= TimeUnit.MILLISECONDS.toNanos(150)
                    || session.received == session.expectedSize)) {
                session.ui.update("Đang tải dữ liệu...", session.received,
                        session.expectedSize, session.startedNanos);
                session.lastUiNanos = now;
            }
        }
    }

    private void handleDownloadDone(String[] p) throws Exception {
        if (p.length < 2) throw new IOException("Gói DOWNLOAD_DONE thiếu ID.");
        long id = Long.parseLong(p[1]);
        DownloadSession session = downloads.get(id);
        if (session == null || !downloads.remove(id, session)) return;
        try {
            synchronized (session) {
                if (session.output != null) {
                    session.output.flush();
                    session.output.close();
                    session.output = null;
                }
            }
            if (!session.started || session.part == null) throw new IOException("Server chưa gửi metadata tệp.");
            if (session.received != session.expectedSize) throw new IOException("Kích thước tệp tải xuống không khớp.");
            String actualSha = sha256(session.part, session.cancelled::get);
            if (!actualSha.equalsIgnoreCase(session.expectedSha)) {
                throw new IOException("SHA-256 không khớp; tệp có thể đã hỏng hoặc bị sửa đổi.");
            }
            checkCancelled(session.cancelled);
            try {
                Files.move(session.part, session.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(session.part, session.target, StandardCopyOption.REPLACE_EXISTING);
            }
            knownNames.put(id, session.originalName);
            if (session.previewOnly || session.autoPreview) {
                previewReady.accept(id, session.target);
                if (session.autoPreview && VoiceMessageBubble.isVoiceMessageName(session.originalName)) {
                    notify("Tin nhắn thoại đã tải và xác minh SHA-256.");
                } else {
                    notify(session.autoPreview
                            ? "Video đã tải và xác minh SHA-256. Bạn có thể phát trong đoạn chat."
                            : "Đã tải và xác minh SHA-256 ảnh xem trước: " + session.originalName);
                }
            } else {
                if (session.ui != null) session.ui.update("Hoàn tất", session.expectedSize,
                        session.expectedSize, session.startedNanos);
                notify("Đã tải và xác minh SHA-256 tệp: " + session.target);
            }
        } catch (Exception ex) {
            session.abort();
            if (VoiceMessageBubble.isVoiceMessageName(session.originalName)) {
                VoiceMessageBubble.onAudioFailure(id);
            }
            throw ex;
        } finally {
            if (session.ui != null) session.ui.close();
        }
    }

    private void handleDownloadFail(String[] p) {
        long id = p.length > 1 ? parseLongOrZero(p[1]) : 0;
        String message = p.length > 2 ? p[2] : "Tải tệp thất bại.";
        DownloadSession session = downloads.remove(id);
        if (session != null) {
            session.abort();
            if (session.ui != null) session.ui.close();
        }
        VoiceMessageBubble.onAudioFailure(id);
        notify("Tải tệp thất bại: " + message);
    }

    private void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) throw new CancellationException("Đã hủy truyền tệp.");
    }

    private static final class UploadJob {
        final File file;
        final long expectedSize;
        final boolean deleteSourceWhenDone;
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile String uploadId;
        volatile int chunkSize;
        volatile String sha256;
        volatile TransferUi ui;
        UploadJob(File file, long expectedSize, boolean deleteSourceWhenDone) {
            this.file = file;
            this.expectedSize = expectedSize;
            this.deleteSourceWhenDone = deleteSourceWhenDone;
        }
    }

    private static final class DownloadSession {
        final long id;
        final Path target;
        final boolean previewOnly;
        final boolean autoPreview;
        final String originalName;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile Path part;
        volatile String fileName = "";
        volatile long expectedSize;
        volatile String expectedSha;
        volatile long received;
        volatile int nextSequence;
        volatile boolean started;
        volatile long startedNanos;
        volatile long lastUiNanos;
        volatile OutputStream output;
        volatile TransferUi ui;

        DownloadSession(long id, Path target, boolean previewOnly, String originalName) {
            this(id, target, previewOnly, originalName, false);
        }
        DownloadSession(long id, Path target, boolean previewOnly, String originalName, boolean autoPreview) {
            this.id = id;
            this.target = target;
            this.previewOnly = previewOnly;
            this.autoPreview = autoPreview;
            this.originalName = originalName == null ? "" : originalName;
        }
        void abort() {
            synchronized (this) {
                if (output != null) {
                    try { output.close(); } catch (IOException ignored) { }
                    output = null;
                }
            }
            if (part != null) {
                try { Files.deleteIfExists(part); } catch (IOException ignored) { }
            }
        }
    }

    private final class TransferUi {
        private final JDialog dialog;
        private final JLabel statusLabel;
        private final JLabel statsLabel;
        private final JProgressBar bar;
        private final JButton cancelButton;

        TransferUi(String title, Runnable cancelAction) {
            if (GraphicsEnvironment.isHeadless()) {
                dialog = null;
                statusLabel = null;
                statsLabel = null;
                bar = null;
                cancelButton = null;
                return;
            }
            dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout(8, 9));
            content.setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));
            statusLabel = new JLabel("Đang chuẩn bị...");
            statsLabel = new JLabel("0% • Đang tính tốc độ...");
            bar = new JProgressBar(0, 100);
            bar.setStringPainted(true);
            cancelButton = new JButton("Hủy truyền");
            cancelButton.addActionListener(e -> {
                cancelButton.setEnabled(false);
                statusLabel.setText("Đang hủy truyền...");
                cancelAction.run();
            });
            JPanel bottom = new JPanel(new BorderLayout(8, 0));
            bottom.add(statsLabel, BorderLayout.CENTER);
            bottom.add(cancelButton, BorderLayout.EAST);
            content.add(statusLabel, BorderLayout.NORTH);
            content.add(bar, BorderLayout.CENTER);
            content.add(bottom, BorderLayout.SOUTH);
            dialog.setContentPane(content);
            dialog.setMinimumSize(new Dimension(470, 120));
            dialog.pack();
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
        }

        void setStatus(String text) {
            if (dialog == null) return;
            SwingUtilities.invokeLater(() -> {
                if (dialog.isDisplayable()) {
                    statusLabel.setText(text == null ? "Đang truyền..." : text);
                    bar.setIndeterminate(text != null && (text.contains("SHA-256") || text.contains("chuẩn bị")));
                }
            });
        }

        void setCancelling() { setStatus("Đang hủy truyền... chờ dừng an toàn."); }

        void update(String stage, long done, long total, long startedNanos) {
            if (dialog == null) return;
            final int percent = total <= 0 ? 100 : (int) Math.min(100, Math.max(0, done * 100.0 / total));
            final double seconds = Math.max(0.001, (System.nanoTime() - startedNanos) / 1_000_000_000.0);
            final double bytesPerSecond = done / seconds;
            final long remainingSeconds = bytesPerSecond > 1 && total > done
                    ? (long) Math.ceil((total - done) / bytesPerSecond) : 0;
            final String stats = percent + "% • " + formatBytesPerSecond(bytesPerSecond)
                    + (total > done && bytesPerSecond <= 1 ? " • Đang tính thời gian còn lại"
                    : " • Còn " + formatDuration(remainingSeconds));
            SwingUtilities.invokeLater(() -> {
                if (!dialog.isDisplayable()) return;
                statusLabel.setText(stage == null ? "Đang truyền..." : stage);
                bar.setIndeterminate(false);
                bar.setValue(percent);
                bar.setString(percent + "%");
                statsLabel.setText(stats);
            });
        }

        void close() {
            if (dialog != null) SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    private TransferUi createProgressUi(String title, Runnable cancelAction) {
        if (SwingUtilities.isEventDispatchThread()) return new TransferUi(title, cancelAction);
        AtomicReference<TransferUi> reference = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> reference.set(new TransferUi(title, cancelAction)));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể tạo cửa sổ tiến độ truyền tệp.", ex);
        }
        return reference.get();
    }

    private boolean sendCommandSafely(String command) {
        try { return Boolean.TRUE.equals(sendCommand.apply(command)); }
        catch (RuntimeException ex) { return false; }
    }

    private void sendQuietly(String command) {
        try { sendCommand.apply(command); } catch (RuntimeException ignored) { }
    }

    private void notify(String message) {
        if (notifier != null && message != null) SwingUtilities.invokeLater(() -> notifier.accept(message));
    }

    public void close() {
        cancelCurrentUpload();
        for (Map.Entry<Long, DownloadSession> entry : downloads.entrySet()) {
            cancelDownload(entry.getKey());
        }
    }

    private static String sha256(Path path, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private static boolean hasDangerousSignature(Path path) throws IOException {
        byte[] head = new byte[8];
        int count;
        try (InputStream in = Files.newInputStream(path)) { count = in.read(head); }
        if (count >= 2 && head[0] == 'M' && head[1] == 'Z') return true; // PE/Windows executable
        if (count >= 4 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') return true;
        if (count >= 4) {
            int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16)
                    | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
            if (magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCEFAEDFE
                    || magic == 0xCFFAEDFE || magic == 0xCAFEBABE) return true;
        }
        return false;
    }

    private static String safeName(String name) {
        if (name == null) return "tep-tin";
        String value = name.replace('\\', '/');
        int index = value.lastIndexOf('/');
        if (index >= 0) value = value.substring(index + 1);
        value = value.replace('|', '_').replace('\n', '_').replace('\r', '_')
                .replace('\0', '_').trim();
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value.isBlank() ? "tep-tin" : value;
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        String value = name.trim().toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot < 0 || dot == value.length() - 1 ? "" : value.substring(dot + 1);
    }

    private static long parseLongOrZero(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0; }
    }

    private static long safeSize(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return 0; }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatBytesPerSecond(double bytesPerSecond) {
        if (!Double.isFinite(bytesPerSecond) || bytesPerSecond <= 0) return "0 B/s";
        if (bytesPerSecond < 1024) return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1024);
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        if (minutes >= 60) return String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, remainder);
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }
}
