package com.gavrh.pzreplay;

import java.util.List;

public record FormatInfo(
    ReplayMetadata metadata,
    String worldStatus,
    String worldReason,
    List<String> warnings
) {
    public FormatInfo {
        worldStatus = worldStatus == null ? "none" : worldStatus;
        worldReason = worldReason == null ? "" : worldReason;
        warnings = List.copyOf(warnings);
    }
}
