package com.gavrh.pzreplay;

public record ReplayEquipmentChange(
    long sequence,
    float timeSeconds,
    ReplayEquipment equipment
) {
    public ReplayEquipmentChange {
        equipment = equipment == null ? ReplayEquipment.none() : equipment;
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            timeSeconds = 0.0f;
        }
        if (sequence < 0L) {
            sequence = 0L;
        }
    }
}
