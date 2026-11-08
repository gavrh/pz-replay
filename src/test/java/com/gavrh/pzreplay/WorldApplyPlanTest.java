package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyPlanTest {
    @Test
    void t0OnlyAppliesAtTimeZero() {
        WorldApplyPlan plan = new WorldApplyPlan(List.of(
            record(1, 0, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false))));

        List<WorldLogRecord> t0 = plan.advanceTo(0L);

        assertEquals(1, t0.size());
        assertEquals(0L, plan.lastAppliedMillis());
        assertTrue(plan.advanceTo(500L).isEmpty());
    }

    @Test
    void stateBeforeAndAfterEachDiff() {
        WorldApplyPlan plan = new WorldApplyPlan(List.of(
            record(1, 0, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false)),
            record(2, 500, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, true))));

        assertEquals(1, plan.recordsTo(499L).size());
        assertEquals(2, plan.recordsTo(500L).size());
        assertEquals(2, plan.recordsTo(10_000L).size());
    }

    @Test
    void multipleDiffsForOneIdentityKeepSequenceOrder() {
        WorldApplyPlan plan = new WorldApplyPlan(List.of(
            record(1, 0, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false)),
            record(2, 500, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, true)),
            record(3, 1000, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false))));

        List<WorldLogRecord> records = plan.advanceTo(1000L);

        assertEquals(List.of(1L, 2L, 3L), records.stream().map(WorldLogRecord::sequence).toList());
        assertFalse(((WorldDoorState) records.get(2).record()).open());
    }

    @Test
    void forwardSeekAppliesOnlyCrossedRecordsOnce() {
        WorldApplyPlan plan = new WorldApplyPlan(List.of(
            record(1, 0, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false)),
            record(2, 500, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, true)),
            record(3, 1000, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false))));

        List<WorldLogRecord> first = plan.advanceTo(500L);
        List<WorldLogRecord> second = plan.advanceTo(2000L);

        assertEquals(List.of(1L, 2L), first.stream().map(WorldLogRecord::sequence).toList());
        assertEquals(List.of(3L), second.stream().map(WorldLogRecord::sequence).toList());
        assertTrue(plan.advanceTo(3000L).isEmpty());
    }

    @Test
    void removalPrecedesAdditionAndParentPrecedesChild() throws Exception {
        WorldDoorState door = door(10, 20, false);
        WorldBarricadeState barricade = barricade(10, 20, 1, 2);
        List<WorldLogEntry> additions = List.of(
            new WorldLogEntry(new WorldObjectAdded(door.target(), door.classId(), door.sprite(),
                "", door.objectIndex(), 1, door)),
            new WorldLogEntry(new WorldObjectAdded(barricade.target(), barricade.classId(),
                barricade.sprite(), "", barricade.objectIndex(), 1, barricade)));
        List<WorldLogRecord> added = WorldLogCodec.decodeAll(
            WorldLogCodec.encodeDiff(additions, 1L, 500L));
        assertEquals(WorldLogCodec.CLASS_DOOR, added.get(0).record().classId());
        assertEquals(WorldLogCodec.CLASS_BARRICADE, added.get(1).record().classId());

        WorldObjectDestroyed destroyed = new WorldObjectDestroyed(door.target(), door.classId(),
            door.sprite(), "", door.objectIndex(), door);
        List<WorldLogEntry> removals = List.of(new WorldLogEntry(destroyed));
        List<WorldLogRecord> removed = WorldLogCodec.decodeAll(
            WorldLogCodec.encodeDiff(removals, 1L, 500L));
        assertEquals(WorldLogCodec.KIND_OBJECT_DESTROYED, removed.get(0).kind());
    }

    @Test
    void doorRemovalIsPerPart() {
        WorldDoorState door = door(10, 20, false);
        WorldObjectDestroyed destroyed = new WorldObjectDestroyed(door.target(), door.classId(),
            door.sprite(), "", door.objectIndex(), door);

        WorldApplyCommand command = WorldApplyPlanner.command(
            record(1, 500, WorldLogCodec.KIND_OBJECT_DESTROYED, destroyed));

        assertEquals(WorldApplyCommand.Action.DOOR_REMOVE, command.action());
        assertTrue(command.perPart());
    }

    @Test
    void windowOpenUsesReflectionAndBlocksWithoutIt() {
        assertEquals(WorldWindowOpen.Strategy.NO_CHANGE,
            WorldWindowOpen.of(false, false, true).strategy());
        assertEquals(WorldWindowOpen.Strategy.REFLECTION,
            WorldWindowOpen.of(false, true, true).strategy());
        assertEquals(WorldWindowOpen.Strategy.BLOCK,
            WorldWindowOpen.of(false, true, false).strategy());
    }

    @Test
    void barricadeMaterialTransitionRebuildsAndSkipsOnlyMatchingComposition() {
        WorldBarricadeState planks = barricade(10, 20, 1, 3);
        WorldBarricadeApplyPlan metal = WorldBarricadeApplyPlan.of(barricade(10, 20, 2, 0),
            1, 2);

        assertTrue(metal.removeFirst());
        assertFalse(metal.addPlanks() > 0);
        assertTrue(metal.addMetal());
        assertFalse(metal.addMetalBar());

        WorldBarricadeApplyPlan same = WorldBarricadeApplyPlan.of(planks, 1, 3);
        assertFalse(same.removeFirst());
        assertEquals(3, same.addPlanks());

        WorldBarricadeApplyPlan more = WorldBarricadeApplyPlan.of(planks, 1, 1);
        assertTrue(more.removeFirst());
        assertEquals(3, more.addPlanks());
    }

    @Test
    void barricadeExactPlankHealthOnlyWhenRecorded() {
        WorldBarricadeState exact = barricade(10, 20, 1, 2);
        WorldBarricadeState aggregate = new WorldBarricadeState(exact.target(), exact.sprite(),
            exact.objectIndex(), 1, 2, 600, new int[0], true);

        assertTrue(WorldBarricadeApplyPlan.of(exact, 1, 2).exactPlankHealth());
        assertFalse(WorldBarricadeApplyPlan.of(aggregate, 1, 2).exactPlankHealth());
    }

    @Test
    void vehiclePoseUsesPhysicsHeightAndRecordedQuaternion() {
        WorldVehicleState vehicle = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            true, true, 10f, 20f, 0f, 0.15f, 0f, 0f, 0f, 1f);

        WorldVehiclePose pose = WorldVehiclePose.of(vehicle, 1000f, 2000f);

        assertEquals(-990f, pose.originX());
        assertEquals(0.15f, pose.originY());
        assertEquals(-1980f, pose.originZ());
        assertEquals(1f, pose.quaternionW());
        assertEquals(WorldApplyCommand.Action.VEHICLE_STATE,
            WorldApplyPlanner.command(record(1, 500, WorldLogCodec.KIND_VEHICLE_STATE,
                vehicle)).action());
    }

    @Test
    void vehiclePoseTransformAndPositionAgreeImmediately() {
        WorldVehicleState vehicle = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            true, true, 10790.5f, 10274f, 0f, 0.15f, 0f, 0f, 0f, 1f);

        WorldVehiclePose pose = WorldVehiclePose.of(vehicle, 1000f, 2000f);

        assertEquals(vehicle.poseX(), pose.originX() + 1000f);
        assertEquals(vehicle.poseY(), pose.originZ() + 2000f);
        assertEquals(vehicle.posePhysicsZ(), pose.originY());
        assertEquals(vehicle.poseX(), 10790.5f);
        assertEquals(vehicle.poseY(), 10274f);
    }

    @Test
    void recordedOnlyVehicleAppliesPoseAndSkipsPresence() {
        WorldVehicleState present = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            true, false, 10f, 20f, 0f, 0.15f, 0f, 0f, 0f, 1f);
        WorldVehicleState removed = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            false, false, 10f, 20f, 0f, 0.15f, 0f, 0f, 0f, 1f);

        WorldApplyCommand presentCommand = WorldApplyPlanner.command(record(1, 2000,
            WorldLogCodec.KIND_VEHICLE_STATE, present, WorldLogCodec.FLAG_RECORDED_ONLY));
        WorldApplyCommand removedCommand = WorldApplyPlanner.command(record(2, 2000,
            WorldLogCodec.KIND_VEHICLE_STATE, removed, WorldLogCodec.FLAG_RECORDED_ONLY));

        assertEquals(WorldApplyCommand.Action.VEHICLE_STATE, presentCommand.action());
        assertEquals(WorldApplyCommand.Action.VEHICLE_REMOVE, removedCommand.action());
        assertTrue(removedCommand.reloadRequired());
    }

    @Test
    void combinedVehicleFlagsCountBothCounters() {
        WorldVehicleState vehicle = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            true, false, 10f, 20f, 0f, 0.15f, 0f, 0f, 0f, 1f);
        int flags = WorldLogCodec.FLAG_RECORDED_ONLY | WorldLogCodec.FLAG_RELOAD_REQUIRED;

        WorldApplyCommand command = WorldApplyPlanner.command(record(1, 2000,
            WorldLogCodec.KIND_VEHICLE_STATE, vehicle, flags));
        WorldApplySummary summary = new WorldApplySummary();
        summary.appliedWithFlags(command.flags(), command.partial());

        assertEquals(WorldApplyCommand.Action.VEHICLE_STATE, command.action());
        assertTrue(command.reloadRequired());
        assertEquals(1, summary.recordedOnlyCount());
        assertEquals(1, summary.reloadRequiredCount());
        assertEquals(0, summary.appliedCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.partialCount());
    }

    @Test
    void zeroAndNonFiniteVehicleQuaternionsAreRejected() {
        WorldVehicleState zero = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            true, true, 10f, 20f, 0f, 0.15f, 0f, 0f, 0f, 0f);
        WorldVehicleState nan = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            true, true, 10f, 20f, 0f, 0.15f, Float.NaN, 0f, 0f, 1f);

        assertThrows(IllegalArgumentException.class, () -> WorldVehiclePose.of(zero, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> WorldVehiclePose.of(nan, 0f, 0f));
    }

    @Test
    void genericAddDestroyAndMovePlan() {
        WorldGenericState chair = new WorldGenericState(
            WorldTarget.generic(10, 20, 0, 0, "zombie.iso.IsoObject"), "chair", 3, false);
        WorldObjectAdded added = new WorldObjectAdded(chair.target(), chair.classId(),
            chair.sprite(), chair.target().type(), chair.objectIndex(), 1, null);
        WorldObjectDestroyed destroyed = new WorldObjectDestroyed(chair.target(),
            chair.classId(), chair.sprite(), chair.target().type(), chair.objectIndex(), null);

        assertEquals(WorldApplyCommand.Action.GENERIC_ADD,
            WorldApplyPlanner.command(record(1, 500, WorldLogCodec.KIND_OBJECT_ADDED,
                added)).action());
        assertEquals(WorldApplyCommand.Action.GENERIC_REMOVE,
            WorldApplyPlanner.command(record(2, 500, WorldLogCodec.KIND_OBJECT_DESTROYED,
                destroyed)).action());

        WorldTarget moved = WorldTarget.generic(11, 20, 0, 0, "zombie.iso.IsoObject");
        WorldObjectAdded atNewIdentity = new WorldObjectAdded(moved, chair.classId(),
            chair.sprite(), moved.type(), chair.objectIndex(), 1, null);
        assertEquals(WorldApplyCommand.Action.GENERIC_ADD,
            WorldApplyPlanner.command(record(3, 500, WorldLogCodec.KIND_OBJECT_ADDED,
                atNewIdentity)).action());
    }

    @Test
    void recordedOnlyPartialAndReloadRequiredAreDistinguished() {
        WorldDoorState door = door(10, 20, false);
        WorldApplyCommand recordedOnly = WorldApplyPlanner.command(record(1, 500,
            WorldLogCodec.KIND_OBJECT_STATE, door, WorldLogCodec.FLAG_RECORDED_ONLY));
        WorldApplyCommand partial = WorldApplyPlanner.command(record(2, 500,
            WorldLogCodec.KIND_OBJECT_STATE, door, WorldLogCodec.FLAG_PARTIAL));
        WorldApplyCommand reload = WorldApplyPlanner.command(record(3, 500,
            WorldLogCodec.KIND_OBJECT_STATE, door, WorldLogCodec.FLAG_RELOAD_REQUIRED));
        WorldVehicleState removed = new WorldVehicleState(
            WorldTarget.vehicle(10, 20, 0, 77L, "Base.CarNormal"),
            false, false, 10f, 20f, 0f, 0.15f, 0f, 0f, 0f, 1f);
        WorldApplyCommand vehicle = WorldApplyPlanner.command(record(4, 500,
            WorldLogCodec.KIND_VEHICLE_STATE, removed, WorldLogCodec.FLAG_RELOAD_REQUIRED));

        assertEquals(WorldApplyCommand.Action.SKIP_RECORDED_ONLY, recordedOnly.action());
        assertEquals(WorldApplyCommand.Action.DOOR_STATE, partial.action());
        assertTrue(partial.partial());
        assertEquals(WorldApplyCommand.Action.SKIP_RELOAD_REQUIRED, reload.action());
        assertEquals(WorldApplyCommand.Action.VEHICLE_REMOVE, vehicle.action());
        assertTrue(vehicle.reloadRequired());
    }

    @Test
    void combinedRecordedOnlyReloadRequiredKeepsBothFlags() {
        WorldDoorState door = door(10, 20, false);
        int flags = WorldLogCodec.FLAG_RECORDED_ONLY | WorldLogCodec.FLAG_RELOAD_REQUIRED;

        WorldApplyCommand command = WorldApplyPlanner.command(record(1, 500,
            WorldLogCodec.KIND_OBJECT_STATE, door, flags));

        assertEquals(WorldApplyCommand.Action.SKIP_RECORDED_ONLY, command.action());
        assertTrue(command.reloadRequired());
        assertEquals(flags, command.flags());
    }

    @Test
    void failureSkipsIncrementSkippedAndTheirOwnCounter() {
        WorldApplySummary summary = new WorldApplySummary();
        summary.missing();
        summary.mismatched();
        summary.unsupported();
        summary.unknownKind(2);
        summary.duplicate();

        assertEquals(1, summary.missingCount());
        assertEquals(1, summary.mismatchedCount());
        assertEquals(1, summary.unsupportedCount());
        assertEquals(2, summary.unknownKindCount());
        assertEquals(1, summary.duplicateCount());
        assertEquals(6, summary.skippedCount());
        assertEquals(0, summary.appliedCount());
        assertEquals(0, summary.recordedOnlyCount());
        assertEquals(0, summary.partialCount());
        assertEquals(0, summary.reloadRequiredCount());
    }

    @Test
    void recordedOnlyCountsOnlyRecordedOnly() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.recordedOnly();

        assertEquals(1, summary.recordedOnlyCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.appliedCount());
        assertEquals(0, summary.reloadRequiredCount());
    }

    @Test
    void reloadRequiredCountsOnlyReloadRequired() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.reloadRequired();

        assertEquals(1, summary.reloadRequiredCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.appliedCount());
        assertEquals(0, summary.recordedOnlyCount());
    }

    @Test
    void recordedOnlyReloadRequiredCountsBothWithoutSkip() {
        WorldApplySummary summary = new WorldApplySummary();
        summary.recordedOnlyReloadRequired();

        assertEquals(1, summary.recordedOnlyCount());
        assertEquals(1, summary.reloadRequiredCount());
        assertEquals(0, summary.skippedCount());
    }

    @Test
    void recordedOnlyPartialCountsBothCategories() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.recordedOnly(true);

        assertEquals(1, summary.recordedOnlyCount());
        assertEquals(1, summary.partialCount());
        assertEquals(0, summary.skippedCount());
    }

    @Test
    void appliedRecordCountsOnlyApplied() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.applied();

        assertEquals(1, summary.appliedCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.partialCount());
        assertEquals(0, summary.recordedOnlyCount());
        assertEquals(0, summary.reloadRequiredCount());
    }

    @Test
    void partialCountsIndependently() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.partial();

        assertEquals(1, summary.partialCount());
        assertEquals(0, summary.appliedCount());
        assertEquals(0, summary.skippedCount());
    }

    @Test
    void appliedPartialCountsOnlyPartial() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.applied(true);

        assertEquals(1, summary.appliedCount());
        assertEquals(1, summary.partialCount());
        assertEquals(0, summary.skippedCount());
        assertEquals(0, summary.recordedOnlyCount());
    }

    @Test
    void reloadRequiredPartialCountsBothCategories() {
        WorldApplySummary summary = new WorldApplySummary();

        summary.reloadRequired(true);

        assertEquals(1, summary.reloadRequiredCount());
        assertEquals(1, summary.partialCount());
        assertEquals(0, summary.skippedCount());
    }

    @Test
    void summaryTextReflectsSeparateCounters() {
        WorldApplySummary summary = new WorldApplySummary();
        summary.applied();
        summary.recordedOnly();
        summary.partial();
        summary.reloadRequired();
        summary.missing();
        summary.duplicate();

        String text = summary.text();

        assertTrue(text.contains("applied=1"));
        assertTrue(text.contains("recorded_only=1"));
        assertTrue(text.contains("partial=1"));
        assertTrue(text.contains("skipped=2"));
        assertTrue(text.contains("missing=1"));
        assertTrue(text.contains("reload_required=1"));
        assertTrue(text.contains("duplicate=1"));
    }

    @Test
    void recordedOnlyAddKeepsItsPartialFlag() {
        WorldGenericState chair = new WorldGenericState(
            WorldTarget.generic(10, 20, 0, 0, "zombie.iso.IsoObject"), "chair", 3, false);
        WorldObjectAdded added = new WorldObjectAdded(chair.target(), chair.classId(),
            chair.sprite(), chair.target().type(), chair.objectIndex(), 2, null);

        WorldApplyCommand command = WorldApplyPlanner.command(record(1, 1500,
            WorldLogCodec.KIND_OBJECT_ADDED, added, WorldLogCodec.FLAG_PARTIAL));

        assertEquals(WorldApplyCommand.Action.SKIP_RECORDED_ONLY, command.action());
        assertTrue(command.partial());
    }

    @Test
    void truncationFreezesAtLastRecordAndWarns() {
        WorldApplyPlan plan = new WorldApplyPlan(List.of(
            record(1, 0, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, false)),
            record(2, 5000, WorldLogCodec.KIND_OBJECT_STATE, door(10, 20, true))));

        assertEquals(5000L, plan.lastRecordMillis());
        assertEquals(2, plan.advanceTo(60_000L).size());
        assertTrue(WorldCoverageWarning.message("truncated", plan.lastRecordMillis())
            .contains("freezes at 5.00s"));
        assertEquals("", WorldCoverageWarning.message("complete", plan.lastRecordMillis()));
    }

    @Test
    void orientationMappingRoundTripsEveryEngineDirection() {
        for (int engine = 0; engine < 8; engine++) {
            int index = WorldOrientation.index(engine);
            assertEquals(engine, WorldOrientation.engineIndex(index));
        }
        assertEquals(7, WorldOrientation.index(1));
        assertEquals(1, WorldOrientation.engineIndex(7));
    }

    @Test
    void logModelHasNoLiveObjectReferences() {
        List<Class<?>> types = List.of(WorldLogRecord.class, WorldTarget.class,
            WorldDoorState.class, WorldWindowState.class, WorldBarricadeState.class,
            WorldVehicleState.class, WorldGenericState.class, WorldObjectDestroyed.class,
            WorldObjectAdded.class);
        for (Class<?> type : types) {
            for (var component : type.getRecordComponents()) {
                assertFalse(component.getType().getName().startsWith("zombie."));
            }
        }
    }

    private static WorldLogRecord record(long sequence, long time, int kind,
        WorldStateRecord record) {
        return record(sequence, time, kind, record, 0);
    }

    private static WorldLogRecord record(long sequence, long time, int kind,
        WorldStateRecord record, int flags) {
        return new WorldLogRecord(kind, sequence, time, flags, record);
    }

    private static WorldDoorState door(int x, int y, boolean open) {
        return new WorldDoorState(WorldTarget.door(x, y, 0, 0), "fixtures_doors_01_1",
            3, false, false, open, 100, 7L, true);
    }

    private static WorldBarricadeState barricade(int x, int y, int material, int planks) {
        return new WorldBarricadeState(WorldTarget.barricade(x, y, 0, 0,
            WorldLogCodec.CLASS_DOOR, 0), "carpentry_01_10", 4, material, planks, 600,
            new int[] {300, 300}, false);
    }
}
