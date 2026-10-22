package com.chat.server.model;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The chat server model: accepts TCP connections and distributes messages.
 *
 * <p>Every accepted connection is served by its own <em>virtual thread</em>
 * ({@link Executors#newVirtualThreadPerTaskExecutor()}), so thousands of idle
 * clients cost almost nothing and messages flow in real time.
 *
 * <p>This class is completely independent of JavaFX; it talks to the outside
 * world only through {@link ServerListener}.
 */
public class ChatServer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerConfig config;
    private final ServerListener listener;

    /** Every open connection, including those that have not finished joining. */
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    /** Joined users with a username, keyed by lower-cased name (names are unique). */
    private final Map<String, ClientHandler> users = new ConcurrentHashMap<>();

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService executor;
    private volatile boolean running;

    public ChatServer(ServerConfig config, ServerListener listener) {
        this.config = config;
        this.listener = listener;
    }

    // ------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName(config.bindAddress()), config.port()));
        serverSocket = ss;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        listener.onServerStarted(config.bindAddress(), ss.getLocalPort());
        listener.onLog("Server Started - listening on " + config.bindAddress() + ":" + ss.getLocalPort());
        executor.submit(this::acceptLoop);
    }

    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (ClientHandler handler : connections) {
            handler.finish(Protocol.frame(Protocol.BYE, "Server is shutting down."));
        }
        ExecutorService ex = executor;
        ex.shutdown();
        try {
            if (!ex.awaitTermination(1, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
        listener.onLog("Server Stopped");
        listener.onServerStopped();
    }

    public boolean isRunning() {
        return running;
    }

    /** The port actually bound (useful when the configured port is 0). */
    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? config.port() : ss.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            listener.onLog("Waiting for Client...");
            try {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket, this);
                connections.add(handler);
                listener.onLog("Client connected from " + handler.remoteAddress());
                executor.submit(handler);
            } catch (IOException e) {
                if (running) {
                    listener.onLog("Accept failed: " + e.getMessage());
                    pause();
                }
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------- used by ClientHandler

    void log(String message) {
        listener.onLog(message);
    }

    /** Atomically claims a username; false when someone already uses it. */
    boolean registerUser(String username, ClientHandler handler) {
        return users.putIfAbsent(key(username), handler) == null;
    }

    void userJoined(ClientHandler handler) {
        String name = handler.username();
        listener.onUserJoined(name);
        listener.onLog("Welcome " + name);
        handler.send(Protocol.frame(Protocol.INFO,
                "Welcome " + name + "! Type allUsers to see who is online, or end / bye to leave."));
        for (ClientHandler other : connections) {
            if (other != handler && other.isJoined()) {
                other.send(Protocol.frame(Protocol.INFO, name + " joined the chat."));
            }
        }
    }

    void readOnlyJoined(ClientHandler handler) {
        listener.onLog("Read-only client joined from " + handler.remoteAddress());
    }

    void disconnected(ClientHandler handler) {
        if (!connections.remove(handler)) {
            return; // already handled
        }
        if (!handler.isJoined()) {
            listener.onLog("Connection from " + handler.remoteAddress() + " closed before joining");
            return;
        }
        if (handler.isReadOnly()) {
            listener.onLog("Read-only client " + handler.remoteAddress() + " disconnected");
            return;
        }
        String name = handler.username();
        users.remove(key(name), handler);
        listener.onUserLeft(name);
        listener.onLog(name + " left the chat");
        broadcast(Protocol.frame(Protocol.INFO, name + " left the chat."));
    }

    /** Formats a message with the sender's name and the time, then sends it to everybody. */
    void chat(ClientHandler sender, String text) {
        String name = sender.username();
        String formatted = "[" + LocalTime.now().format(TIME) + "] " + name + ": " + text;
        listener.onLog("Message from " + name + ": " + text);
        broadcast(Protocol.frame(Protocol.MSG, name + Protocol.SEP + formatted));
    }

    void broadcast(String line) {
        for (ClientHandler handler : connections) {
            if (handler.isJoined()) {
                handler.send(line);
            }
        }
    }

    List<String> userNames() {
        return users.values().stream().map(ClientHandler::username).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
