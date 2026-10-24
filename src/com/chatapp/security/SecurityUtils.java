package com.chatapp.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

public final class SecurityUtils {

    private static final String PBKDF2_PREFIX = "pbkdf2-sha256";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BITS = 256;
    private static final int CURRENT_ITERATIONS = 600_000;

    // Giới hạn để tránh xử lý hash với số vòng lặp bất thường.
    private static final int MIN_ACCEPTED_ITERATIONS = 10_000;
    private static final int MAX_ACCEPTED_ITERATIONS = 2_000_000;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecurityUtils() {
    }

    /**
     * Tạo hash mật khẩu PBKDF2-HMAC-SHA256 với salt ngẫu nhiên riêng.
     *
     * Định dạng:
     * pbkdf2-sha256$iterations$base64Salt$base64Hash
     */
    public static String hashPassword(String password) {
        if (password == null) {
            return null;
        }

        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        try {
            byte[] hash = derivePasswordHash(
                    password,
                    salt,
                    CURRENT_ITERATIONS
            );

            return PBKDF2_PREFIX
                    + "$" + CURRENT_ITERATIONS
                    + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(hash);

        } catch (GeneralSecurityException e) {
            System.err.println(
                    "[SecurityUtils] Lỗi tạo hash mật khẩu: "
                    + e.getMessage()
            );
            return null;
        }
    }

    /**
     * Kiểm tra mật khẩu.
     *
     * Hỗ trợ:
     * 1. PBKDF2 mới.
     * 2. SHA-256 hex cũ để không khóa các tài khoản hiện có.
     */
    public static boolean verifyPassword(
            String rawPassword,
            String storedHash) {

        if (rawPassword == null || storedHash == null) {
            return false;
        }

        if (storedHash.startsWith(PBKDF2_PREFIX + "$")) {
            return verifyPbkdf2(rawPassword, storedHash);
        }

        // Tương thích tài khoản đã lưu hash SHA-256 64 ký tự hex.
        if (isLegacySha256Hash(storedHash)) {
            String calculatedHash = hashSHA256(rawPassword);

            if (calculatedHash == null) {
                return false;
            }

            byte[] expected = storedHash
                    .toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.US_ASCII);

            byte[] actual = calculatedHash
                    .getBytes(StandardCharsets.US_ASCII);

            return MessageDigest.isEqual(expected, actual);
        }

        return false;
    }

    /**
     * Cho biết hash có cần nâng cấp sang thông số hiện tại không.
     * Dùng sau khi verifyPassword() đã xác thực thành công.
     */
    public static boolean needsUpgrade(String storedHash) {
        if (storedHash == null) {
            return false;
        }

        if (isLegacySha256Hash(storedHash)) {
            return true;
        }

        if (!storedHash.startsWith(PBKDF2_PREFIX + "$")) {
            return false;
        }

        String[] fields = storedHash.split("\\$", -1);

        if (fields.length != 4) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(fields[1]);
            return iterations < CURRENT_ITERATIONS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean verifyPbkdf2(
            String password,
            String storedHash) {

        try {
            String[] fields = storedHash.split("\\$", -1);

            if (fields.length != 4
                    || !PBKDF2_PREFIX.equals(fields[0])) {
                return false;
            }

            int iterations = Integer.parseInt(fields[1]);

            if (iterations < MIN_ACCEPTED_ITERATIONS
                    || iterations > MAX_ACCEPTED_ITERATIONS) {
                return false;
            }

            byte[] salt = Base64.getDecoder().decode(fields[2]);
            byte[] expectedHash = Base64.getDecoder().decode(fields[3]);

            if (salt.length < 16 || expectedHash.length != 32) {
                return false;
            }

            byte[] actualHash = derivePasswordHash(
                    password,
                    salt,
                    iterations
            );

            return MessageDigest.isEqual(expectedHash, actualHash);

        } catch (GeneralSecurityException
                | IllegalArgumentException e) {

            return false;
        }
    }

    private static byte[] derivePasswordHash(
            String password,
            byte[] salt,
            int iterations) throws GeneralSecurityException {

        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                iterations,
                HASH_LENGTH_BITS
        );

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);

            return factory.generateSecret(spec).getEncoded();

        } finally {
            spec.clearPassword();
        }
    }

    private static boolean isLegacySha256Hash(String value) {
        return value != null
                && value.matches("(?i)[0-9a-f]{64}");
    }

    /**
     * Hàm SHA-256 thông thường, giữ lại để tương thích mã cũ.
     * Không dùng hàm này để tạo hash mật khẩu mới.
     */
    public static String hashSHA256(String input) {
        if (input == null) {
            return null;
        }

        return hashAlgorithm(input, "SHA-256");
    }

    /**
     * Hàm băm SHA-256 có nối salt kiểu cũ.
     * Không thay thế PBKDF2 dành cho mật khẩu.
     */
    public static String hashSHA256WithSalt(
            String password,
            String salt) {

        if (password == null) {
            return null;
        }

        String saltedInput = (salt == null ? "" : salt) + password;

        return hashSHA256(saltedInput);
    }

    /**
     * Giữ lại để tương thích với mã kiểm tra checksum cũ.
     * Không dùng MD5 cho mục đích bảo mật mật mã.
     */
    public static String hashMD5(String input) {
        if (input == null) {
            return null;
        }

        return hashAlgorithm(input, "MD5");
    }

    private static String hashAlgorithm(
            String input,
            String algorithm) {

        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);

            byte[] hashBytes = md.digest(
                    input.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder hexString = new StringBuilder();

            for (byte b : hashBytes) {
                String hex = Integer.toHexString(b & 0xff);

                if (hex.length() == 1) {
                    hexString.append('0');
                }

                hexString.append(hex);
            }

            return hexString.toString();

        } catch (GeneralSecurityException e) {
            System.err.println(
                    "[SecurityUtils] Lỗi thuật toán "
                    + algorithm + ": " + e.getMessage()
            );
            return null;
        }
    }
}