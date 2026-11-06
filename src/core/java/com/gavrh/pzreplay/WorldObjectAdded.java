package com.gavrh.pzreplay;

public record WorldObjectAdded(
    WorldTarget target,
    int classId,
    String sprite,
    String type,
    int objectIndex,
    int addMode,
    WorldStateRecord state
) implements WorldStateRecord {
    public WorldObjectAdded {
        sprite = sprite == null ? "" : sprite;
        type = type == null ? "" : type;
    }

    @Override
    public int kind() {
        return WorldLogCodec.KIND_OBJECT_ADDED;
    }
}
