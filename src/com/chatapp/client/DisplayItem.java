package com.chatapp.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Model nội bộ cho một tin nhắn/notice trong lịch sử chat. */
class DisplayItem implements MessageActionController.MessageView {
    long id;
    String kind;
    String key;
    String sender;
    String text;
    String type;
    String prefix;
    String notice;
    String timestamp = "";
    boolean edited;
    boolean deleted;
    boolean pinned;
    long fileId = -1;
    long fileSize;
    String fileName = "";
    final Map<String, Set<String>> reactions = new HashMap<>();

    DisplayItem(String notice) { this.notice = notice; this.id = -1; }

    DisplayItem(long id, String kind, String key, String sender,
                String text, String type, boolean edited,
                boolean deleted, boolean pinned, String prefix) {
        this(id, kind, key, sender, text, type, edited, deleted, pinned, prefix, "");
    }

    DisplayItem(long id, String kind, String key, String sender,
                String text, String type, boolean edited,
                boolean deleted, boolean pinned, String prefix, String timestamp) {
        this.id = id; this.kind = kind; this.key = key; this.sender = sender;
        this.text = text; this.type = type; this.edited = edited; this.deleted = deleted;
        this.pinned = pinned; this.prefix = prefix;
        this.timestamp = timestamp == null ? "" : timestamp.trim();
    }

    @Override public long id() { return id; }
    @Override public String kind() { return kind; }
    @Override public String key() { return key; }
    @Override public String sender() { return sender; }
    @Override public String text() { return text; }
    @Override public String type() { return type; }
    @Override public boolean isDeleted() { return deleted; }
    @Override public boolean isPinned() { return pinned; }

    String renderText() {
        if (id <= 0) return notice == null ? "" : notice;
        StringBuilder sb = new StringBuilder();
        sb.append(sender == null ? "" : sender).append("\n");
        if (deleted) { sb.append("Tin nhắn đã bị thu hồi"); return sb.toString(); }
        sb.append(text == null ? "" : text);
        if (edited) sb.append("  (đã sửa)");
        if (pinned) sb.append("  • Đã ghim");
        String summary = ReactionController.formatSummary(reactions);
        if (summary != null && !summary.isBlank()) sb.append("\n").append(summary);
        return sb.toString();
    }
}
