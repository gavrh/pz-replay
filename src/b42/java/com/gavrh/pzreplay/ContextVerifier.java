package com.gavrh.pzreplay;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import zombie.core.Core;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.savefile.PlayerDB;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclesDB2;

final class ContextVerifier {
    private static final Field PLAYER_DB_INSTANCE = field(PlayerDB.class, "instance");
    private static final Field PLAYER_DB_STORE = field(PlayerDB.class, "store");
    private static final Field VEHICLE_STREAMER = field(VehiclesDB2.class, "worldStreamer");
    private static final Field VEHICLE_STORE = field(
        "zombie.vehicles.VehiclesDB2$WorldStreamerThread", "store");

    private ContextVerifier() {
    }

    static String verifyVehicles(String replayPath) {
        List<String> expected = expectedVehicles(replayPath);
        if (expected == null) {
            return "the world log vehicles could not be read";
        }
        List<String> loaded = loadedVehicles();
        boolean vehicleStoreOpen = vehicleStoreOpen();
        boolean playerStoreOpen = playerStoreOpen();
        boolean noSave = Core.getInstance().isNoSave();
        boolean realSaveUnchanged = WorldContextManager.realSaveUnchanged();
        logVehicles();
        System.out.println("[pz-replay] context vehicles store="
            + (vehicleStoreOpen ? "open" : "closed")
            + " player_store=" + (playerStoreOpen ? "open" : "closed")
            + " loaded=" + loaded.size()
            + " expected=" + expected.size()
            + " no_save=" + noSave
            + " real_save=" + (realSaveUnchanged ? "unchanged" : "changed"));
        return ContextVehicleCheck.verify(vehicleStoreOpen, expected, loaded,
            playerStoreOpen, noSave, realSaveUnchanged);
    }

    private static List<String> expectedVehicles(String replayPath) {
        try {
            byte[] log = FormatReader.readWorldLog(Paths.get(replayPath));
            List<WorldLogRecord> records = WorldLogCodec.decodeAll(log);
            List<String> expected = new ArrayList<>();
            for (WorldLogRecord record : records) {
                if (record.kind() != WorldLogCodec.KIND_VEHICLE_STATE
                    || record.timeMillis() != 0L
                    || !(record.record() instanceof WorldVehicleState vehicle)
                    || !vehicle.present()) {
                    continue;
                }
                expected.add(identity(vehicle.target().sqlId(), vehicle.target().script()));
            }
            return expected;
        } catch (IOException | ReplayFormatException | RuntimeException exception) {
            System.out.println("[pz-replay] context vehicle log failed error="
                + exception.getMessage());
            return null;
        }
    }

    private static List<String> loadedVehicles() {
        List<String> loaded = new ArrayList<>();
        IsoCell cell = cell();
        if (cell == null) {
            return loaded;
        }
        for (BaseVehicle vehicle : cell.getVehicles()) {
            if (vehicle == null) {
                continue;
            }
            loaded.add(identity(Integer.toUnsignedLong(vehicle.getSqlId()),
                vehicle.getScriptName()));
        }
        return loaded;
    }

    private static void logVehicles() {
        IsoCell cell = cell();
        if (cell == null) {
            return;
        }
        for (BaseVehicle vehicle : cell.getVehicles()) {
            if (vehicle == null) {
                continue;
            }
            System.out.println("[pz-replay] context vehicle sql="
                + Integer.toUnsignedLong(vehicle.getSqlId())
                + " script=" + vehicle.getScriptName()
                + " added=" + vehicle.addedToWorld
                + " square=" + (vehicle.getSquare() != null)
                + " chunk=" + (vehicle.chunk != null));
        }
    }

    private static boolean vehicleStoreOpen() {
        try {
            Object streamer = VEHICLE_STREAMER == null ? null
                : VEHICLE_STREAMER.get(VehiclesDB2.instance);
            Object store = streamer == null || VEHICLE_STORE == null ? null
                : VEHICLE_STORE.get(streamer);
            return store != null && connectionOpen(store);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean playerStoreOpen() {
        try {
            Object instance = PLAYER_DB_INSTANCE == null ? null
                : PLAYER_DB_INSTANCE.get(null);
            Object store = instance == null || PLAYER_DB_STORE == null ? null
                : PLAYER_DB_STORE.get(instance);
            return store != null && connectionOpen(store);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return true;
        }
    }

    private static boolean connectionOpen(Object store) {
        try {
            Field connection = store.getClass().getDeclaredField("conn");
            connection.setAccessible(true);
            return connection.get(store) != null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static String identity(long sqlId, String script) {
        return "sql=" + sqlId + " script=" + (script == null ? "" : script);
    }

    private static IsoCell cell() {
        return IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static Field field(String typeName, String name) {
        try {
            return field(Class.forName(typeName), name);
        } catch (ClassNotFoundException | RuntimeException exception) {
            return null;
        }
    }
}
