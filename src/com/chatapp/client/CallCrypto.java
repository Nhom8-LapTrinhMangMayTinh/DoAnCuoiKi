package com.chatapp.client;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Ephemeral RSA key exchange + AES/GCM for call media; the relay never receives the AES key in clear. */
final class CallCrypto {
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private KeyPair keyPair;
    private volatile SecretKey mediaKey;
    private volatile PublicKey peerPublicKey;

    synchronized String createPublicKey() throws Exception {
        if (keyPair == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, RANDOM);
            keyPair = generator.generateKeyPair();
        }
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    synchronized String createKeyOffer(String publicKeyToken) throws Exception {
        PublicKey peer = decodePublicKey(publicKeyToken);
        peerPublicKey = peer;
        if (mediaKey == null) {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, RANDOM);
            mediaKey = generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, peer);
        return Base64.getEncoder().encodeToString(cipher.doFinal(mediaKey.getEncoded()));
    }

    synchronized void acceptKeyOffer(String encryptedKey) throws Exception {
        if (keyPair == null) throw new IllegalStateException("Chưa tạo cặp khóa cuộc gọi.");
        byte[] wrapped = Base64.getDecoder().decode(encryptedKey);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] raw = cipher.doFinal(wrapped);
        mediaKey = new javax.crypto.spec.SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    boolean isReady() { return mediaKey != null; }

    synchronized void clear() { mediaKey = null; peerPublicKey = null; keyPair = null; }

    byte[] encrypt(byte[] plain) throws Exception {
        SecretKey key = mediaKey;
        if (key == null) throw new IllegalStateException("Khóa media chưa sẵn sàng.");
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] out = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
        return out;
    }

    byte[] decrypt(byte[] packet) throws Exception {
        SecretKey key = mediaKey;
        if (key == null || packet == null || packet.length <= NONCE_BYTES + 16) {
            throw new IllegalArgumentException("Gói media không hợp lệ.");
        }
        byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(packet, NONCE_BYTES, packet.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(encrypted);
    }

    static byte[] decodeToken(String token) {
        return Base64.getDecoder().decode(token);
    }

    private static PublicKey decodePublicKey(String token) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(token);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }
}
