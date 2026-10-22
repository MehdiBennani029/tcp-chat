package com.chat.server.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Network settings of the server, loaded at runtime from {@code server.properties}.
 *
 * <p>Lookup order: the file named by {@code -Dchat.config=...}, then
 * {@code ./server.properties} in the working directory, then the copy bundled in the JAR.
 * Editing the external file therefore changes the deployment without recompiling.
 */
public record ServerConfig(String bindAddress, int port) {

    public static final String FILE_NAME = "server.properties";
    public static final String OVERRIDE_PROPERTY = "chat.config";

    public ServerConfig {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("server.address must not be empty");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 0 and 65535, got " + port);
        }
    }

    public static ServerConfig load() throws IOException {
        Properties props = new Properties();
        String source = read(props);
        String address = props.getProperty("server.address", "0.0.0.0").trim();
        String portText = props.getProperty("server.port", "3000").trim();
        try {
            return new ServerConfig(address, Integer.parseInt(portText));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid server.port '" + portText + "' in " + source);
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
        try (InputStream in = ServerConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                return "bundled " + FILE_NAME;
            }
        }
        return "built-in defaults";
    }
}
