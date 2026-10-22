package com.chat.client.model;

import java.io.IOException;

/** The server was reachable but refused the join (duplicate or invalid username). */
public class ConnectionRejectedException extends IOException {

    private static final long serialVersionUID = 1L;

    public ConnectionRejectedException(String message) {
        super(message);
    }
}
