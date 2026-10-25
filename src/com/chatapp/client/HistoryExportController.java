package com.chatapp.client;

import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;

import javax.swing.*;
import java.awt.Cursor;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Điều phối việc xuất lịch sử chat thành TXT hoặc JSON.
 *
 * Lớp này tách khỏi MainFrame phần chọn định dạng, chọn tệp lưu,
 * nhận các trang lịch sử, giải mã nội dung và ghi tệp UTF-8.
 */
public final class HistoryExportController {

    private final JFrame parent;
    private final String username;
    private final Predicate<String> commandSender;
    private final BooleanSupplier connectedSupplier;
    private final BooleanSupplier closedSupplier;
    private final Runnable stateChanged;

    private boolean inProgress;
    private String kind = "";          // G = nhóm, P = riêng
    private String key = "";           // ID phòng hoặc username đối thoại
    private String title = "";
    private String format = "txt";
    private int page;
    private File destination;
    private Cursor cursorBeforeExport;
    private final Map<Long, ExportedMessage> messages = new LinkedHashMap<>();

    public HistoryExportController(
            JFrame parent,
            String username,
            Predicate<String> commandSender,
            BooleanSupplier connectedSupplier,
            BooleanSupplier closedSupplier,
            Runnable stateChanged) {
        this.parent = parent;
        this.username = username == null ? "" : username;
        this.commandSender = commandSender;
        this.connectedSupplier = connectedSupplier;
        this.closedSupplier = closedSupplier;
        this.stateChanged = stateChanged;
    }

    public boolean isInProgress() {
        return inProgress;
    }

    /** Bắt đầu quy trình xuất lịch sử của một cuộc trò chuyện cụ thể. */
    public void start(String conversationKind, String conversationKey,
                      String conversationTitle, String suggestedBaseName) {
        if (!isConnected()) {
            JOptionPane.showMessageDialog(parent,
                    "Bạn chưa kết nối tới Server.", "Xuất lịch sử",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (inProgress) {
            JOptionPane.showMessageDialog(parent,
                    "Đang xuất một cuộc trò chuyện. Vui lòng đợi hoàn tất.",
                    "Xuất lịch sử", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!("G".equals(conversationKind) || "P".equals(conversationKind))
                || conversationKey == null || conversationKey.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "Hãy mở một cuộc trò chuyện hợp lệ trước.",
                    "Xuất lịch sử", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Object[] formats = {"TXT (.txt)", "JSON (.json)", "Hủy"};
        int choice = JOptionPane.showOptionDialog(
                parent,
                "Chọn định dạng xuất lịch sử của:\n" + conversationTitle,
                "Xuất lịch sử trò chuyện",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, formats, formats[0]);
        if (choice != 0 && choice != 1) return;
        String selectedFormat = choice == 0 ? "txt" : "json";

        String safeBase = sanitizeFileName(suggestedBaseName);
        String timeSuffix = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Lưu lịch sử trò chuyện");
        chooser.setSelectedFile(new File(safeBase + "_" + timeSuffix + "." + selectedFormat));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File selected = chooser.getSelectedFile();
        String path = selected.getAbsolutePath();
        if (!path.toLowerCase().endsWith("." + selectedFormat)) {
            selected = new File(path + "." + selectedFormat);
        }
        if (selected.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(parent,
                    "Tệp đã tồn tại. Bạn có muốn ghi đè không?",
                    "Xác nhận ghi đè", JOptionPane.YES_NO_OPTION);
            if (overwrite != JOptionPane.YES_OPTION) return;
        }

        this.kind = conversationKind;
        this.key = conversationKey;
        this.title = conversationTitle == null ? "" : conversationTitle;
        this.format = selectedFormat;
        this.destination = selected;
        this.page = 0;
        this.messages.clear();
        this.inProgress = true;
        this.cursorBeforeExport = parent.getCursor();
        parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        notifyStateChanged();
        requestCurrentPage();
    }

    /**
     * Cho controller xử lý HISTORY_ITEM của đúng cuộc trò chuyện đang xuất.
     * Trả về true nếu gói tin đã được tiêu thụ và không nên hiển thị vào chat.
     */
    public boolean handleHistoryItem(String[] parts) {
        if (!inProgress || parts == null || parts.length < 8) return false;
        if (!matchesTarget(parts[1], parts[2])) return false;

        long id;
        try {
            id = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return true; // Gói lịch sử của tác vụ xuất nhưng ID lỗi: không đưa vào giao diện.
        }
        captureMessage(id, parts[4], parts[5], parts[6], parts[7], parts);
        return true;
    }

    /**
     * Cho controller xử lý HISTORY_END. Trả true nếu đây là phản hồi của tác vụ xuất.
     */
    public boolean handleHistoryEnd(String[] parts) {
        if (!inProgress || parts == null || parts.length < 4) return false;
        if (!matchesTarget(parts[1], parts[2])) return false;

        int responsePage;
        try {
            responsePage = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            fail("Server trả về số trang lịch sử không hợp lệ.");
            return true;
        }
        if (responsePage != page) return true;

        boolean hasMore = parts.length > 4 && "1".equals(parts[4]);
        if (hasMore) {
            page++;
            requestCurrentPage();
        } else {
            completeExport();
        }
        return true;
    }

    /** Báo lỗi giao thức khi server từ chối yêu cầu lịch sử. */
    public void handleServerError(String message) {
        if (inProgress) {
            fail("Server từ chối hoặc không thể tải lịch sử: " + safe(message));
        }
    }

    /** Kết thúc tác vụ khi kết nối tới server bị mất. */
    public void failForDisconnect() {
        if (inProgress) fail("Mất kết nối trong khi đang xuất lịch sử.");
    }

    /** Dọn trạng thái khi người dùng đăng xuất/đóng cửa sổ. */
    public void cancelSilently() {
        if (inProgress) stopState();
    }

    private boolean isConnected() {
        return connectedSupplier.getAsBoolean() && !closedSupplier.getAsBoolean();
    }

    private void requestCurrentPage() {
        if (!inProgress) return;
        if (!isConnected()) {
            fail("Kết nối tới Server đã bị ngắt.");
            return;
        }

        String command;
        if ("G".equals(kind)) {
            command = Protocol.join(Protocol.LOAD_HISTORY_PAGED, key, page);
        } else {
            command = Protocol.join(Protocol.LOAD_PRIVATE_HISTORY, key, page);
        }
        if (!commandSender.test(command)) {
            fail("Không thể gửi yêu cầu tải lịch sử tới Server.");
        }
    }

    private boolean matchesTarget(String responseKind, String responseKey) {
        if (responseKind == null || responseKey == null || !kind.equals(responseKind)) {
            return false;
        }
        return "P".equals(kind)
                ? key.equals(responseKey)
                : key.equals(responseKey);
    }

    private void captureMessage(long id, String sender, String timestamp,
                                String type, String rawContent, String[] parts) {
        if (id <= 0 || messages.containsKey(id)) return;

        boolean edited = parts.length > 8 && "1".equals(parts[8]);
        boolean deleted = parts.length > 9 && "1".equals(parts[9]);
        boolean pinned = parts.length > 10 && "1".equals(parts[10]);
        String content;
        long fileId = -1;
        String fileName = "";
        long fileSize = 0;

        if (deleted) {
            content = "Tin nhắn đã bị thu hồi";
        } else if (Protocol.TYPE_FILE.equals(type)) {
            FileInfo file = parseFileContent(rawContent);
            if (file != null) {
                fileId = file.id;
                fileName = file.name;
                fileSize = file.size;
                content = "Tệp đính kèm: " + file.name + " (" + formatFileSize(file.size) + ")";
            } else {
                content = "Tệp đính kèm (metadata không đọc được)";
            }
        } else {
            content = safe(AESUtils.decrypt(rawContent));
        }

        messages.put(id, new ExportedMessage(id, sender, timestamp, type,
                content, edited, deleted, pinned, fileId, fileName, fileSize));
    }

    private void completeExport() {
        if (!inProgress) return;
        try {
            List<ExportedMessage> ordered = new ArrayList<>(messages.values());
            ordered.sort((a, b) -> Long.compare(a.id, b.id));
            if ("json".equals(format)) writeJson(ordered);
            else writeText(ordered);

            File completedFile = destination;
            stopState();
            JOptionPane.showMessageDialog(parent,
                    "Đã xuất " + ordered.size() + " tin nhắn.\n\nTệp: "
                            + completedFile.getAbsolutePath(),
                    "Xuất lịch sử thành công", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            fail("Không thể ghi tệp: " + e.getMessage());
        }
    }

    private void writeText(List<ExportedMessage> ordered) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                destination.toPath(), StandardCharsets.UTF_8)) {
            writer.write("LỊCH SỬ TRÒ CHUYỆN"); writer.newLine();
            writer.write("Cuộc trò chuyện: " + title); writer.newLine();
            writer.write("Loại: " + ("G".equals(kind) ? "Chat nhóm" : "Chat riêng")); writer.newLine();
            writer.write("Người xuất: " + username); writer.newLine();
            writer.write("Thời điểm xuất: " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))); writer.newLine();
            writer.write("Tổng số tin nhắn: " + ordered.size()); writer.newLine();
            writer.write("=".repeat(72)); writer.newLine(); writer.newLine();

            for (ExportedMessage message : ordered) {
                writer.write("[" + message.timestamp + "] " + message.sender);
                if (message.edited) writer.write(" (đã sửa)");
                if (message.pinned) writer.write(" (đã ghim)");
                writer.newLine();
                writer.write(message.content);
                writer.newLine();
                writer.write("-".repeat(48));
                writer.newLine();
            }
        }
    }

    private void writeJson(List<ExportedMessage> ordered) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                destination.toPath(), StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"conversation\": \"" + jsonEscape(title) + "\",\n");
            writer.write("  \"conversationType\": \"" + ("G".equals(kind) ? "group" : "private") + "\",\n");
            writer.write("  \"conversationKey\": \"" + jsonEscape(key) + "\",\n");
            writer.write("  \"exportedBy\": \"" + jsonEscape(username) + "\",\n");
            writer.write("  \"exportedAt\": \"" + jsonEscape(LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))) + "\",\n");
            writer.write("  \"messageCount\": " + ordered.size() + ",\n");
            writer.write("  \"messages\": [\n");
            for (int i = 0; i < ordered.size(); i++) {
                ExportedMessage message = ordered.get(i);
                writer.write("    {\n");
                writer.write("      \"id\": " + message.id + ",\n");
                writer.write("      \"sender\": \"" + jsonEscape(message.sender) + "\",\n");
                writer.write("      \"timestamp\": \"" + jsonEscape(message.timestamp) + "\",\n");
                writer.write("      \"type\": \"" + jsonEscape(message.type) + "\",\n");
                writer.write("      \"content\": \"" + jsonEscape(message.content) + "\",\n");
                writer.write("      \"edited\": " + message.edited + ",\n");
                writer.write("      \"deleted\": " + message.deleted + ",\n");
                writer.write("      \"pinned\": " + message.pinned);
                if (message.fileId > 0) {
                    writer.write(",\n      \"fileId\": " + message.fileId + ",\n");
                    writer.write("      \"fileName\": \"" + jsonEscape(message.fileName) + "\",\n");
                    writer.write("      \"fileSize\": " + message.fileSize);
                }
                writer.write("\n    }");
                if (i + 1 < ordered.size()) writer.write(",");
                writer.write("\n");
            }
            writer.write("  ]\n}\n");
        }
    }

    private void fail(String message) {
        if (!inProgress) return;
        stopState();
        JOptionPane.showMessageDialog(parent, message,
                "Không thể xuất lịch sử", JOptionPane.ERROR_MESSAGE);
    }

    private void stopState() {
        inProgress = false;
        kind = "";
        key = "";
        title = "";
        page = 0;
        destination = null;
        messages.clear();
        parent.setCursor(cursorBeforeExport == null
                ? Cursor.getDefaultCursor() : cursorBeforeExport);
        cursorBeforeExport = null;
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        if (stateChanged != null) stateChanged.run();
    }

    private FileInfo parseFileContent(String content) {
        if (content == null || !content.startsWith("FILE:")) return null;
        String[] fields = content.split(":", 5);
        if (fields.length != 5) return null;
        try {
            long id = Long.parseLong(fields[1]);
            String fileName = new String(
                    Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8);
            long size = Long.parseLong(fields[3]);
            String sha = fields[4];
            if (id <= 0 || size <= 0 || fileName.isBlank()
                    || !sha.matches("[0-9a-fA-F]{64}")) return null;
            return new FileInfo(id, fileName, size);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "lich_su_chat";
        String safeName = name.replaceAll("[\\\\/:*?\\\"<>|]+", "_").trim();
        return safeName.isBlank() ? "lich_su_chat" : safeName;
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class FileInfo {
        final long id;
        final String name;
        final long size;

        FileInfo(long id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }
    }

    private static final class ExportedMessage {
        final long id;
        final String sender;
        final String timestamp;
        final String type;
        final String content;
        final boolean edited;
        final boolean deleted;
        final boolean pinned;
        final long fileId;
        final String fileName;
        final long fileSize;

        ExportedMessage(long id, String sender, String timestamp, String type,
                        String content, boolean edited, boolean deleted,
                        boolean pinned, long fileId, String fileName, long fileSize) {
            this.id = id;
            this.sender = sender == null ? "" : sender;
            this.timestamp = timestamp == null ? "" : timestamp;
            this.type = type == null ? "TEXT" : type;
            this.content = content == null ? "" : content;
            this.edited = edited;
            this.deleted = deleted;
            this.pinned = pinned;
            this.fileId = fileId;
            this.fileName = fileName == null ? "" : fileName;
            this.fileSize = fileSize;
        }
    }
}
