package com.gavrh.pzreplay;

public final class RecordingCountdown {
    public static final int DEFAULT_SECONDS = 3;
    public static final int MIN_SECONDS = 0;
    public static final int MAX_SECONDS = 10;

    private enum State {
        IDLE,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private State state = State.IDLE;
    private int durationSeconds;
    private long remainingMillis;
    private long lastTickMillis;

    public static boolean isValidSeconds(int seconds) {
        return seconds >= MIN_SECONDS && seconds <= MAX_SECONDS;
    }

    public boolean start(int seconds, long nowMillis) {
        if (!isValidSeconds(seconds)) {
            throw new IllegalArgumentException("countdown must be between "
                + MIN_SECONDS + " and " + MAX_SECONDS + " seconds");
        }
        if (state == State.RUNNING) {
            return false;
        }
        durationSeconds = seconds;
        remainingMillis = seconds * 1000L;
        lastTickMillis = nowMillis;
        state = seconds == 0 ? State.COMPLETED : State.RUNNING;
        return true;
    }

    public boolean tick(long nowMillis, boolean paused) {
        if (state != State.RUNNING) {
            return false;
        }
        long elapsedMillis = Math.max(0L, nowMillis - lastTickMillis);
        lastTickMillis = nowMillis;
        if (paused) {
            return false;
        }
        remainingMillis = Math.max(0L, remainingMillis - elapsedMillis);
        if (remainingMillis > 0L) {
            return false;
        }
        state = State.COMPLETED;
        return true;
    }

    public boolean cancel() {
        if (state != State.RUNNING) {
            return false;
        }
        state = State.CANCELLED;
        remainingMillis = 0L;
        return true;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isCancelled() {
        return state == State.CANCELLED;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public double getRemainingSeconds() {
        return remainingMillis / 1000.0;
    }
}
