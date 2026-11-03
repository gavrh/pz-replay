package com.gavrh.pzreplay;

public record AppearanceChange(
    long sequence,
    float timeSeconds,
    Appearance appearance
) {
    public AppearanceChange {
        appearance = appearance == null ? Appearance.none() : appearance;
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            timeSeconds = 0.0f;
        }
        if (sequence < 0L) {
            sequence = 0L;
        }
    }
}
