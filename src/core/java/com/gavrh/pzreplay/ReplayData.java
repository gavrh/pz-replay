package com.gavrh.pzreplay;

import java.util.List;

public record ReplayData(ReplayMetadata metadata, List<CaptureSample> frames) {
    public ReplayData {
        frames = List.copyOf(frames);
    }
}
