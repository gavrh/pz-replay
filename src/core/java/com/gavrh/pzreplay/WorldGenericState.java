package com.gavrh.pzreplay;

public record WorldGenericState(
    WorldTarget target,
    String sprite,
    int objectIndex,
    boolean complex
) implements WorldStateRecord {
    public WorldGenericState {
        target = target == null ? WorldTarget.generic(0, 0, 0, 0, "zombie.iso.IsoObject")
            : target;
        sprite = sprite == null ? "" : sprite;
    }

    @Override
    public int kind() {
        return 0;
    }

    @Override
    public int classId() {
        return WorldLogCodec.CLASS_GENERIC;
    }
}
