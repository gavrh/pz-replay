package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import zombie.GameTime;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;

final class ReplayRecorder {
    static final ReplayRecorder INSTANCE = new ReplayRecorder();
    private static final double SAMPLE_RATE_HZ = 30.0;
    private static final int MAX_FRAMES = 30 * 60 * 30;

    private final List<ReplaySample> frames = new ArrayList<>();
    private ReplayMetadata metadata;
    private String captureName;
    private boolean recording;
    private long startWallMillis;
    private long nextSampleMillis;
    private long lastSampleMillis;
    private float lastX;
    private float lastY;
    private float lastZ;
    private boolean hasLastPosition;
    private String lastPath;
    private int lastFrameCount;
    private String lastError;

    private ReplayRecorder() {
    }

    boolean start(String name) {
        if (recording) {
            return false;
        }
        try {
            metadata = CaptureMetadataFactory.build(SAMPLE_RATE_HZ);
        } catch (RuntimeException exception) {
            lastError = exception.getMessage();
            System.out.println("[pz-replay] recording start failed: " + lastError);
            return false;
        }

        captureName = normalizeName(name);
        frames.clear();
        hasLastPosition = false;
        lastPath = null;
        lastFrameCount = 0;
        lastError = null;
        startWallMillis = System.currentTimeMillis();
        nextSampleMillis = startWallMillis;
        lastSampleMillis = startWallMillis;
        recording = true;
        System.out.println("[pz-replay] recording started name=" + captureName);
        return true;
    }

    void tick(long tick) {
        long nowMillis = System.currentTimeMillis();
        if (!recording || nowMillis < nextSampleMillis) {
            return;
        }

        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null || !player.isLocalPlayer()) {
            return;
        }

        float x = player.getX();
        float y = player.getY();
        float z = player.getZ();
        float speed = 0f;
        if (hasLastPosition) {
            float deltaSeconds = (nowMillis - lastSampleMillis) / 1000f;
            if (deltaSeconds > 0f) {
                float dx = x - lastX;
                float dy = y - lastY;
                float dz = z - lastZ;
                speed = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / deltaSeconds);
            }
        }

        boolean prone = player.isProne();
        boolean sneaking = player.isSneaking();
        boolean sprinting = player.isSprinting();
        boolean running = player.isRunning();
        boolean aiming = player.isAiming();
        boolean moving = player.isPlayerMoving() || speed > 0.05f;

        frames.add(new ReplaySample(
            tick,
            (nowMillis - startWallMillis) / 1000f,
            gameSeconds(),
            x,
            y,
            z,
            player.getDirectionAngle(),
            speed,
            locomotion(sprinting, running, sneaking, moving),
            stance(prone, sneaking),
            ReplaySample.flags(aiming, sneaking, sprinting, running, prone, moving)
        ));

        lastSampleMillis = nowMillis;
        lastX = x;
        lastY = y;
        lastZ = z;
        hasLastPosition = true;
        nextSampleMillis = nowMillis + Math.round(1000.0 / SAMPLE_RATE_HZ);

        if (frames.size() >= MAX_FRAMES) {
            stop();
        }
    }

    boolean stop() {
        if (!recording) {
            return false;
        }
        recording = false;
        long endMillis = System.currentTimeMillis();
        ReplayMetadata finalMetadata = metadata.withEnd(endMillis);
        if (frames.isEmpty()) {
            lastError = "no frames";
            System.out.println("[pz-replay] recording stopped with no frames");
            frames.clear();
            return false;
        }

        Path path = Path.of(ZomboidFileSystem.instance.getCacheDir(), "pz-replay",
            captureName + ".pzrp.gz");
        try {
            BinaryReplayCodec.write(path, finalMetadata, frames, true);
            ReplayData readBack = ReplayReader.read(path);
            lastPath = path.toAbsolutePath().toString();
            lastFrameCount = readBack.frames().size();
            System.out.println("[pz-replay] recording finalized path=" + lastPath
                + " frames=" + lastFrameCount
                + " bytes=" + Files.size(path));
            return true;
        } catch (IOException | ReplayFormatException exception) {
            lastError = exception.getMessage();
            System.out.println("[pz-replay] recording write failed: " + lastError);
            return false;
        } finally {
            frames.clear();
        }
    }

    String getStatus() {
        if (recording) {
            return "recording name=" + captureName + " frames=" + frames.size();
        }
        if (lastError != null) {
            return "idle error=" + lastError;
        }
        if (lastPath != null) {
            return "idle path=" + lastPath + " frames=" + lastFrameCount;
        }
        return "idle";
    }

    String getLastReplayPath() {
        return lastPath == null ? "" : lastPath;
    }

    private static float gameSeconds() {
        try {
            return (float) (GameTime.getInstance().getWorldAgeHours() * 3600.0);
        } catch (RuntimeException exception) {
            return 0f;
        }
    }

    private static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        String sanitized = trimmed.replaceAll("[^A-Za-z0-9._-]+", "_");
        return sanitized.isEmpty() ? CaptureMetadataFactory.newCaptureId() : sanitized;
    }

    private static ReplaySample.Locomotion locomotion(boolean sprinting, boolean running,
        boolean sneaking, boolean moving) {
        if (sprinting) {
            return ReplaySample.Locomotion.SPRINT;
        }
        if (running) {
            return ReplaySample.Locomotion.RUN;
        }
        if (sneaking) {
            return ReplaySample.Locomotion.SNEAK;
        }
        if (moving) {
            return ReplaySample.Locomotion.WALK;
        }
        return ReplaySample.Locomotion.IDLE;
    }

    private static ReplaySample.Stance stance(boolean prone, boolean sneaking) {
        if (prone) {
            return ReplaySample.Stance.PRONE;
        }
        if (sneaking) {
            return ReplaySample.Stance.CROUCH;
        }
        return ReplaySample.Stance.STAND;
    }
}
