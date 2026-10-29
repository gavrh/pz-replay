package com.gavrh.pzreplay;

import java.util.Locale;

public enum ReplayCameraMode {
    PLAYER("player", "Player"),
    FOLLOW_GHOST("follow_ghost", "Follow ghost"),
    FREE("free", "Free camera");

    private final String id;
    private final String label;

    ReplayCameraMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static ReplayCameraMode fromId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (ReplayCameraMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return mode;
            }
        }
        return null;
    }
}
