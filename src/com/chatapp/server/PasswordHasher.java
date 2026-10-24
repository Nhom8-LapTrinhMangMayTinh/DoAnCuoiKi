package com.chatapp.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Lưu mật khẩu theo PBKDF2-HMAC-SHA256 có salt riêng cho từng tài khoản.
 * Hỗ trợ xác minh hash SHA-256 cũ để không buộc người dùng tạo tài khoản mới.
 */
public final class PasswordHasher {
    private static final String PREFIX = "PBKDF2$";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Mật khẩu không được null.");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password.toCharArray(), salt, ITERATIONS, HASH_BITS);
        return PREFIX + ITERATIONS + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    public static boolean isModernHash(String encoded) {
        return encoded != null && encoded.startsWith(PREFIX);
    }

    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null || encoded.isEmpty()) {
            return false;
        }
        if (!isModernHash(encoded)) {
            // Tương thích các tài khoản cũ đang dùng SecurityUtils.
            try {
                return SecurityUtils.verifyPassword(password, encoded);
            } catch (Exception ex) {
                return false;
            }
        }

        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"PBKDF2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000 || iterations > 1_000_000) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < 8 || salt.length > 64 || expected.length != HASH_BITS / 8) {
                return false;
            }
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể tạo hash mật khẩu bằng PBKDF2.", ex);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }
}
