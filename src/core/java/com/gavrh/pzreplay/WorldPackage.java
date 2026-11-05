package com.gavrh.pzreplay;

public record WorldPackage(
    byte[] base,
    byte[] log,
    int paddingTiles,
    int sampleRateHz,
    String coverageStatus,
    long coverageSquares,
    long coverageObjects,
    long logChanges,
    int worldVersion
) {
    public static final int DEFAULT_SAMPLE_RATE_HZ = 2;

    public WorldPackage {
        base = base == null ? new byte[0] : base.clone();
        log = log == null ? new byte[0] : log.clone();
        coverageStatus = coverageStatus == null ? "complete" : coverageStatus;
        worldVersion = worldVersion < 1 ? 1 : worldVersion;
    }

    public WorldPackage(byte[] base, byte[] log, int paddingTiles, int sampleRateHz,
        String coverageStatus, long coverageSquares, long coverageObjects, long logChanges) {
        this(base, log, paddingTiles, sampleRateHz, coverageStatus, coverageSquares,
            coverageObjects, logChanges, 1);
    }

    @Override
    public byte[] base() {
        return base.clone();
    }

    @Override
    public byte[] log() {
        return log.clone();
    }

    public long baseBytes() {
        return base.length;
    }

    public long logBytes() {
        return log.length;
    }
}
