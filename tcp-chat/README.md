# TCP Group Chat (Java Sockets + JavaFX)

A real-time group chat: a central **TCPServer** distributes messages between any number of **TCPClient** applications over TCP.

| | |
|---|---|
| Language / build | Java 21, Maven (two independent projects: `TCPServer`, `TCPClient`) |
| Networking | Java sockets (TCP), line-based UTF-8 protocol |
| Concurrency | Server uses **virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) |
| GUI | JavaFX 21 (`GridPane` layouts + CSS) |
| Config | `server.properties` / `client.properties`, loaded at runtime (no recompilation) |

## Build

Requires JDK 21+ and Maven 3.9+ (internet access to Maven Central for JavaFX and plugins).

```bash
cd TCPServer && mvn clean package     # -> target/TCPServer.jar
cd ../TCPClient && mvn clean package  # -> target/TCPClient.jar
```

The JARs are fat/executable JARs. JavaFX native libraries are bundled for the OS you build on, so build on the OS where you will run them.

## Run

```bash
# 1. Server (reads server.properties: address + port)
java -jar TCPServer/target/TCPServer.jar

# 2. Clients (as many as you like)
java -jar TCPClient/target/TCPClient.jar localhost 3000
```

Equivalent to `java TCPServer` / `java TCPClient localhost 3000` from the classes directory. During development you can also use `mvn javafx:run` in each project.

### Configuration

* `server.properties` — `server.address` (interface to bind, default `0.0.0.0`) and `server.port` (default `3000`).
* `client.properties` — `server.ip` and `server.port`, used when the client is started **without** arguments. Command-line arguments always override them.
* Lookup order: `-Dchat.config=/path/file`, then the file in the working directory, then the copy bundled in the JAR. To change the deployment, drop an edited file next to the JAR.

## Features

**Client**
* Login screen: enter a username to chat. Leave it empty to join in **READ-ONLY MODE** (you receive messages; the input and SEND are disabled and the server refuses writes).
* Usernames are unique (case-insensitive) and limited to 1-20 letters/digits/`_`/`-`/`.`.
* Send with the **SEND** button or **Enter** (Shift+Enter = new line).
* `allUsers` returns the list of active users to you only.
* `end`, `bye` or the **LEAVE** button disconnects (server is notified). Closing the window also leaves cleanly.
* Green dot + **Online** label; red dot + **Offline** when disconnected or when the server stops.

**Server**
* Accepts many concurrent clients (one virtual thread to read and one to write per client; a slow client cannot block the others).
* Formats every message as `[HH:mm:ss] username: text` and distributes it to all active connections.
* Live `ListView` of connected usernames, each with a random (mutually distinct) background colour.
* Activity log: "Server Started", "Waiting for Client...", "Welcome alice", "alice left the chat", ...

## Architecture

Strict Model-View separation in both projects:

```
com.chat.server.model   ChatServer, ClientHandler, ServerConfig, ServerListener, Protocol   (no javafx imports)
com.chat.server.view    ServerApp                                                          (JavaFX)
com.chat.client.model   ChatClient, ChatListener, ClientConfig, ConnectionRejectedException, Protocol (no javafx imports)
com.chat.client.view    ClientApp, ChatLine                                                (JavaFX)
```

The model talks to the outside only through listener interfaces (`ServerListener`, `ChatListener`); the view implements them and hops to the UI thread with `Platform.runLater`. The model packages compile and run with no JavaFX on the classpath (this is how the integration test runs).

### Protocol (one UTF-8 line per frame, `COMMAND|payload`)

| Client -> Server | Server -> Client |
|---|---|
| `JOIN|username` (empty = read-only) | `WELCOME|username`, `READONLY`, `ERR|reason` |
| `MSG|text` | `MSG|sender|[HH:mm:ss] sender: text` |
| `ALLUSERS` | `USERS|alice,bob` |
| `LEAVE` | `INFO|text`, `BYE|reason` |

`Protocol` is duplicated in both projects on purpose so each Maven project builds on its own.

### UML

* Class diagram: `docs/class-diagram.png` (source `docs/class-diagram.puml`)
* Deployment diagram: `docs/deployment-diagram.png` (source `docs/deployment-diagram.puml`)

Render with `plantuml -tpng docs/*.puml`.

## Tests

`test/IntegrationTest.java` starts the real server and 40+ clients over real sockets, headless:

```bash
mkdir -p /tmp/models && javac -d /tmp/models $(find TCPServer/src/main/java/com/chat/server/model TCPClient/src/main/java/com/chat/client/model -name "*.java")
java -cp /tmp/models test/IntegrationTest.java
```

It checks joining, duplicate/invalid names, read-only mode, broadcast formatting, `allUsers`, `bye`/`end`/LEAVE, crashed clients, 40 concurrent clients and server shutdown.

## Known limitations

* No message history: a client only sees messages sent while it is connected.
* Plain TCP, no encryption or passwords (the "username" is an identity, not authentication).
* Maximum message length is 2000 characters.

