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
        return summary(data, false);
    }

    public static String summary(ReplayData data, boolean actionEventsDropped) {
        ReplayMetadata metadata = data.metadata();
        long durationMs = Math.max(0, metadata.endTimeMillis() - metadata.startTimeMillis());
        return "format=" + metadata.formatVersion()
            + " frames=" + data.frames().size()
            + " events=" + eventCount(metadata)
            + (actionEventsDropped ? " action_events=dropped" : "")
            + " appearance_changes=" + appearanceChangeCount(metadata)
            + " duration_ms=" + durationMs
            + " rate_hz=" + metadata.sampleRateHz()
            + " map=" + metadata.mapName()
            + " save=" + metadata.saveName()
            + " mode=" + metadata.gameMode()
            + " player=" + metadata.playerName();
    }

    private static String eventCount(ReplayMetadata metadata) {
        if (!metadata.events().isPresent()) {
            return "0";
        }
        try {
            return Integer.toString(metadata.events().decode().events().size());
        } catch (ReplayFormatException exception) {
            return "unknown";
        }
    }

    private static String appearanceChangeCount(ReplayMetadata metadata) {
        if (!metadata.appearanceChanges().isPresent()) {
            return "0";
        }
        try {
            return Integer.toString(metadata.appearanceChanges().decode().changes().size());
        } catch (ReplayFormatException exception) {
            return "unknown";
        }
    }
}
