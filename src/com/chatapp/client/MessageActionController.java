package com.chatapp.client;

import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Quản lý menu chuột phải của từng tin nhắn trong khung chat.
 * MainFrame chỉ truyền lệnh gửi lên server; phần hiển thị bubble do ChatTranscriptPanel quản lý.
 */
public final class MessageActionController {

    /** Giao diện dữ liệu tin nhắn mà controller sử dụng. */
    public interface MessageView {
        long id();
        String kind();
        String key();
        String sender();
        String text();
        String type();
        boolean isDeleted();
        boolean isPinned();
    }

    private final JFrame parent;
    private final String username;
    private final Consumer<String> sendCommand;

    public MessageActionController(
            JFrame parent,
            String username,
            Consumer<String> sendCommand) {
        this.parent = parent;
        this.username = username == null ? "" : username;
        this.sendCommand = sendCommand;
    }

    /**
     * Được truyền trực tiếp vào ChatTranscriptPanel làm popupInstaller.
     * Cài listener trên bubble và các thành phần con vì Swing không tự nổi bọt sự kiện chuột.
     */
    public void install(Component component, DisplayItem item) {
        if (component == null || item == null) return;
        installRecursively(component, item);
    }

    private void installRecursively(Component component, DisplayItem item) {
        component.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showPopup(e, component, item); }
            @Override public void mouseReleased(MouseEvent e) { showPopup(e, component, item); }
        });

        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                installRecursively(child, item);
            }
        }
    }

    private void showPopup(MouseEvent e, Component source, DisplayItem item) {
        if (!e.isPopupTrigger() || item.id <= 0) return;

        JPopupMenu menu = new JPopupMenu();

        JMenuItem copy = new JMenuItem("Sao chép nội dung");
        copy.addActionListener(ev -> copyMessage(item));
        menu.add(copy);

        boolean mine = item.sender != null && item.sender.equals(username);
        boolean deleted = item.deleted;

        // Chỉ cho sửa và thu hồi tin nhắn của chính người dùng đang đăng nhập.
        if (mine && !deleted && Protocol.TYPE_TEXT.equals(item.type)) {
            JMenuItem edit = new JMenuItem("Sửa tin nhắn");
            edit.addActionListener(ev -> editMessage(item));
            menu.add(edit);
        }

        if (mine && !deleted && !Protocol.TYPE_FILE.equals(item.type)) {
            JMenuItem recall = new JMenuItem("Thu hồi tin nhắn");
            recall.addActionListener(ev -> recallMessage(item));
            menu.add(recall);
        }

        // Ghim chỉ áp dụng cho tin nhắn phòng nhóm. Dùng key của tin nhắn để tránh nhầm phòng.
        if ("G".equals(item.kind) && !deleted) {
            JMenuItem pin = new JMenuItem(item.pinned ? "Bỏ ghim tin nhắn" : "Ghim tin nhắn");
            pin.addActionListener(ev -> togglePin(item));
            menu.add(pin);
        }

        if (!deleted) {
            JMenu reactions = new JMenu("Thả cảm xúc");
            String[] labels = {"👍 Thích", "❤️ Yêu thích", "😂 Haha", "😮 Wow", "😢 Buồn", "😡 Phẫn nộ"};
            for (int i = 0; i < Protocol.REACTIONS.length; i++) {
                final String reaction = Protocol.REACTIONS[i];
                JMenuItem reactionItem = new JMenuItem(i < labels.length ? labels[i] : reaction);
                reactionItem.addActionListener(ev -> sendCommand.accept(
                        Protocol.join(Protocol.REACT_MSG, item.id, reaction)));
                reactions.add(reactionItem);
            }
            menu.add(reactions);
        }

        if (menu.getComponentCount() > 0) {
            menu.show(source, e.getX(), e.getY());
        }
    }

    private void copyMessage(DisplayItem item) {
        String value = Protocol.TYPE_FILE.equals(item.type)
                ? safe(item.fileName) : safe(item.text);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(value), null);
        } catch (IllegalStateException ex) {
            JOptionPane.showMessageDialog(parent,
                    "Không thể truy cập clipboard lúc này.",
                    "Sao chép tin nhắn", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void editMessage(DisplayItem item) {
        if (item == null || item.deleted || !Protocol.TYPE_TEXT.equals(item.type)) return;

        String editedText = JOptionPane.showInputDialog(
                parent, "Nhập nội dung mới:", item.text == null ? "" : item.text);
        if (editedText == null) return;
        editedText = editedText.trim();

        if (editedText.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Nội dung tin nhắn không được để trống.");
            return;
        }
        if (editedText.length() > Protocol.MAX_TEXT_CHARS) {
            JOptionPane.showMessageDialog(parent,
                    "Tin nhắn tối đa " + Protocol.MAX_TEXT_CHARS + " ký tự.");
            return;
        }

        String encrypted = AESUtils.encrypt(editedText);
        if (encrypted == null || encrypted.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Không thể mã hóa nội dung mới.");
            return;
        }

        sendCommand.accept(Protocol.join(Protocol.EDIT_MSG, item.id, encrypted));
    }

    private void recallMessage(DisplayItem item) {
        if (item == null || item.deleted) return;
        int result = JOptionPane.showConfirmDialog(
                parent,
                "Bạn có chắc muốn thu hồi tin nhắn này?",
                "Xác nhận thu hồi",
                JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.YES_OPTION) {
            sendCommand.accept(Protocol.join(Protocol.RECALL_MSG, item.id));
        }
    }

    private void togglePin(DisplayItem item) {
        if (item == null || item.deleted || !"G".equals(item.kind)) return;
        if (item.key == null || item.key.trim().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Không xác định được phòng của tin nhắn này.",
                    "Ghim tin nhắn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Server MessageActionService mong đợi PIN_MSG|messageId|pinValue.
        // Gửi sai thứ tự (roomId|messageId) khiến messageId bị hiểu nhầm thành trạng thái ghim.
        int pinValue = item.isPinned() ? 0 : 1;
        sendCommand.accept(Protocol.join(Protocol.PIN_MSG, item.id(), pinValue));
    }

    private String safe(String value) { return value == null ? "" : value; }
}
