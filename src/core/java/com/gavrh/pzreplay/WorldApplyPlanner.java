package com.gavrh.pzreplay;

public final class WorldApplyPlanner {
    private WorldApplyPlanner() {
    }

    public static WorldApplyCommand command(WorldLogRecord entry) {
        if (entry == null || entry.record() == null) {
            throw new IllegalArgumentException("world log record is missing");
        }
        int flags = entry.flags();
        WorldStateRecord record = entry.record();
        if ((flags & WorldLogCodec.FLAG_RECORDED_ONLY) != 0) {
            return WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_RECORDED_ONLY, record,
                flags);
        }
        if (record instanceof WorldVehicleState vehicle) {
            return vehicleCommand(vehicle, flags);
        }
        if ((flags & WorldLogCodec.FLAG_RELOAD_REQUIRED) != 0) {
            return WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_RELOAD_REQUIRED, record,
                flags);
        }
        return switch (entry.kind()) {
            case WorldLogCodec.KIND_OBJECT_STATE -> stateCommand(record, flags);
            case WorldLogCodec.KIND_OBJECT_DESTROYED -> destroyedCommand(record, flags);
            case WorldLogCodec.KIND_OBJECT_ADDED -> addedCommand(record, flags);
            default -> WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_UNSUPPORTED, record,
                flags);
        };
    }

    private static WorldApplyCommand vehicleCommand(WorldVehicleState vehicle, int flags) {
        if (!vehicle.present()) {
            return new WorldApplyCommand(WorldApplyCommand.Action.VEHICLE_REMOVE, vehicle,
                flags, false, false, true);
        }
        if ((flags & WorldLogCodec.FLAG_RELOAD_REQUIRED) != 0) {
            return WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_RELOAD_REQUIRED, vehicle,
                flags);
        }
        return WorldApplyCommand.of(WorldApplyCommand.Action.VEHICLE_STATE, vehicle, flags);
    }

    private static WorldApplyCommand stateCommand(WorldStateRecord record, int flags) {
        return switch (record.classId()) {
            case WorldLogCodec.CLASS_DOOR ->
                WorldApplyCommand.of(WorldApplyCommand.Action.DOOR_STATE, record, flags);
            case WorldLogCodec.CLASS_WINDOW ->
                WorldApplyCommand.of(WorldApplyCommand.Action.WINDOW_STATE, record, flags);
            case WorldLogCodec.CLASS_BARRICADE ->
                WorldApplyCommand.of(WorldApplyCommand.Action.BARRICADE_STATE, record, flags);
            default ->
                WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_UNSUPPORTED, record, flags);
        };
    }

    private static WorldApplyCommand destroyedCommand(WorldStateRecord record, int flags) {
        return switch (record.classId()) {
            case WorldLogCodec.CLASS_DOOR ->
                WorldApplyCommand.of(WorldApplyCommand.Action.DOOR_REMOVE, record, flags);
            case WorldLogCodec.CLASS_WINDOW ->
                WorldApplyCommand.of(WorldApplyCommand.Action.WINDOW_REMOVE, record, flags);
            case WorldLogCodec.CLASS_BARRICADE ->
                WorldApplyCommand.of(WorldApplyCommand.Action.BARRICADE_REMOVE, record, flags);
            case WorldLogCodec.CLASS_GENERIC ->
                WorldApplyCommand.of(WorldApplyCommand.Action.GENERIC_REMOVE, record, flags);
            default ->
                WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_UNSUPPORTED, record, flags);
        };
    }

    private static WorldApplyCommand addedCommand(WorldStateRecord record, int flags) {
        if (record instanceof WorldObjectAdded added && added.addMode() != 1) {
            return WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_RECORDED_ONLY, record,
                flags | WorldLogCodec.FLAG_RECORDED_ONLY);
        }
        return switch (record.classId()) {
            case WorldLogCodec.CLASS_DOOR ->
                WorldApplyCommand.of(WorldApplyCommand.Action.DOOR_ADD, record, flags);
            case WorldLogCodec.CLASS_WINDOW ->
                WorldApplyCommand.of(WorldApplyCommand.Action.WINDOW_ADD, record, flags);
            case WorldLogCodec.CLASS_BARRICADE ->
                WorldApplyCommand.of(WorldApplyCommand.Action.BARRICADE_ADD, record, flags);
            case WorldLogCodec.CLASS_GENERIC ->
                WorldApplyCommand.of(WorldApplyCommand.Action.GENERIC_ADD, record, flags);
            default ->
                WorldApplyCommand.of(WorldApplyCommand.Action.SKIP_UNSUPPORTED, record, flags);
        };
    }
}
