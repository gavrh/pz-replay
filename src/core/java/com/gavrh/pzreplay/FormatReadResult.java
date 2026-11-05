package com.gavrh.pzreplay;

import java.util.List;

public record FormatReadResult(
    ReplayMetadata metadata,
    List<CaptureSample> frames,
    List<String> warnings,
    String worldStatus,
    String worldReason,
    byte[] preview,
    List<FormatMod> mods
) {
    public FormatReadResult {
        frames = List.copyOf(frames);
        warnings = List.copyOf(warnings);
        worldStatus = worldStatus == null ? "none" : worldStatus;
        worldReason = worldReason == null ? "" : worldReason;
        preview = preview == null ? new byte[0] : preview.clone();
        mods = List.copyOf(mods);
    }

    @Override
    public byte[] preview() {
        return preview.clone();
    }
}
