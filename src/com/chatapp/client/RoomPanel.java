package com.chatapp.client;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Danh sách phòng: ảnh đại diện, tên phòng, số tin chưa đọc và thông tin phụ. */
public final class RoomPanel extends JPanel {
    public interface Host {
        int getUnreadCount(String kind, String key);
        String escapeHtml(String value);
        void onOpenRoom(MainFrame.RoomItem room);
        void onCreateRoom();
        void onJoinRoom();
        void onLeaveRoom();
        default ImageIcon getRoomAvatarIcon(int roomId, String roomName, int size) { return null; }
    }

    private final Host host;
    private final DefaultListModel<MainFrame.RoomItem> roomListModel = new DefaultListModel<>();
    private final JList<MainFrame.RoomItem> listRooms = new JList<>(roomListModel);
    private final JButton btnCreateRoom = new JButton("Tạo phòng");
    private final JButton btnJoinRoom = new JButton("Vào phòng");
    private final JButton btnLeaveRoom = new JButton("Rời phòng");

    public RoomPanel(Host host) {
        if (host == null) throw new IllegalArgumentException("host không được null");
        this.host = host;
        buildUi();
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 5));
        setBorder(BorderFactory.createTitledBorder("Phòng chat"));

        listRooms.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listRooms.setFixedCellHeight(84);
        listRooms.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                if (!(value instanceof MainFrame.RoomItem)) return label;

                MainFrame.RoomItem room = (MainFrame.RoomItem) value;
                int unread = Math.max(0, host.getUnreadCount("G", String.valueOf(room.id)));
                String lock = room.locked ? "  🔒" : "";
                String name = host.escapeHtml(room.name) + lock;
                String identity = room.publicId == null || room.publicId.isBlank()
                        ? room.memberCount + " thành viên"
                        : "ID " + host.escapeHtml(room.publicId) + " · " + room.memberCount + " thành viên";
                String status;
                if (unread > 0) {
                    status = "<font color='#DC2626'><b>● " + unread + " tin chưa đọc</b></font>";
                } else {
                    status = "<font color='#64748B'>" + identity + "</font>";
                }
                String details = unread > 0
                        ? "<font color='#64748B'>" + identity + "</font><br>" + status
                        : status;
                label.setText("<html><div style='width:175px'><b>" + name + "</b><br>"
                        + details + "</div></html>");
                ImageIcon avatar = host.getRoomAvatarIcon(room.id, room.name, 60);
                if (avatar != null) label.setIcon(avatar);
                else label.setIcon(null);
                label.setIconTextGap(10);
                label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                return label;
            }
        });

        listRooms.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    MainFrame.RoomItem room = listRooms.getSelectedValue();
                    if (room != null) host.onOpenRoom(room);
                }
            }
        });

        JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
        buttons.add(btnCreateRoom);
        buttons.add(btnJoinRoom);
        buttons.add(btnLeaveRoom);

        add(new JScrollPane(listRooms), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        btnCreateRoom.addActionListener(event -> host.onCreateRoom());
        btnJoinRoom.addActionListener(event -> host.onJoinRoom());
        btnLeaveRoom.addActionListener(event -> host.onLeaveRoom());
    }

    public JList<MainFrame.RoomItem> getListRooms() { return listRooms; }
    public DefaultListModel<MainFrame.RoomItem> getRoomListModel() { return roomListModel; }
    public JButton getCreateButton() { return btnCreateRoom; }
    public JButton getJoinButton() { return btnJoinRoom; }
    public JButton getLeaveButton() { return btnLeaveRoom; }
    public void refreshRoomList() { listRooms.repaint(); }
    public void setActionsEnabled(boolean enabled) {
        btnCreateRoom.setEnabled(enabled);
        btnJoinRoom.setEnabled(enabled);
        btnLeaveRoom.setEnabled(enabled);
    }
}
