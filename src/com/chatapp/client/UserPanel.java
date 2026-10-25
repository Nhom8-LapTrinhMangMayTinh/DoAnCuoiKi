package com.chatapp.client;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.ImageIcon;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Giao diện danh sách người dùng và cuộc trò chuyện riêng. */
public final class UserPanel extends JPanel {
    public interface Host {
        int getUnreadCount(String kind, String key);
        String escapeHtml(String value);
        void onSearchTextChanged();
        void onOpenUser(MainFrame.UserItem user);
        void onFindOrOpenFromSearchField();
        void onViewProfile(MainFrame.UserItem user);
        String getDisplayName(String username);
        ImageIcon getAvatarIcon(String username, int size);
    }

    private final Host host;
    private final JTextField txtSearchUser = new JTextField();
    private final DefaultListModel<MainFrame.UserItem> userListModel = new DefaultListModel<>();
    private final JList<MainFrame.UserItem> listUsers = new JList<>(userListModel);
    private final JButton findUserButton = new JButton("Tìm / chat riêng");

    public UserPanel(Host host) {
        if (host == null) throw new IllegalArgumentException("host không được null");
        this.host = host;
        buildUi();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 5));
        setBorder(BorderFactory.createTitledBorder("Cuộc trò chuyện riêng"));
        txtSearchUser.setToolTipText("Tìm theo username/ID người dùng");
        txtSearchUser.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { host.onSearchTextChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { host.onSearchTextChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { host.onSearchTextChanged(); }
        });

        listUsers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listUsers.setFixedCellHeight(84);
        listUsers.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                if (!(value instanceof MainFrame.UserItem)) return label;
                MainFrame.UserItem user = (MainFrame.UserItem) value;
                int unread = host.getUnreadCount("P", user.name);
                String unreadBadge = unread > 0
                        ? " <font color='#DC2626'><b>(" + unread + " chưa đọc)</b></font>" : "";
                String displayName = host.getDisplayName(user.name);
                if (displayName == null || displayName.trim().isEmpty()) displayName = user.name;
                label.setText("<html><b>" + host.escapeHtml(displayName)
                        + "</b><br><font color='#64748B'>@" + host.escapeHtml(user.name)
                        + "</font>" + unreadBadge + "</html>");
                label.setIcon(host.getAvatarIcon(user.name, 60));
                label.setIconTextGap(12);
                label.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
                return label;
            }
        });

        listUsers.addMouseListener(new MouseAdapter() {
            private void showPopup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int index = listUsers.locationToIndex(event.getPoint());
                if (index < 0) return;
                java.awt.Rectangle bounds = listUsers.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) return;
                listUsers.setSelectedIndex(index);
                MainFrame.UserItem user = listUsers.getSelectedValue();
                if (user == null) return;
                JPopupMenu popup = new JPopupMenu();
                JMenuItem profile = new JMenuItem("Xem hồ sơ");
                profile.addActionListener(e -> host.onViewProfile(user));
                JMenuItem chat = new JMenuItem("Mở chat riêng");
                chat.addActionListener(e -> host.onOpenUser(user));
                popup.add(profile);
                popup.add(chat);
                popup.show(listUsers, event.getX(), event.getY());
            }
            @Override public void mousePressed(MouseEvent event) { showPopup(event); }
            @Override public void mouseReleased(MouseEvent event) { showPopup(event); }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && !event.isPopupTrigger()) {
                    MainFrame.UserItem user = listUsers.getSelectedValue();
                    if (user != null) host.onOpenUser(user);
                }
            }
        });
        findUserButton.addActionListener(event -> host.onFindOrOpenFromSearchField());
        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchPanel.add(txtSearchUser, BorderLayout.CENTER);
        searchPanel.add(findUserButton, BorderLayout.EAST);
        add(searchPanel, BorderLayout.NORTH);
        add(new JScrollPane(listUsers), BorderLayout.CENTER);
    }

    public JTextField getSearchField() { return txtSearchUser; }
    public JList<MainFrame.UserItem> getListUsers() { return listUsers; }
    public DefaultListModel<MainFrame.UserItem> getUserListModel() { return userListModel; }
    public void refreshUserList() { listUsers.repaint(); }
}
