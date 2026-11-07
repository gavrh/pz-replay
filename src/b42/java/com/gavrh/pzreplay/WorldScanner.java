package com.gavrh.pzreplay;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Quaternionf;
import zombie.characters.IsoPlayer;
import zombie.core.physics.Transform;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoObjectUtils;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.pathfind.VehiclePoly;
import zombie.vehicles.BaseVehicle;

final class WorldScanner {
    private static final int Z_RADIUS = 1;

    record Result(
        List<WorldStateRecord> records,
        Set<Long> observableSquares,
        Set<String> presentVehicleIdentities,
        Set<String> duplicateIdentities,
        String coverageStatus,
        long durationMillis
    ) {
        Result {
            records = List.copyOf(records);
            observableSquares = Set.copyOf(observableSquares);
            presentVehicleIdentities = Set.copyOf(presentVehicleIdentities);
            duplicateIdentities = Set.copyOf(duplicateIdentities);
        }
    }

    private final Quaternionf rotation = new Quaternionf();
    private final Transform transform = new Transform();

    Result scan(IsoPlayer player, int padding) {
        long startedNanos = System.nanoTime();
        IsoCell cell = player.getCell();
        int centerX = (int) Math.floor(player.getX());
        int centerY = (int) Math.floor(player.getY());
        int centerZ = (int) Math.floor(player.getZ());
        int minX = centerX - padding;
        int maxX = centerX + padding;
        int minY = centerY - padding;
        int maxY = centerY + padding;
        int minZ = Math.max(-32, centerZ - Z_RADIUS);
        int maxZ = Math.min(31, centerZ + Z_RADIUS);
        List<WorldStateRecord> records = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        Set<Long> observableSquares = new HashSet<>();
        boolean partial = false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    IsoGridSquare square = cell.getGridSquare(x, y, z);
                    if (square == null) {
                        if (z == centerZ) {
                            partial = true;
                        }
                        continue;
                    }
                    observableSquares.add(WorldDiffSampler.squareKey(x, y, z));
                    for (int index = 0; index < square.getObjects().size(); index++) {
                        IsoObject object = square.getObjects().get(index);
                        if (object instanceof IsoDoor door) {
                            captureDoor(door, records, seen, duplicates);
                        } else if (object instanceof IsoWindow window) {
                            captureWindow(window, records, seen, duplicates);
                        } else if (!(object instanceof IsoBarricade)
                            && !(object instanceof IsoMovingObject)) {
                            captureGeneric(object, records, seen, duplicates);
                        }
                    }
                }
            }
        }
        Set<String> presentVehicleIdentities = new HashSet<>();
        for (BaseVehicle vehicle : cell.getVehicles()) {
            if (vehicle == null) {
                continue;
            }
            int sqlId = vehicle.getSqlId();
            String script = vehicle.getScriptName();
            if (sqlId <= 0 || script == null || script.isBlank()) {
                continue;
            }
            String identity = "4:" + Integer.toUnsignedLong(sqlId) + ":" + script;
            if (vehicle.isRemovedFromWorld()) {
                continue;
            }
            presentVehicleIdentities.add(identity);
            if (!vehicleCovered(vehicle, minX, maxX, minY, maxY)) {
                continue;
            }
            captureVehicle(vehicle, records, seen, duplicates);
        }
        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new Result(records, observableSquares, presentVehicleIdentities, duplicates,
            partial ? "partial" : "complete", durationMillis);
    }

    private void captureDoor(IsoDoor door, List<WorldStateRecord> records, Set<String> seen,
        Set<String> duplicates) {
        IsoGridSquare square = door.getSquare();
        if (square == null) {
            return;
        }
        int orientation = door.getNorth() ? 0 : 2;
        String key = "1:" + square.getX() + ":" + square.getY() + ":" + square.getZ()
            + ":" + orientation;
        if (!seen.add(key)) {
            duplicates.add(key);
            return;
        }
        int keyId = door.getKeyId();
        boolean hasKey = keyId >= 0;
        records.add(new WorldDoorState(
            WorldTarget.door(square.getX(), square.getY(), square.getZ(), orientation),
            spriteName(door), door.getObjectIndex(), door.isLocked(),
            door.isLockedByKey(), door.isOpen(), door.getHealth(),
            hasKey ? Integer.toUnsignedLong(keyId) : 0L, hasKey));
        captureBarricade(door.getBarricadeOnSameSquare(), door, square, orientation, 0,
            records, seen, duplicates);
        captureBarricade(door.getBarricadeOnOppositeSquare(), door, square, orientation, 1,
            records, seen, duplicates);
    }

    private void captureWindow(IsoWindow window, List<WorldStateRecord> records,
        Set<String> seen, Set<String> duplicates) {
        IsoGridSquare square = window.getSquare();
        if (square == null) {
            return;
        }
        int orientation = window.isNorth() ? 0 : 2;
        String key = "2:" + square.getX() + ":" + square.getY() + ":" + square.getZ()
            + ":" + orientation;
        if (!seen.add(key)) {
            duplicates.add(key);
            return;
        }
        records.add(new WorldWindowState(
            WorldTarget.window(square.getX(), square.getY(), square.getZ(), orientation),
            spriteName(window), window.getObjectIndex(), window.IsOpen(), window.isSmashed(),
            window.isLocked(), window.isGlassRemoved(), window.isPermaLocked() ? 1 : 0));
        captureBarricade(window.getBarricadeOnSameSquare(), window, square, orientation, 0,
            records, seen, duplicates);
        captureBarricade(window.getBarricadeOnOppositeSquare(), window, square, orientation,
            1, records, seen, duplicates);
    }

    private void captureBarricade(IsoBarricade barricade, IsoObject parent,
        IsoGridSquare square, int orientation, int opposite, List<WorldStateRecord> records,
        Set<String> seen, Set<String> duplicates) {
        if (barricade == null) {
            return;
        }
        int material;
        if (barricade.isMetalBar()) {
            material = 3;
        } else if (barricade.isMetal()) {
            material = 2;
        } else if (barricade.getNumPlanks() > 0) {
            material = 1;
        } else {
            return;
        }
        int parentClass = parent instanceof IsoDoor
            ? WorldLogCodec.CLASS_DOOR : WorldLogCodec.CLASS_WINDOW;
        String key = "3:" + square.getX() + ":" + square.getY() + ":" + square.getZ()
            + ":" + parentClass + ":" + orientation + ":" + opposite;
        if (!seen.add(key)) {
            duplicates.add(key);
            return;
        }
        int[] health = plankHealth(barricade);
        boolean aggregateOnly = health.length == 0;
        records.add(new WorldBarricadeState(
            WorldTarget.barricade(square.getX(), square.getY(), square.getZ(), orientation,
                parentClass, opposite),
            spriteName(barricade), barricade.getObjectIndex(), material,
            barricade.getNumPlanks(), barricade.getHealth(), health, aggregateOnly));
    }

    private void captureGeneric(IsoObject object, List<WorldStateRecord> records,
        Set<String> seen, Set<String> duplicates) {
        IsoGridSquare square = object.getSquare();
        if (square == null) {
            return;
        }
        int orientation = directionIndex(object.getDir());
        String type = object.getClass().getName();
        String key = "5:" + type + ":" + square.getX() + ":" + square.getY() + ":"
            + square.getZ() + ":" + orientation;
        if (!seen.add(key)) {
            duplicates.add(key);
            return;
        }
        records.add(new WorldGenericState(
            WorldTarget.generic(square.getX(), square.getY(), square.getZ(), orientation,
                type),
            spriteName(object), object.getObjectIndex(), !isSimpleGeneric(object)));
    }

    private void captureVehicle(BaseVehicle vehicle, List<WorldStateRecord> records,
        Set<String> seen, Set<String> duplicates) {
        int sqlId = vehicle.getSqlId();
        String script = vehicle.getScriptName();
        if (sqlId <= 0 || script == null || script.isBlank()) {
            return;
        }
        long id = Integer.toUnsignedLong(sqlId);
        String key = "4:" + id;
        if (!seen.add(key)) {
            duplicates.add(key);
            return;
        }
        vehicle.getWorldTransform(transform);
        transform.getRotation(rotation);
        records.add(new WorldVehicleState(
            WorldTarget.vehicle((int) Math.floor(vehicle.getX()),
                (int) Math.floor(vehicle.getY()), (int) Math.floor(vehicle.getZ()), id, script),
            !vehicle.isRemovedFromWorld(), vehicle.getHeadlightsOn(),
            vehicle.getX(), vehicle.getY(), vehicle.getZ(), transform.origin.y,
            rotation.x, rotation.y, rotation.z, rotation.w));
    }

    private boolean vehicleCovered(BaseVehicle vehicle, int minX, int maxX, int minY,
        int maxY) {
        VehiclePoly poly = vehicle.getPoly();
        if (poly == null) {
            return false;
        }
        float lowestX = Math.min(Math.min(poly.x1, poly.x2), Math.min(poly.x3, poly.x4));
        float highestX = Math.max(Math.max(poly.x1, poly.x2), Math.max(poly.x3, poly.x4));
        float lowestY = Math.min(Math.min(poly.y1, poly.y2), Math.min(poly.y3, poly.y4));
        float highestY = Math.max(Math.max(poly.y1, poly.y2), Math.max(poly.y3, poly.y4));
        return highestX >= minX && lowestX <= maxX + 1
            && highestY >= minY && lowestY <= maxY + 1;
    }

    private static boolean isSimpleGeneric(IsoObject object) {
        try {
            if (object.getClass() != IsoObject.class) {
                return false;
            }
            if (IsoObjectUtils.isObjectMultiSquare(object)) {
                return false;
            }
            if (object.getContainer() != null) {
                return false;
            }
            if (object.hasModData()) {
                return false;
            }
            if (object.getAttachedAnimSpriteCount() > 0) {
                return false;
            }
            if (object.getChildSprites() != null && !object.getChildSprites().isEmpty()) {
                return false;
            }
            return !spriteName(object).isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int directionIndex(IsoDirections direction) {
        return direction == null ? 0 : WorldOrientation.index(direction.ordinal());
    }

    private static String spriteName(IsoObject object) {
        try {
            String sprite = object.getSpriteName();
            return sprite == null ? "" : sprite;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static int[] plankHealth(IsoBarricade barricade) {
        try {
            Field field = IsoBarricade.class.getDeclaredField("plankHealth");
            field.setAccessible(true);
            int[] values = (int[]) field.get(barricade);
            if (values == null) {
                return new int[0];
            }
            int count = Math.max(0, Math.min(barricade.getNumPlanks(), values.length));
            int[] result = new int[count];
            System.arraycopy(values, 0, result, 0, count);
            return result;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return new int[0];
        }
    }
}
