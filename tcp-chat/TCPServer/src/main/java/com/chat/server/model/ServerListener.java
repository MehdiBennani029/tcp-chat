package com.chat.server.model;

/**
 * Observer through which the model reports what happens to whoever presents it
 * (JavaFX view, console, tests...). The model never imports any UI class.
 *
 * <p>Callbacks are invoked from server threads, not from a UI thread: a GUI
 * implementation must hop onto its own UI thread.
 */
public interface ServerListener {

    void onServerStarted(String address, int port);

    void onServerStopped();

    /** An activity line ("Server Started", "Waiting for Client...", "Welcome alice"...). */
    void onLog(String message);

    /** A client with a username joined (read-only clients are not reported here). */
    void onUserJoined(String username);

    void onUserLeft(String username);
}
