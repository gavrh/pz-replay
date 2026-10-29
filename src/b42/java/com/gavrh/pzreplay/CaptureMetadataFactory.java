package com.gavrh.pzreplay;

import java.io.IOException;
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
            gameMode(),
            world == null ? "" : world.getMap(),
            player.getFullName(),
            player.getX(),
            player.getY(),
            player.getZ(),
            sampleRateHz,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            captureAppearance(player),
            captureEquipment(player)
        );
    }

    private static ReplayAppearance captureAppearance(IsoPlayer player) {
        try {
            ReplayAppearance appearance = AppearanceSnapshot.capture(player);
            System.out.println("[pz-replay] appearance captured bytes="
                + appearance.data().length());
            return appearance;
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] appearance capture failed: " + exception);
            return ReplayAppearance.none();
        }
    }

    private static ReplayEquipment captureEquipment(IsoPlayer player) {
        try {
            ReplayEquipment equipment = EquipmentSnapshot.capture(player);
            System.out.println("[pz-replay] equipment captured bytes="
                + equipment.data().length());
            return equipment;
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] equipment capture failed: " + exception);
            return ReplayEquipment.none();
        }
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

    private static String gameMode() {
        try {
            return Core.getInstance().getGameMode();
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
