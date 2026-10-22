import com.chat.client.model.ChatClient;
import com.chat.client.model.ChatListener;
import com.chat.client.model.ConnectionRejectedException;
import com.chat.server.model.ChatServer;
import com.chat.server.model.ServerConfig;
import com.chat.server.model.ServerListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Headless end-to-end test: the real ChatServer and ChatClient models talk over real TCP
 * sockets. No JavaFX is on the class path, which also proves the models are UI-independent.
 *
 * Run: java -cp <server-classes>:<client-classes> test/IntegrationTest.java
 */
public class IntegrationTest {

    static int passed = 0;
    static int failed = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    /** Records every event a client receives. */
    static class Recorder implements ChatListener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final List<String> all = new CopyOnWriteArrayList<>();

        private void add(String e) {
            all.add(e);
            events.add(e);
        }

        @Override public void onMessage(String sender, String text) { add("MSG\t" + sender + "\t" + text); }
        @Override public void onInfo(String text) { add("INFO\t" + text); }
        @Override public void onUserList(List<String> users) { add("USERS\t" + users); }
        @Override public void onDisconnected(String reason) { add("DISC\t" + reason); }

        /** Waits for an event matching the predicate (older non-matching events are skipped). */
        String await(Predicate<String> p) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end) {
                String e = events.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && p.test(e)) {
                    return e;
                }
            }
            return null;
        }

        boolean got(Predicate<String> p) {
            return all.stream().anyMatch(p);
        }
    }

    static class ServerRecorder implements ServerListener {
        final List<String> log = new CopyOnWriteArrayList<>();
        final List<String> joined = new CopyOnWriteArrayList<>();
        final List<String> left = new CopyOnWriteArrayList<>();
        volatile boolean stopped;

        @Override public void onServerStarted(String a, int p) { }
        @Override public void onServerStopped() { stopped = true; }
        @Override public void onLog(String m) { log.add(m); }
        @Override public void onUserJoined(String u) { joined.add(u); }
        @Override public void onUserLeft(String u) { left.add(u); }
    }

    static class Session {
        final ChatClient client;
        final Recorder rec = new Recorder();

        Session(int port, String name) throws Exception {
            client = new ChatClient("localhost", port);
            client.connect(name);
            client.setListener(rec);
            client.startListening();
        }
    }

    public static void main(String[] args) throws Exception {
        ServerRecorder srv = new ServerRecorder();
        ChatServer server = new ChatServer(new ServerConfig("127.0.0.1", 0), srv);
        server.start();
        int port = server.getPort();
        System.out.println("Server on port " + port);

        System.out.println("\n[logging]");
        Thread.sleep(200);
        check("log: 'Server Started'", srv.log.stream().anyMatch(l -> l.contains("Server Started")));
        check("log: 'Waiting for Client'", srv.log.stream().anyMatch(l -> l.contains("Waiting for Client")));

        System.out.println("\n[join / identity]");
        Session alice = new Session(port, "alice");
        Session bob = new Session(port, "bob");
        check("alice is in CHAT mode with her username",
                !alice.client.isReadOnly() && "alice".equals(alice.client.getUsername()));
        check("bob receives a personal 'Welcome bob!' notice",
                bob.rec.await(e -> e.startsWith("INFO") && e.contains("Welcome bob!")) != null);
        check("alice is told 'bob joined the chat'",
                alice.rec.await(e -> e.startsWith("INFO") && e.contains("bob joined the chat")) != null);
        check("server log: 'Welcome alice'", srv.log.contains("Welcome alice"));
        check("server list model got alice and bob", srv.joined.containsAll(List.of("alice", "bob")));

        try {
            new ChatClient("localhost", port).connect("ALICE");
            check("duplicate username (case-insensitive) rejected", false);
        } catch (ConnectionRejectedException e) {
            check("duplicate username (case-insensitive) rejected: " + e.getMessage(),
                    e.getMessage().contains("already taken"));
        }
        try {
            new ChatClient("localhost", port).connect("bad name!");
            check("invalid username rejected", false);
        } catch (ConnectionRejectedException e) {
            check("invalid username rejected: " + e.getMessage(), true);
        }

        System.out.println("\n[read-only mode]");
        Session reader = new Session(port, "");
        check("empty username => READ_ONLY, no username",
                reader.client.isReadOnly() && reader.client.getUsername() == null);
        check("read-only client is told about READ-ONLY MODE",
                reader.rec.await(e -> e.startsWith("INFO") && e.contains("READ-ONLY")) != null);
        check("read-only client is not listed as a user", !srv.joined.contains(""));

        System.out.println("\n[messaging]");
        alice.client.sendUserInput("hello everyone");
        String re = "MSG\talice\t\\[\\d\\d:\\d\\d:\\d\\d\\] alice: hello everyone";
        check("alice receives her own formatted message (name + time)", alice.rec.await(e -> e.matches(re)) != null);
        check("bob receives it", bob.rec.await(e -> e.matches(re)) != null);
        check("read-only client also receives it", reader.rec.await(e -> e.matches(re)) != null);

        reader.client.sendUserInput("can I talk?");
        check("read-only client is refused when writing",
                reader.rec.await(e -> e.startsWith("INFO") && e.contains("READ-ONLY MODE: ")) != null);
        Thread.sleep(300);
        check("refused message was NOT distributed",
                !alice.rec.got(e -> e.contains("can I talk")) && !bob.rec.got(e -> e.contains("can I talk")));

        bob.client.sendUserInput("pipes | and \\ backslashes");
        check("special characters (| and \\) survive the protocol",
                alice.rec.await(e -> e.contains("bob: pipes | and \\ backslashes")) != null);
        bob.client.sendUserInput("line one\nline two");
        check("multi-line message survives",
                alice.rec.await(e -> e.contains("bob: line one\nline two")) != null);

        System.out.println("\n[allUsers command]");
        bob.client.sendUserInput("allUsers");
        check("bob gets the active users list", bob.rec.await(e -> e.equals("USERS\t[alice, bob]")) != null);
        Thread.sleep(200);
        check("alice did not get bob's list", !alice.rec.got(e -> e.startsWith("USERS")));

        System.out.println("\n[disconnection]");
        bob.client.sendUserInput("bye");
        check("typing 'bye' disconnects the client", bob.rec.await(e -> e.startsWith("DISC")) != null);
        check("bob client reports not connected", !bob.client.isConnected());
        check("alice is notified that bob left", alice.rec.await(e -> e.startsWith("INFO") && e.contains("bob left")) != null);
        Thread.sleep(200);
        check("server list model removed bob", srv.left.contains("bob"));
        check("server log: 'bob left the chat'", srv.log.contains("bob left the chat"));

        Session bob2 = new Session(port, "bob");
        check("username is free again after leaving", "bob".equals(bob2.client.getUsername()));
        bob2.client.sendUserInput("END");
        check("typing 'END' (any case) disconnects too", bob2.rec.await(e -> e.startsWith("DISC")) != null);

        Session carol = new Session(port, "carol");
        carol.client.disconnect(); // what the LEAVE button calls
        check("LEAVE (disconnect()) closes the connection", carol.rec.await(e -> e.startsWith("DISC")) != null);

        System.out.println("\n[abrupt disconnect]");
        Session dave = new Session(port, "dave");
        check("dave joined", dave.client.isConnected());
        // kill the TCP connection without LEAVE
        try (Socket raw = new Socket("localhost", port)) {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(raw.getOutputStream(), StandardCharsets.UTF_8));
            w.write("JOIN|eve\n");
            w.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
            check("raw socket receives WELCOME", "WELCOME|eve".equals(r.readLine()));
        }
        check("server notices a crashed client", alice.rec.await(e -> e.contains("eve left the chat")) != null);

        System.out.println("\n[concurrency: 40 simultaneous clients, virtual threads]");
        int n = 40;
        List<Session> crowd = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(n);
        List<Session> sync = new CopyOnWriteArrayList<>();
        for (int i = 0; i < n; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    sync.add(new Session(port, "user" + id));
                } catch (Exception e) {
                    System.out.println("connect failed: " + e);
                } finally {
                    ready.countDown();
                }
            });
        }
        ready.await(20, TimeUnit.SECONDS);
        crowd.addAll(sync);
        check("all " + n + " clients connected", crowd.size() == n);
        for (Session s : crowd) {
            s.client.sendUserInput("ping from " + s.client.getUsername());
        }
        boolean everyoneGotEverything = true;
        for (Session s : crowd) {
            int count = 0;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (count < n && System.nanoTime() < end) {
                String e = s.rec.events.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && e.startsWith("MSG") && e.contains("ping from")) {
                    count++;
                }
            }
            if (count != n) {
                everyoneGotEverything = false;
                System.out.println("    " + s.client.getUsername() + " received " + count + "/" + n);
            }
        }
        check("every client received all " + n + " broadcast messages", everyoneGotEverything);
        pool.shutdown();

        System.out.println("\n[server shutdown]");
        server.stop();
        check("alice is told the server shut down",
                alice.rec.await(e -> e.startsWith("DISC") && e.contains("shutting down")) != null);
        check("read-only client is told too",
                reader.rec.await(e -> e.startsWith("DISC") && e.contains("shutting down")) != null);
        check("server listener got onServerStopped", srv.stopped);
        try (Socket s = new Socket("localhost", port)) {
            check("server no longer accepts connections", false);
        } catch (java.io.IOException e) {
            check("server no longer accepts connections", true);
        }

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
