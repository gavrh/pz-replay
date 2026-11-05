package com.gavrh.pzreplay;

import java.util.List;

public record ReplayData(ReplayMetadata metadata, List<CaptureSample> frames,
    List<String> warnings) {
    public ReplayData {
        frames = List.copyOf(frames);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public ReplayData(ReplayMetadata metadata, List<CaptureSample> frames) {
        this(metadata, frames, List.of());
    }
}
