package com.gavrh.pzreplay;

public final class ReplaySurfaceModel {
    public static final String HUD = "hud";
    public static final String PANEL = "panel";
    public static final String LIBRARY = "library";
    public static final String DIALOG = "dialog";

    public enum EscapeAction {
        DIALOG,
        FREE_CAMERA,
        PANEL,
        LIBRARY,
        NONE
    }

    private String active;
    private String dialogOwner;

    public synchronized boolean open(String surface) {
        if (!isInteractive(surface)) {
            return false;
        }
        if (surface.equals(active)) {
            return true;
        }
        if (active != null || dialogOwner != null) {
            return false;
        }
        active = surface;
        return true;
    }

    public synchronized boolean close(String surface) {
        if (surface == null || !surface.equals(active)) {
            return false;
        }
        active = null;
        dialogOwner = null;
        return true;
    }

    public synchronized boolean isOpen(String surface) {
        return surface != null && surface.equals(active);
    }

    public synchronized String active() {
        return active == null ? "" : active;
    }

    public synchronized boolean openDialog(String owner) {
        if (owner == null || !owner.equals(active) || dialogOwner != null) {
            return false;
        }
        dialogOwner = owner;
        return true;
    }

    public synchronized boolean closeDialog() {
        if (dialogOwner == null) {
            return false;
        }
        dialogOwner = null;
        return true;
    }

    public synchronized boolean isDialogOpen() {
        return dialogOwner != null;
    }

    public synchronized EscapeAction escapeAction(boolean freeCamera) {
        if (dialogOwner != null) {
            return EscapeAction.DIALOG;
        }
        if (freeCamera) {
            return EscapeAction.FREE_CAMERA;
        }
        if (PANEL.equals(active)) {
            return EscapeAction.PANEL;
        }
        if (LIBRARY.equals(active)) {
            return EscapeAction.LIBRARY;
        }
        return EscapeAction.NONE;
    }

    public synchronized boolean allowsGlobalShortcut(boolean textEntryFocused) {
        return dialogOwner == null && !textEntryFocused && active == null;
    }

    public synchronized void reset() {
        active = null;
        dialogOwner = null;
    }

    private static boolean isInteractive(String surface) {
        return PANEL.equals(surface) || LIBRARY.equals(surface);
    }
}
