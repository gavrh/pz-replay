package com.gavrh.pzreplay;

public record WorldDoorState(
    WorldTarget target,
    String sprite,
    int objectIndex,
    boolean locked,
    boolean lockedByKey,
    boolean open,
    int health,
    long keyId,
    boolean hasKey
) implements WorldStateRecord {
    public WorldDoorState {
        target = target == null ? WorldTarget.door(0, 0, 0, 0) : target;
        sprite = sprite == null ? "" : sprite;
    }

    @Override
    public int kind() {
        return WorldLogCodec.KIND_OBJECT_STATE;
    }

    @Override
    public int classId() {
        return WorldLogCodec.CLASS_DOOR;
    }
}
