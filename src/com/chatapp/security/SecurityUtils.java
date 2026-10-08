package com.chatapp.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class SecurityUtils {

    private SecurityUtils() {
        // Private constructor để ngăn tạo đối tượng không cần thiết
    }

    /**
     * Băm chuỗi văn bản bằng thuật toán SHA-256
     * @param input Chuỗi cần băm (mật khẩu gốc, chuỗi xác thực...)
     * @return Chuỗi Hex 64 ký tự (hoặc null nếu đầu vào null/lỗi)
     */
    public static String hashSHA256(String input) {
        if (input == null) {
            return null;
        }
        return hashAlgorithm(input, "SHA-256");
    }

    /**
     * Băm mật khẩu kèm Chuỗi Salt (tăng cường bảo mật chống tấn công Rainbow Table)
     * @param password Mật khẩu gốc
     * @param salt Chuỗi muối riêng biệt (ví dụ: username hoặc chuỗi ngẫu nhiên)
     * @return Chuỗi Hex SHA-256 đã qua Salt
     */
    public static String hashSHA256WithSalt(String password, String salt) {
        if (password == null) {
            return null;
        }
        String saltedInput = (salt != null ? salt : "") + password;
        return hashSHA256(saltedInput);
    }

    /**
     * Băm chuỗi bằng MD5 (dùng cho checksum kiểm tra tính toàn vẹn của File)
     * @param input Chuỗi cần băm
     * @return Chuỗi Hex MD5
     */
    public static String hashMD5(String input) {
        if (input == null) {
            return null;
        }
        return hashAlgorithm(input, "MD5");
    }

    /**
     * Kiểm tra xem mật khẩu nhập vào có khớp với hash đã lưu hay không
     */
    public static boolean verifyPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        String hashOfInput = hashSHA256(rawPassword);
        return storedHash.equalsIgnoreCase(hashOfInput);
    }

    // ==========================================
    // HÀM TIỆN ÍCH DÙNG CHUNG (PRIVATE HELPER)
    // ==========================================
    private static String hashAlgorithm(String input, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}