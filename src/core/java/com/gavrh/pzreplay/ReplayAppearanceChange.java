package com.gavrh.pzreplay;

public record ReplayAppearanceChange(
    long sequence,
    float timeSeconds,
    ReplayAppearance appearance
) {
    public ReplayAppearanceChange {
        appearance = appearance == null ? ReplayAppearance.none() : appearance;
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            timeSeconds = 0.0f;
        }
        if (sequence < 0L) {
            sequence = 0L;
        }
    }
}
