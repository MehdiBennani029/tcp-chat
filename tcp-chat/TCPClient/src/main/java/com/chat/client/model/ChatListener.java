package com.chat.client.model;

import java.util.List;

/**
 * Observer through which {@link ChatClient} reports events to its presenter
 * (JavaFX view, console, tests...). The model never imports any UI class.
 *
 * <p>Callbacks are invoked from a network thread, not from a UI thread.
 */
public interface ChatListener {

    /** A chat message, already formatted by the server as {@code [HH:mm:ss] sender: text}. */
    void onMessage(String sender, String formattedText);

    /** A system notice (someone joined/left, read-only warning, ...). */
    void onInfo(String text);

    /** Answer to the {@code allUsers} command. */
    void onUserList(List<String> usernames);

    /** The connection is over (user left, server stopped or network failure). Called once. */
    void onDisconnected(String reason);
}
