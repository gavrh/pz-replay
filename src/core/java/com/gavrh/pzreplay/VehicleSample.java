package com.gavrh.pzreplay;

public record VehicleSample(
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
