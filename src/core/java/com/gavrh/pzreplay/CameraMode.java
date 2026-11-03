package com.gavrh.pzreplay;

import java.util.Locale;

public enum CameraMode {
    PLAYER("player", "Player"),
    FOLLOW_GHOST("follow_ghost", "Follow ghost"),
    FREE("free", "Free camera");

    private final String id;
    private final String label;

    CameraMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static CameraMode fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (CameraMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
