package com.gavrh.pzreplay;

public record ReplayVehicleSample(
    float timeSeconds,
    float x,
    float y,
    float z,
    float rotationX,
    float rotationY,
    float rotationZ,
    float rotationW
) {
}
