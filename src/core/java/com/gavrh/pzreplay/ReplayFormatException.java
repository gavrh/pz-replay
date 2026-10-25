package com.gavrh.pzreplay;

public class ReplayFormatException extends Exception {
    public ReplayFormatException(String message) {
        super(message);
    }

    public ReplayFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
