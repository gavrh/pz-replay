package com.gavrh.pzreplay;

public final class WorldSeekDecision {
    private static final double BACKWARD_EPSILON = 0.0001;

    public enum Action {
        NORMAL,
        APPLY_FORWARD,
        RELOAD_REQUIRED
    }

    private WorldSeekDecision() {
    }

    public static Action seek(boolean worldPlayback, double currentSeconds, double targetSeconds) {
        if (!worldPlayback) {
            return Action.NORMAL;
        }
        if (targetSeconds < currentSeconds - BACKWARD_EPSILON) {
            return Action.RELOAD_REQUIRED;
        }
        return Action.APPLY_FORWARD;
    }

    public static Action restart(boolean worldPlayback) {
        return worldPlayback ? Action.RELOAD_REQUIRED : Action.NORMAL;
    }
}
