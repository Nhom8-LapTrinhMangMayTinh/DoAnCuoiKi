package com.chatapp.client;

import com.chatapp.protocol.Protocol;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Quản lý trạng thái của các cuộc trò chuyện: đã đọc, chưa đọc và đang nhập.
 * Lớp này không tự tạo kết nối mạng; mọi lệnh gửi và cập nhật UI đều thông qua Host.
 */
public final class ConversationStateController {

    /** Các thông tin/hành động mà bộ điều phối cần lấy từ MainFrame. */
    public interface Host {
        String username();
        boolean hasCurrentConversation();
        String currentConversationKind();
        String currentConversationKey();
        boolean isCurrentConversation(String kind, String key);
        boolean isConnected();
        boolean isClosed();
        boolean sendCommand(String command);
        String draftText();
        List<DisplayItem> transcriptItems();
        JLabel typingStatusLabel();
        void refreshUnreadBadges();
        void renderTranscript();
    }

    private final Host host;

    // Mốc tin nhắn đã đọc của từng thành viên theo từng cuộc trò chuyện.
    private final Map<String, Map<String, Long>> readMarkersByConversation = new HashMap<>();
    private final Map<String, Set<String>> typingUsersByConversation = new HashMap<>();
    private final Map<String, Long> lastReadSentByConversation = new HashMap<>();

    // Số tin chưa đọc theo phòng/cuộc trò chuyện riêng.
    private final Map<String, Integer> unreadCountsByConversation = new HashMap<>();
    private final Map<String, Long> unreadSnapshotHighWater = new HashMap<>();
    private final Map<String, Integer> unreadSnapshotBaseCounts = new HashMap<>();
    private final Map<String, Set<Long>> unreadMessagesDuringSnapshot = new HashMap<>();
    private final Set<String> unreadSnapshotReceivedKeys = new HashSet<>();
    private boolean receivingUnreadSnapshot;

    private Timer typingStopTimer;
    private boolean typingActive;

    public ConversationStateController(Host host) {
        if (host == null) throw new IllegalArgumentException("host không được null");
        this.host = host;
    }

    private String conversationStateKey(String kind, String key) {
        String safeKey = key == null ? "" : key.trim();
        // Private-conversation keys are exact usernames; Huy and huy differ.
        return kind + ":" + safeKey;
    }

    public void requestCurrentConversationStates() {
        if (!host.hasCurrentConversation()) return;
        String kind = host.currentConversationKind();
        String key = host.currentConversationKey();
        String stateKey = conversationStateKey(kind, key);
        readMarkersByConversation.remove(stateKey);
        typingUsersByConversation.remove(stateKey);
        renderTypingStatus();
        host.sendCommand(Protocol.join(Protocol.GET_READ_STATE, kind, key));
        host.sendCommand(Protocol.join(Protocol.GET_TYPING_STATE, kind, key));
    }

    public int getUnreadCount(String kind, String key) {
        Integer value = unreadCountsByConversation.get(conversationStateKey(kind, key));
        return value == null ? 0 : Math.max(0, value);
    }

    public void clearUnreadForConversation(String kind, String key) {
        unreadCountsByConversation.put(conversationStateKey(kind, key), 0);
        refreshUnreadBadges();
    }

    public void incrementUnreadForIncoming(String kind, String key, String sender, long messageId) {
        if (sender == null || sender.equals(host.username())) return;
        if (host.isCurrentConversation(kind, key)) {
            clearUnreadForConversation(kind, key);
            return;
        }

        String stateKey = conversationStateKey(kind, key);
        if (receivingUnreadSnapshot) {
            if (messageId > 0) {
                unreadMessagesDuringSnapshot
                        .computeIfAbsent(stateKey, ignored -> new HashSet<>())
                        .add(messageId);
            }
            updateVisibleUnreadDuringSnapshot(kind, key, stateKey);
        } else {
            unreadCountsByConversation.merge(stateKey, 1, Integer::sum);
        }
        refreshUnreadBadges();
    }

    private void updateVisibleUnreadDuringSnapshot(String kind, String key, String stateKey) {
        if (host.isCurrentConversation(kind, key)) {
            unreadCountsByConversation.put(stateKey, 0);
            return;
        }
        int base = unreadSnapshotBaseCounts.getOrDefault(stateKey, 0);
        Set<Long> pending = unreadMessagesDuringSnapshot.get(stateKey);
        int delta = 0;
        if (pending != null) {
            long highWater = unreadSnapshotHighWater.getOrDefault(stateKey, 0L);
            boolean hasSnapshotRow = unreadSnapshotReceivedKeys.contains(stateKey);
            for (Long id : pending) {
                if (id != null && (!hasSnapshotRow || id > highWater)) delta++;
            }
        }
        unreadCountsByConversation.put(stateKey, base + delta);
    }

    public void processUnreadBegin() {
        unreadCountsByConversation.clear();
        unreadSnapshotHighWater.clear();
        unreadSnapshotBaseCounts.clear();
        unreadMessagesDuringSnapshot.clear();
        unreadSnapshotReceivedKeys.clear();
        receivingUnreadSnapshot = true;
    }

    public void processUnreadCount(String[] p) {
        if (p == null || p.length < 4) return;
        String kind = p[1];
        String key;
        try {
            if ("G".equals(kind)) {
                key = String.valueOf(Integer.parseInt(p[2]));
            } else if ("P".equals(kind)) {
                key = new String(Base64.getUrlDecoder().decode(p[2]), StandardCharsets.UTF_8);
            } else {
                return;
            }
            int count = Math.max(0, Integer.parseInt(p[3]));
            long highWater = p.length >= 5 ? Math.max(0L, Long.parseLong(p[4])) : 0L;
            String stateKey = conversationStateKey(kind, key);
            unreadSnapshotBaseCounts.put(stateKey, count);
            unreadSnapshotHighWater.put(stateKey, highWater);
            unreadSnapshotReceivedKeys.add(stateKey);
            updateVisibleUnreadDuringSnapshot(kind, key, stateKey);
        } catch (IllegalArgumentException ex) {
            // Bỏ qua packet có khóa hoặc số lượng không hợp lệ.
        }
    }

    public void processUnreadEnd() {
        for (Map.Entry<String, Set<Long>> entry : unreadMessagesDuringSnapshot.entrySet()) {
            String stateKey = entry.getKey();
            String[] keyParts = stateKey.split(":", 2);
            String kind = keyParts.length > 0 ? keyParts[0] : "";
            String key = keyParts.length > 1 ? keyParts[1] : "";
            if (host.isCurrentConversation(kind, key)) {
                unreadCountsByConversation.put(stateKey, 0);
                continue;
            }

            int base = unreadSnapshotBaseCounts.getOrDefault(stateKey, 0);
            long highWater = unreadSnapshotHighWater.getOrDefault(stateKey, 0L);
            boolean hasSnapshotRow = unreadSnapshotReceivedKeys.contains(stateKey);
            int delta = 0;
            for (Long messageId : entry.getValue()) {
                if (messageId != null && (!hasSnapshotRow || messageId > highWater)) delta++;
            }
            unreadCountsByConversation.put(stateKey, base + delta);
        }
        receivingUnreadSnapshot = false;
        unreadMessagesDuringSnapshot.clear();
        unreadSnapshotHighWater.clear();
        unreadSnapshotBaseCounts.clear();
        unreadSnapshotReceivedKeys.clear();
        refreshUnreadBadges();
    }

    public void processReadState(String[] p) {
        if (p == null || p.length < 5) return;
        String kind = p[1];
        String key = p[2];
        String reader = p[3];
        long lastRead;
        try {
            lastRead = Long.parseLong(p[4]);
        } catch (NumberFormatException ex) {
            return;
        }
        if (reader == null || reader.trim().isEmpty() || lastRead < 0) return;

        String conversationKey = conversationStateKey(kind, key);
        Map<String, Long> markers = readMarkersByConversation.computeIfAbsent(
                conversationKey, ignored -> new HashMap<>());
        String existingName = null;
        Long previous = null;
        for (Map.Entry<String, Long> entry : markers.entrySet()) {
            if (entry.getKey().equals(reader)) {
                existingName = entry.getKey();
                previous = entry.getValue();
                break;
            }
        }
        if (previous == null || lastRead > previous) {
            markers.put(existingName == null ? reader : existingName, lastRead);
            if (host.isCurrentConversation(kind, key)) host.renderTranscript();
        }
    }

    public void processTypingState(String[] p) {
        if (p == null || p.length < 5) return;
        String kind = p[1];
        String key = p[2];
        String name = p[3];
        boolean typing = "1".equals(p[4]);
        if (name == null || name.trim().isEmpty()) return;

        String conversationKey = conversationStateKey(kind, key);
        Set<String> users = typingUsersByConversation.computeIfAbsent(
                conversationKey, ignored -> new TreeSet<>());
        if (typing) users.add(name);
        else users.remove(name);
        if (users.isEmpty()) typingUsersByConversation.remove(conversationKey);
        if (host.isCurrentConversation(kind, key)) renderTypingStatus();
    }

    public void markCurrentConversationRead() {
        if (!host.hasCurrentConversation()) return;
        String kind = host.currentConversationKind();
        String key = host.currentConversationKey();
        long maxId = 0;
        for (DisplayItem item : host.transcriptItems()) {
            if (item.id <= 0 || item.kind == null || item.key == null) continue;
            if (item.kind.equals(kind) && item.key.equals(key)) {
                maxId = Math.max(maxId, item.id);
            }
        }
        if (maxId > 0) markReadUpTo(kind, key, maxId);
    }

    public void markReadUpTo(String kind, String key, long messageId) {
        if (messageId <= 0 || !host.isConnected() || host.isClosed()) return;
        String stateKey = conversationStateKey(kind, key);
        Long previous = lastReadSentByConversation.get(stateKey);
        if (previous != null && messageId <= previous) return;
        if (host.sendCommand(Protocol.join(Protocol.MARK_READ, kind, key, messageId))) {
            lastReadSentByConversation.put(stateKey, messageId);
        }
    }

    /** Trả nhãn trạng thái chỉ cho tin nhắn do tài khoản hiện tại gửi. */
    public String readReceiptText(DisplayItem item) {
        if (item == null || item.id <= 0 || item.deleted
                || item.sender == null || !item.sender.equals(host.username())
                || item.kind == null || item.key == null) return null;

        Map<String, Long> markers = readMarkersByConversation.get(
                conversationStateKey(item.kind, item.key));
        List<String> readers = new ArrayList<>();
        if (markers != null) {
            Set<String> added = new HashSet<>();
            for (Map.Entry<String, Long> entry : markers.entrySet()) {
                String reader = entry.getKey();
                if (reader == null || reader.equals(host.username())
                        || entry.getValue() == null || entry.getValue() < item.id) continue;
                if (added.add(reader)) readers.add(reader);
            }
        }
        readers.sort(String.CASE_INSENSITIVE_ORDER);

        if ("P".equals(item.kind)) {
            return readers.isEmpty() ? "Chưa được xem"
                    : "Đã xem bởi: " + String.join(", ", readers);
        }
        return readers.isEmpty() ? "Chưa ai xem" : "Đã xem bởi: " + String.join(", ", readers);
    }

    public void onMessageDraftChanged() {
        if (host.draftText() == null) return;
        if (host.draftText().trim().isEmpty()) {
            stopTypingNow();
            return;
        }
        if (!host.isConnected() || host.isClosed() || !host.hasCurrentConversation()) return;

        typingActive = true;
        host.sendCommand(Protocol.join(Protocol.TYPING,
                host.currentConversationKind(), host.currentConversationKey(), "1"));

        if (typingStopTimer == null) {
            typingStopTimer = new Timer(2400, event -> stopTypingNow());
            typingStopTimer.setRepeats(false);
        }
        typingStopTimer.restart();
    }

    public void stopTypingNow() {
        if (typingStopTimer != null) typingStopTimer.stop();
        if (!typingActive) return;
        typingActive = false;
        if (host.isConnected() && !host.isClosed() && host.hasCurrentConversation()) {
            host.sendCommand(Protocol.join(Protocol.TYPING,
                    host.currentConversationKind(), host.currentConversationKey(), "0"));
        }
    }

    public void renderTypingStatus() {
        JLabel label = host.typingStatusLabel();
        if (label == null) return;
        if (!host.hasCurrentConversation()) {
            label.setText(" ");
            return;
        }
        Set<String> active = typingUsersByConversation.get(
                conversationStateKey(host.currentConversationKind(), host.currentConversationKey()));
        List<String> names = new ArrayList<>();
        if (active != null) {
            for (String name : active) {
                if (name != null && !name.equals(host.username())) names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) {
            label.setText(" ");
        } else if (names.size() == 1) {
            label.setText(names.get(0) + " đang nhập tin nhắn...");
        } else {
            label.setText(String.join(", ", names) + " đang nhập tin nhắn...");
        }
    }

    /** Dừng timer và xóa trạng thái typing khi kết nối bị mất. */
    public void onDisconnected() {
        if (typingStopTimer != null) typingStopTimer.stop();
        typingActive = false;
        JLabel label = host.typingStatusLabel();
        if (label != null) label.setText(" ");
    }

    /** Giải phóng timer khi đóng cửa sổ client. */
    public void dispose() {
        if (typingStopTimer != null) {
            typingStopTimer.stop();
            typingStopTimer = null;
        }
        typingActive = false;
    }

    private void refreshUnreadBadges() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshUnreadBadges);
            return;
        }
        host.refreshUnreadBadges();
    }
}
