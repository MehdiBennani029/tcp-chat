package com.chat.server.model;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Serves one client connection. {@link #run()} is executed on a virtual thread and
 * reads commands; a second virtual thread drains an outbound queue so that one slow
 * client can never block the broadcast to the others.
 */
final class ClientHandler implements Runnable {

    static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int OUTBOX_CAPACITY = 1000;
    private static final Pattern VALID_NAME = Pattern.compile("[\\p{L}\\p{N}_.-]{1,20}");
    private static final String POISON = "\u0000end-of-stream";

    private final Socket socket;
    private final ChatServer server;
    private final String remoteAddress;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** null for read-only clients (and before the handshake completes). */
    private volatile String username;
    private volatile boolean joined;

    ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
        this.remoteAddress = String.valueOf(socket.getRemoteSocketAddress());
    }

    String remoteAddress() {
        return remoteAddress;
    }

    String username() {
        return username;
    }

    boolean isJoined() {
        return joined;
    }

    boolean isReadOnly() {
        return username == null;
    }

    // ------------------------------------------------------------- reading

    @Override
    public void run() {
        Thread.ofVirtual().name("writer-" + remoteAddress).start(this::writeLoop);
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            if (handshake(in)) {
                String line;
                while ((line = in.readLine()) != null && handle(line)) {
                    // keep reading until the client leaves or the connection drops
                }
            }
        } catch (IOException e) {
            // client vanished, timed out during handshake, or the server is stopping
        } finally {
            server.disconnected(this);
            finish(null);
        }
    }

    private boolean handshake(BufferedReader in) throws IOException {
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        String line = in.readLine();
        socket.setSoTimeout(0);
        if (line == null) {
            return false;
        }
        String[] parts = Protocol.split(line);
        if (!Protocol.JOIN.equals(parts[0])) {
            send(Protocol.frame(Protocol.ERROR, "Expected JOIN as the first command."));
            return false;
        }
        String name = Protocol.unescape(parts[1]).trim();

        if (name.isEmpty()) {
            send(Protocol.READ_ONLY);
            joined = true; // username stays null => read-only
            server.readOnlyJoined(this);
            send(Protocol.frame(Protocol.INFO, "You joined in READ-ONLY MODE: you can read but not write."));
            return true;
        }
        if (!VALID_NAME.matcher(name).matches()) {
            send(Protocol.frame(Protocol.ERROR,
                    "Invalid username. Use 1-20 letters, digits, '_', '-' or '.'."));
            return false;
        }
        if (!server.registerUser(name, this)) {
            server.log("Rejected duplicate username '" + name + "' from " + remoteAddress);
            send(Protocol.frame(Protocol.ERROR, "Username '" + name + "' is already taken."));
            return false;
        }
        username = name;
        send(Protocol.frame(Protocol.WELCOME, name));
        joined = true;
        server.userJoined(this);
        return true;
    }

    /** @return false when the connection should be closed */
    private boolean handle(String line) {
        String[] parts = Protocol.split(line);
        switch (parts[0]) {
            case Protocol.MSG -> {
                if (isReadOnly()) {
                    send(Protocol.frame(Protocol.INFO, "READ-ONLY MODE: you cannot send messages."));
                    return true;
                }
                String text = Protocol.unescape(parts[1]).trim();
                if (text.isEmpty()) {
                    return true;
                }
                if (text.length() > MAX_MESSAGE_LENGTH) {
                    send(Protocol.frame(Protocol.INFO,
                            "Message too long (max " + MAX_MESSAGE_LENGTH + " characters)."));
                    return true;
                }
                server.chat(this, text);
            }
            case Protocol.ALL_USERS -> {
                server.log((isReadOnly() ? "Read-only client " + remoteAddress : username)
                        + " requested the user list");
                send(Protocol.frame(Protocol.USERS, String.join(",", server.userNames())));
            }
            case Protocol.LEAVE -> {
                return false;
            }
            default -> send(Protocol.frame(Protocol.INFO, "Unknown command."));
        }
        return true;
    }

    // ------------------------------------------------------------- writing

    /** Queues a line for this client; never blocks the caller. */
    void send(String line) {
        if (closed.get() || finishing.get()) {
            return;
        }
        if (!outbox.offer(line)) {
            server.log("Client " + (username != null ? username : remoteAddress)
                    + " is too slow - dropping connection");
            closeNow();
        }
    }

    /** Flushes what is queued (optionally followed by {@code lastLine}), then closes the socket. */
    void finish(String lastLine) {
        if (finishing.compareAndSet(false, true)) {
            if (lastLine != null) {
                outbox.offer(lastLine);
            }
            if (!outbox.offer(POISON)) {
                closeNow();
            }
        }
    }

    private void writeLoop() {
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line = outbox.take();
                if (POISON.equals(line)) {
                    break;
                }
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            // client is gone
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeNow();
        }
    }

    private void closeNow() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
    }
}
