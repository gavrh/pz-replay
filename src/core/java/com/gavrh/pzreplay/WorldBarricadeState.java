package com.gavrh.pzreplay;

public record WorldBarricadeState(
    WorldTarget target,
    String sprite,
    int objectIndex,
    int material,
    int plankCount,
    int aggregateHealth,
    int[] plankHealth,
    boolean aggregateOnly
) implements WorldStateRecord {
    public WorldBarricadeState {
        target = target == null ? WorldTarget.barricade(0, 0, 0, 0, 1, 0) : target;
        sprite = sprite == null ? "" : sprite;
        plankHealth = plankHealth == null ? new int[0] : plankHealth.clone();
    }

    @Override
    public int[] plankHealth() {
        return plankHealth.clone();
    }

    @Override
    public int kind() {
        return WorldLogCodec.KIND_OBJECT_STATE;
    }

    @Override
    public int classId() {
        return WorldLogCodec.CLASS_BARRICADE;
    }
}
