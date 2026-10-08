package com.chatapp.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class AESUtils {

    // Khóa bí mật mặc định 16 bytes (128-bit)
    private static final String DEFAULT_SECRET_KEY = "HiraTechGroupKey";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private AESUtils() {
        // Private constructor để ngăn tạo instance
    }

    // ==========================================
    // 1. MÃ HÓA & GIẢI MÃ VỚI KHÓA MẶC ĐỊNH
    // ==========================================

    /**
     * Mã hóa chuỗi với khóa mặc định
     */
    public static String encrypt(String strToEncrypt) {
        return encrypt(strToEncrypt, DEFAULT_SECRET_KEY);
    }

    /**
     * Giải mã chuỗi với khóa mặc định
     */
    public static String decrypt(String strToDecrypt) {
        return decrypt(strToDecrypt, DEFAULT_SECRET_KEY);
    }

    // ==========================================
    // 2. MÃ HÓA & GIẢI MÃ VỚI KHÓA TÙY CHỈNH (DYNAMIC KEY)
    // ==========================================

    /**
     * Mã hóa chuỗi văn bản bằng AES với khóa tùy chỉnh
     * @param strToEncrypt Chuỗi cần mã hóa
     * @param secretKey Khóa bí mật (sẽ được chuẩn hóa về 16 bytes)
     * @return Chuỗi đã mã hóa dạng Base64 (hoặc null nếu lỗi)
     */
    public static String encrypt(String strToEncrypt, String secretKey) {
        if (strToEncrypt == null || strToEncrypt.isEmpty()) {
            return strToEncrypt;
        }
        try {
            SecretKeySpec keySpec = buildKey(secretKey);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encryptedBytes = cipher.doFinal(strToEncrypt.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Giải mã chuỗi Base64 bằng AES với khóa tùy chỉnh
     * @param strToDecrypt Chuỗi Base64 cần giải mã
     * @param secretKey Khóa bí mật đã dùng để mã hóa
     * @return Chuỗi văn bản gốc (hoặc null nếu lỗi/sai khóa)
     */
    public static String decrypt(String strToDecrypt, String secretKey) {
        if (strToDecrypt == null || strToDecrypt.isEmpty()) {
            return strToDecrypt;
        }
        try {
            SecretKeySpec keySpec = buildKey(secretKey);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(strToDecrypt));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ==========================================
    // 3. HÀM TIỆN ÍCH CHUẨN HÓA KHÓA (16 BYTES)
    // ==========================================

    /**
     * Đảm bảo chuỗi secretKey luôn đúng độ dài 16 bytes (128 bits) cho AES
     */
    private static SecretKeySpec buildKey(String key) {
        byte[] keyBytes = new byte[16];
        byte[] inputBytes = key.getBytes(StandardCharsets.UTF_8);
        
        // Copy dữ liệu key vào mảng 16 bytes (cắt ngắn nếu dài hơn, bù byte 0 nếu ngắn hơn)
        int len = Math.min(inputBytes.length, keyBytes.length);
        System.arraycopy(inputBytes, 0, keyBytes, 0, len);

        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}