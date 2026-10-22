package com.chat.client.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where to connect. Defaults come from {@code client.properties} (looked up via
 * {@code -Dchat.config=...}, then the working directory, then the copy bundled in the JAR);
 * the command line {@code <ServerIPAddress> <PortNumber>} overrides them.
 */
public record ClientConfig(String host, int port) {

    public static final String FILE_NAME = "client.properties";
    public static final String OVERRIDE_PROPERTY = "chat.config";
    public static final String USAGE = "Usage: java TCPClient <ServerIPAddress> <PortNumber>   (e.g. java TCPClient localhost 3000)";

    public ClientConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Server address must not be empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got " + port);
        }
    }

    /** @param args command line arguments: none (use the file) or exactly host and port */
    public static ClientConfig resolve(String[] args) throws IOException {
        if (args.length != 0 && args.length != 2) {
            throw new IllegalArgumentException(USAGE);
        }
        Properties props = new Properties();
        String source = read(props);
        String host = props.getProperty("server.ip", "localhost").trim();
        String portText = props.getProperty("server.port", "3000").trim();
        String portSource = source;
        if (args.length == 2) {
            host = args[0].trim();
            portText = args[1].trim();
            portSource = "the command line";
        }
        try {
            return new ClientConfig(host, Integer.parseInt(portText));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port '" + portText + "' in " + portSource);
        }
    }

    private static String read(Properties props) throws IOException {
        Path external = Path.of(System.getProperty(OVERRIDE_PROPERTY, FILE_NAME));
        if (Files.isRegularFile(external)) {
            try (Reader reader = Files.newBufferedReader(external, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return external.toAbsolutePath().toString();
        }
        try (InputStream in = ClientConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                return "bundled " + FILE_NAME;
            }
        }
        return "built-in defaults";
    }
}
