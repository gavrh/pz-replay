package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoSaveWindowTest {
    @Test
    void opensAndClosesOnlyOnceBetweenTheLifecycleHooks() {
        NoSaveWindow window = new NoSaveWindow();

        assertFalse(window.open());
        window.begin();

        assertTrue(window.open());
        assertTrue(window.isOpen());
        assertFalse(window.open());
        assertTrue(window.close());
        assertFalse(window.isOpen());
        assertFalse(window.close());
        assertFalse(window.open());
    }

    @Test
    void endingTheContextClosesTheWindowAndDisarmsIt() {
        NoSaveWindow window = new NoSaveWindow();
        window.begin();
        assertTrue(window.open());

        window.end();

        assertFalse(window.isOpen());
        assertFalse(window.open());
    }
}
