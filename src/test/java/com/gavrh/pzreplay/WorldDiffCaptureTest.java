package com.gavrh.pzreplay;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDiffCaptureTest {
    private static final long TIME = 500L;

    @Test
    void unchangedIdentitiesProduceNoRecord() throws Exception {
        WorldDoorState door = door(10, 20, false);
        WorldDiffSampler sampler = sampler(door);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(door),
            Set.of(square(10, 20)), Set.of()));

        assertTrue(entries.isEmpty());
    }

    @Test
    void unchangedBarricadeWithEqualPlankHealthProducesNoRecord() throws Exception {
        WorldBarricadeState before = barricade(10, 20);
        WorldBarricadeState after = barricade(10, 20);
        WorldDiffSampler sampler = sampler(before);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(after),
            Set.of(square(10, 20)), Set.of()));

        assertTrue(entries.isEmpty());
    }

    @Test
    void changedWindowEmitsAbsoluteStateWithStableIdentity() throws Exception {
        WorldWindowState before = window(10, 20, "fixtures_windows_01_31", false);
        WorldWindowState after = window(10, 20, "fixtures_windows_01_25", true);
        WorldDiffSampler sampler = sampler(before);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(after),
            Set.of(square(10, 20)), Set.of()));

        assertEquals(1, entries.size());
        WorldWindowState changed = assertInstanceOf(WorldWindowState.class,
            entries.get(0).record());
        assertEquals(before.target(), changed.target());
        assertEquals("fixtures_windows_01_25", changed.sprite());
        assertTrue(changed.open());
        WorldWindowState decoded = assertInstanceOf(WorldWindowState.class,
            WorldLogCodec.decodeAll(WorldLogCodec.encodeDiff(entries, 1L, TIME))
                .get(0).record());
        assertEquals(changed, decoded);
    }

    @Test
    void newlyCoveredDoorEmitsStateInsteadOfAddition() throws Exception {
        WorldDoorState door = door(30, 40, true);
        WorldDiffSampler sampler = sampler();

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(door),
            Set.of(square(30, 40)), Set.of(square(30, 40))));

        assertEquals(1, entries.size());
        assertInstanceOf(WorldDoorState.class, entries.get(0).record());
        assertEquals(WorldLogCodec.KIND_OBJECT_STATE, entries.get(0).record().kind());
    }

    @Test
    void objectCreatedAfterCoverageEmitsAddition() throws Exception {
        WorldDiffSampler sampler = sampler();
        sampler.diff(sample(List.of(), Set.of(square(30, 40)), Set.of(square(30, 40))));

        WorldDoorState door = door(30, 40, false);
        List<WorldLogEntry> entries = sampler.diff(sample(List.of(door),
            Set.of(square(30, 40)), Set.of()));

        assertEquals(1, entries.size());
        WorldObjectAdded added = assertInstanceOf(WorldObjectAdded.class,
            entries.get(0).record());
        assertEquals(WorldLogCodec.CLASS_DOOR, added.classId());
        assertEquals(1, added.addMode());
        assertEquals(door, added.state());
    }

    @Test
    void disappearedDoorEmitsSelfSufficientDestroyRecord() throws Exception {
        WorldDoorState door = door(10, 20, true);
        WorldDiffSampler sampler = sampler(door);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(),
            Set.of(square(10, 20)), Set.of()));

        assertEquals(1, entries.size());
        WorldObjectDestroyed destroyed = assertInstanceOf(WorldObjectDestroyed.class,
            entries.get(0).record());
        assertEquals(door.target(), destroyed.target());
        assertEquals(door.sprite(), destroyed.sprite());
        assertEquals(door, destroyed.restoreState());
    }

    @Test
    void removalOrdersChildBeforeParent() throws Exception {
        WorldDoorState door = door(10, 20, false);
        WorldBarricadeState barricade = barricade(10, 20);
        WorldDiffSampler sampler = sampler(door, barricade);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(),
            Set.of(square(10, 20)), Set.of()));
        List<WorldLogRecord> decoded = WorldLogCodec.decodeAll(
            WorldLogCodec.encodeDiff(entries, 1L, TIME));

        assertEquals(WorldLogCodec.KIND_OBJECT_DESTROYED, decoded.get(0).kind());
        assertEquals(WorldLogCodec.CLASS_BARRICADE, decoded.get(0).record().classId());
        assertEquals(WorldLogCodec.CLASS_DOOR, decoded.get(1).record().classId());
    }

    @Test
    void additionOrdersParentBeforeChild() throws Exception {
        WorldDoorState door = door(10, 20, false);
        WorldBarricadeState barricade = barricade(10, 20);
        WorldDiffSampler sampler = sampler();
        sampler.diff(sample(List.of(), Set.of(square(10, 20)),
            Set.of(square(10, 20))));

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(door, barricade),
            Set.of(square(10, 20)), Set.of()));
        List<WorldLogRecord> decoded = WorldLogCodec.decodeAll(
            WorldLogCodec.encodeDiff(entries, 1L, TIME));

        assertEquals(WorldLogCodec.KIND_OBJECT_ADDED, decoded.get(0).kind());
        assertEquals(WorldLogCodec.CLASS_DOOR, decoded.get(0).record().classId());
        assertEquals(WorldLogCodec.CLASS_BARRICADE, decoded.get(1).record().classId());
    }

    @Test
    void unloadedSquareKeepsLastStateUntilItReloads() throws Exception {
        WorldDoorState before = door(10, 20, false);
        WorldDoorState after = door(10, 20, true);
        WorldDiffSampler sampler = sampler(before);

        assertTrue(sampler.diff(sample(List.of(), Set.of(), Set.of())).isEmpty());
        List<WorldLogEntry> reloaded = sampler.diff(sample(List.of(after),
            Set.of(square(10, 20)), Set.of()));

        assertEquals(1, reloaded.size());
        assertInstanceOf(WorldDoorState.class, reloaded.get(0).record());
    }

    @Test
    void genericMoveIsCapturedAsDestroyPlusAdd() throws Exception {
        WorldGenericState before = generic(10, 20, "chair", false);
        WorldGenericState after = generic(11, 20, "chair", false);
        WorldDiffSampler sampler = sampler(before);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(after),
            Set.of(square(10, 20), square(11, 20)), Set.of()));
        List<WorldLogRecord> decoded = WorldLogCodec.decodeAll(
            WorldLogCodec.encodeDiff(entries, 1L, TIME));

        assertEquals(2, decoded.size());
        assertEquals(WorldLogCodec.KIND_OBJECT_DESTROYED, decoded.get(0).kind());
        assertEquals(10, decoded.get(0).record().target().sx());
        assertEquals(WorldLogCodec.KIND_OBJECT_ADDED, decoded.get(1).kind());
        assertEquals(11, decoded.get(1).record().target().sx());
    }

    @Test
    void complexGenericAdditionIsRecordedOnly() throws Exception {
        WorldGenericState complex = generic(10, 20, "container", true);
        WorldDiffSampler sampler = sampler();
        sampler.diff(sample(List.of(), Set.of(square(10, 20)), Set.of(square(10, 20))));

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(complex),
            Set.of(square(10, 20)), Set.of()));

        assertEquals(1, entries.size());
        WorldObjectAdded added = assertInstanceOf(WorldObjectAdded.class,
            entries.get(0).record());
        assertEquals(2, added.addMode());
        assertEquals(WorldLogCodec.FLAG_RECORDED_ONLY, entries.get(0).flags());
    }

    @Test
    void newlyCoveredGenericOnlySeedsTheLastState() throws Exception {
        WorldGenericState generic = generic(10, 20, "chair", false);
        WorldDiffSampler sampler = sampler();

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(generic),
            Set.of(square(10, 20)), Set.of(square(10, 20))));

        assertTrue(entries.isEmpty());
    }

    @Test
    void vehicleRemovalIsPresentFalseAndReloadRequired() throws Exception {
        WorldVehicleState vehicle = vehicle(10, 20, 1f);
        WorldDiffSampler sampler = sampler(vehicle);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(),
            Set.of(square(10, 20)), Set.of()));

        assertEquals(1, entries.size());
        WorldVehicleState removed = assertInstanceOf(WorldVehicleState.class,
            entries.get(0).record());
        assertFalse(removed.present());
        assertTrue((entries.get(0).flags() & WorldLogCodec.FLAG_RELOAD_REQUIRED) != 0);
    }

    @Test
    void vehicleOutsideCoverageIsKeptRatherThanRemoved() throws Exception {
        WorldVehicleState vehicle = vehicle(10, 20, 1f);
        String identity = WorldIdentity.of(vehicle);
        WorldDiffSampler sampler = sampler(vehicle);

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(),
            Set.of(square(10, 20)), Set.of(), Set.of(identity)));

        assertTrue(entries.isEmpty());
    }

    @Test
    void duplicateIdentityIsPartialAndCounted() throws Exception {
        WorldDoorState first = door(10, 20, false);
        WorldDoorState second = door(10, 20, true);
        WorldDiffSampler sampler = sampler();

        List<WorldLogEntry> entries = sampler.diff(sample(List.of(first, second),
            Set.of(square(10, 20)), Set.of(square(10, 20))));

        assertEquals(1, entries.size());
        assertEquals(first, entries.get(0).record());
        assertEquals(WorldLogCodec.FLAG_PARTIAL, entries.get(0).flags());
        assertEquals(1L, sampler.getDuplicateIdentities());
    }

    @Test
    void scannerDuplicateIdentityIsPartialAndCounted() throws Exception {
        WorldDoorState door = door(10, 20, false);
        WorldDiffSampler sampler = sampler();

        List<WorldLogEntry> entries = sampler.diff(new WorldDiffSample(List.of(door),
            Set.of(square(10, 20)), Set.of(square(10, 20)), Set.of(),
            Set.of(WorldIdentity.of(door))));

        assertEquals(1, entries.size());
        assertEquals(WorldLogCodec.FLAG_PARTIAL, entries.get(0).flags());
        assertEquals(1L, sampler.getDuplicateIdentities());
    }

    @Test
    void sequenceAndTimeAdvanceAcrossSamples() throws Exception {
        WorldDoorState closed = door(10, 20, false);
        WorldDoorState open = door(10, 20, true);
        WorldLogBuilder builder = new WorldLogBuilder();
        builder.appendT0(List.of(closed));
        WorldDiffSampler sampler = sampler(closed);

        List<WorldLogEntry> first = sampler.diff(sample(List.of(open),
            Set.of(square(10, 20)), Set.of()));
        List<WorldLogEntry> second = sampler.diff(sample(List.of(),
            Set.of(square(10, 20)), Set.of()));
        assertTrue(builder.append(first, 500L));
        assertTrue(builder.append(second, 1000L));

        List<WorldLogRecord> decoded = WorldLogCodec.decodeAll(builder.payload());
        assertEquals(List.of(1L, 2L, 3L),
            decoded.stream().map(WorldLogRecord::sequence).toList());
        assertEquals(List.of(0L, 500L, 1000L),
            decoded.stream().map(WorldLogRecord::timeMillis).toList());
        assertEquals(3L, builder.changes());
    }

    @Test
    void capStopsTheSampleAndKeepsTheExistingLog() throws Exception {
        WorldDoorState closed = door(10, 20, false);
        WorldDoorState open = door(10, 20, true);
        WorldLogBuilder builder = new WorldLogBuilder(1L, 1_000_000L);
        builder.appendT0(List.of(closed));
        WorldDiffSampler sampler = sampler(closed);
        List<WorldLogEntry> entries = sampler.diff(sample(List.of(open),
            Set.of(square(10, 20)), Set.of()));

        assertFalse(builder.append(entries, 500L));
        assertTrue(builder.isTruncated());
        assertEquals(1L, builder.changes());
        assertEquals(1, WorldLogCodec.countRecords(builder.payload()));
        assertFalse(builder.append(entries, 1000L));
    }

    @Test
    void byteCapStopsBeforeExceedingTheContainerLimit() throws Exception {
        WorldDoorState closed = door(10, 20, false);
        WorldLogBuilder builder = new WorldLogBuilder(100L,
            WorldLogCodec.encodeT0(List.of(closed)).length);
        builder.appendT0(List.of(closed));
        WorldDiffSampler sampler = sampler(closed);
        List<WorldLogEntry> entries = sampler.diff(sample(List.of(door(10, 20, true)),
            Set.of(square(10, 20)), Set.of()));

        assertFalse(builder.append(entries, 500L));
        assertTrue(builder.isTruncated());
        assertEquals(WorldLogCodec.encodeT0(List.of(closed)).length, builder.bytes());
    }

    @Test
    void sameInputSequenceProducesIdenticalBytes() throws Exception {
        WorldDoorState door = door(10, 20, false);
        WorldBarricadeState barricade = barricade(10, 20);

        byte[] first = runDeterministic(door, barricade);
        byte[] second = runDeterministic(door, barricade);

        assertArrayEquals(first, second);
    }

    @Test
    void decreasingTimeIsRejected() throws Exception {
        WorldLogBuilder builder = new WorldLogBuilder();
        builder.appendT0(List.of(door(10, 20, false)));
        WorldDiffSampler sampler = sampler(door(10, 20, false));
        List<WorldLogEntry> entries = sampler.diff(sample(List.of(door(10, 20, true)),
            Set.of(square(10, 20)), Set.of()));
        builder.append(entries, 500L);

        assertThrows(ReplayFormatException.class,
            () -> builder.append(entries, 499L));
    }

    private static byte[] runDeterministic(WorldDoorState door,
        WorldBarricadeState barricade) throws Exception {
        WorldLogBuilder builder = new WorldLogBuilder();
        builder.appendT0(List.of(door));
        WorldDiffSampler sampler = sampler(door);
        List<WorldLogEntry> first = sampler.diff(sample(List.of(),
            Set.of(square(10, 20)), Set.of()));
        List<WorldLogEntry> second = sampler.diff(sample(List.of(door, barricade),
            Set.of(square(10, 20)), Set.of()));
        builder.append(first, 500L);
        builder.append(second, 1000L);
        return builder.payload();
    }

    private static WorldDiffSampler sampler(WorldStateRecord... records) {
        WorldDiffSampler sampler = new WorldDiffSampler();
        sampler.seedT0(List.of(records));
        return sampler;
    }

    private static WorldDiffSample sample(List<WorldStateRecord> current,
        Set<Long> observableSquares, Set<Long> newlyCoveredSquares) {
        return sample(current, observableSquares, newlyCoveredSquares, Set.of());
    }

    private static WorldDiffSample sample(List<WorldStateRecord> current,
        Set<Long> observableSquares, Set<Long> newlyCoveredSquares,
        Set<String> presentVehicleIdentities) {
        return WorldDiffSample.of(current, observableSquares, newlyCoveredSquares,
            presentVehicleIdentities);
    }

    private static long square(int x, int y) {
        return WorldDiffSampler.squareKey(x, y, 0);
    }

    private static WorldDoorState door(int x, int y, boolean open) {
        return new WorldDoorState(WorldTarget.door(x, y, 0, 0), "fixtures_doors_01_1",
            3, false, false, open, 100, 7L, true);
    }

    private static WorldWindowState window(int x, int y, String sprite, boolean open) {
        return new WorldWindowState(WorldTarget.window(x, y, 0, 0), sprite, 2,
            open, false, false, false, 0);
    }

    private static WorldBarricadeState barricade(int x, int y) {
        return new WorldBarricadeState(WorldTarget.barricade(x, y, 0, 0,
            WorldLogCodec.CLASS_DOOR, 0), "carpentry_01_10", 4, 1, 2, 600,
            new int[] {300, 300}, false);
    }

    private static WorldVehicleState vehicle(int x, int y, float poseX) {
        return new WorldVehicleState(WorldTarget.vehicle(x, y, 0, 77L, "Base.CarNormal"),
            true, true, poseX, y, 0f, 0.15f, 0f, 0f, 0f, 1f);
    }

    private static WorldGenericState generic(int x, int y, String type, boolean complex) {
        return new WorldGenericState(WorldTarget.generic(x, y, 0, 0, type), "sprite", 5,
            complex);
    }
}
