package com.gavrh.pzreplay;

public record WorldWindowState(
    WorldTarget target,
    String sprite,
    int objectIndex,
    boolean open,
    boolean smashed,
    boolean locked,
    boolean glassRemoved,
    int permaLocked
) implements WorldStateRecord {
    public WorldWindowState {
        target = target == null ? WorldTarget.window(0, 0, 0, 0) : target;
        sprite = sprite == null ? "" : sprite;
    }

    @Override
    public int kind() {
        return WorldLogCodec.KIND_OBJECT_STATE;
    }

    @Override
    public int classId() {
        return WorldLogCodec.CLASS_WINDOW;
    }
}
