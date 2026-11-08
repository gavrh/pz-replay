package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadingGateClickerTest {
    @Test
    void clicksOnlyWhileAContextLoadIsActiveAndTheGateIsPresent() {
        LoadingGateClicker clicker = new LoadingGateClicker();

        assertEquals(LoadingGateClicker.Action.NONE,
            clicker.update(false, true, 0L));
        assertEquals(LoadingGateClicker.Action.NONE,
            clicker.update(true, false, 0L));
        assertEquals(LoadingGateClicker.Action.PRESS,
            clicker.update(true, true, 0L));
        assertTrue(clicker.isPressed());

        assertEquals(LoadingGateClicker.Action.NONE,
            clicker.update(true, true, 10L));
        assertEquals(LoadingGateClicker.Action.RELEASE,
            clicker.update(true, false, 20L));
        assertFalse(clicker.isPressed());
    }

    @Test
    void releasesAndRetriesWhenTheGateStaysVisible() {
        LoadingGateClicker clicker = new LoadingGateClicker();

        assertEquals(LoadingGateClicker.Action.PRESS,
            clicker.update(true, true, 0L));
        assertEquals(LoadingGateClicker.Action.NONE,
            clicker.update(true, true, 999L));
        assertEquals(LoadingGateClicker.Action.RELEASE,
            clicker.update(true, true, 1_000L));
        assertEquals(LoadingGateClicker.Action.PRESS,
            clicker.update(true, true, 1_001L));
    }

    @Test
    void clearingOnFailureMenuOrCleanupReleasesAQueuedClick() {
        LoadingGateClicker clicker = new LoadingGateClicker();

        assertEquals(LoadingGateClicker.Action.PRESS,
            clicker.update(true, true, 0L));

        assertEquals(LoadingGateClicker.Action.RELEASE, clicker.clear());
        assertFalse(clicker.isPressed());
        assertEquals(LoadingGateClicker.Action.NONE, clicker.clear());
        assertEquals(LoadingGateClicker.Action.NONE,
            clicker.update(false, true, 20L));
    }
}
