package com.chatapp.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class AESUtils {

    /*
     * Giữ khóa hiện tại để các client đã dùng chung khóa
     * vẫn có thể đọc những tin nhắn được mã hóa trước đây.
     *
     * Lưu ý: khóa nhúng trong mã nguồn client không phải
     * cách quản lý khóa an toàn cho hệ thống thực tế.
     */
    private static final String SECRET_KEY = "ChatAppSecureKey";

    private static final String GCM_PREFIX = "GCM1:";
    private static final String AES_ALGORITHM = "AES";
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String LEGACY_TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AESUtils() {
    }

    private static SecretKeySpec getKey() {
        byte[] keyBytes = SECRET_KEY.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }

    /**
     * Mã hóa tin nhắn mới bằng AES-GCM.
     * Định dạng kết quả: GCM1:Base64(nonce + ciphertext + tag).
     */
    public static String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }

        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            SECURE_RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(
                    GCM_TAG_LENGTH_BITS,
                    nonce
            );

            cipher.init(Cipher.ENCRYPT_MODE, getKey(), gcmSpec);

            byte[] ciphertext = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8)
            );

            byte[] combined = new byte[nonce.length + ciphertext.length];

            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(
                    ciphertext,
                    0,
                    combined,
                    nonce.length,
                    ciphertext.length
            );

            return GCM_PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (GeneralSecurityException e) {
            System.err.println("[AESUtils] Lỗi mã hóa: " + e.getMessage());
            return null;
        }
    }

    /**
     * Giải mã tin nhắn GCM mới hoặc tin nhắn ECB cũ.
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return null;
        }

        if (encryptedText.startsWith(GCM_PREFIX)) {
            return decryptGcm(encryptedText.substring(GCM_PREFIX.length()));
        }

        // Tương thích với tin nhắn lịch sử mã hóa bằng AES/ECB.
        return decryptLegacyEcb(encryptedText);
    }

    private static String decryptGcm(String encodedData) {
        try {
            byte[] combined = Base64.getDecoder().decode(encodedData);

            if (combined.length <= GCM_NONCE_LENGTH) {
                return null;
            }

            byte[] nonce = Arrays.copyOfRange(
                    combined,
                    0,
                    GCM_NONCE_LENGTH
            );

            byte[] ciphertext = Arrays.copyOfRange(
                    combined,
                    GCM_NONCE_LENGTH,
                    combined.length
            );

            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
            );

            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Không trả về bản rõ nếu xác thực GCM thất bại.
            System.err.println("[AESUtils] Không thể xác thực/giải mã GCM.");
            return null;
        }
    }

    private static String decryptLegacyEcb(String encryptedText) {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedText);

            Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getKey());

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            System.err.println("[AESUtils] Lỗi giải mã tin nhắn cũ.");
            return null;
        }
    }
}