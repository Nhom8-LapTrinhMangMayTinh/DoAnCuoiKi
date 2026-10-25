package com.chatapp.client;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Ephemeral group media key. The server sees only public keys and RSA-wrapped AES keys. */
final class GroupCallCrypto {
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private KeyPair keyPair;
    private volatile SecretKey groupKey;

    GroupCallCrypto(boolean keyOwner) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        keyPair = generator.generateKeyPair();
        if (keyOwner) {
            KeyGenerator aes = KeyGenerator.getInstance("AES");
            aes.init(256, RANDOM);
            groupKey = aes.generateKey();
        }
    }

    synchronized String createPublicKey() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    synchronized String wrapGroupKeyFor(String publicKeyToken) throws Exception {
        if (groupKey == null) throw new IllegalStateException("Chưa có khóa nhóm.");
        byte[] encoded = Base64.getDecoder().decode(publicKeyToken);
        PublicKey target = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, target);
        return Base64.getEncoder().encodeToString(cipher.doFinal(groupKey.getEncoded()));
    }

    synchronized void acceptGroupKeyOffer(String wrappedToken) throws Exception {
        byte[] wrapped = Base64.getDecoder().decode(wrappedToken);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] raw = cipher.doFinal(wrapped);
        try { groupKey = new SecretKeySpec(raw, "AES"); }
        finally { Arrays.fill(raw, (byte) 0); }
    }

    boolean isReady() { return groupKey != null; }

    byte[] encrypt(byte[] plain) throws Exception {
        SecretKey key = groupKey;
        if (key == null) throw new IllegalStateException("Khóa nhóm chưa sẵn sàng.");
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] packet = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packet, 0, nonce.length);
        System.arraycopy(encrypted, 0, packet, nonce.length, encrypted.length);
        return packet;
    }

    byte[] decrypt(byte[] packet) throws Exception {
        SecretKey key = groupKey;
        if (key == null || packet == null || packet.length <= NONCE_BYTES + 16) {
            throw new IllegalArgumentException("Gói media nhóm không hợp lệ.");
        }
        byte[] nonce = Arrays.copyOfRange(packet, 0, NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(packet, NONCE_BYTES, packet.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(encrypted);
    }

    synchronized void clear() { groupKey = null; keyPair = null; }
}
