package com.gavrh.pzreplay;

import java.util.Arrays;
import java.util.Objects;

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
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorldBarricadeState that)) {
            return false;
        }
        return objectIndex == that.objectIndex
            && material == that.material
            && plankCount == that.plankCount
            && aggregateHealth == that.aggregateHealth
            && aggregateOnly == that.aggregateOnly
            && target.equals(that.target)
            && sprite.equals(that.sprite)
            && Arrays.equals(plankHealth, that.plankHealth);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(target, sprite, objectIndex, material, plankCount,
            aggregateHealth, aggregateOnly);
        return 31 * result + Arrays.hashCode(plankHealth);
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
