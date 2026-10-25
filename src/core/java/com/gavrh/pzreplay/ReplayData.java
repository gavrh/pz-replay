package com.gavrh.pzreplay;

import java.util.List;

public record ReplayData(ReplayMetadata metadata, List<ReplaySample> frames) {
    public ReplayData {
        frames = List.copyOf(frames);
    }
}
