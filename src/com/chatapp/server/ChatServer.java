
package com.chatapp.server;

import com.chatapp.database.DatabaseManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {

    public static final int PORT = 12345;

    // username -> ClientHandler
    public static final ConcurrentHashMap<String, ClientHandler>
            onlineClients = new ConcurrentHashMap<>();

    // roomID -> danh sach thanh vien dang ket noi
    public static final ConcurrentHashMap<Integer,
            CopyOnWriteArrayList<ClientHandler>> roomMembers =
            new ConcurrentHashMap<>();

    // TCP sockets hiện đang được xử lý; khác với số người đã đăng nhập.
    public static final AtomicInteger tcpConnections = new AtomicInteger();

    private static final AtomicBoolean running = new AtomicBoolean(true);

    private static ExecutorService clientPool;

    private ChatServer() {
    }

    public static void main(String[] args) {
        // Create chat_files metadata table before DatabaseManager creates its indexes.
        FileTransferService.getInstance();
        DatabaseManager.initializeDatabase();
        AdminService.bootstrapAdmin();
        AdminService.startMetricsPublisher();

        clientPool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {

            System.out.println(
                    "[Server] Chat Server dang chay tai cong "
                    + PORT
            );

            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();

                    socket.setKeepAlive(true);
                    socket.setTcpNoDelay(true);

                    System.out.println(
                            "[Server] Client ket noi tu: "
                            + socket.getRemoteSocketAddress()
                    );

                    ClientHandler handler = new ClientHandler(socket);

                    clientPool.execute(handler);

                } catch (SocketException e) {
                    if (running.get()) {
                        System.err.println(
                                "[Server] Loi socket: "
                                + e.getMessage()
                        );
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println(
                                "[Server] Khong the tiep nhan client: "
                                + e.getMessage()
                        );
                    }
                }
            }

        } catch (IOException e) {
            System.err.println(
                    "[Server] Khong the mo cong " + PORT
                    + ". Co the cong dang duoc su dung."
            );
            e.printStackTrace();

        } finally {
            AdminService.stopMetricsPublisher();
            shutdownClientPool();
        }
    }

    // Goi ham nay khi can dung server tu code.
    public static void stopServer() {
        running.set(false);
    }

    private static void shutdownClientPool() {
        if (clientPool == null) {
            return;
        }

        clientPool.shutdown();

        try {
            if (!clientPool.awaitTermination(5, TimeUnit.SECONDS)) {
                clientPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            clientPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("[Server] Da dung client pool.");
    }
}