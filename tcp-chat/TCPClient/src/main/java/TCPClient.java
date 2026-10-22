import com.chat.client.model.ClientConfig;
import com.chat.client.view.ClientApp;
import javafx.application.Application;

/**
 * Entry point: {@code java TCPClient <ServerIPAddress> <PortNumber>}
 * (for example {@code java TCPClient localhost 3000}).
 *
 * <p>Without arguments the address and port come from {@code client.properties}.
 * This class deliberately does not extend {@link Application}: a launcher that is not an
 * Application subclass lets the packaged fat JAR start without a JavaFX module path.
 */
public class TCPClient {

    public static void main(String[] args) {
        if (args.length != 0 && args.length != 2) {
            System.err.println(ClientConfig.USAGE);
            System.exit(1);
        }
        Application.launch(ClientApp.class, args);
    }
}
