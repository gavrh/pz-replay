package com.gavrh.pzreplay;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldWindowPermaLockTest {
    @Test
    void nullStateClearsThePrivateFieldWithoutThrowing() throws Exception {
        Window window = new Window();
        Field field = Window.class.getDeclaredField("permaLocked");
        field.setAccessible(true);

        assertTrue(WorldWindowPermaLock.clear(window, field));
        assertFalse(field.getBoolean(window));
    }

    @Test
    void missingFieldDegradesInsteadOfThrowing() {
        assertFalse(WorldWindowPermaLock.clear(new Window(), null));
    }

    private static final class Window {
        private boolean permaLocked = true;
    }
}
