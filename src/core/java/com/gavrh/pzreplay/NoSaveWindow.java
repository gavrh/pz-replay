package com.gavrh.pzreplay;

public final class NoSaveWindow {
    private boolean active;
    private boolean open;

    public void begin() {
        active = true;
        open = false;
    }

    public boolean open() {
        if (!active || open) {
            return false;
        }
        open = true;
        return true;
    }

    public boolean close() {
        if (!open) {
            return false;
        }
        open = false;
        active = false;
        return true;
    }

    public boolean isOpen() {
        return open;
    }

    public void end() {
        active = false;
        open = false;
    }
}
