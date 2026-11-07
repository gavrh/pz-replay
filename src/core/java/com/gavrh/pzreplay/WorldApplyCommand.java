package com.gavrh.pzreplay;

public record WorldApplyCommand(
    Action action,
    WorldStateRecord record,
    int flags,
    boolean perPart,
    boolean partial,
    boolean reloadRequired
) {
    public enum Action {
        DOOR_STATE,
        DOOR_REMOVE,
        DOOR_ADD,
        WINDOW_STATE,
        WINDOW_REMOVE,
        WINDOW_ADD,
        BARRICADE_STATE,
        BARRICADE_REMOVE,
        BARRICADE_ADD,
        VEHICLE_STATE,
        VEHICLE_REMOVE,
        GENERIC_ADD,
        GENERIC_REMOVE,
        SKIP_RECORDED_ONLY,
        SKIP_RELOAD_REQUIRED,
        SKIP_UNSUPPORTED
    }

    public WorldApplyCommand {
        if (action == null || record == null) {
            throw new IllegalArgumentException("world apply command is incomplete");
        }
    }

    public static WorldApplyCommand of(Action action, WorldStateRecord record, int flags) {
        return new WorldApplyCommand(action, record, flags,
            action == Action.DOOR_REMOVE,
            (flags & WorldLogCodec.FLAG_PARTIAL) != 0,
            (flags & WorldLogCodec.FLAG_RELOAD_REQUIRED) != 0);
    }
}
