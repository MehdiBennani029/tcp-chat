package com.chat.server.model;

/**
 * Wire protocol shared by server and client (the client carries an identical copy).
 *
 * <p>The protocol is line based (UTF-8, one frame per line): {@code COMMAND|payload}.
 * Line breaks inside a payload are escaped so a frame always fits on one line.
 *
 * <pre>
 * client -> server            server -> client
 * JOIN|username (may be empty) WELCOME|username   | READONLY | ERR|reason
 * MSG|text                     MSG|sender|[HH:mm:ss] sender: text
 * ALLUSERS                     USERS|alice,bob
 * LEAVE                        INFO|text
 *                              BYE|reason
 * </pre>
 */
public final class Protocol {

    public static final char SEP = '|';

    // client -> server
    public static final String JOIN = "JOIN";
    public static final String MSG = "MSG";
    public static final String ALL_USERS = "ALLUSERS";
    public static final String LEAVE = "LEAVE";

    // server -> client
    public static final String WELCOME = "WELCOME";
    public static final String READ_ONLY = "READONLY";
    public static final String ERROR = "ERR";
    public static final String INFO = "INFO";
    public static final String USERS = "USERS";
    public static final String BYE = "BYE";

    private Protocol() {
    }

    /** Builds one protocol line (without the trailing newline). */
    public static String frame(String command, String payload) {
        if (payload == null || payload.isEmpty()) {
            return command;
        }
        return command + SEP + escape(payload);
    }

    /** Splits a line into {command, payload}; the payload is still escaped. */
    public static String[] split(String line) {
        int i = line.indexOf(SEP);
        return i < 0
                ? new String[]{line, ""}
                : new String[]{line.substring(0, i), line.substring(i + 1)};
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { /* dropped */ }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                sb.append(next == 'n' ? '\n' : next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
