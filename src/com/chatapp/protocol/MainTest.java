package com.chatapp;

import com.chatapp.protocol.Protocol;
import com.chatapp.security.AESUtils;
import com.chatapp.security.SecurityUtils;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class MainTest {
    public static void main(String[] args) {
        try {
            // Ép luồng in ra Output NetBeans / Console sử dụng chuẩn UTF-8
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // --- CODE TEST GIỮ NGUYÊN BÊN DƯỚI ---
        String rawPass = "123456";
        String hashedPass = SecurityUtils.hashSHA256(rawPass);
        System.out.println("-> Pass gốc: " + rawPass);
        System.out.println("-> Pass sau khi băm SHA-256: " + hashedPass);

        String originalMsg = "Xin chào, đây là tin nhắn bí mật!";
        String encryptedMsg = AESUtils.encrypt(originalMsg);
        String decryptedMsg = AESUtils.decrypt(encryptedMsg);
        
        System.out.println("\n-> Tin nhắn gốc: " + originalMsg);
        System.out.println("-> Tin nhắn mã hóa AES: " + encryptedMsg);
        System.out.println("-> Tin nhắn sau giải mã: " + decryptedMsg);

        String command = Protocol.SEND_MSG_GROUP + Protocol.DELIMITER + "ROOM_01" + Protocol.DELIMITER + encryptedMsg;
        System.out.println("\n-> Chuỗi Protocol truyền đi qua Socket: " + command);
    }
}