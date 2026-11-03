package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceCaptureTest {
    private static Appearance appearance(String value) {
        return new Appearance(Appearance.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] payload(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void ignoresPayloadsEqualToListedBaseline() {
        AppearanceCapture capture = new AppearanceCapture();
        capture.markBaseline(payload("outfit-a"));

        assertFalse(capture.accept(1.0f, payload("outfit-a"), appearance("outfit-a")));
        assertTrue(capture.accept(2.0f, payload("outfit-b"), appearance("outfit-b")));
        assertFalse(capture.accept(3.0f, payload("outfit-b"), appearance("outfit-b")));
        assertEquals(1, capture.size());
    }

    @Test
    void recordsOneEntryPerMeaningfulChangeWithIncreasingSequence() {
        AppearanceCapture capture = new AppearanceCapture();

        assertTrue(capture.accept(1.5f, payload("a"), appearance("a")));
        assertFalse(capture.accept(1.6f, payload("a"), appearance("a")));
        assertTrue(capture.accept(4.25f, payload("b"), appearance("b")));

        assertEquals(2, capture.size());
        assertEquals(1L, capture.changes().get(0).sequence());
        assertEquals(1.5f, capture.changes().get(0).timeSeconds());
        assertEquals(2L, capture.changes().get(1).sequence());
        assertEquals(4.25f, capture.changes().get(1).timeSeconds());
        assertFalse(capture.isDropped());
    }

    @Test
    void equalTimestampsKeepDistinctSequenceOrder() {
        AppearanceCapture capture = new AppearanceCapture();

        assertTrue(capture.accept(2.0f, payload("a"), appearance("a")));
        assertTrue(capture.accept(2.0f, payload("b"), appearance("b")));

        assertEquals(2, capture.size());
        assertEquals(1L, capture.changes().get(0).sequence());
        assertEquals(2L, capture.changes().get(1).sequence());
    }

    @Test
    void dropsChangesAfterTheEntryLimit() {
        AppearanceCapture capture = new AppearanceCapture();
        for (int index = 0; index < AppearanceChangeCodec.MAX_CHANGES; index++) {
            byte[] payload = new byte[] { (byte) index, (byte) (index >>> 8) };
            assertTrue(capture.accept(index, payload, appearance("entry")));
        }

        assertFalse(capture.accept(999.0f, payload("final"), appearance("final")));
        assertTrue(capture.isDropped());
        assertEquals(AppearanceChangeCodec.MAX_CHANGES, capture.size());
    }

    @Test
    void dropsChangesAboveTheTotalSnapshotLimit() {
        AppearanceCapture capture = new AppearanceCapture();
        byte[] large = new byte[AppearanceChangeCodec.MAX_TOTAL_SNAPSHOT_BYTES / 2 + 1];
        byte[] second = large.clone();
        second[0] = 1;

        assertTrue(capture.accept(1.0f, large, appearance("first")));
        assertFalse(capture.accept(2.0f, second, appearance("second")));
        assertTrue(capture.isDropped());
        assertEquals(1, capture.size());
    }

    @Test
    void rejectsEmptyOrUnsupportedSnapshots() {
        AppearanceCapture capture = new AppearanceCapture();

        assertFalse(capture.accept(1.0f, new byte[0], appearance("empty")));
        assertFalse(capture.accept(2.0f, payload("a"),
            new Appearance(Appearance.CURRENT_VERSION + 1, "AQ==")));
        assertFalse(capture.accept(3.0f, payload("b"), null));
        assertEquals(0, capture.size());
        assertFalse(capture.isDropped());
    }

    @Test
    void resetClearsBaselineDropsAndSequence() {
        AppearanceCapture capture = new AppearanceCapture();
        capture.markBaseline(payload("baseline"));
        assertTrue(capture.accept(1.0f, payload("a"), appearance("a")));

        capture.reset();

        assertEquals(0, capture.size());
        assertFalse(capture.isDropped());
        assertTrue(capture.accept(0.0f, payload("baseline"), appearance("baseline")));
        assertEquals(1L, capture.changes().get(0).sequence());
    }
}
