package com.gavrh.pzreplay;

public record WorldVehiclePose(
    float originX,
    float originY,
    float originZ,
    float quaternionX,
    float quaternionY,
    float quaternionZ,
    float quaternionW
) {
    public WorldVehiclePose {
        if (!Float.isFinite(originX) || !Float.isFinite(originY) || !Float.isFinite(originZ)
            || !Float.isFinite(quaternionX) || !Float.isFinite(quaternionY)
            || !Float.isFinite(quaternionZ) || !Float.isFinite(quaternionW)) {
            throw new IllegalArgumentException("vehicle pose is not finite");
        }
        double lengthSquared = (double) quaternionX * quaternionX
            + (double) quaternionY * quaternionY
            + (double) quaternionZ * quaternionZ
            + (double) quaternionW * quaternionW;
        if (!(lengthSquared > 0.0)) {
            throw new IllegalArgumentException("vehicle quaternion is zero");
        }
    }

    public static WorldVehiclePose of(WorldVehicleState state, float offsetX, float offsetY) {
        return new WorldVehiclePose(
            state.poseX() - offsetX,
            state.posePhysicsZ(),
            state.poseY() - offsetY,
            state.quaternionX(),
            state.quaternionY(),
            state.quaternionZ(),
            state.quaternionW());
    }
}
