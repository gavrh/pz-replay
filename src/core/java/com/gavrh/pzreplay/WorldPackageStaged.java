package com.gavrh.pzreplay;

import java.nio.file.Path;

public record WorldPackageStaged(
    Path base,
    Path log,
    long baseBytes,
    long logBytes,
    int paddingTiles,
    int sampleRateHz,
    String coverageStatus,
    long coverageSquares,
    long coverageObjects,
    long logChanges,
    int worldVersion
) {
    public WorldPackageStaged {
        if (base == null || log == null) {
            throw new IllegalArgumentException("world staging paths are required");
        }
        if (baseBytes < 0L || logBytes < 0L) {
            throw new IllegalArgumentException("world payload lengths must not be negative");
        }
        coverageStatus = coverageStatus == null ? "complete" : coverageStatus;
        worldVersion = worldVersion < 1 ? 1 : worldVersion;
    }
}
