package com.chatapp.client;

import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Điều phối tìm kiếm tin nhắn trong cuộc trò chuyện hiện tại.
 * Client gửi từ khóa cho server; server kiểm tra quyền và tìm trong SQLite.
 * Nội dung tin nhắn được gửi về dưới dạng mã hóa/Base64 và giải mã tại client.
 */
public final class MessageSearchController {

    private final JFrame parent;
    private final String username;
    private final Predicate<String> commandSender;
    private final BooleanSupplier connectedSupplier;
    private final BooleanSupplier closedSupplier;

    private boolean inProgress;
    private String kind = "";
    private String key = "";
    private String title = "";
    private String query = "";
    private String queryToken = "";
    private Cursor cursorBeforeSearch;
    private final List<SearchResult> results = new ArrayList<>();

    public MessageSearchController(JFrame parent, String username,
                                   Predicate<String> commandSender,
                                   BooleanSupplier connectedSupplier,
                                   BooleanSupplier closedSupplier) {
        this.parent = parent;
        this.username = username == null ? "" : username;
        this.commandSender = commandSender;
        this.connectedSupplier = connectedSupplier;
        this.closedSupplier = closedSupplier;
    }

    public boolean isInProgress() {
        return inProgress;
    }

    public void start(String conversationKind, String conversationKey,
                      String conversationTitle) {
        if (!isConnected()) {
            JOptionPane.showMessageDialog(parent,
                    "Bạn chưa kết nối tới Server.", "Tìm tin nhắn",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (inProgress) {
            JOptionPane.showMessageDialog(parent,
                    "Một lượt tìm kiếm đang chạy. Vui lòng đợi hoàn tất.",
                    "Tìm tin nhắn", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!("G".equals(conversationKind) || "P".equals(conversationKind))
                || conversationKey == null || conversationKey.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "Hãy mở một cuộc trò chuyện hợp lệ trước.",
                    "Tìm tin nhắn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String entered = JOptionPane.showInputDialog(parent,
                "Nhập từ khóa cần tìm trong:\n" + conversationTitle
                        + "\n\nCó hỗ trợ tìm kiếm không phân biệt dấu tiếng Việt.",
                "Tìm tin nhắn", JOptionPane.QUESTION_MESSAGE);
        if (entered == null) return;
        entered = entered.trim();
        if (entered.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Từ khóa không được để trống.",
                    "Tìm tin nhắn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (entered.length() > 100) {
            JOptionPane.showMessageDialog(parent,
                    "Từ khóa tối đa 100 ký tự.",
                    "Tìm tin nhắn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        this.kind = conversationKind;
        this.key = conversationKey;
        this.title = conversationTitle == null ? "Cuộc trò chuyện" : conversationTitle;
        this.query = entered;
        this.queryToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(entered.getBytes(StandardCharsets.UTF_8));
        this.results.clear();
        this.inProgress = true;
        this.cursorBeforeSearch = parent.getCursor();
        parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        String command = Protocol.join(Protocol.SEARCH_MESSAGES,
                kind, key, queryToken);
        if (!commandSender.test(command)) {
            stopState();
            JOptionPane.showMessageDialog(parent,
                    "Không thể gửi yêu cầu tìm kiếm tới Server.",
                    "Tìm tin nhắn", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Trả true nếu packet thuộc giao thức tìm kiếm và đã được xử lý. */
    public boolean handlePacket(String[] parts) {
        if (parts == null || parts.length == 0) return false;
        String command = parts[0];
        if (!(Protocol.SEARCH_BEGIN.equals(command)
                || Protocol.SEARCH_ITEM.equals(command)
                || Protocol.SEARCH_END.equals(command)
                || Protocol.SEARCH_FAIL.equals(command))) {
            return false;
        }
        if (!inProgress || parts.length < 4
                || !matches(parts[1], parts[2], parts[3])) {
            return true;
        }

        switch (command) {
            case Protocol.SEARCH_BEGIN:
                return true;
            case Protocol.SEARCH_ITEM:
                acceptItem(parts);
                return true;
            case Protocol.SEARCH_END:
                complete(parts);
                return true;
            case Protocol.SEARCH_FAIL:
                String reason = parts.length > 4 ? decodeText(parts[4])
                        : "Server không thể tìm tin nhắn.";
                fail(reason);
                return true;
            default:
                return true;
        }
    }

    private void acceptItem(String[] p) {
        // SEARCH_ITEM|kind|key|queryToken|id|sender|timestamp|type|contentB64|edited|deleted|pinned
        if (p.length < 12) return;
        try {
            long id = Long.parseLong(p[4]);
            String sender = p[5];
            String timestamp = p[6];
            String type = p[7];
            String rawContent = decodeText(p[8]);
            boolean edited = "1".equals(p[9]);
            boolean deleted = "1".equals(p[10]);
            boolean pinned = "1".equals(p[11]);

            String content;
            if (Protocol.TYPE_FILE.equals(type)) {
                content = readableFileContent(rawContent);
            } else {
                content = safe(AESUtils.decrypt(rawContent));
            }
            results.add(new SearchResult(id, sender, timestamp, type,
                    content, edited, deleted, pinned));
        } catch (Exception ignored) {
            // Bỏ qua một kết quả sai định dạng, không làm hỏng toàn bộ lượt tìm kiếm.
        }
    }

    private void complete(String[] p) {
        boolean truncated = p.length > 5 && "1".equals(p[5]);
        List<SearchResult> completedResults = new ArrayList<>(results);
        String completedTitle = title;
        String completedQuery = query;
        stopState();
        showResults(completedResults, truncated, completedTitle, completedQuery);
    }

    private void showResults(List<SearchResult> completedResults, boolean truncated,
                             String completedTitle, String completedQuery) {
        int count = completedResults.size();
        if (completedResults.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Không tìm thấy tin nhắn phù hợp với từ khóa: " + completedQuery,
                    "Kết quả tìm kiếm", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        DefaultListModel<SearchResult> model = new DefaultListModel<>();
        for (SearchResult result : completedResults) model.addElement(result);
        JList<SearchResult> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(12);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> owner, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(owner, value, index, selected, focus);
                setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
                setText(value == null ? "" : value.toString());
                return this;
            }
        });

        JLabel heading = new JLabel("Tìm thấy " + count + " kết quả trong " + completedTitle
                + " cho từ khóa: “" + completedQuery + "”");
        heading.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(heading, BorderLayout.NORTH);
        center.add(new JScrollPane(list), BorderLayout.CENTER);
        if (truncated) {
            JLabel limit = new JLabel("Đã giới hạn phạm vi/kết quả tìm kiếm để bảo đảm hiệu năng.");
            limit.setBorder(BorderFactory.createEmptyBorder(0, 8, 5, 8));
            limit.setForeground(new java.awt.Color(160, 90, 0));
            center.add(limit, BorderLayout.SOUTH);
        }

        JButton details = new JButton("Xem nội dung");
        JButton copy = new JButton("Sao chép nội dung");
        JButton close = new JButton("Đóng");
        JDialog dialog = new JDialog(parent, "Kết quả tìm tin nhắn", true);
        Runnable showSelectedDetails = () -> {
            SearchResult selected = list.getSelectedValue();
            if (selected == null) {
                JOptionPane.showMessageDialog(dialog, "Hãy chọn một kết quả.",
                        "Kết quả tìm kiếm", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            showDetails(dialog, selected);
        };
        details.addActionListener(e -> showSelectedDetails.run());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showSelectedDetails.run();
            }
        });
        copy.addActionListener(e -> {
            SearchResult selected = list.getSelectedValue();
            if (selected == null) {
                JOptionPane.showMessageDialog(dialog, "Hãy chọn một kết quả.");
                return;
            }
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(selected.content), null);
        });
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(details);
        buttons.add(copy);
        buttons.add(close);

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(center, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(700, 440));
        dialog.setSize(820, 520);
        dialog.setLocationRelativeTo(parent);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        dialog.setVisible(true);
    }

    private void showDetails(JDialog dialog, SearchResult result) {
        StringBuilder details = new StringBuilder();
        details.append("Người gửi: ").append(result.sender).append('\n');
        details.append("Thời gian: ").append(result.timestamp).append('\n');
        details.append("Mã tin nhắn: ").append(result.id).append('\n');
        details.append("Loại: ").append(Protocol.TYPE_FILE.equals(result.type)
                ? "Tệp đính kèm" : "Tin nhắn văn bản").append('\n');
        if (result.edited) details.append("Trạng thái: Đã chỉnh sửa\n");
        if (result.pinned) details.append("Trạng thái: Đã ghim\n");
        details.append("\nNội dung:\n").append(result.content);
        JTextArea text = new JTextArea(details.toString(), 12, 48);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(dialog, new JScrollPane(text),
                "Chi tiết tin nhắn", JOptionPane.INFORMATION_MESSAGE);
    }

    private String readableFileContent(String content) {
        if (content == null || !content.startsWith("FILE:")) return "Tệp đính kèm";
        String[] p = content.split(":", 5);
        if (p.length < 4) return "Tệp đính kèm";
        try {
            String name = new String(Base64.getUrlDecoder().decode(p[2]), StandardCharsets.UTF_8);
            long bytes = Long.parseLong(p[3]);
            return "Tệp đính kèm: " + name + " (" + formatSize(bytes) + ")";
        } catch (Exception e) {
            return "Tệp đính kèm";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private boolean matches(String responseKind, String responseKey, String token) {
        if (!kind.equals(responseKind) || !queryToken.equals(token) || responseKey == null) return false;
        return key.equals(responseKey);
    }

    private String decodeText(String base64) {
        return new String(Base64.getUrlDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private boolean isConnected() {
        return connectedSupplier.getAsBoolean() && !closedSupplier.getAsBoolean();
    }

    private void fail(String message) {
        stopState();
        JOptionPane.showMessageDialog(parent, message,
                "Không thể tìm tin nhắn", JOptionPane.ERROR_MESSAGE);
    }

    /** Nếu server cũ trả ERROR thay vì SEARCH_FAIL, giải phóng trạng thái chờ. */
    public void handleServerError(String message) {
        if (inProgress) {
            fail("Server từ chối hoặc không thể thực hiện tìm kiếm: "
                    + (message == null ? "Lỗi không xác định." : message));
        }
    }

    public void failForDisconnect() {
        if (inProgress) fail("Mất kết nối trong khi đang tìm tin nhắn.");
    }

    public void cancelSilently() {
        if (inProgress) stopState();
    }

    private void stopState() {
        inProgress = false;
        kind = "";
        key = "";
        title = "";
        queryToken = "";
        query = "";
        results.clear();
        parent.setCursor(cursorBeforeSearch == null
                ? Cursor.getDefaultCursor() : cursorBeforeSearch);
        cursorBeforeSearch = null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class SearchResult {
        final long id;
        final String sender;
        final String timestamp;
        final String type;
        final String content;
        final boolean edited;
        final boolean deleted;
        final boolean pinned;

        SearchResult(long id, String sender, String timestamp, String type,
                     String content, boolean edited, boolean deleted, boolean pinned) {
            this.id = id;
            this.sender = sender == null ? "" : sender;
            this.timestamp = timestamp == null ? "" : timestamp;
            this.type = type == null ? "TEXT" : type;
            this.content = content == null ? "" : content;
            this.edited = edited;
            this.deleted = deleted;
            this.pinned = pinned;
        }

        @Override public String toString() {
            String excerpt = content.replace('\n', ' ').replace('\r', ' ').trim();
            if (excerpt.length() > 140) excerpt = excerpt.substring(0, 137) + "...";
            StringBuilder label = new StringBuilder();
            label.append('[').append(timestamp).append("]  ").append(sender).append(" — ").append(excerpt);
            if (edited) label.append("  (đã sửa)");
            if (pinned) label.append("  (đã ghim)");
            return label.toString();
        }
    }
}
