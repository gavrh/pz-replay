package com.gavrh.pzreplay;

public record WorldObjectDestroyed(
    WorldTarget target,
    int classId,
    String sprite,
    String type,
    int objectIndex,
    WorldStateRecord restoreState
) implements WorldStateRecord {
    public WorldObjectDestroyed {
        sprite = sprite == null ? "" : sprite;
        type = type == null ? "" : type;
    }

    @Override
    public int kind() {
        return WorldLogCodec.KIND_OBJECT_DESTROYED;
    }
}
