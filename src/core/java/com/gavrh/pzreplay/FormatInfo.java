package com.gavrh.pzreplay;

import java.util.List;

public record FormatInfo(
    ReplayMetadata metadata,
    String worldStatus,
    String worldReason,
    int worldPaddingTiles,
    String worldCoverageStatus,
    List<String> warnings
) {
    public FormatInfo {
        worldStatus = worldStatus == null ? "none" : worldStatus;
        worldReason = worldReason == null ? "" : worldReason;
        worldPaddingTiles = Math.max(0, worldPaddingTiles);
        worldCoverageStatus = worldCoverageStatus == null || worldCoverageStatus.isBlank()
            ? "complete" : worldCoverageStatus;
        warnings = List.copyOf(warnings);
    }
}
