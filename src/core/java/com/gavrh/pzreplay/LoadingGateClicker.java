package com.gavrh.pzreplay;

public final class LoadingGateClicker {
    public enum Action {
        NONE,
        PRESS,
        RELEASE
    }

    private static final long HOLD_MILLIS = 1_000L;

    private boolean pressed;
    private long pressedAtMillis;

    public Action update(boolean contextLoadActive, boolean gatePresent, long nowMillis) {
        if (!contextLoadActive) {
            return clear();
        }
        if (pressed) {
            if (!gatePresent || nowMillis - pressedAtMillis >= HOLD_MILLIS) {
                pressed = false;
                return Action.RELEASE;
            }
            return Action.NONE;
        }
        if (!gatePresent) {
            return Action.NONE;
        }
        pressed = true;
        pressedAtMillis = nowMillis;
        return Action.PRESS;
    }

    public Action clear() {
        if (!pressed) {
            return Action.NONE;
        }
        pressed = false;
        return Action.RELEASE;
    }

    public boolean isPressed() {
        return pressed;
    }
}
