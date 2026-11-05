package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLogCodecTest {
    @Test
    void encodesAndDecodesDoorIdentityAndState() throws Exception {
        WorldDoorState door = new WorldDoorState(WorldTarget.door(10775, 10270, 0, 0),
            "doors_1", 12, true, true, false, 87, 4242L, true);

        byte[] payload = WorldLogCodec.encodeT0(List.of(door));
        List<WorldStateRecord> decoded = WorldLogCodec.decodeT0(payload);

        assertEquals(1, decoded.size());
        assertEquals(door, decoded.get(0));
        assertEquals(WorldLogCodec.CLASS_DOOR, decoded.get(0).classId());
        assertEquals(1, WorldLogCodec.countRecords(payload));
        WorldLogCodec.validate(payload, 1L, 1L);
    }

    @Test
    void doorWithoutKeyStoresTheFlagAndNotASentinel() throws Exception {
        WorldDoorState door = new WorldDoorState(WorldTarget.door(5, 6, 1, 2),
            "doors_2", 3, false, false, true, 100, 0L, false);

        WorldDoorState decoded = (WorldDoorState) WorldLogCodec.decodeT0(
            WorldLogCodec.encodeT0(List.of(door))).get(0);

        assertFalse(decoded.hasKey());
        assertEquals(0L, decoded.keyId());
        assertTrue(decoded.open());
    }

    @Test
    void windowSpriteIsStateNotIdentity() throws Exception {
        WorldWindowState first = new WorldWindowState(WorldTarget.window(100, 200, 0, 0),
            "fixtures_windows_01_31", 7, false, false, true, false, 0);
        WorldWindowState second = new WorldWindowState(WorldTarget.window(100, 200, 0, 0),
            "fixtures_windows_01_25", 7, false, false, true, true, 2);

        List<WorldStateRecord> decoded = WorldLogCodec.decodeT0(
            WorldLogCodec.encodeT0(List.of(first, second)));

        assertEquals(2, decoded.size());
        assertEquals(first.target(), decoded.get(0).target());
        assertEquals(second.target(), decoded.get(1).target());
        assertEquals("fixtures_windows_01_31", ((WorldWindowState) decoded.get(0)).sprite());
        assertEquals("fixtures_windows_01_25", ((WorldWindowState) decoded.get(1)).sprite());
    }

    @Test
    void encodesBarricadeParentSideAndPlankHealth() throws Exception {
        WorldBarricadeState barricade = new WorldBarricadeState(
            WorldTarget.barricade(10, 11, 0, 2, WorldLogCodec.CLASS_WINDOW, 1),
            "barricade_plank", 4, 1, 3, 300, new int[] {100, 90, 110}, false);

        WorldBarricadeState decoded = (WorldBarricadeState) WorldLogCodec.decodeT0(
            WorldLogCodec.encodeT0(List.of(barricade))).get(0);

        assertEquals(WorldLogCodec.CLASS_BARRICADE, decoded.classId());
        assertEquals(WorldLogCodec.CLASS_WINDOW, decoded.target().parentClass());
        assertEquals(1, decoded.target().opposite());
        assertEquals(2, decoded.target().orientation());
        assertEquals(1, decoded.material());
        assertEquals(3, decoded.plankCount());
        assertEquals(300, decoded.aggregateHealth());
        assertArrayEquals(new int[] {100, 90, 110}, decoded.plankHealth());
        assertFalse(decoded.aggregateOnly());
    }

    @Test
    void encodesVehicleScriptSqlIdAndQuaternionPose() throws Exception {
        WorldVehicleState vehicle = new WorldVehicleState(
            WorldTarget.vehicle(500, 600, 0, 77L, "Base.CarNormal"),
            true, true, 500.25f, 600.5f, 0f,
            0.1f, 0.2f, 0.3f, 0.92736185f);

        WorldVehicleState decoded = (WorldVehicleState) WorldLogCodec.decodeT0(
            WorldLogCodec.encodeT0(List.of(vehicle))).get(0);

        assertEquals(WorldLogCodec.CLASS_VEHICLE, decoded.classId());
        assertEquals(77L, decoded.target().sqlId());
        assertEquals("Base.CarNormal", decoded.target().script());
        assertEquals(255, decoded.target().orientation());
        assertTrue(decoded.present());
        assertTrue(decoded.headlightsOn());
        assertEquals(500.25f, decoded.poseX());
        assertEquals(600.5f, decoded.poseY());
        assertEquals(0.92736185f, decoded.quaternionW());
    }

    @Test
    void t0OrderingIsDeterministicAndParentsComeBeforeChildren() throws Exception {
        WorldBarricadeState barricade = new WorldBarricadeState(
            WorldTarget.barricade(10, 10, 0, 0, WorldLogCodec.CLASS_DOOR, 0),
            "barricade", 1, 1, 1, 100, new int[] {100}, false);
        WorldDoorState door = new WorldDoorState(WorldTarget.door(10, 10, 0, 0),
            "door", 2, false, false, false, 100, 0L, false);
        WorldVehicleState vehicle = new WorldVehicleState(
            WorldTarget.vehicle(5, 5, 0, 1L, "Base.CarNormal"),
            true, false, 5f, 5f, 0f, 0f, 0f, 0f, 1f);

        List<WorldStateRecord> decoded = WorldLogCodec.decodeT0(
            WorldLogCodec.encodeT0(List.of(vehicle, barricade, door)));

        assertEquals(WorldLogCodec.CLASS_VEHICLE, decoded.get(0).classId());
        assertEquals(WorldLogCodec.CLASS_DOOR, decoded.get(1).classId());
        assertEquals(WorldLogCodec.CLASS_BARRICADE, decoded.get(2).classId());
    }

    @Test
    void rejectsCountMismatchAndNonZeroTime() throws Exception {
        byte[] payload = WorldLogCodec.encodeT0(List.of(new WorldDoorState(
            WorldTarget.door(1, 2, 0, 0), "door", 0, false, false, false, 100, 0L,
            false)));

        assertThrows(ReplayFormatException.class,
            () -> WorldLogCodec.validate(payload, 2L, 1L));
        assertThrows(ReplayFormatException.class,
            () -> WorldLogCodec.validate(payload, 1L, 2L));

        byte[] invalidTime = payload.clone();
        int timeOffset = 4 + 8;
        for (int index = 0; index < 8; index++) {
            invalidTime[timeOffset + index] = 0;
        }
        invalidTime[timeOffset + 7] = 1;
        assertThrows(ReplayFormatException.class,
            () -> WorldLogCodec.decodeT0(invalidTime));
    }

    @Test
    void rejectsInvalidIdentityRangeAndAscii() {
        WorldDoorState badSquare = new WorldDoorState(
            new WorldTarget(-1, 0, 0, 0, 0, 0, 0L, "", ""),
            "door", 0, false, false, false, 100, 0L, false);
        assertThrows(ReplayFormatException.class,
            () -> WorldLogCodec.encodeT0(List.of(badSquare)));

        WorldDoorState badSprite = new WorldDoorState(WorldTarget.door(1, 2, 0, 0),
            "bad sprite!", 0, false, false, false, 100, 0L, false);
        assertThrows(ReplayFormatException.class,
            () -> WorldLogCodec.encodeT0(List.of(badSprite)));
    }
}
