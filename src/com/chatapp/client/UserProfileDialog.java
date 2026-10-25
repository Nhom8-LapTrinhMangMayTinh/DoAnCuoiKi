package com.chatapp.client;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Box;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Dialog xem/sửa hồ sơ cá nhân. Dữ liệu được lưu trên server thông qua Host. */
public final class UserProfileDialog extends JDialog {
    public interface Host {
        void updateProfile(String displayName, String email, String phone,
                           String birthday, String gender, String bio);
        void uploadAvatar(byte[] jpegBytes);
    }

    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final String targetUsername;
    private final boolean editable;
    private final Host host;

    private final JLabel lblAvatar = new JLabel("?", JLabel.CENTER);
    private final JLabel lblUsername = new JLabel(" ");
    private final JLabel lblPublicId = new JLabel("ID người dùng: Đang tải...");
    private final JLabel lblStatus = new JLabel("Đang tải hồ sơ...");
    private final JTextField txtDisplayName = new JTextField(24);
    private final JTextField txtEmail = new JTextField(24);
    private final JTextField txtPhone = new JTextField(24);
    private final JTextField txtBirthday = new JTextField(24);
    private final JComboBox<String> cboGender = new JComboBox<>(
            new String[]{"", "Không muốn nêu", "Nam", "Nữ", "Khác"});
    private final JTextArea txtBio = new JTextArea(4, 24);
    private final JButton btnChooseAvatar = new JButton("Chọn ảnh đại diện...");
    private final JButton btnSave = new JButton("Lưu hồ sơ");
    private byte[] pendingAvatarBytes;

    public UserProfileDialog(java.awt.Frame parent, String targetUsername,
                             boolean editable, Host host) {
        super(parent, "Hồ sơ người dùng", ModalityType.APPLICATION_MODAL);
        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên tài khoản không được để trống.");
        }
        if (host == null) throw new IllegalArgumentException("Host không được null.");
        this.targetUsername = targetUsername.trim();
        this.editable = editable;
        this.host = host;
        buildUi();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(530, 560));
        pack();
        setLocationRelativeTo(parent);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JPanel top = new JPanel(new BorderLayout(14, 0));
        lblAvatar.setPreferredSize(new Dimension(96, 96));
        lblAvatar.setMinimumSize(new Dimension(96, 96));
        lblAvatar.setOpaque(true);
        lblAvatar.setBackground(new Color(226, 232, 240));
        lblAvatar.setForeground(new Color(51, 65, 85));
        lblAvatar.setFont(lblAvatar.getFont().deriveFont(Font.BOLD, 30f));
        lblAvatar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));
        lblUsername.setText("@" + targetUsername);
        lblUsername.setFont(lblUsername.getFont().deriveFont(Font.BOLD, 18f));
        JPanel titlePanel = new JPanel(new BorderLayout(0, 8));
        titlePanel.setOpaque(false);
        JPanel identityPanel = new JPanel();
        identityPanel.setOpaque(false);
        identityPanel.setLayout(new javax.swing.BoxLayout(identityPanel, javax.swing.BoxLayout.Y_AXIS));
        lblUsername.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblPublicId.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblPublicId.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblPublicId.setForeground(new Color(37, 99, 235));
        JLabel detail = new JLabel(editable ? "Chỉnh sửa thông tin cá nhân" : "Thông tin người dùng");
        detail.setForeground(new Color(100, 116, 139));
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        identityPanel.add(lblUsername);
        identityPanel.add(Box.createVerticalStrut(5));
        identityPanel.add(lblPublicId);
        identityPanel.add(Box.createVerticalStrut(5));
        identityPanel.add(detail);
        titlePanel.add(identityPanel, BorderLayout.CENTER);
        if (editable) {
            btnChooseAvatar.addActionListener(e -> chooseAvatar());
            titlePanel.add(btnChooseAvatar, BorderLayout.SOUTH);
        } else {
            btnChooseAvatar.setVisible(false);
        }
        top.add(lblAvatar, BorderLayout.WEST);
        top.add(titlePanel, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 3, 5, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        addField(fields, c, 0, "Tên hiển thị", txtDisplayName);
        addField(fields, c, 1, "Email", txtEmail);
        addField(fields, c, 2, "Số điện thoại", txtPhone);
        addField(fields, c, 3, "Ngày sinh (yyyy-MM-dd)", txtBirthday);
        addField(fields, c, 4, "Giới tính", cboGender);

        txtBio.setLineWrap(true);
        txtBio.setWrapStyleWord(true);
        txtBio.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        c.gridx = 0; c.gridy = 5; c.weightx = 0; c.weighty = 0;
        fields.add(new JLabel("Giới thiệu"), c);
        c.gridx = 1; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        fields.add(new JScrollPane(txtBio), c);
        root.add(fields, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        lblStatus.setForeground(new Color(100, 116, 139));
        bottom.add(lblStatus, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton btnClose = new JButton("Đóng");
        btnClose.addActionListener(e -> dispose());
        buttons.add(btnClose);
        if (editable) {
            btnSave.addActionListener(e -> save());
            buttons.add(btnSave);
        } else {
            btnSave.setVisible(false);
        }
        bottom.add(buttons, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        setEditableFields(editable);
        setContentPane(root);
    }

    private void addField(JPanel panel, GridBagConstraints c, int row,
                          String label, java.awt.Component field) {
        c.gridy = row; c.gridx = 0; c.weightx = 0; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
    }

    private void setEditableFields(boolean value) {
        txtDisplayName.setEditable(value);
        txtEmail.setEditable(value);
        txtPhone.setEditable(value);
        txtBirthday.setEditable(value);
        cboGender.setEnabled(value);
        txtBio.setEditable(value);
    }

    public String getTargetUsername() { return targetUsername; }

    /** Hiển thị ID công khai 5 chữ số do server cấp; người dùng không thể sửa trường này. */
    public void setPublicId(String publicId) {
        String value = publicId == null ? "" : publicId.trim();
        if (value.matches("\\d{5}")) {
            lblPublicId.setText("ID người dùng: " + value);
        } else {
            lblPublicId.setText("ID người dùng: Chưa có ID");
        }
    }

    public void setProfile(String displayName, String email, String phone,
                           String birthday, String gender, String bio) {
        txtDisplayName.setText(safe(displayName));
        txtEmail.setText(safe(email));
        txtPhone.setText(safe(phone));
        txtBirthday.setText(safe(birthday));
        cboGender.setSelectedItem(safe(gender));
        txtBio.setText(safe(bio));
        lblStatus.setText("Đã tải hồ sơ.");
    }

    public void setAvatarBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            pendingAvatarBytes = null;
            lblAvatar.setIcon(null);
            lblAvatar.setText(initial(targetUsername));
            return;
        }
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) return;
            Image scaled = image.getScaledInstance(92, 92, Image.SCALE_SMOOTH);
            lblAvatar.setText("");
            lblAvatar.setIcon(new javax.swing.ImageIcon(scaled));
        } catch (IOException ignored) {
            lblAvatar.setText(initial(targetUsername));
        }
    }

    public void setStatus(String message, boolean isError) {
        lblStatus.setText(safe(message));
        lblStatus.setForeground(isError ? new Color(185, 28, 28) : new Color(71, 85, 105));
        if (btnSave != null) btnSave.setEnabled(editable);
    }

    private void chooseAvatar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn ảnh đại diện");
        chooser.setFileFilter(new FileNameExtensionFilter("Ảnh PNG/JPEG", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        try {
            BufferedImage source = ImageIO.read(file);
            if (source == null) {
                JOptionPane.showMessageDialog(this, "File không phải ảnh PNG/JPEG hợp lệ.",
                        "Ảnh không hợp lệ", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (source.getWidth() <= 0 || source.getHeight() <= 0
                    || source.getWidth() > 8000 || source.getHeight() > 8000
                    || (long) source.getWidth() * source.getHeight() > 30_000_000L) {
                JOptionPane.showMessageDialog(this, "Kích thước ảnh quá lớn.",
                        "Ảnh không hợp lệ", JOptionPane.WARNING_MESSAGE);
                return;
            }
            double scale = Math.min(1.0, 256.0 / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = normalized.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
            g.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(normalized, "jpg", output)) {
                throw new IOException("Không thể chuyển ảnh sang JPEG.");
            }
            byte[] jpeg = output.toByteArray();
            if (jpeg.length == 0 || jpeg.length > MAX_AVATAR_BYTES) {
                JOptionPane.showMessageDialog(this, "Ảnh đại diện sau khi xử lý vượt quá 512 KB.",
                        "Ảnh quá lớn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            pendingAvatarBytes = jpeg;
            setAvatarBytes(jpeg);
            lblStatus.setText("Đã chọn ảnh. Nhấn Lưu hồ sơ để tải lên server.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Không đọc được ảnh: " + ex.getMessage(),
                    "Lỗi ảnh", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        String displayName = txtDisplayName.getText().trim();
        String email = txtEmail.getText().trim();
        String phone = txtPhone.getText().trim();
        String birthday = txtBirthday.getText().trim();
        String gender = safe((String) cboGender.getSelectedItem()).trim();
        String bio = txtBio.getText().trim();
        if (displayName.length() > 80) {
            showValidation("Tên hiển thị tối đa 80 ký tự."); return;
        }
        if (email.length() > 254 || (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches())) {
            showValidation("Email không hợp lệ."); return;
        }
        if (!phone.matches("[0-9+() .-]{0,32}")) {
            showValidation("Số điện thoại chỉ được gồm số và các ký tự + ( ) . - hoặc khoảng trắng."); return;
        }
        if (!birthday.isEmpty()) {
            try { LocalDate.parse(birthday); }
            catch (DateTimeParseException ex) { showValidation("Ngày sinh phải có định dạng yyyy-MM-dd."); return; }
        }
        if (bio.length() > 500) {
            showValidation("Giới thiệu bản thân tối đa 500 ký tự."); return;
        }
        setStatus("Đang gửi thay đổi...", false);
        host.updateProfile(displayName, email, phone, birthday, gender, bio);
        if (pendingAvatarBytes != null) {
            host.uploadAvatar(pendingAvatarBytes);
            pendingAvatarBytes = null;
        }
    }

    private void showValidation(String message) {
        JOptionPane.showMessageDialog(this, message, "Thông tin chưa hợp lệ", JOptionPane.WARNING_MESSAGE);
    }

    private static String initial(String value) {
        String s = safe(value).trim();
        return s.isEmpty() ? "?" : s.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
