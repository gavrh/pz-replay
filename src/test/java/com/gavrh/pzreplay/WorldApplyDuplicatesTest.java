package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyDuplicatesTest {
    @Test
    void duplicateT0IdentityKeepsTheFirstRecordAndWarnsOnce() {
        WorldApplyDuplicates duplicates = new WorldApplyDuplicates();
        WorldApplySummary summary = new WorldApplySummary();
        WorldLogRecord first = record(1, 0, WorldLogCodec.KIND_OBJECT_STATE,
            door(false));
        WorldLogRecord second = new WorldLogRecord(WorldLogCodec.KIND_OBJECT_STATE, 2, 0,
            WorldLogCodec.FLAG_PARTIAL, door(true));

        List<WorldLogRecord> applied = accept(duplicates, summary, first, second);

        assertEquals(List.of(1L), applied.stream().map(WorldLogRecord::sequence).toList());
        assertFalse(((WorldDoorState) applied.get(0).record()).open());
        assertEquals(1, summary.skippedCount());
        assertEquals(1, summary.duplicateCount());
        assertEquals(0, summary.partialCount());
        assertEquals(1, summary.warnings().size());
    }

    @Test
    void twoStateRecordsAtOneTimeKeepTheFirstRecord() {
        WorldApplyDuplicates duplicates = new WorldApplyDuplicates();
        WorldApplySummary summary = new WorldApplySummary();

        List<WorldLogRecord> applied = accept(duplicates, summary,
            record(1, 0, WorldLogCodec.KIND_OBJECT_STATE, door(false)),
            record(2, 500, WorldLogCodec.KIND_OBJECT_STATE, door(true)),
            record(3, 500, WorldLogCodec.KIND_OBJECT_STATE, door(false)));

        assertEquals(List.of(1L, 2L), applied.stream().map(WorldLogRecord::sequence).toList());
        assertTrue(((WorldDoorState) applied.get(1).record()).open());
        assertEquals(1, summary.duplicateCount());
    }

    @Test
    void orderedDestroyThenAddAppliesBothRecords() {
        WorldApplyDuplicates duplicates = new WorldApplyDuplicates();
        WorldApplySummary summary = new WorldApplySummary();
        WorldDoorState door = door(false);
        WorldObjectDestroyed destroyed = new WorldObjectDestroyed(door.target(),
            door.classId(), door.sprite(), "", door.objectIndex(), door);
        WorldObjectAdded added = new WorldObjectAdded(door.target(), door.classId(),
            door.sprite(), "", door.objectIndex(), 1, door);

        List<WorldLogRecord> applied = accept(duplicates, summary,
            record(1, 500, WorldLogCodec.KIND_OBJECT_DESTROYED, destroyed),
            record(2, 500, WorldLogCodec.KIND_OBJECT_ADDED, added));

        assertEquals(List.of(1L, 2L), applied.stream().map(WorldLogRecord::sequence).toList());
        assertEquals(0, summary.duplicateCount());
    }

    @Test
    void duplicateHandlingIsDeterministicWhenPhaseOrderChanges() {
        WorldLogRecord first = record(1, 500, WorldLogCodec.KIND_OBJECT_STATE, door(true));
        WorldLogRecord second = record(2, 500, WorldLogCodec.KIND_OBJECT_STATE, door(false));

        assertFirstWins(first, second);
        assertFirstWins(second, first);
    }

    @Test
    void recordsAtDifferentTimesAreNotDuplicates() {
        WorldApplyDuplicates duplicates = new WorldApplyDuplicates();
        WorldApplySummary summary = new WorldApplySummary();

        List<WorldLogRecord> applied = accept(duplicates, summary,
            record(1, 500, WorldLogCodec.KIND_OBJECT_STATE, door(true)),
            record(2, 1000, WorldLogCodec.KIND_OBJECT_STATE, door(false)));

        assertEquals(2, applied.size());
        assertEquals(0, summary.duplicateCount());
    }

    private static void assertFirstWins(WorldLogRecord first, WorldLogRecord second) {
        WorldApplyDuplicates duplicates = new WorldApplyDuplicates();
        WorldApplySummary summary = new WorldApplySummary();

        List<WorldLogRecord> applied = accept(duplicates, summary, first, second);

        assertEquals(List.of(first), applied);
        assertEquals(1, summary.skippedCount());
        assertEquals(1, summary.duplicateCount());
        assertEquals(1, summary.warnings().size());
    }

    private static List<WorldLogRecord> accept(WorldApplyDuplicates duplicates,
        WorldApplySummary summary, WorldLogRecord... records) {
        List<WorldLogRecord> applied = new ArrayList<>();
        for (WorldLogRecord record : records) {
            if (!duplicates.skip(record, summary)) {
                applied.add(record);
            }
        }
        return applied;
    }

    private static WorldLogRecord record(long sequence, long time, int kind,
        WorldStateRecord record) {
        return new WorldLogRecord(kind, sequence, time, 0, record);
    }

    private static WorldDoorState door(boolean open) {
        return new WorldDoorState(WorldTarget.door(10, 20, 0, 0), "fixtures_doors_01_1",
            3, false, false, open, 100, 7L, true);
    }
}
