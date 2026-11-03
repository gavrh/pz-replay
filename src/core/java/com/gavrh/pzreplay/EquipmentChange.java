package com.gavrh.pzreplay;

public record EquipmentChange(
    long sequence,
    float timeSeconds,
    Equipment equipment
) {
    public EquipmentChange {
        equipment = equipment == null ? Equipment.none() : equipment;
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            timeSeconds = 0.0f;
        }
        if (sequence < 0L) {
            sequence = 0L;
        }
    }
}
