package com.gavrh.pzreplay;

public record ReplayGap(
    float startTimeSeconds,
    float endTimeSeconds,
    Reason reason,
    float lastX,
    float lastY,
    float lastZ,
    float nextX,
    float nextY,
    float nextZ
) {
    public enum Reason {
        TELEPORT(0),
        LOADING(1),
        RECORDING_PAUSE(2),
        LONG_TICK(3),
        DEATH(4),
        PLAYER_UNAVAILABLE(5),
        UNKNOWN(6);

        private final int id;

        Reason(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Reason fromId(int id) {
            for (Reason value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return null;
        }
    }

    public ReplayGap {
        if (!Float.isFinite(startTimeSeconds) || startTimeSeconds < 0.0f) {
            startTimeSeconds = 0.0f;
        }
        if (!Float.isFinite(endTimeSeconds) || endTimeSeconds < startTimeSeconds) {
            endTimeSeconds = startTimeSeconds;
        }
        reason = reason == null ? Reason.UNKNOWN : reason;
        lastX = finiteOrZero(lastX);
        lastY = finiteOrZero(lastY);
        lastZ = finiteOrZero(lastZ);
        nextX = finiteOrZero(nextX);
        nextY = finiteOrZero(nextY);
        nextZ = finiteOrZero(nextZ);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }
}
