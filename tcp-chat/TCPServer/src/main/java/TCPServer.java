import com.chat.server.view.ServerApp;
import javafx.application.Application;

/**
 * Entry point: {@code java TCPServer}.
 *
 * <p>This class deliberately does not extend {@link Application}: a launcher that is not an
 * Application subclass lets the packaged fat JAR start without a JavaFX module path.
 */
public class TCPServer {

    public static void main(String[] args) {
        Application.launch(ServerApp.class, args);
    }
}
