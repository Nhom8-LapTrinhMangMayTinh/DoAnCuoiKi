package com.chatapp.security;

import java.util.regex.Pattern;

/**
 * Quy tắc kiểm tra tài khoản và mật khẩu dùng chung ở client/server.
 * Server vẫn là nơi quyết định cuối cùng; client chỉ kiểm tra sớm để báo lỗi dễ hiểu.
 */
public final class PasswordPolicy {
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.-]{3,20}$");

    private PasswordPolicy() {
    }

    public static String usernameError(String username) {
        if (username == null || username.trim().isEmpty()) {
            return "Tên đăng nhập không được để trống.";
        }
        String value = username.trim();
        if (value.length() < 3 || value.length() > 20) {
            return "Tên đăng nhập phải có từ 3 đến 20 ký tự.";
        }
        if (!USERNAME_PATTERN.matcher(value).matches()) {
            return "Tên đăng nhập chỉ được dùng chữ cái không dấu, số, dấu chấm, gạch dưới hoặc gạch ngang.";
        }
        return null;
    }

    public static String passwordError(String password) {
        return passwordError(password, null);
    }

    public static String passwordError(String password, String username) {
        if (password == null || password.isEmpty()) {
            return "Mật khẩu không được để trống.";
        }
        if (password.length() < 8) {
            return "Mật khẩu phải có ít nhất 8 ký tự.";
        }
        if (password.length() > 64) {
            return "Mật khẩu không được vượt quá 64 ký tự.";
        }
        if (password.indexOf('|') >= 0 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
            return "Mật khẩu không được chứa ký tự | hoặc xuống dòng.";
        }

        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            hasLower |= Character.isLowerCase(ch);
            hasUpper |= Character.isUpperCase(ch);
            hasDigit |= Character.isDigit(ch);
        }
        if (!hasLower || !hasUpper || !hasDigit) {
            return "Mật khẩu phải có ít nhất 1 chữ thường, 1 chữ hoa và 1 chữ số.";
        }

        if (username != null && !username.trim().isEmpty()
                && password.toLowerCase().contains(username.trim().toLowerCase())) {
            return "Mật khẩu không nên chứa tên đăng nhập.";
        }
        return null;
    }
}
