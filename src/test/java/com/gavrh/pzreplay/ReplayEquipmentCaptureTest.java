package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEquipmentCaptureTest {
    private static ReplayEquipment equipment(String value) {
        return new ReplayEquipment(ReplayEquipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] payload(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void recordsT0AsTheFirstEntry() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();

        assertTrue(capture.accept(0f, payload("initial"), equipment("initial")));

        List<ReplayEquipmentChange> changes = capture.changes();
        assertEquals(1, changes.size());
        assertEquals(1L, changes.get(0).sequence());
        assertEquals(0f, changes.get(0).timeSeconds());
        assertEquals(equipment("initial"), changes.get(0).equipment());
    }

    @Test
    void ignoresAnUnchangedVisual() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();
        capture.accept(0f, payload("initial"), equipment("initial"));

        assertFalse(capture.accept(1f, payload("initial"), equipment("initial")));
        assertEquals(1, capture.size());
    }

    @Test
    void recordsOnlyActualChangesWithIncreasingSequence() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();
        capture.accept(0f, payload("initial"), equipment("initial"));

        assertTrue(capture.accept(1.25f, payload("pistol"), equipment("pistol")));
        assertFalse(capture.accept(1.5f, payload("pistol"), equipment("pistol")));
        assertTrue(capture.accept(2.0f, payload("none"), equipment("none")));

        List<ReplayEquipmentChange> changes = capture.changes();
        assertEquals(3, changes.size());
        assertEquals(1.25f, changes.get(1).timeSeconds());
        assertEquals(2.0f, changes.get(2).timeSeconds());
        assertEquals(2L, changes.get(1).sequence());
        assertEquals(3L, changes.get(2).sequence());
    }

    @Test
    void resetClearsTheTimelineAndSequence() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();
        capture.accept(0f, payload("initial"), equipment("initial"));
        capture.accept(1f, payload("pistol"), equipment("pistol"));

        capture.reset();

        assertEquals(0, capture.size());
        assertFalse(capture.isDropped());
        assertTrue(capture.accept(0f, payload("new"), equipment("new")));
        assertEquals(1L, capture.changes().get(0).sequence());
    }

    @Test
    void rejectsMissingUnsupportedOrEmptyData() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();

        assertFalse(capture.accept(0f, null, equipment("initial")));
        assertFalse(capture.accept(0f, new byte[0], equipment("initial")));
        assertFalse(capture.accept(0f, payload("initial"),
            new ReplayEquipment(ReplayEquipment.CURRENT_VERSION + 1, "AQ==")));
        assertEquals(0, capture.size());
    }

    @Test
    void dropsChangesPastTheCountLimitButKeepsEarlierOnes() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();
        for (int index = 0; index < ReplayEquipmentChangeCodec.MAX_CHANGES; index++) {
            assertTrue(capture.accept(index, payload("item-" + index),
                equipment("item-" + index)));
        }

        assertFalse(capture.accept(ReplayEquipmentChangeCodec.MAX_CHANGES,
            payload("overflow"), equipment("overflow")));

        assertTrue(capture.isDropped());
        assertEquals(ReplayEquipmentChangeCodec.MAX_CHANGES, capture.size());
    }

    @Test
    void dropsChangesPastTheByteLimit() {
        ReplayEquipmentCapture capture = new ReplayEquipmentCapture();
        int half = ReplayEquipmentChangeCodec.MAX_TOTAL_SNAPSHOT_BYTES / 2;
        byte[] first = new byte[half];
        byte[] second = new byte[half];
        second[half - 1] = 1;
        byte[] third = new byte[1];

        assertTrue(capture.accept(0f, first, equipment("first")));
        assertTrue(capture.accept(1f, second, equipment("second")));
        assertFalse(capture.accept(2f, third, equipment("third")));

        assertTrue(capture.isDropped());
        assertEquals(2, capture.size());
    }
}
