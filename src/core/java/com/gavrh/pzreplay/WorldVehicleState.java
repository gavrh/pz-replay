package com.gavrh.pzreplay;

public record WorldVehicleState(
    WorldTarget target,
    boolean present,
    boolean headlightsOn,
    float poseX,
    float poseY,
    float poseZ,
    float posePhysicsZ,
    float quaternionX,
    float quaternionY,
    float quaternionZ,
    float quaternionW
) implements WorldStateRecord {
    public WorldVehicleState {
        target = target == null ? WorldTarget.vehicle(0, 0, 0, 1L, "") : target;
    }

    public WorldVehicleState(WorldTarget target, boolean present, boolean headlightsOn,
        float poseX, float poseY, float poseZ, float quaternionX, float quaternionY,
        float quaternionZ, float quaternionW) {
        this(target, present, headlightsOn, poseX, poseY, poseZ, poseZ, quaternionX,
            quaternionY, quaternionZ, quaternionW);
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
