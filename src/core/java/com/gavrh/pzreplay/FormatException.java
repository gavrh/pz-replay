package com.gavrh.pzreplay;

public final class FormatException extends ReplayFormatException {
    private final String reason;

    public FormatException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FormatException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
