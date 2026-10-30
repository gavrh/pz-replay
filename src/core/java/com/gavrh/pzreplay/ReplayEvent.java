package com.gavrh.pzreplay;

import java.util.Locale;

public record ReplayEvent(
    long sequence,
    float timeSeconds,
    Kind kind,
    boolean end,
    String detail,
    float direction,
    float durationSeconds
) {
    public static final int MAX_DETAIL_LENGTH = 64;
    public static final float MAX_DURATION_SECONDS = 10.0f;

    public enum Kind {
        MELEE_ATTACK("melee_attack", 1.2f, false),
        RANGED_ATTACK("ranged_attack", 1.0f, false),
        RELOAD("reload", 2.5f, false),
        DOOR("door", 0.6f, false),
        SIT("sit", 0.0f, true),
        FALL("fall", 1.0f, false),
        CLIMB("climb", 1.5f, false);

        private final String id;
        private final float defaultDurationSeconds;
        private final boolean persistent;

        Kind(String id, float defaultDurationSeconds, boolean persistent) {
            this.id = id;
            this.defaultDurationSeconds = defaultDurationSeconds;
            this.persistent = persistent;
        }

        public String id() {
            return id;
        }

        public float defaultDurationSeconds() {
            return defaultDurationSeconds;
        }

        public boolean persistent() {
            return persistent;
        }

        public static Kind fromId(String id) {
            if (id == null) {
                return null;
            }
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.id.equals(normalized)) {
                    return kind;
                }
            }
            return null;
        }

        public static Kind fromCode(int code) {
            for (Kind kind : values()) {
                if (kind.ordinal() + 1 == code) {
                    return kind;
                }
            }
            return null;
        }

        public int code() {
            return ordinal() + 1;
        }
    }

    public ReplayEvent {
        kind = kind == null ? Kind.MELEE_ATTACK : kind;
        detail = safeDetail(detail);
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            timeSeconds = 0.0f;
        }
        if (!Float.isFinite(direction)) {
            direction = 0.0f;
        }
        if (!Float.isFinite(durationSeconds) || durationSeconds < 0.0f) {
            durationSeconds = 0.0f;
        }
        durationSeconds = Math.min(durationSeconds, MAX_DURATION_SECONDS);
    }

    public float effectiveDurationSeconds() {
        return durationSeconds > 0.0f ? durationSeconds : kind.defaultDurationSeconds();
    }

    private static String safeDetail(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (trimmed.length() > MAX_DETAIL_LENGTH) {
            return trimmed.substring(0, MAX_DETAIL_LENGTH);
        }
        return trimmed;
    }
}
