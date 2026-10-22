package com.chat.client.model;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The chat client model: owns the TCP connection and speaks the protocol.
 *
 * <p>Usage: {@link #connect(String)} (blocking handshake), then {@link #setListener},
 * then {@link #startListening()}; afterwards feed what the user types to
 * {@link #sendUserInput(String)}. Completely independent of JavaFX.
 */
public class ChatClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    public enum Mode { CHAT, READ_ONLY }

    private final String host;
    private final int port;

    private volatile ChatListener listener = new ChatListener() {
        @Override public void onMessage(String sender, String formattedText) { }
        @Override public void onInfo(String text) { }
        @Override public void onUserList(List<String> usernames) { }
        @Override public void onDisconnected(String reason) { }
    };

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private volatile Mode mode = Mode.CHAT;
    private volatile String username;
    private final AtomicBoolean connected = new AtomicBoolean();
    /** Single thread => messages leave in the order the user typed them. */
    private final ExecutorService sender =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("client-sender").factory());

    public ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setListener(ChatListener listener) {
        this.listener = listener;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** Username, or {@code null} in read-only mode. */
    public String getUsername() {
        return username;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isReadOnly() {
        return mode == Mode.READ_ONLY;
    }

    public boolean isConnected() {
        return connected.get();
    }

    // --------------------------------------------------------- connection

    /**
     * Opens the connection and performs the JOIN handshake. Blocking: call it off the UI thread.
     *
     * @param requestedUsername empty or blank to join in READ-ONLY MODE
     * @throws ConnectionRejectedException the server refused the username
     * @throws IOException                 the server is unreachable or misbehaves
     */
    public void connect(String requestedUsername) throws IOException {
        String name = requestedUsername == null ? "" : requestedUsername.trim();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            writeLine(Protocol.frame(Protocol.JOIN, name));

            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            String reply = in.readLine();
            s.setSoTimeout(0);
            if (reply == null) {
                throw new IOException("The server closed the connection.");
            }
            String[] parts = Protocol.split(reply);
            switch (parts[0]) {
                case Protocol.WELCOME -> {
                    mode = Mode.CHAT;
                    username = Protocol.unescape(parts[1]);
                }
                case Protocol.READ_ONLY -> {
                    mode = Mode.READ_ONLY;
                    username = null;
                }
                case Protocol.ERROR -> throw new ConnectionRejectedException(Protocol.unescape(parts[1]));
                default -> throw new IOException("Unexpected reply from server: " + parts[0]);
            }
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // original failure is more useful
            }
            throw e;
        }
        socket = s;
        connected.set(true);
    }

    /** Starts the background reader. Call after {@link #setListener} so no event is lost. */
    public void startListening() {
        Thread.ofVirtual().name("client-reader").start(this::readLoop);
    }

    private void readLoop() {
        String reason = "Connection to the server was lost.";
        try {
            String line;
            while (connected.get() && (line = in.readLine()) != null) {
                String[] parts = Protocol.split(line);
                String payload = Protocol.unescape(parts[1]);
                switch (parts[0]) {
                    case Protocol.MSG -> {
                        int sep = payload.indexOf(Protocol.SEP);
                        if (sep > 0) {
                            listener.onMessage(payload.substring(0, sep), payload.substring(sep + 1));
                        }
                    }
                    case Protocol.INFO, Protocol.ERROR -> listener.onInfo(payload);
                    case Protocol.USERS -> listener.onUserList(
                            payload.isEmpty() ? List.of() : Arrays.asList(payload.split(",")));
                    case Protocol.BYE -> {
                        reason = payload.isEmpty() ? "The server closed the connection." : payload;
                        if (connected.compareAndSet(true, false)) {
                            closeQuietly();
                            sender.shutdown();
                            listener.onDisconnected(reason);
                        }
                        return;
                    }
                    default -> { /* ignore unknown frames */ }
                }
            }
        } catch (IOException e) {
            // fall through: lost connection
        }
        if (connected.compareAndSet(true, false)) {
            closeQuietly();
            sender.shutdown();
            listener.onDisconnected(reason);
        }
    }

    // ------------------------------------------------------------ sending

    /**
     * Interprets what the user typed: {@code end}/{@code bye} leave, {@code allUsers} asks the
     * server for the active users, anything else is a chat message (refused in read-only mode).
     */
    public void sendUserInput(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || !connected.get()) {
            return;
        }
        if (text.equalsIgnoreCase("end") || text.equalsIgnoreCase("bye")) {
            disconnect();
        } else if (text.equalsIgnoreCase("allUsers")) {
            enqueue(Protocol.ALL_USERS);
        } else if (isReadOnly()) {
            listener.onInfo("READ-ONLY MODE: enter a username to send messages.");
        } else {
            enqueue(Protocol.frame(Protocol.MSG, text));
        }
    }

    /** Leaves the chat: notifies the server (LEAVE), closes the connection and reports it. */
    public void disconnect() {
        if (!connected.compareAndSet(true, false)) {
            return;
        }
        try {
            sender.execute(() -> {
                try {
                    writeLine(Protocol.LEAVE);
                } catch (IOException ignored) {
                    // server already gone
                } finally {
                    closeQuietly();
                }
            });
            sender.shutdown();
            sender.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            closeQuietly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.onDisconnected("You left the chat.");
    }

    private void enqueue(String line) {
        try {
            sender.execute(() -> {
                try {
                    writeLine(line);
                } catch (IOException e) {
                    if (connected.compareAndSet(true, false)) {
                        closeQuietly();
                        sender.shutdown();
                        listener.onDisconnected("Connection to the server was lost.");
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // already disconnected
        }
    }

    private void writeLine(String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}
