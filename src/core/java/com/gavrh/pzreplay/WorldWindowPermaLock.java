package com.gavrh.pzreplay;

import java.lang.reflect.Field;

final class WorldWindowPermaLock {
    private WorldWindowPermaLock() {
    }

    static boolean clear(Object window, Field field) {
        if (window == null || field == null) {
            return false;
        }
        try {
            field.setBoolean(window, false);
            return true;
        } catch (IllegalAccessException | RuntimeException exception) {
            return false;
        }
    }
}
