package com.chatapp.protocol;

public class Protocol {
    // Ký tự phân tách lệnh
    public static final String DELIMITER = "|";

    // Danh sách lệnh Xác thực
    public static final String AUTH_REGISTER = "AUTH_REGISTER";
    public static final String AUTH_LOGIN = "AUTH_LOGIN";

    // Danh sách lệnh Quản lý phòng
    public static final String CREATE_ROOM = "CREATE_ROOM";
    public static final String JOIN_ROOM = "JOIN_ROOM";
    public static final String LEAVE_ROOM = "LEAVE_ROOM";

    // Danh sách lệnh Tin nhắn & File
    public static final String SEND_MSG_GROUP = "SEND_MSG_GROUP";
    public static final String SEND_MSG_PRIVATE = "SEND_MSG_PRIVATE";
    public static final String SEND_FILE = "SEND_FILE";
    
    // Danh sách lệnh Lịch sử tin nhắn
    public static final String LOAD_HISTORY_PAGED = "LOAD_HISTORY_PAGED";
}