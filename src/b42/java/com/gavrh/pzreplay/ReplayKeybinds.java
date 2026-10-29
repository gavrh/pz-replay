package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjglx.input.Keyboard;

final class ReplayKeybinds {
    private static final Map<Integer, String> KNOWN_BINDINGS = knownBindings();

    private ReplayKeybinds() {
    }

    static List<ReplaySettings.Conflict> conflicts() {
        return ReplaySettingsStore.get().conflicts(KNOWN_BINDINGS);
    }

    static String check(String action, int key) {
        if (key <= 0) {
            return "invalid key";
        }
        if (isModifier(key)) {
            return "modifier keys cannot be used";
        }
        ReplaySettings settings = ReplaySettingsStore.get();
        for (String otherAction : ReplaySettings.ACTIONS) {
            if (!otherAction.equals(action) && settings.key(otherAction) == key) {
                return "already used by " + label(otherAction);
            }
        }
        String known = KNOWN_BINDINGS.get(key);
        if (known != null) {
            return "conflicts with " + known;
        }
        return "";
    }

    static List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        for (ReplaySettings.Conflict conflict : conflicts()) {
            warnings.add(warning(conflict));
        }
        return warnings;
    }

    static boolean hasConflict(String action) {
        for (ReplaySettings.Conflict conflict : conflicts()) {
            if (conflict.action().equals(action)) {
                return true;
            }
        }
        return false;
    }

    static String warning(ReplaySettings.Conflict conflict) {
        return label(conflict.action()) + " binding " + keyName(conflict.key())
            + " conflicts with " + conflict.other();
    }

    static String keyName(int key) {
        try {
            String name = Keyboard.getKeyName(key);
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (RuntimeException ignored) {
        }
        return "#" + key;
    }

    static boolean isModifier(int key) {
        return key == Keyboard.KEY_LSHIFT
            || key == Keyboard.KEY_RSHIFT
            || key == Keyboard.KEY_LCONTROL
            || key == Keyboard.KEY_RCONTROL
            || key == Keyboard.KEY_LMENU
            || key == Keyboard.KEY_RMENU
            || key == Keyboard.KEY_LMETA
            || key == Keyboard.KEY_RMETA;
    }

    static String label(String action) {
        return switch (action) {
            case ReplaySettings.ACTION_RECORD -> "record/stop";
            case ReplaySettings.ACTION_PLAY -> "play/stop";
            case ReplaySettings.ACTION_CAMERA -> "camera follow";
            case ReplaySettings.ACTION_PANEL -> "panel";
            default -> action;
        };
    }

    private static Map<Integer, String> knownBindings() {
        Map<Integer, String> bindings = new LinkedHashMap<>();
        bindings.put(Keyboard.KEY_W, "move forward");
        bindings.put(Keyboard.KEY_S, "move backward");
        bindings.put(Keyboard.KEY_A, "move left");
        bindings.put(Keyboard.KEY_D, "move right");
        bindings.put(Keyboard.KEY_LSHIFT, "run");
        bindings.put(Keyboard.KEY_E, "interact");
        bindings.put(Keyboard.KEY_LMENU, "sprint or floor attack");
        bindings.put(Keyboard.KEY_R, "reload, sharpen, or rotate");
        bindings.put(Keyboard.KEY_TAB, "toggle mode");
        bindings.put(Keyboard.KEY_ESCAPE, "menu or cancel");
        bindings.put(Keyboard.KEY_Y, "walk to");
        bindings.put(Keyboard.KEY_C, "crouch");
        bindings.put(Keyboard.KEY_LCONTROL, "aim");
        bindings.put(Keyboard.KEY_SPACE, "melee or brake");
        bindings.put(Keyboard.KEY_X, "rack firearm");
        bindings.put(Keyboard.KEY_N, "start engine");
        bindings.put(Keyboard.KEY_F, "vehicle headlights");
        bindings.put(Keyboard.KEY_O, "vehicle heater");
        bindings.put(Keyboard.KEY_U, "vehicle mechanics");
        bindings.put(Keyboard.KEY_Q, "vehicle horn");
        bindings.put(Keyboard.KEY_V, "vehicle radial or animal radial");
        bindings.put(Keyboard.KEY_Z, "switch seat");
        bindings.put(Keyboard.KEY_B, "crafting");
        bindings.put(Keyboard.KEY_I, "inventory");
        bindings.put(Keyboard.KEY_L, "skills");
        bindings.put(Keyboard.KEY_H, "health");
        bindings.put(Keyboard.KEY_J, "info");
        bindings.put(Keyboard.KEY_P, "clothing protection");
        bindings.put(Keyboard.KEY_RBRACKET, "moveable panel mode");
        bindings.put(Keyboard.KEY_M, "map");
        bindings.put(Keyboard.KEY_F1, "survival guide");
        bindings.put(Keyboard.KEY_F2, "pause");
        bindings.put(Keyboard.KEY_F3, "normal speed");
        bindings.put(Keyboard.KEY_F4, "fast forward x1");
        bindings.put(Keyboard.KEY_F5, "fast forward x2");
        bindings.put(Keyboard.KEY_F6, "fast forward x3");
        bindings.put(Keyboard.KEY_F10, "screenshot or developer motion start");
        bindings.put(Keyboard.KEY_F8, "developer capture");
        bindings.put(Keyboard.KEY_F9, "developer ghost toggle");
        bindings.put(Keyboard.KEY_F11, "developer motion pause");
        bindings.put(Keyboard.KEY_F12, "developer motion stop");
        bindings.put(Keyboard.KEY_EQUALS, "zoom in");
        bindings.put(Keyboard.KEY_MINUS, "zoom out");
        return Map.copyOf(bindings);
    }
}
