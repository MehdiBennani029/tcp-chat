package com.chat.client.view;

import com.chat.client.model.ChatClient;
import com.chat.client.model.ChatListener;
import com.chat.client.model.ClientConfig;
import com.chat.client.model.ConnectionRejectedException;
import java.io.IOException;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/**
 * JavaFX view of the client: a login screen followed by the chat screen.
 * All networking is delegated to {@link ChatClient}; this class only renders its events.
 */
public class ClientApp extends Application {

    private Stage stage;
    private ClientConfig config;
    private ChatClient client;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        try {
            config = ClientConfig.resolve(getParameters().getRaw().toArray(new String[0]));
        } catch (IOException | IllegalArgumentException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            alert.setHeaderText("Invalid client configuration");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        stage.setTitle("TCP Group Chat");
        stage.setOnCloseRequest(e -> leaveIfConnected());
        showLogin(null, false);
        stage.show();
    }

    @Override
    public void stop() {
        leaveIfConnected();
    }

    private void leaveIfConnected() {
        if (client != null) {
            client.disconnect();
        }
    }

    private void show(GridPane root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource("/client.css").toExternalForm());
        stage.setScene(scene);
        stage.sizeToScene();
        stage.centerOnScreen();
    }

    // ---------------------------------------------------------- login screen

    private void showLogin(String notice, boolean noticeIsError) {
        Label title = new Label("TCP Group Chat");
        title.getStyleClass().add("title");
        Label server = new Label("Server: " + config.host() + ":" + config.port());
        server.getStyleClass().add("muted");

        TextField userField = new TextField();
        userField.setPromptText("Username");
        Label hint = new Label("Leave the username empty to join in READ-ONLY MODE.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        Button connect = new Button("CONNECT");
        connect.setDefaultButton(true);
        connect.setMaxWidth(Double.MAX_VALUE);

        Label message = new Label(notice == null ? "" : notice);
        message.setWrapText(true);
        message.getStyleClass().add(noticeIsError ? "notice-error" : "notice-info");

        connect.setOnAction(e -> connect(userField.getText(), connect, userField, message));

        GridPane grid = new GridPane();
        grid.getStyleClass().add("login-pane");
        grid.setAlignment(Pos.CENTER);
        grid.setVgap(12);
        ColumnConstraints col = new ColumnConstraints(320);
        grid.getColumnConstraints().add(col);
        grid.add(title, 0, 0);
        grid.add(server, 0, 1);
        grid.add(userField, 0, 2);
        grid.add(hint, 0, 3);
        grid.add(connect, 0, 4);
        grid.add(message, 0, 5);
        show(grid, 440, 360);
        userField.requestFocus();
    }

    private void connect(String username, Button button, TextField field, Label message) {
        button.setDisable(true);
        field.setDisable(true);
        message.getStyleClass().setAll("notice-info");
        message.setText("Connecting...");

        ChatClient candidate = new ChatClient(config.host(), config.port());
        Thread.ofVirtual().name("connect").start(() -> {
            try {
                candidate.connect(username);
                Platform.runLater(() -> showChat(candidate));
            } catch (ConnectionRejectedException e) {
                Platform.runLater(() -> failed(e.getMessage(), button, field, message));
            } catch (IOException e) {
                Platform.runLater(() -> failed("Cannot reach " + config.host() + ":" + config.port()
                        + " - " + e.getMessage(), button, field, message));
            }
        });
    }

    private void failed(String text, Button button, TextField field, Label message) {
        message.getStyleClass().setAll("notice-error");
        message.setText(text);
        button.setDisable(false);
        field.setDisable(false);
        field.requestFocus();
    }

    // ----------------------------------------------------------- chat screen

    private void showChat(ChatClient connected) {
        client = connected;
        boolean readOnly = connected.isReadOnly();

        // header: status indicator, identity, leave button
        Circle dot = new Circle(6);
        dot.getStyleClass().addAll("status-dot", "online");
        Label status = new Label("Online");
        status.getStyleClass().add("status-text");
        Label who = new Label(readOnly ? "READ-ONLY MODE" : "Signed in as " + connected.getUsername());
        who.getStyleClass().add(readOnly ? "badge-readonly" : "who");
        Button leave = new Button("LEAVE");
        leave.getStyleClass().add("leave");

        GridPane header = new GridPane();
        header.getStyleClass().add("header");
        header.setHgap(8);
        header.add(dot, 0, 0);
        header.add(status, 1, 0);
        header.add(who, 2, 0);
        header.add(leave, 3, 0);
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHalignment(HPos.RIGHT);
        c2.setHgrow(Priority.ALWAYS);
        header.getColumnConstraints().addAll(c0, c1, c2);
        GridPane.setMargin(leave, new Insets(0, 0, 0, 10));

        // messages
        ObservableList<ChatLine> lines = FXCollections.observableArrayList();
        ListView<ChatLine> messages = new ListView<>(lines);
        messages.getStyleClass().add("message-list");
        messages.setCellFactory(lv -> new MessageCell());
        messages.setFocusTraversable(false);

        // composer
        TextArea input = new TextArea();
        input.getStyleClass().add("input");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setPromptText(readOnly
                ? "READ-ONLY MODE - reconnect with a username to write"
                : "Type a message. Enter = send, Shift+Enter = new line. Commands: allUsers, end, bye");
        Button send = new Button("SEND");
        send.getStyleClass().add("send");
        send.setMaxHeight(Double.MAX_VALUE);
        input.setDisable(readOnly);
        send.setDisable(readOnly);

        Runnable submit = () -> {
            String text = input.getText();
            if (!text.isBlank()) {
                connected.sendUserInput(text);
                input.clear();
            }
        };
        send.setOnAction(e -> submit.run());
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                if (e.isShiftDown()) {
                    input.insertText(input.getCaretPosition(), "\n");
                } else {
                    submit.run();
                }
            }
        });

        GridPane composer = new GridPane();
        composer.getStyleClass().add("composer");
        composer.setHgap(10);
        composer.add(input, 0, 0);
        composer.add(send, 1, 0);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        composer.getColumnConstraints().add(grow);

        // root
        GridPane root = new GridPane();
        root.getStyleClass().add("chat-pane");
        root.add(header, 0, 0);
        root.add(messages, 0, 1);
        root.add(composer, 0, 2);
        ColumnConstraints full = new ColumnConstraints();
        full.setHgrow(Priority.ALWAYS);
        root.getColumnConstraints().add(full);
        RowConstraints fixed = new RowConstraints();
        RowConstraints stretch = new RowConstraints();
        stretch.setVgrow(Priority.ALWAYS);
        RowConstraints fixed2 = new RowConstraints();
        root.getRowConstraints().addAll(fixed, stretch, fixed2);

        // buttons
        boolean[] online = {true};
        leave.setOnAction(e -> {
            if (online[0]) {
                connected.disconnect();
            } else {
                showLogin(null, false);
            }
        });

        // model -> view
        Runnable scroll = () -> messages.scrollTo(lines.size() - 1);
        connected.setListener(new ChatListener() {
            @Override
            public void onMessage(String sender, String formattedText) {
                Platform.runLater(() -> {
                    boolean own = connected.getUsername() != null
                            && connected.getUsername().equalsIgnoreCase(sender);
                    lines.add(new ChatLine(own ? ChatLine.Kind.OWN : ChatLine.Kind.OTHER, formattedText));
                    scroll.run();
                });
            }

            @Override
            public void onInfo(String text) {
                Platform.runLater(() -> {
                    lines.add(new ChatLine(ChatLine.Kind.INFO, text));
                    scroll.run();
                });
            }

            @Override
            public void onUserList(List<String> usernames) {
                String text = "Active users (" + usernames.size() + "): " + String.join(", ", usernames);
                onInfo(text);
            }

            @Override
            public void onDisconnected(String reason) {
                Platform.runLater(() -> {
                    online[0] = false;
                    dot.getStyleClass().removeAll("online", "offline");
                    dot.getStyleClass().add("offline");
                    status.setText("Offline");
                    input.setDisable(true);
                    send.setDisable(true);
                    leave.setText("BACK TO LOGIN");
                    lines.add(new ChatLine(ChatLine.Kind.INFO, reason));
                    scroll.run();
                });
            }
        });
        connected.startListening();

        show(root, 680, 600);
        if (!readOnly) {
            input.requestFocus();
        }
    }

    /** A message bubble: right-aligned for my own messages, centered for system notices. */
    private static final class MessageCell extends ListCell<ChatLine> {

        MessageCell() {
            setWrapText(true);
            setPrefWidth(0); // follow the list width instead of growing horizontally
        }

        @Override
        protected void updateItem(ChatLine line, boolean empty) {
            super.updateItem(line, empty);
            getStyleClass().removeAll("cell-own", "cell-other", "cell-info");
            if (empty || line == null) {
                setText(null);
                return;
            }
            setText(line.text());
            getStyleClass().add(switch (line.kind()) {
                case OWN -> "cell-own";
                case OTHER -> "cell-other";
                case INFO -> "cell-info";
            });
        }
    }
}
