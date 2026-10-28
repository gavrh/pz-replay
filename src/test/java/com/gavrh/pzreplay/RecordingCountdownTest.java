package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingCountdownTest {
    @Test
    void countsDownAndCompletesExactlyOnce() {
        RecordingCountdown countdown = new RecordingCountdown();

        assertTrue(countdown.start(3, 0L));
        assertTrue(countdown.isRunning());
        assertEquals(3.0, countdown.getRemainingSeconds());

        assertFalse(countdown.tick(1_000L, false));
        assertEquals(2.0, countdown.getRemainingSeconds());

        assertFalse(countdown.tick(2_999L, false));
        assertEquals(0.001, countdown.getRemainingSeconds(), 0.0001);

        assertTrue(countdown.tick(3_000L, false));
        assertFalse(countdown.isRunning());
        assertTrue(countdown.isCompleted());
        assertEquals(0.0, countdown.getRemainingSeconds());

        assertFalse(countdown.tick(4_000L, false));
    }

    @Test
    void pausedTicksDoNotAdvanceTheCountdown() {
        RecordingCountdown countdown = new RecordingCountdown();
        assertTrue(countdown.start(3, 0L));

        assertFalse(countdown.tick(1_000L, true));
        assertEquals(3.0, countdown.getRemainingSeconds());

        assertFalse(countdown.tick(2_000L, false));
        assertEquals(2.0, countdown.getRemainingSeconds());

        assertTrue(countdown.tick(4_000L, false));
        assertTrue(countdown.isCompleted());
    }

    @Test
    void cancelBeforeCompletionNeverCompletes() {
        RecordingCountdown countdown = new RecordingCountdown();
        assertTrue(countdown.start(3, 0L));
        assertFalse(countdown.tick(1_000L, false));

        assertTrue(countdown.cancel());
        assertTrue(countdown.isCancelled());
        assertFalse(countdown.isRunning());
        assertEquals(0.0, countdown.getRemainingSeconds());

        assertFalse(countdown.tick(10_000L, false));
        assertFalse(countdown.isCompleted());
    }

    @Test
    void zeroSecondsCompletesImmediately() {
        RecordingCountdown countdown = new RecordingCountdown();

        assertTrue(countdown.start(0, 100L));

        assertFalse(countdown.isRunning());
        assertTrue(countdown.isCompleted());
        assertEquals(0, countdown.getDurationSeconds());
    }

    @Test
    void rejectsOutOfRangeDurationsAndSecondStartWhileRunning() {
        RecordingCountdown countdown = new RecordingCountdown();

        assertThrows(IllegalArgumentException.class, () -> countdown.start(-1, 0L));
        assertThrows(IllegalArgumentException.class,
            () -> countdown.start(RecordingCountdown.MAX_SECONDS + 1, 0L));

        assertTrue(countdown.start(2, 0L));
        assertFalse(countdown.start(5, 100L));
        assertEquals(2, countdown.getDurationSeconds());
        assertEquals(2.0, countdown.getRemainingSeconds());
    }

    @Test
    void canStartAgainAfterCompletionOrCancellation() {
        RecordingCountdown countdown = new RecordingCountdown();

        assertTrue(countdown.start(1, 0L));
        assertTrue(countdown.tick(1_000L, false));
        assertTrue(countdown.start(2, 2_000L));
        assertTrue(countdown.isRunning());
        assertEquals(2, countdown.getDurationSeconds());

        assertTrue(countdown.cancel());
        assertTrue(countdown.start(3, 3_000L));
        assertTrue(countdown.isRunning());
        assertEquals(3, countdown.getDurationSeconds());
    }

    @Test
    void validatesConfiguredRange() {
        assertTrue(RecordingCountdown.isValidSeconds(0));
        assertTrue(RecordingCountdown.isValidSeconds(3));
        assertTrue(RecordingCountdown.isValidSeconds(10));
        assertFalse(RecordingCountdown.isValidSeconds(-1));
        assertFalse(RecordingCountdown.isValidSeconds(11));
        assertEquals(3, RecordingCountdown.DEFAULT_SECONDS);
    }
}
