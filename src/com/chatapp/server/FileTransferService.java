package com.chatapp.server;

import com.chatapp.database.DatabaseManager;
import com.chatapp.protocol.Protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quản lý upload/download file cho giao thức TCP theo dòng của ChatApp.
 * File được chia khối Base64; metadata file đồng thời được lưu trong chat_files
 * và messages để file xuất hiện trong lịch sử chat như một tin nhắn FILE.
 */
public final class FileTransferService {

    private static final long MAX_FILE_BYTES = 1L * 1024L * 1024L * 1024L;
    private static final long MAX_PREVIEW_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_PREVIEW_SOURCE_PIXELS = 12_000_000L;
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
    private static final int SAFE_FRAME_BYTES = Math.max(
            1024, ((Protocol.MAX_FRAME_CHARS - 256) * 3) / 4);
    private static final int CHUNK_BYTES = Math.max(1024,
            Math.min(Protocol.FILE_CHUNK_BYTES, SAFE_FRAME_BYTES));
    private static final Path FILE_DIR = Path.of("data", "files");

    private static final FileTransferService INSTANCE = new FileTransferService();
    private final ConcurrentHashMap<String, UploadSession> uploads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DownloadKey, DownloadSession> downloads = new ConcurrentHashMap<>();

    public static FileTransferService getInstance() {
        return INSTANCE;
    }

    private FileTransferService() {
        ensureMetadataTable();
    }

    private static final class UploadSession {
        final ClientHandler client;
        final String username;
        final String scope;
        final int roomId;
        final String receiver;
        final String fileName;
        final long expectedSize;
        final String expectedSha;
        final Path tempPath;
        OutputStream output;
        long received;
        int nextChunk;

        UploadSession(ClientHandler client, String username, String scope,
                      int roomId, String receiver, String fileName,
                      long expectedSize, String expectedSha, Path tempPath,
                      OutputStream output) {
            this.client = client;
            this.username = username;
            this.scope = scope;
            this.roomId = roomId;
            this.receiver = receiver;
            this.fileName = fileName;
            this.expectedSize = expectedSize;
            this.expectedSha = expectedSha;
            this.tempPath = tempPath;
            this.output = output;
        }
    }

    private void ensureMetadataTable() {
        try {
            Files.createDirectories(FILE_DIR);
            try (Connection conn = DatabaseManager.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS chat_files ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "sender TEXT, uploader TEXT, receiver TEXT, room_id INTEGER, "
                        + "scope TEXT, file_name TEXT, original_name TEXT, "
                        + "file_path TEXT, stored_path TEXT, file_size INTEGER, "
                        + "sha256 TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            }
            // Hỗ trợ database đã tồn tại từ phiên bản cũ của ứng dụng.
            ensureColumn("sender", "TEXT");
            ensureColumn("uploader", "TEXT");
            ensureColumn("receiver", "TEXT");
            ensureColumn("room_id", "INTEGER");
            ensureColumn("scope", "TEXT");
            ensureColumn("file_name", "TEXT");
            ensureColumn("original_name", "TEXT");
            // Một số database cũ dùng stored_name NOT NULL để lưu tên file vật lý.
            // Thêm cột nullable cho database mới và tương thích database cũ.
            ensureColumn("stored_name", "TEXT");
            ensureColumn("file_path", "TEXT");
            ensureColumn("stored_path", "TEXT");
            ensureColumn("file_size", "INTEGER");
            ensureColumn("sha256", "TEXT");
            ensureColumn("created_at", "DATETIME");
        } catch (Exception e) {
            System.err.println("[FileTransfer] Không khởi tạo được metadata file: " + e.getMessage());
        }
    }

    private void ensureColumn(String name, String type) throws Exception {
        boolean found = false;
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(chat_files)")) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            try (Connection conn = DatabaseManager.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE chat_files ADD COLUMN " + name + " " + type);
            }
        }
    }

    /** SEND_FILE|GROUP/PRIVATE|roomId/receiver|urlBase64FileName|size|sha256 */
    public void handleSendFile(ClientHandler client, String username, String[] parts) {
        if (username == null || !client.requireLogin()) return;
        if (parts.length < 6) {
            reject(client, "", "Thiếu thông tin file.");
            return;
        }

        String scope = parts[1].trim().toUpperCase(Locale.ROOT);
        String target = parts[2].trim();
        String fileName;
        long size;
        String sha = parts[5].trim().toLowerCase(Locale.ROOT);

        try {
            fileName = new String(Base64.getUrlDecoder().decode(parts[3]), java.nio.charset.StandardCharsets.UTF_8);
            size = Long.parseLong(parts[4]);
        } catch (Exception e) {
            reject(client, "", "Tên file hoặc kích thước không hợp lệ.");
            return;
        }

        fileName = safeName(fileName);
        if (fileName.isBlank() || !isSafeFileName(fileName)
                || size < 0 || size > MAX_FILE_BYTES
                || !sha.matches("[0-9a-f]{64}")) {
            reject(client, "", "Tên file không hợp lệ, file nguy hiểm hoặc vượt giới hạn 1 GB.");
            return;
        }

        int roomId = 0;
        String receiver = null;
        if ("GROUP".equals(scope)) {
            try {
                roomId = Integer.parseInt(target);
            } catch (NumberFormatException e) {
                reject(client, "", "ID phòng không hợp lệ.");
                return;
            }
            if (roomId <= 0 || !client.isCurrentRoomMember(roomId)) {
                reject(client, "", "Bạn phải tham gia phòng trước khi gửi file.");
                return;
            }
        } else if ("PRIVATE".equals(scope)) {
            receiver = target;
            if (receiver.isBlank() || receiver.equals(username)) {
                reject(client, "", "Người nhận không hợp lệ.");
                return;
            }
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT 1 FROM users WHERE username COLLATE BINARY = ? COLLATE BINARY")) {
                ps.setString(1, receiver);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        reject(client, "", "Tài khoản người nhận không tồn tại.");
                        return;
                    }
                }
            } catch (Exception e) {
                reject(client, "", "Không thể kiểm tra người nhận.");
                return;
            }
        } else {
            reject(client, "", "Phạm vi gửi file không hợp lệ.");
            return;
        }

        String uploadId = UUID.randomUUID().toString();
        try {
            Files.createDirectories(FILE_DIR);
            Path temp = FILE_DIR.resolve("upload-" + uploadId + ".part");
            OutputStream os = new BufferedOutputStream(Files.newOutputStream(temp));
            UploadSession session = new UploadSession(
                    client, username, scope, roomId, receiver, fileName,
                    size, sha, temp, os);
            uploads.put(uploadId, session);
            client.sendMessage(Protocol.join(Protocol.UPLOAD_READY, uploadId, CHUNK_BYTES));
        } catch (Exception e) {
            reject(client, "", "Không tạo được phiên tải file lên.");
        }
    }

    /** UPLOAD_CHUNK|uploadId|sequence|base64(bytes) */
    public void handleUploadChunk(ClientHandler client, String username, String[] parts) {
        if (parts.length < 4) {
            reject(client, parts.length > 1 ? parts[1] : "", "Khối dữ liệu file không hợp lệ.");
            return;
        }
        String id = parts[1];
        UploadSession session = uploads.get(id);
        if (session == null || session.client != client || !session.username.equals(username)) {
            reject(client, id, "Phiên tải file không tồn tại.");
            return;
        }

        try {
            int sequence = Integer.parseInt(parts[2]);
            byte[] bytes = Base64.getDecoder().decode(parts[3]);
            synchronized (session) {
                if (sequence != session.nextChunk) {
                    throw new IOException("Thứ tự khối file không chính xác.");
                }
                if (bytes.length > CHUNK_BYTES
                        || session.received + bytes.length > session.expectedSize) {
                    throw new IOException("Khối file vượt kích thước cho phép.");
                }
                session.output.write(bytes);
                session.received += bytes.length;
                session.nextChunk++;
            }
        } catch (Exception e) {
            rejectAndRemove(client, id, session, e.getMessage());
        }
    }

    /** UPLOAD_FINISH|uploadId */
    public void handleUploadFinish(ClientHandler client, String username, String[] parts) {
        if (parts.length < 2) {
            reject(client, "", "Thiếu mã phiên tải file.");
            return;
        }
        String uploadId = parts[1];
        UploadSession session = uploads.get(uploadId);
        if (session == null || session.client != client || !session.username.equals(username)) {
            reject(client, uploadId, "Phiên tải file đã hết hạn.");
            return;
        }

        Path finalPath = null;
        try {
            synchronized (session) {
                if (session.output != null) {
                    session.output.flush();
                    session.output.close();
                    session.output = null;
                }
                if (session.received != session.expectedSize) {
                    throw new IOException("Kích thước file nhận được không khớp.");
                }
            }

            String actualSha = sha256(session.tempPath);
            if (!actualSha.equalsIgnoreCase(session.expectedSha)) {
                throw new IOException("File bị lỗi trong quá trình truyền (SHA-256 không khớp).");
            }
            if (!isSafeFileName(session.fileName) || hasDangerousSignature(session.tempPath)) {
                throw new IOException("Server đã chặn loại file nguy hiểm hoặc file thực thi.");
            }

            finalPath = FILE_DIR.resolve(UUID.randomUUID() + ".bin");
            try {
                Files.move(session.tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(session.tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }

            SavedFile saved = saveFileAndMessage(session, finalPath, actualSha);
            uploads.remove(uploadId, session);
            client.sendMessage(Protocol.join(Protocol.UPLOAD_DONE, uploadId, saved.fileId));
            broadcastFileMessage(session, saved.messageId, saved.fileId, actualSha);
        } catch (Exception e) {
            if (finalPath != null) {
                try { Files.deleteIfExists(finalPath); } catch (IOException ignored) { }
            }
            rejectAndRemove(client, uploadId, session,
                    e.getMessage() == null ? "Không lưu được file." : e.getMessage());
            System.err.println("[File upload] " + e.getMessage());
        }
    }

    /** UPLOAD_CANCEL|uploadId */
    public void handleUploadCancel(ClientHandler client, String username, String[] parts) {
        if (parts.length < 2) return;
        String id = parts[1];
        UploadSession session = uploads.get(id);
        if (session == null || session.client != client || !session.username.equals(username)) return;
        removeUpload(id, session);
    }

    public void cancelUploadsByClient(ClientHandler client) {
        for (var entry : uploads.entrySet()) {
            UploadSession session = entry.getValue();
            if (session.client == client && uploads.remove(entry.getKey(), session)) {
                closeAndDelete(session);
            }
        }
    }

    /** DOWNLOAD_FILE|fileId[|PREVIEW] - truyền tải chạy ở worker để nhận được lệnh hủy song song. */
    public void handleDownloadFile(ClientHandler client, String username, String[] parts) {
        if (username == null || !client.requireLogin()) return;
        if (parts.length < 2) {
            client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, "0", "Thiếu mã tệp."));
            return;
        }

        long fileId;
        try {
            fileId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, "0", "Mã tệp không hợp lệ."));
            return;
        }
        boolean preview = parts.length > 2 && "PREVIEW".equalsIgnoreCase(parts[2].trim());
        DownloadSession transfer;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, COALESCE(NULLIF(sender,''), uploader, '') AS sender, "
                     + "receiver, room_id, COALESCE(NULLIF(file_name,''), original_name, '') AS file_name, "
                     + "COALESCE(NULLIF(file_path,''), stored_path, '') AS file_path, "
                     + "file_size, sha256 FROM chat_files WHERE id = ?")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId, "Không tìm thấy tệp."));
                    return;
                }
                String sender = rs.getString("sender");
                String receiver = rs.getString("receiver");
                int roomId = rs.getInt("room_id");
                boolean groupFile = !rs.wasNull() && roomId > 0;
                String fileName = safeName(rs.getString("file_name"));
                String rawPath = rs.getString("file_path");
                long size = rs.getLong("file_size");
                String sha = rs.getString("sha256");

                boolean allowed;
                if (groupFile) {
                    allowed = client.isCurrentRoomMember(roomId);
                } else {
                    allowed = username.equals(sender) || username.equals(receiver);
                }
                if (!allowed) {
                    client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId,
                            "Bạn không có quyền tải tệp này."));
                    return;
                }
                if (!isSafeFileName(fileName)) {
                    client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId,
                            "Server chặn loại tệp nguy hiểm."));
                    return;
                }
                if (preview && !isPreviewableImage(fileName)) {
                    client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId,
                            "Chỉ hỗ trợ xem trước PNG, JPG, JPEG, GIF hoặc BMP."));
                    return;
                }
                Path filePath = Path.of(rawPath == null ? "" : rawPath).toAbsolutePath().normalize();
                if (!Files.isRegularFile(filePath) || size < 0 || size > MAX_FILE_BYTES
                        || Files.size(filePath) != size || sha == null || !sha.matches("(?i)[0-9a-f]{64}")) {
                    client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId,
                            "Tệp trên máy chủ không tồn tại, vượt dung lượng hoặc đã hỏng."));
                    return;
                }
                transfer = new DownloadSession(client, username, fileId, fileName,
                        filePath, size, sha.toLowerCase(Locale.ROOT), preview);
            }
        } catch (Exception e) {
            client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId,
                    "Không thể chuẩn bị tải tệp: " + safeProtocolText(e.getMessage())));
            System.err.println("[File download] " + e.getMessage());
            return;
        }

        DownloadKey key = new DownloadKey(client, fileId);
        transfer.key = key;
        DownloadSession old = downloads.putIfAbsent(key, transfer);
        if (old != null) {
            client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL, fileId,
                    "Tệp này đang được truyền trên kết nối của bạn."));
            return;
        }
        Thread worker = new Thread(() -> streamDownload(transfer),
                "ChatApp-Download-" + fileId + "-" + username);
        worker.setDaemon(true);
        worker.start();
    }

    /** DOWNLOAD_CANCEL|fileId - có thể xử lý song song khi worker đang phát chunk. */
    public void handleDownloadCancel(ClientHandler client, String username, String[] parts) {
        if (parts.length < 2 || username == null) return;
        long fileId;
        try { fileId = Long.parseLong(parts[1]); }
        catch (NumberFormatException e) { return; }
        DownloadKey key = new DownloadKey(client, fileId);
        DownloadSession session = downloads.remove(key);
        if (session != null && session.username.equals(username)) {
            session.cancelled.set(true);
        }
    }

    public void cancelDownloadsByClient(ClientHandler client) {
        for (var entry : downloads.entrySet()) {
            DownloadSession session = entry.getValue();
            if (session.client == client && downloads.remove(entry.getKey(), session)) {
                session.cancelled.set(true);
            }
        }
    }

    private void streamDownload(DownloadSession transfer) {
        try {
            checkDownloadCancelled(transfer);
            String outgoingName = transfer.fileName;
            String outgoingSha = transfer.sha256;
            long outgoingSize = transfer.expectedSize;
            byte[] previewBytes = null;
            if (transfer.preview) {
                previewBytes = createImagePreview(transfer.filePath, transfer.fileName);
                outgoingName = "preview-" + transfer.fileId + ".jpg";
                outgoingSize = previewBytes.length;
                outgoingSha = sha256(previewBytes);
                if (outgoingSize > MAX_PREVIEW_BYTES) {
                    throw new IOException("Ảnh xem trước vượt kích thước cho phép.");
                }
            }

            String encodedName = Base64.getEncoder().encodeToString(
                    outgoingName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (transfer.preview) {
                transfer.client.sendMessage(Protocol.join(Protocol.DOWNLOAD_BEGIN,
                        transfer.fileId, encodedName, outgoingSize, outgoingSha, "PREVIEW"));
            } else {
                transfer.client.sendMessage(Protocol.join(Protocol.DOWNLOAD_BEGIN,
                        transfer.fileId, encodedName, outgoingSize, outgoingSha));
            }

            try (InputStream in = transfer.preview
                    ? new ByteArrayInputStream(previewBytes)
                    : new BufferedInputStream(Files.newInputStream(transfer.filePath))) {
                byte[] buffer = new byte[CHUNK_BYTES];
                int sequence = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    checkDownloadCancelled(transfer);
                    byte[] chunk = n == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, n);
                    String encoded = Base64.getEncoder().encodeToString(chunk);
                    transfer.client.sendMessage(Protocol.join(Protocol.DOWNLOAD_CHUNK,
                            transfer.fileId, sequence++, encoded));
                }
            }
            checkDownloadCancelled(transfer);
            transfer.client.sendMessage(Protocol.join(Protocol.DOWNLOAD_DONE, transfer.fileId));
        } catch (java.util.concurrent.CancellationException ignored) {
            // Client đã hủy tải hoặc ngắt kết nối. Không phát thêm dữ liệu.
        } catch (Exception e) {
            if (!transfer.cancelled.get()) {
                transfer.client.sendMessage(Protocol.join(Protocol.DOWNLOAD_FAIL,
                        transfer.fileId, safeProtocolText(e.getMessage())));
                System.err.println("[File download] " + e.getMessage());
            }
        } finally {
            if (transfer.key != null) downloads.remove(transfer.key, transfer);
        }
    }

    private void checkDownloadCancelled(DownloadSession transfer) {
        if (transfer.cancelled.get()) throw new java.util.concurrent.CancellationException();
    }

    private static byte[] createImagePreview(Path path, String fileName) throws Exception {
        if (!isPreviewableImage(fileName)) throw new IOException("Định dạng ảnh không được hỗ trợ.");
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IOException("Không đọc được ảnh.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Tệp không phải ảnh raster hợp lệ.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PREVIEW_SOURCE_PIXELS) {
                    throw new IOException("Ảnh có kích thước quá lớn để tạo xem trước an toàn.");
                }
                BufferedImage original = reader.read(0);
                if (original == null) throw new IOException("Không giải mã được ảnh.");
                double scale = Math.min(1.0, Math.min(480.0 / original.getWidth(), 360.0 / original.getHeight()));
                int thumbWidth = Math.max(1, (int) Math.round(original.getWidth() * scale));
                int thumbHeight = Math.max(1, (int) Math.round(original.getHeight() * scale));
                BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = thumbnail.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, thumbWidth, thumbHeight);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(original, 0, 0, thumbWidth, thumbHeight, null);
                } finally {
                    graphics.dispose();
                    original.flush();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(thumbnail, "jpg", out)) throw new IOException("Không tạo được thumbnail JPEG.");
                thumbnail.flush();
                byte[] result = out.toByteArray();
                if (result.length > MAX_PREVIEW_BYTES) throw new IOException("Thumbnail vượt dung lượng an toàn.");
                return result;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * LIST_FILES|G/P|key|page -> FILE_LIST|G/P|key|page|hasMore|records
     * Mỗi record: id,sender,fileNameBase64Url,size,createdAt
     */
    public void handleListFiles(ClientHandler client, String username, String[] parts) {
        if (username == null || !client.requireLogin()) return;
        if (parts.length < 4) {
            client.sendError("Thiếu thông tin phân trang danh sách file.");
            return;
        }

        String kind = parts[1].trim().toUpperCase(Locale.ROOT);
        String key = parts[2].trim();
        int page;
        try {
            page = Integer.parseInt(parts[3]);
            if (page < 0 || page > 100000) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            client.sendError("Trang danh sách file không hợp lệ.");
            return;
        }

        int pageSize = Protocol.PAGE_SIZE;
        long offsetLong = (long) page * pageSize;
        if (offsetLong > Integer.MAX_VALUE) {
            client.sendError("Trang danh sách file vượt giới hạn.");
            return;
        }
        int offset = (int) offsetLong;
        String sql;
        Object[] args;

        if ("G".equals(kind)) {
            int roomId;
            try { roomId = Integer.parseInt(key); }
            catch (NumberFormatException ex) {
                client.sendError("ID phòng không hợp lệ.");
                return;
            }
            if (roomId <= 0 || !client.isCurrentRoomMember(roomId)) {
                client.sendError("Bạn phải tham gia phòng mới được xem tệp.");
                return;
            }
            sql = "SELECT id, COALESCE(NULLIF(sender,''), uploader, '') AS sender, "
                    + "COALESCE(NULLIF(file_name,''), original_name, '') AS file_name, "
                    + "file_size, COALESCE(created_at,'') AS created_at "
                    + "FROM chat_files WHERE room_id = ? ORDER BY id DESC LIMIT ? OFFSET ?";
            args = new Object[]{roomId, pageSize + 1, offset};
        } else if ("P".equals(kind)) {
            if (key.isBlank() || key.equals(username)) {
                client.sendError("Người đối thoại không hợp lệ.");
                return;
            }
            sql = "SELECT id, COALESCE(NULLIF(sender,''), uploader, '') AS sender, "
                    + "COALESCE(NULLIF(file_name,''), original_name, '') AS file_name, "
                    + "file_size, COALESCE(created_at,'') AS created_at "
                    + "FROM chat_files WHERE COALESCE(room_id,0) = 0 AND "
                    + "((COALESCE(NULLIF(sender,''),uploader,'') COLLATE BINARY = ? COLLATE BINARY "
                    + "AND COALESCE(receiver,'') COLLATE BINARY = ? COLLATE BINARY) OR "
                    + "(COALESCE(NULLIF(sender,''),uploader,'') COLLATE BINARY = ? COLLATE BINARY "
                    + "AND COALESCE(receiver,'') COLLATE BINARY = ? COLLATE BINARY)) "
                    + "ORDER BY id DESC LIMIT ? OFFSET ?";
            args = new Object[]{username, key, key, username, pageSize + 1, offset};
        } else {
            client.sendError("Loại cuộc trò chuyện không hợp lệ.");
            return;
        }

        StringBuilder records = new StringBuilder();
        boolean hasMore = false;
        int count = 0;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (count >= pageSize) {
                        hasMore = true;
                        break;
                    }
                    if (count > 0) records.append(';');
                    String fileName = safeName(rs.getString("file_name"));
                    String encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                            fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String sender = Protocol.clean(rs.getString("sender"));
                    String timestamp = Protocol.clean(rs.getString("created_at"));
                    records.append(rs.getLong("id")).append(',')
                            .append(sender).append(',')
                            .append(encodedName).append(',')
                            .append(Math.max(0L, rs.getLong("file_size"))).append(',')
                            .append(timestamp);
                    count++;
                }
            }
            client.sendMessage(Protocol.join(Protocol.FILE_LIST, kind,
                    Protocol.clean(key), page, hasMore ? 1 : 0, records));
        } catch (Exception e) {
            client.sendError("Không thể tải danh sách file: " + safeProtocolText(e.getMessage()));
            System.err.println("[File list] " + e.getMessage());
        }
    }

    /** LIST_CONVERSATION_FILES|G|roomId hoặc LIST_CONVERSATION_FILES|P|peer */
    public void handleListConversationFiles(ClientHandler client, String username, String[] parts) {
        if (username == null || !client.requireLogin()) return;
        if (parts.length < 3) {
            client.sendError("Thiếu thông tin cuộc trò chuyện để tải danh sách tệp.");
            return;
        }
        String kind = parts[1].trim().toUpperCase(Locale.ROOT);
        String key = parts[2].trim();
        StringBuilder response = new StringBuilder(Protocol.CONVERSATION_FILES)
                .append('|').append(kind).append('|').append(Protocol.clean(key)).append('|');
        boolean first = true;

        String sql;
        if ("G".equals(kind)) {
            int roomId;
            try { roomId = Integer.parseInt(key); }
            catch (NumberFormatException e) { client.sendError("ID phòng không hợp lệ."); return; }
            if (!client.isCurrentRoomMember(roomId)) {
                client.sendError("Bạn phải tham gia phòng mới được xem tệp.");
                return;
            }
            sql = "SELECT id, COALESCE(NULLIF(sender,''), uploader, '') AS sender, "
                    + "COALESCE(NULLIF(file_name,''), original_name, '') AS file_name, "
                    + "file_size, sha256 FROM chat_files WHERE room_id = ? ORDER BY id DESC";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!first) response.append(';');
                        first = false;
                        appendFileListItem(response, rs);
                    }
                }
            } catch (Exception e) {
                client.sendError("Không thể tải danh sách tệp của phòng.");
                return;
            }
        } else if ("P".equals(kind)) {
            String peer = key;
            if (peer.isBlank() || peer.equals(username)) {
                client.sendError("Người đối thoại không hợp lệ.");
                return;
            }
            sql = "SELECT id, COALESCE(NULLIF(sender,''), uploader, '') AS sender, "
                    + "COALESCE(NULLIF(file_name,''), original_name, '') AS file_name, "
                    + "file_size, sha256 FROM chat_files WHERE COALESCE(room_id,0) = 0 AND "
                    + "(((sender COLLATE BINARY = ? COLLATE BINARY OR uploader = ?) AND receiver COLLATE BINARY = ? COLLATE BINARY) "
                    + "OR ((sender COLLATE BINARY = ? COLLATE BINARY OR uploader = ?) AND receiver COLLATE BINARY = ? COLLATE BINARY)) ORDER BY id DESC";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, username);
                ps.setString(3, peer);
                ps.setString(4, peer);
                ps.setString(5, peer);
                ps.setString(6, username);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!first) response.append(';');
                        first = false;
                        appendFileListItem(response, rs);
                    }
                }
            } catch (Exception e) {
                client.sendError("Không thể tải danh sách tệp chat riêng.");
                return;
            }
        } else {
            client.sendError("Loại cuộc trò chuyện không hợp lệ.");
            return;
        }
        client.sendMessage(response.toString());
    }

    private void appendFileListItem(StringBuilder sb, ResultSet rs) throws Exception {
        String fileName = rs.getString("file_name");
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                safeName(fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sb.append(rs.getLong("id")).append(',')
          .append(Protocol.clean(rs.getString("sender"))).append(',')
          .append(encoded).append(',')
          .append(rs.getLong("file_size"));
        // sha không cần thiết cho danh sách, giữ giao thức gọn để không hiện ID/hash cho người dùng.
    }

    private static final class DownloadKey {
        final ClientHandler client;
        final long fileId;
        DownloadKey(ClientHandler client, long fileId) { this.client = client; this.fileId = fileId; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DownloadKey)) return false;
            DownloadKey key = (DownloadKey) other;
            return client == key.client && fileId == key.fileId;
        }
        @Override public int hashCode() {
            return 31 * System.identityHashCode(client) + Long.hashCode(fileId);
        }
    }

    private static final class DownloadSession {
        final ClientHandler client;
        final String username;
        final long fileId;
        final String fileName;
        final Path filePath;
        final long expectedSize;
        final String sha256;
        final boolean preview;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile DownloadKey key;
        DownloadSession(ClientHandler client, String username, long fileId, String fileName,
                        Path filePath, long expectedSize, String sha256, boolean preview) {
            this.client = client;
            this.username = username;
            this.fileId = fileId;
            this.fileName = fileName;
            this.filePath = filePath;
            this.expectedSize = expectedSize;
            this.sha256 = sha256;
            this.preview = preview;
        }
    }

    private static final class SavedFile {
        final long fileId;
        final long messageId;
        SavedFile(long fileId, long messageId) {
            this.fileId = fileId;
            this.messageId = messageId;
        }
    }

    private SavedFile saveFileAndMessage(UploadSession session, Path finalPath,
                                         String sha) throws Exception {
        String path = finalPath.toAbsolutePath().normalize().toString();
        long fileId;
        long messageId;
        String content;
        String encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                session.fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Database cũ có thể dùng storage_path hoặc store_path (NOT NULL),
                // trong khi phiên bản mới dùng file_path/stored_path. Tạo INSERT theo
                // schema thực tế để mọi cột đường dẫn bắt buộc nhận cùng giá trị.
                insertChatFileMetadata(conn, session, path, sha);
                fileId = lastInsertId(conn);
                content = "FILE:" + fileId + ":" + encodedName + ":"
                        + session.expectedSize + ":" + sha;

                String roomName = null;
                if (session.roomId > 0) {
                    try (PreparedStatement room = conn.prepareStatement(
                            "SELECT room_name FROM rooms WHERE id = ?")) {
                        room.setInt(1, session.roomId);
                        try (ResultSet rs = room.executeQuery()) {
                            if (rs.next()) roomName = rs.getString("room_name");
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO messages(sender, receiver, room_id, room_name, content, msg_type) "
                        + "VALUES(?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, session.username);
                    if (session.receiver == null) ps.setNull(2, java.sql.Types.VARCHAR);
                    else ps.setString(2, session.receiver);
                    if (session.roomId > 0) ps.setInt(3, session.roomId);
                    else ps.setNull(3, java.sql.Types.INTEGER);
                    if (roomName == null) ps.setNull(4, java.sql.Types.VARCHAR);
                    else ps.setString(4, roomName);
                    ps.setString(5, content);
                    ps.setString(6, Protocol.TYPE_FILE);
                    ps.executeUpdate();
                }
                messageId = lastInsertId(conn);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return new SavedFile(fileId, messageId);
    }

    private void insertChatFileMetadata(Connection conn, UploadSession session,
                                        String absolutePath, String sha) throws Exception {
        Map<String, Object> valuesByName = new LinkedHashMap<>();
        String now = new java.sql.Timestamp(System.currentTimeMillis()).toString();
        String mimeType = "application/octet-stream";
        try {
            String detected = Files.probeContentType(Path.of(session.fileName));
            if (detected != null && !detected.isBlank()) mimeType = detected;
        } catch (Exception ignored) {
            // Keep the safe default MIME type.
        }
        String extension = "";
        int dot = session.fileName.lastIndexOf('.');
        if (dot >= 0 && dot < session.fileName.length() - 1) {
            extension = session.fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        putAliases(valuesByName, session.username, "sender", "uploader", "uploaded_by", "owner");
        putAliases(valuesByName, session.receiver, "receiver", "recipient", "target_user");
        putAliases(valuesByName, session.roomId > 0 ? session.roomId : null,
                "room_id", "roomid", "group_id");
        putAliases(valuesByName, session.scope, "scope", "file_scope", "chat_scope");
        putAliases(valuesByName, session.fileName,
                "file_name", "filename", "original_name", "original_filename", "name");
        // stored_name is used by some legacy schemas as the physical server-side filename.
        // Keep the original user-visible name in file_name/original_name; store the generated
        // UUID .bin name here to avoid path traversal and collisions.
        String storedName = Path.of(absolutePath).getFileName().toString();
        putAliases(valuesByName, storedName,
                "stored_name", "storage_name", "disk_name", "physical_name");
        // Set all known path aliases, not just the two names used by the new schema.
        putAliases(valuesByName, absolutePath,
                "file_path", "stored_path", "storage_path", "store_path",
                "saved_path", "physical_path", "disk_path", "file_location", "local_path");
        putAliases(valuesByName, session.expectedSize, "file_size", "size", "size_bytes", "filesize");
        putAliases(valuesByName, sha, "sha256", "sha_256", "file_hash", "hash");
        putAliases(valuesByName, mimeType, "mime_type", "content_type", "mimetype");
        putAliases(valuesByName, extension, "extension", "file_extension", "ext");
        putAliases(valuesByName, "COMPLETED", "status", "transfer_status", "file_status");
        putAliases(valuesByName, now, "created_at", "uploaded_at", "created_time", "upload_time", "timestamp");

        List<String> insertColumns = new ArrayList<>();
        List<Object> insertValues = new ArrayList<>();
        List<String> missingRequiredColumns = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(chat_files)")) {
            while (rs.next()) {
                String column = rs.getString("name");
                if (column == null || column.isBlank()) continue;
                String normalized = column.toLowerCase(Locale.ROOT);
                boolean primaryKey = rs.getInt("pk") != 0;
                boolean notNull = rs.getInt("notnull") != 0;
                String defaultValue = rs.getString("dflt_value");

                // SQLite tự tạo ID cho INTEGER PRIMARY KEY AUTOINCREMENT.
                if (primaryKey && "id".equals(normalized)) continue;

                if (valuesByName.containsKey(normalized)) {
                    insertColumns.add(column);
                    insertValues.add(valuesByName.get(normalized));
                } else if (notNull && defaultValue == null && !primaryKey) {
                    missingRequiredColumns.add(column);
                }
            }
        }

        if (!missingRequiredColumns.isEmpty()) {
            throw new java.sql.SQLException(
                    "Bảng chat_files có cột NOT NULL chưa được hỗ trợ: "
                    + String.join(", ", missingRequiredColumns)
                    + ". Hãy kiểm tra schema PRAGMA table_info(chat_files).");
        }
        if (insertColumns.isEmpty()) {
            throw new java.sql.SQLException("Không tìm thấy cấu trúc hợp lệ của bảng chat_files.");
        }

        StringBuilder sql = new StringBuilder("INSERT INTO chat_files (");
        for (int i = 0; i < insertColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            // Column names originate only from PRAGMA table_info; quote safely anyway.
            sql.append('"').append(insertColumns.get(i).replace("\"", "\"\"")).append('"');
        }
        sql.append(") VALUES (");
        for (int i = 0; i < insertColumns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append('?');
        }
        sql.append(')');

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < insertValues.size(); i++) {
                Object value = insertValues.get(i);
                if (value == null) ps.setNull(i + 1, java.sql.Types.NULL);
                else if (value instanceof Integer) ps.setInt(i + 1, (Integer) value);
                else if (value instanceof Long) ps.setLong(i + 1, (Long) value);
                else ps.setString(i + 1, String.valueOf(value));
            }
            ps.executeUpdate();
        }
    }

    private static void putAliases(Map<String, Object> values, Object value, String... names) {
        for (String name : names) values.put(name, value);
    }

    private long lastInsertId(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            if (rs.next()) return rs.getLong(1);
        }
        throw new IOException("Không lấy được ID bản ghi.");
    }

    private void broadcastFileMessage(UploadSession session, long messageId,
                                      long fileId, String sha) {
        String encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                session.fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String content = "FILE:" + fileId + ":" + encodedName + ":"
                + session.expectedSize + ":" + sha;
        long timestamp = System.currentTimeMillis();
        if (session.roomId > 0) {
            String packet = Protocol.join(Protocol.MSG_GROUP,
                    session.roomId, messageId, session.username, timestamp,
                    Protocol.TYPE_FILE, content);
            session.client.broadcastToRoom(session.roomId, packet);
        } else {
            String packet = Protocol.join(Protocol.MSG_PRIVATE,
                    messageId, session.username, session.receiver, timestamp,
                    Protocol.TYPE_FILE, content);
            ClientHandler target = ChatServer.onlineClients.get(session.receiver);
            if (target != null) target.sendMessage(packet);
            session.client.sendMessage(packet);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] result = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : result) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private void rejectAndRemove(ClientHandler client, String id,
                                 UploadSession session, String message) {
        if (uploads.remove(id, session)) closeAndDelete(session);
        reject(client, id, message == null ? "Truyền file thất bại." : message);
    }

    private void removeUpload(String id, UploadSession session) {
        if (uploads.remove(id, session)) closeAndDelete(session);
    }

    private void closeAndDelete(UploadSession session) {
        synchronized (session) {
            if (session.output != null) {
                try { session.output.close(); } catch (IOException ignored) { }
                session.output = null;
            }
        }
        try { Files.deleteIfExists(session.tempPath); } catch (IOException ignored) { }
    }

    private void reject(ClientHandler client, String id, String reason) {
        client.sendMessage(Protocol.join(Protocol.UPLOAD_REJECT,
                id == null ? "" : id, safeProtocolText(reason)));
    }

    private static boolean isSafeFileName(String name) {
        String value = safeName(name).toLowerCase(Locale.ROOT);
        if (value.isBlank()) return false;
        for (String segment : value.split("\\.")) {
            if (BLOCKED_EXTENSIONS.contains(segment)) return false;
        }
        return !BLOCKED_EXTENSIONS.contains(extensionOf(value));
    }

    private static boolean isPreviewableImage(String name) {
        return isSafeFileName(name) && PREVIEW_EXTENSIONS.contains(extensionOf(name));
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        String value = name.trim().toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot < 0 || dot == value.length() - 1 ? "" : value.substring(dot + 1);
    }

    private static boolean hasDangerousSignature(Path path) throws IOException {
        byte[] head = new byte[8];
        int count;
        try (InputStream in = Files.newInputStream(path)) { count = in.read(head); }
        if (count >= 2 && head[0] == 'M' && head[1] == 'Z') return true;
        if (count >= 4 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') return true;
        if (count >= 4) {
            int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16)
                    | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
            if (magic == 0xFEEDFACE || magic == 0xFEEDFACF || magic == 0xCEFAEDFE
                    || magic == 0xCFFAEDFE || magic == 0xCAFEBABE) return true;
        }
        return false;
    }

    private static String safeProtocolText(String value) {
        if (value == null || value.isBlank()) return "Không rõ nguyên nhân.";
        return value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String safeName(String name) {
        if (name == null) return "";
        String result = name.replace('\\', '/');
        int slash = result.lastIndexOf('/');
        if (slash >= 0) result = result.substring(slash + 1);
        result = result.replace('|', '_').replace('\n', '_').replace('\r', '_').replace('\0', '_').trim();
        while (result.endsWith(".")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
