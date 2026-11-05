package com.gavrh.pzreplay;

import java.util.List;
import java.util.Map;

final class FormatMetadata {
    private FormatMetadata() {
    }

    static ReplayMetadata fromManifest(FormatManifest manifest,
        Map<String, String> actorMetadata, List<CaptureSample> frames) {
        float startX = 0f;
        float startY = 0f;
        float startZ = 0f;
        if (!frames.isEmpty()) {
            CaptureSample first = frames.get(0);
            startX = first.x();
            startY = first.y();
            startZ = first.z();
        }
        String playerName = manifest.sourcePlayerName;
        if (playerName.isEmpty() && !manifest.actors.isEmpty()) {
            playerName = manifest.actors.get(0).displayName();
        }
        return new ReplayMetadata(
            1,
            manifest.createdBy,
            manifest.gameVersion,
            manifest.sourceSaveName,
            manifest.sourceSaveMode,
            manifest.sourceMapName,
            playerName,
            startX,
            startY,
            startZ,
            manifest.actorSampleRateHz,
            manifest.startTimeMillis,
            manifest.endTimeMillis,
            Appearance.fromValues(actorMetadata),
            AppearanceChanges.fromValues(actorMetadata),
            Equipment.fromValues(actorMetadata),
            EquipmentChanges.fromValues(actorMetadata),
            ActionEvents.fromValues(actorMetadata),
            Vehicles.fromValues(actorMetadata),
            TimelineGaps.fromValues(actorMetadata)
        );
    }
}
