package com.chatapp.client;

import com.chatapp.protocol.Protocol;
import com.chatapp.security.PasswordPolicy;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class LoginFrame extends JFrame {
    private JTextField txtServerIP;
    private JTextField txtPort;
    private JTextField txtUsername;
    private JPasswordField txtPassword;
    private JButton btnLogin;
    private JButton btnRegister;

    public LoginFrame() {
        setTitle("Secure Chat - Đăng nhập hệ thống");
        setSize(780, 440);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(false);
        initComponents();
    }

    private void initComponents() {
        // --- GIAO DIỆN CHÍNH: CHIA 2 CỘT (SPLIT LAYOUT) ---
        JPanel root = new JPanel(new GridLayout(1, 2));
        root.setBackground(Color.WHITE);

        // 1. CỘT TRÁI: BRANDING / THÔNG TIN ỨNG DỤNG (Gradient độc đáo)
        JPanel brandPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, new Color(0x0D47A1), 0, getHeight(), new Color(0x1976D2));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        brandPanel.setBorder(new EmptyBorder(40, 30, 40, 30));

        JLabel brandIcon = new JLabel("🛡️");
        brandIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 46));
        brandIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("GROUP CHAT");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel desc = new JLabel("<html><div style='text-align:center; color:#E3F2FD; margin-top:10px; font-size:11px;'>Mã hóa AES & SHA-256<br>Bảo mật toàn diện cho nhóm</div></html>");
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);

        brandPanel.add(Box.createVerticalGlue());
        brandPanel.add(brandIcon);
        brandPanel.add(Box.createRigidArea(new Dimension(0, 15)));
        brandPanel.add(title);
        brandPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        brandPanel.add(desc);
        brandPanel.add(Box.createVerticalGlue());

        // 2. CỘT PHẢI: FORM ĐĂNG NHẬP / KẾT NỐI
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.setBorder(new EmptyBorder(30, 35, 30, 35));
        formPanel.setBackground(Color.WHITE);

        JLabel formTitle = new JLabel("Đăng nhập tài khoản");
        formTitle.setFont(new Font("Segoe UI", Font.BOLD, 18));
        formTitle.setForeground(new Color(0x212121));
        formTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel formSub = new JLabel("Mật khẩu đăng ký cần tối thiểu 8 ký tự, có chữ hoa, chữ thường và số");
        formSub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        formSub.setForeground(new Color(0x757575));
        formSub.setAlignmentX(Component.LEFT_ALIGNMENT);

        formPanel.add(formTitle);
        formPanel.add(formSub);
        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Khởi tạo các trường nhập liệu với kích thước chuẩn
        txtServerIP = new JTextField("100.119.81.67");
        txtPort = new JTextField("12345");
        txtUsername = new JTextField();
        txtPassword = new JPasswordField();

        // Server IP & Port (Được gom chung 1 hàng gọn gàng)
        JPanel serverRow = new JPanel(new BorderLayout(8, 0));
        serverRow.setBackground(Color.WHITE);
        serverRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        
        JPanel ipWrapper = createInputField("Server IP:", txtServerIP);
        JPanel portWrapper = createInputField("Port:", txtPort);
        portWrapper.setPreferredSize(new Dimension(80, 0));
        
        serverRow.add(ipWrapper, BorderLayout.CENTER);
        serverRow.add(portWrapper, BorderLayout.EAST);

        formPanel.add(serverRow);
        formPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        formPanel.add(createInputField("Tên đăng nhập:", txtUsername));
        formPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        formPanel.add(createInputField("Mật khẩu:", txtPassword));
        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Khu vực Nút bấm (Buttons)
        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        btnPanel.setBackground(Color.WHITE);
        btnPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        btnLogin = new JButton("Đăng nhập");
        btnLogin.setBackground(new Color(0x1976D2));
        btnLogin.setForeground(Color.WHITE);
        btnLogin.setFocusPainted(false);
        btnLogin.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnLogin.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnRegister = new JButton("Đăng ký");
        btnRegister.setBackground(new Color(0xF5F5F5));
        btnRegister.setForeground(new Color(0x333333));
        btnRegister.setFocusPainted(false);
        btnRegister.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnRegister.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnPanel.add(btnLogin);
        btnPanel.add(btnRegister);
        formPanel.add(btnPanel);

        root.add(brandPanel);
        root.add(formPanel);
        add(root);

        // Sự kiện Đăng nhập / Đăng ký (Giữ nguyên logic gốc của bạn)
        btnLogin.addActionListener(e -> authenticate(Protocol.AUTH_LOGIN));
        btnRegister.addActionListener(e -> authenticate(Protocol.AUTH_REGISTER));
        txtPassword.addActionListener(e -> authenticate(Protocol.AUTH_LOGIN));
        txtUsername.addActionListener(e -> txtPassword.requestFocus());
        getRootPane().setDefaultButton(btnLogin);
    }

    // Hàm tiện ích tạo giao diện cho từng trường input
    private JPanel createInputField(String labelText, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        panel.setBackground(Color.WHITE);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lbl.setForeground(new Color(0x616161));
        
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        if (field instanceof JTextField) {
            ((JTextField) field).setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xBDBDBD)),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)
            ));
        }
        
        panel.add(lbl, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void authenticate(String action) {
        String ip = txtServerIP.getText().trim();
        int port;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Cổng (Port) không hợp lệ!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String username = txtUsername.getText().trim();
        String password = new String(txtPassword.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu!", "Cảnh báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (username.contains("|") || password.contains("|")
                || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            JOptionPane.showMessageDialog(this, "Tên đăng nhập/mật khẩu chứa ký tự không được hỗ trợ.", "Thông tin không hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String confirmPassword = password;
        if (Protocol.AUTH_REGISTER.equals(action)) {
            String usernameError = PasswordPolicy.usernameError(username);
            if (usernameError != null) {
                JOptionPane.showMessageDialog(this, usernameError, "Tên đăng nhập không hợp lệ", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String passwordError = PasswordPolicy.passwordError(password, username);
            if (passwordError != null) {
                JOptionPane.showMessageDialog(this, passwordError, "Mật khẩu chưa đạt yêu cầu", JOptionPane.WARNING_MESSAGE);
                return;
            }

            JPasswordField confirmField = new JPasswordField(18);
            JPanel confirmPanel = new JPanel(new BorderLayout(0, 6));
            confirmPanel.add(new JLabel("Nhập lại mật khẩu để xác nhận:"), BorderLayout.NORTH);
            confirmPanel.add(confirmField, BorderLayout.CENTER);
            int confirmResult = JOptionPane.showConfirmDialog(
                    this, confirmPanel, "Xác nhận mật khẩu đăng ký",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (confirmResult != JOptionPane.OK_OPTION) {
                return;
            }
            confirmPassword = new String(confirmField.getPassword());
            if (!password.equals(confirmPassword)) {
                JOptionPane.showMessageDialog(this, "Mật khẩu xác nhận không khớp.", "Lỗi đăng ký", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        try {
            Socket socket = new Socket(ip, port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

            // Gửi xác thực lên server. Đăng ký gửi cả mật khẩu xác nhận.
            if (Protocol.AUTH_REGISTER.equals(action)) {
                out.println(Protocol.join(action, username, password, confirmPassword));
            } else {
                out.println(Protocol.join(action, username, password));
            }
            String response = in.readLine();
            if (response == null) {
                JOptionPane.showMessageDialog(this, "Mất kết nối từ Server!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                socket.close();
                return;
            }
            String[] parts = Protocol.split(response);

            if (action.equals(Protocol.AUTH_LOGIN)) {
                if (parts[0].equals(Protocol.AUTH_OK)) {
                    JOptionPane.showMessageDialog(this, "Đăng nhập thành công!");
                    this.dispose();
                    new MainFrame(socket, in, out, username).setVisible(true);
                } else {
                    JOptionPane.showMessageDialog(this, parts.length > 1 ? parts[1] : "Đăng nhập thất bại!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                    socket.close();
                }
            } else { // REGISTER
                if (parts[0].equals(Protocol.REGISTER_OK)) {
                    JOptionPane.showMessageDialog(this, "Đăng ký thành công! Bạn có thể đăng nhập ngay.");
                    socket.close();
                } else {
                    JOptionPane.showMessageDialog(this, parts.length > 1 ? parts[1] : "Đăng ký thất bại!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                    socket.close();
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Không thể kết nối tới Server: " + e.getMessage(), "Lỗi Mạng", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}