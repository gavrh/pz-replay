package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraBoundsTest {
    @Test
    void boundsCoverTheUnionOfIdentitySquares() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(
            door(10, 20), door(30, 40)), 16);

        assertTrue(bounds.isBounded());
        assertEquals(-6.0f, bounds.minX());
        assertEquals(4.0f, bounds.minY());
        assertEquals(47.0f, bounds.maxX());
        assertEquals(57.0f, bounds.maxY());
    }

    @Test
    void paddingAppliesInEveryDirection() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(10, 20)), 5);

        assertEquals(5.0f, bounds.minX());
        assertEquals(15.0f, bounds.minY());
        assertEquals(16.0f, bounds.maxX());
        assertEquals(26.0f, bounds.maxY());
    }

    @Test
    void zeroPaddingCoversOneWholeSquare() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(10, 20)), 0);

        assertTrue(bounds.isBounded());
        assertEquals(10.0f, bounds.minX());
        assertEquals(20.0f, bounds.minY());
        assertEquals(11.0f, bounds.maxX());
        assertEquals(21.0f, bounds.maxY());
    }

    @Test
    void aSingleObjectProducesAUsableBound() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(10, 20)), 16);

        assertTrue(bounds.minX() < bounds.maxX());
        assertTrue(bounds.minY() < bounds.maxY());
        assertEquals(10.0f - 16.0f, bounds.minX());
        assertEquals(20.0f + 1.0f + 16.0f, bounds.maxY());
    }

    @Test
    void manyObjectsKeepTheOutermostSquares() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(
            door(0, 0), door(5, 5), door(-4, 20), door(30, -2)), 2);

        assertEquals(-6.0f, bounds.minX());
        assertEquals(-4.0f, bounds.minY());
        assertEquals(33.0f, bounds.maxX());
        assertEquals(23.0f, bounds.maxY());
    }

    @Test
    void diffRecordsExtendTheBounds() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(
            door(10, 20), diffDoor(40, 50, 500L)), 0);

        assertEquals(10.0f, bounds.minX());
        assertEquals(20.0f, bounds.minY());
        assertEquals(41.0f, bounds.maxX());
        assertEquals(51.0f, bounds.maxY());
    }

    @Test
    void emptyCoverageIsUnbounded() {
        assertFalse(CameraBounds.fromRecords(List.of(), 16).isBounded());
        assertFalse(CameraBounds.fromRecords(null, 16).isBounded());
    }

    @Test
    void recordsWithoutTargetsAreIgnored() {
        assertFalse(CameraBounds.fromRecords(List.of(
            new WorldLogRecord(WorldLogCodec.KIND_OBJECT_STATE, 1L, 0L, 0, null)), 16)
            .isBounded());
    }

    @Test
    void clampStopsAtEveryEdgeAndCorner() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(10, 20)), 0);

        assertEquals(10.0f, bounds.clampX(-100.0f));
        assertEquals(11.0f, bounds.clampX(100.0f));
        assertEquals(20.0f, bounds.clampY(-100.0f));
        assertEquals(21.0f, bounds.clampY(100.0f));
        assertEquals(10.5f, bounds.clampX(10.5f));
        assertEquals(20.5f, bounds.clampY(20.5f));
    }

    @Test
    void followOffsetKeepsTheCameraTargetInsideTheBounds() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(0, 0), door(99, 99)), 0);

        assertEquals(-50.0f, bounds.clampOffsetX(50.0f, -100.0f));
        assertEquals(50.0f, bounds.clampOffsetX(50.0f, 100.0f));
        assertEquals(-50.0f, bounds.clampOffsetY(50.0f, -100.0f));
        assertEquals(50.0f, bounds.clampOffsetY(50.0f, 100.0f));
        assertEquals(5.0f, bounds.clampOffsetX(50.0f, 5.0f));
        assertEquals(-3.0f, bounds.clampOffsetY(50.0f, -3.0f));
    }

    @Test
    void followOffsetCornerClampsBothAxesIndependently() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(0, 0)), 0);

        assertEquals(-40.0f, bounds.clampOffsetX(40.0f, -50.0f));
        assertEquals(-30.0f, bounds.clampOffsetY(30.0f, -40.0f));
        assertEquals(-39.0f, bounds.clampOffsetX(40.0f, 50.0f));
        assertEquals(-29.0f, bounds.clampOffsetY(30.0f, 40.0f));
    }

    @Test
    void unboundedOffsetsAreUnchanged() {
        assertEquals(7.0f, CameraBounds.empty().clampOffsetX(1.0f, 7.0f));
        assertEquals(-7.0f, CameraBounds.empty().clampOffsetY(1.0f, -7.0f));
    }

    @Test
    void paddingIsTheMarginAtTheBoundary() {
        CameraBounds bounds = CameraBounds.fromRecords(List.of(door(10, 20)), 16);

        assertEquals(bounds.maxX(), bounds.clampX(bounds.maxX() + 0.01f));
        assertEquals(bounds.minX(), bounds.clampX(bounds.minX() - 0.01f));
        assertEquals(10.0f - 16.0f, bounds.minX());
        assertEquals(10.0f + 1.0f + 16.0f, bounds.maxX());
    }

    private static WorldLogRecord door(int sx, int sy) {
        return diffDoor(sx, sy, 0L);
    }

    private static WorldLogRecord diffDoor(int sx, int sy, long timeMillis) {
        return new WorldLogRecord(WorldLogCodec.KIND_OBJECT_STATE, 1L, timeMillis, 0,
            new WorldDoorState(WorldTarget.door(sx, sy, 0, 0), "door", 0, false, false,
                false, 100, 0L, false));
    }
}
