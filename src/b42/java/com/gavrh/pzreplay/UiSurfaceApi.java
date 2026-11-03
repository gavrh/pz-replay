package com.gavrh.pzreplay;

import me.zed_0xff.zombie_buddy.Exposer;

@Exposer.LuaClass(name = "PZReplaySurface")
public final class UiSurfaceApi {
    private static final UiSurfaceModel MODEL = new UiSurfaceModel();

    private UiSurfaceApi() {
    }

    public static boolean open(String surface) {
        return MODEL.open(surface);
    }

    public static boolean close(String surface) {
        return MODEL.close(surface);
    }

    public static boolean isOpen(String surface) {
        return MODEL.isOpen(surface);
    }

    public static String active() {
        return MODEL.active();
    }

    public static boolean openDialog(String owner) {
        return MODEL.openDialog(owner);
    }

    public static boolean closeDialog() {
        return MODEL.closeDialog();
    }

    public static boolean isDialogOpen() {
        return MODEL.isDialogOpen();
    }

    public static String escapeAction() {
        switch (MODEL.escapeAction(PlaybackApi.isCameraFree())) {
            case DIALOG:
                return "dialog";
            case FREE_CAMERA:
                return "free_camera";
            case PANEL:
                return "panel";
            case LIBRARY:
                return "library";
            default:
                return "none";
        }
    }

    public static boolean allowsGlobalShortcut(boolean textEntryFocused) {
        return MODEL.allowsGlobalShortcut(textEntryFocused);
    }

    public static void reset() {
        MODEL.reset();
    }
}
