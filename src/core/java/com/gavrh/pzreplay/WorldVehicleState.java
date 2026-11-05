package com.gavrh.pzreplay;

public record WorldVehicleState(
    WorldTarget target,
    boolean present,
    boolean headlightsOn,
    float poseX,
    float poseY,
    float poseZ,
    float quaternionX,
    float quaternionY,
    float quaternionZ,
    float quaternionW
) implements WorldStateRecord {
    public WorldVehicleState {
        target = target == null ? WorldTarget.vehicle(0, 0, 0, 1L, "") : target;
    }

    @Override
    public int kind() {
        return WorldLogCodec.KIND_VEHICLE_STATE;
    }

    @Override
    public int classId() {
        return WorldLogCodec.CLASS_VEHICLE;
    }
}
