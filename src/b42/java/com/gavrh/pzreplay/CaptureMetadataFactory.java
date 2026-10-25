package com.gavrh.pzreplay;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoWorld;

final class CaptureMetadataFactory {
    private static final DateTimeFormatter ID_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private CaptureMetadataFactory() {
    }

    static ReplayMetadata build(double sampleRateHz) {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            throw new IllegalStateException("no local player is loaded");
        }
        IsoWorld world = IsoWorld.instance;
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            BuildInfo.VERSION,
            gameVersion(),
            world == null ? "" : world.getWorld(),
            world == null ? "" : world.getMap(),
            player.getFullName(),
            player.getX(),
            player.getY(),
            player.getZ(),
            sampleRateHz,
            System.currentTimeMillis(),
            System.currentTimeMillis()
        );
    }

    static String newCaptureId() {
        return "replay-" + LocalDateTime.now().format(ID_FORMAT)
            + "-" + SEQUENCE.incrementAndGet();
    }

    private static String gameVersion() {
        try {
            return Core.getInstance().getGameAndBuildVersion();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }
}
