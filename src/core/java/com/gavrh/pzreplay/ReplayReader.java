package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Path;

public final class ReplayReader {
    private ReplayReader() {
    }

    public static ReplayData read(Path path) throws IOException, ReplayFormatException {
        return BinaryReplayCodec.read(path);
    }

    public static String summary(ReplayData data) {
        ReplayMetadata metadata = data.metadata();
        long durationMs = Math.max(0, metadata.endTimeMillis() - metadata.startTimeMillis());
        return "format=" + metadata.formatVersion()
            + " frames=" + data.frames().size()
            + " duration_ms=" + durationMs
            + " rate_hz=" + metadata.sampleRateHz()
            + " map=" + metadata.mapName()
            + " save=" + metadata.saveName()
            + " player=" + metadata.playerName();
    }
}
