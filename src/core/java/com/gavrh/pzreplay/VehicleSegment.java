package com.gavrh.pzreplay;

import java.util.List;

public record VehicleSegment(
    float startTimeSeconds,
    float endTimeSeconds,
    String scriptName,
    String modelFile,
    int skinIndex,
    int seat,
    List<VehicleSample> samples
) {
    public static final int MAX_SCRIPT_LENGTH = 128;
    public static final int MAX_MODEL_LENGTH = 256;
    public static final int MAX_SEAT = 7;

    public VehicleSegment {
        scriptName = safe(scriptName, MAX_SCRIPT_LENGTH);
        modelFile = safe(modelFile, MAX_MODEL_LENGTH);
        samples = samples == null ? List.of() : List.copyOf(samples);
        if (!Float.isFinite(startTimeSeconds) || startTimeSeconds < 0.0f) {
            startTimeSeconds = 0.0f;
        }
        if (!Float.isFinite(endTimeSeconds) || endTimeSeconds < startTimeSeconds) {
            endTimeSeconds = startTimeSeconds;
        }
        skinIndex = Math.max(0, skinIndex);
        seat = Math.max(0, Math.min(MAX_SEAT, seat));
    }

    public boolean isValid() {
        return !scriptName.isBlank() && !samples.isEmpty();
    }

    public String key() {
        return scriptName + "|" + modelFile + "|" + skinIndex + "|" + seat;
    }

    private static String safe(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String flattened = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (flattened.length() > maxLength) {
            return flattened.substring(0, maxLength);
        }
        return flattened;
    }
}
