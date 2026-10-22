package com.chat.server.view;

import com.chat.server.model.ChatServer;
import com.chat.server.model.ServerConfig;
import com.chat.server.model.ServerListener;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/**
 * JavaFX view of the server. It only renders what the {@link ChatServer} model reports
 * through {@link ServerListener}; all networking lives in the model package.
 */
public class ServerApp extends Application implements ServerListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObservableList<String> users = FXCollections.observableArrayList();
    private final Map<String, Color> userColors = new HashMap<>();
    private final Map<String, Double> userHues = new HashMap<>();
    private final Random random = new Random();

    private ChatServer server;
    private final Circle statusDot = new Circle(6);
    private final Label statusLabel = new Label("Starting...");
    private final Label usersTitle = new Label("Connected users (0)");
    private final TextArea logArea = new TextArea();

    @Override
    public void start(Stage stage) {
        ServerConfig config;
        try {
            config = ServerConfig.load();
        } catch (IOException | IllegalArgumentException e) {
            fatal("Invalid server configuration", e.getMessage());
            return;
        }
        server = new ChatServer(config, this);

        stage.setTitle("TCP Chat Server");
        stage.setScene(buildScene());
        stage.setOnCloseRequest(e -> server.stop());
        stage.show();

        try {
            server.start();
        } catch (IOException e) {
            setStatus(false, "Not running");
            appendLog("Could not start the server on " + config.bindAddress() + ":" + config.port()
                    + " - " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    // ------------------------------------------------------------------ UI

    private Scene buildScene() {
        statusDot.getStyleClass().addAll("status-dot", "offline");
        Label title = new Label("TCP Chat Server");
        title.getStyleClass().add("title");

        GridPane header = new GridPane();
        header.getStyleClass().add("header");
        header.setHgap(10);
        header.add(title, 0, 0);
        header.add(statusDot, 1, 0);
        header.add(statusLabel, 2, 0);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        header.getColumnConstraints().add(grow);

        usersTitle.getStyleClass().add("section-title");
        Label logTitle = new Label("Activity log");
        logTitle.getStyleClass().add("section-title");

        ListView<String> userList = new ListView<>(users);
        userList.getStyleClass().add("user-list");
        userList.setPlaceholder(new Label("No user connected"));
        userList.setCellFactory(lv -> new UserCell());

        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("log-area");

        GridPane root = new GridPane();
        root.getStyleClass().add("root-pane");
        root.setHgap(14);
        root.setVgap(8);
        root.setPadding(new Insets(0, 0, 14, 0));
        root.add(header, 0, 0, 2, 1);
        root.add(usersTitle, 0, 1);
        root.add(logTitle, 1, 1);
        root.add(userList, 0, 2);
        root.add(logArea, 1, 2);
        GridPane.setMargin(usersTitle, new Insets(6, 0, 0, 14));
        GridPane.setMargin(logTitle, new Insets(6, 0, 0, 0));
        GridPane.setMargin(userList, new Insets(0, 0, 0, 14));
        GridPane.setMargin(logArea, new Insets(0, 14, 0, 0));
        GridPane.setHgrow(header, Priority.ALWAYS);

        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(34);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(66);
        root.getColumnConstraints().addAll(left, right);
        RowConstraints fixed = new RowConstraints();
        RowConstraints fixed2 = new RowConstraints();
        RowConstraints stretch = new RowConstraints();
        stretch.setVgrow(Priority.ALWAYS);
        root.getRowConstraints().addAll(fixed, fixed2, stretch);

        Scene scene = new Scene(root, 860, 520);
        scene.getStylesheets().add(getClass().getResource("/server.css").toExternalForm());
        return scene;
    }

    /** List cell with a random pastel background per user. */
    private final class UserCell extends ListCell<String> {
        @Override
        protected void updateItem(String name, boolean empty) {
            super.updateItem(name, empty);
            if (empty || name == null) {
                setText(null);
                setStyle("");
                return;
            }
            setText(name);
            Color color = userColors.computeIfAbsent(name, ServerApp.this::randomColorFor);
            setStyle("-fx-background-color: " + toWeb(color) + ";"
                    + "-fx-background-insets: 3 0 3 0; -fx-background-radius: 8;"
                    + "-fx-text-fill: #1f2937; -fx-font-weight: bold; -fx-padding: 8 12;");
        }
    }

    /**
     * Random colour that stays visually distinct: among a handful of random hues, keep the one
     * farthest from the hues already given to connected users.
     */
    private Color randomColorFor(String user) {
        double bestHue = random.nextDouble() * 360;
        double bestGap = -1;
        for (int i = 0; i < 24; i++) {
            double candidate = random.nextDouble() * 360;
            double gap = 360;
            for (double used : userHues.values()) {
                double d = Math.abs(candidate - used);
                gap = Math.min(gap, Math.min(d, 360 - d));
            }
            if (gap > bestGap) {
                bestGap = gap;
                bestHue = candidate;
            }
        }
        userHues.put(user, bestHue);
        return Color.hsb(bestHue, 0.35 + random.nextDouble() * 0.2, 0.97);
    }

    private static String toWeb(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private void setStatus(boolean running, String text) {
        statusDot.getStyleClass().removeAll("online", "offline");
        statusDot.getStyleClass().add(running ? "online" : "offline");
        statusLabel.setText(text);
    }

    private void appendLog(String message) {
        logArea.appendText("[" + LocalTime.now().format(TIME) + "] " + message + "\n");
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    private void fatal(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR, detail);
        alert.setHeaderText(header);
        alert.showAndWait();
        Platform.exit();
    }

    // ------------------------------------- ServerListener (called off the UI thread)

    @Override
    public void onServerStarted(String address, int port) {
        Platform.runLater(() -> setStatus(true, "Running on " + address + ":" + port));
    }

    @Override
    public void onServerStopped() {
        Platform.runLater(() -> setStatus(false, "Stopped"));
    }

    @Override
    public void onLog(String message) {
        Platform.runLater(() -> appendLog(message));
    }

    @Override
    public void onUserJoined(String username) {
        Platform.runLater(() -> {
            users.add(username);
            usersTitle.setText("Connected users (" + users.size() + ")");
        });
    }

    @Override
    public void onUserLeft(String username) {
        Platform.runLater(() -> {
            users.remove(username);
            userColors.remove(username);
            userHues.remove(username);
            usersTitle.setText("Connected users (" + users.size() + ")");
        });
    }
}
