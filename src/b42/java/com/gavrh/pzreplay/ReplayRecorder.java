package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import zombie.GameTime;
import zombie.characters.IsoPlayer;

final class ReplayRecorder {
    static final ReplayRecorder INSTANCE = new ReplayRecorder();
    private static final long STATUS_LOG_INTERVAL_MILLIS = 5000L;

    private CaptureSession session = new CaptureSession();
    private ReplayMetadata metadata;
    private String captureName;
    private long tickCounter;
    private long nextStatusLogMillis;
    private long captureCostNanos;
    private long captureCostMaxNanos;
    private long captureCostTicks;
    private boolean stopReported;
    private boolean capturePaused;
    private boolean captureInVehicle;
    private String lastError;
    private String lastReplayPath;
    private String lastReplaySummary;

    private ReplayRecorder() {
    }

    boolean start(String name, double sampleRateHz) {
        if (session.isRecording()) {
            return false;
        }
        CaptureSession startedSession;
        try {
            startedSession = new CaptureSession(sampleRateHz);
        } catch (IllegalArgumentException exception) {
            lastError = exception.getMessage();
            System.out.println("[pz-replay] capture start failed: " + lastError);
            return false;
        }
        try {
            metadata = CaptureMetadataFactory.build(sampleRateHz);
        } catch (RuntimeException exception) {
            lastError = exception.getMessage();
            System.out.println("[pz-replay] capture start failed: " + lastError);
            return false;
        }
        session = startedSession;

        captureName = normalizeName(name);
        tickCounter = 0;
        captureCostNanos = 0;
        captureCostMaxNanos = 0;
        captureCostTicks = 0;
        stopReported = false;
        capturePaused = false;
        captureInVehicle = false;
        lastError = null;

        long nowMillis = System.currentTimeMillis();
        session.start(nowMillis);
        nextStatusLogMillis = nowMillis + STATUS_LOG_INTERVAL_MILLIS;
        System.out.println("[pz-replay] capture started name=" + captureName
            + " rate=" + sampleRateHz + "Hz"
            + " max_frames=" + CaptureSession.MAX_FRAMES
            + " max_duration=" + (long) CaptureSession.MAX_DURATION_SECONDS + "s"
            + " x=" + metadata.startX()
            + " y=" + metadata.startY()
            + " z=" + metadata.startZ());
        return true;
    }

    void tick() {
        if (!session.isRecording()) {
            return;
        }

        long tickStartNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        tickCounter++;

        IsoPlayer player = IsoPlayer.getInstance();
        if (player instanceof ReplayGhost) {
            lastError = "ghost is the global player";
            recordCost(tickStartNanos);
            stopWithReason(CaptureSession.StopReason.PLAYER_UNAVAILABLE);
            return;
        }
        if (player == null || !player.isLocalPlayer()) {
            session.tick(tickCounter, nowMillis, null, false);
            recordCost(tickStartNanos);
            afterTick(nowMillis);
            return;
        }
        if (player.isDead()) {
            recordCost(tickStartNanos);
            stopWithReason(CaptureSession.StopReason.PLAYER_DEAD);
            return;
        }

        boolean paused = GameTime.isGamePaused();
        if (paused != capturePaused) {
            capturePaused = paused;
            System.out.println("[pz-replay] capture " + (paused ? "paused" : "resumed"));
        }
        boolean inVehicle = player.getVehicle() != null;
        if (inVehicle != captureInVehicle) {
            captureInVehicle = inVehicle;
            System.out.println("[pz-replay] capture vehicle "
                + (inVehicle ? "entered" : "exited"));
        }
        CaptureSession.PlayerSnapshot snapshot = paused ? null : snapshot(player);
        session.tick(tickCounter, nowMillis, snapshot, paused);
        recordCost(tickStartNanos);
        afterTick(nowMillis);
    }

    boolean stop() {
        return stop(CaptureSession.StopReason.REQUESTED);
    }

    boolean stop(CaptureSession.StopReason reason) {
        if (!session.isRecording()) {
            return false;
        }
        session.stop(reason);
        logStopped();
        return true;
    }

    boolean isRecording() {
        return session.isRecording();
    }

    String getStatus() {
        CaptureSession.Summary summary = session.status();
        StringBuilder builder = new StringBuilder(session.isRecording() ? "recording" : "idle")
            .append(" name=").append(captureName == null ? "" : captureName)
            .append(' ').append(summary.format());
        if (!session.isRecording() && lastError != null) {
            builder.append(" error=").append(lastError);
        }
        return builder.toString();
    }

    String getLastReplayPath() {
        return lastReplayPath == null ? "" : lastReplayPath;
    }

    String getLastReplaySummary() {
        return lastReplaySummary == null ? "" : lastReplaySummary;
    }

    CaptureSession.Summary getSummary() {
        return session.status();
    }

    String getLastError() {
        return lastError == null ? "" : lastError;
    }

    private void stopWithReason(CaptureSession.StopReason reason) {
        session.stop(reason);
        logStopped();
    }

    private void afterTick(long nowMillis) {
        if (!session.isRecording()) {
            logStopped();
            return;
        }
        if (nowMillis >= nextStatusLogMillis) {
            System.out.println("[pz-replay] capture status name=" + captureName
                + " " + session.status().format() + overhead());
            nextStatusLogMillis = nowMillis + STATUS_LOG_INTERVAL_MILLIS;
        }
    }

    private void logStopped() {
        if (stopReported) {
            return;
        }
        stopReported = true;
        CaptureSession.Summary summary = session.getLastSummary();
        if (summary.stopReason() == CaptureSession.StopReason.NO_FRAMES) {
            lastError = "no frames";
        } else if (summary.stopReason() == CaptureSession.StopReason.PLAYER_DEAD) {
            lastError = "player died";
        } else if (summary.stopReason() == CaptureSession.StopReason.MAX_FRAMES) {
            lastError = "frame limit reached";
        } else if (summary.stopReason() == CaptureSession.StopReason.MAX_DURATION) {
            lastError = "duration limit reached";
        } else if (summary.stopReason() == CaptureSession.StopReason.PLAYER_UNAVAILABLE
            && lastError == null) {
            lastError = "player unavailable";
        }
        System.out.println("[pz-replay] capture stopped name=" + captureName
            + " " + summary.format() + overhead());
        persist(summary);
    }

    private void persist(CaptureSession.Summary summary) {
        if (summary.frameCount() == 0 || metadata == null) {
            return;
        }
        long durationMillis = Math.round(summary.durationSeconds() * 1000.0);
        ReplayMetadata finalMetadata = metadata.withEnd(
            metadata.startTimeMillis() + durationMillis);
        Path path = ReplayStorage.pathFor(captureName);
        boolean written = false;
        try {
            BinaryReplayCodec.write(path, finalMetadata, session.frames(), true);
            written = true;
            ReplayData data = ReplayReader.read(path);
            lastReplayPath = path.toString();
            lastReplaySummary = ReplayReader.summary(data);
            System.out.println("[pz-replay] replay written path=" + lastReplayPath
                + " bytes=" + Files.size(path) + " " + lastReplaySummary);
        } catch (IOException | ReplayFormatException exception) {
            lastError = "replay write failed: " + exception.getMessage();
            System.out.println("[pz-replay] replay write failed path=" + path
                + " error=" + lastError);
            if (written) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void recordCost(long tickStartNanos) {
        long costNanos = System.nanoTime() - tickStartNanos;
        captureCostNanos += costNanos;
        captureCostMaxNanos = Math.max(captureCostMaxNanos, costNanos);
        captureCostTicks++;
    }

    private String overhead() {
        if (captureCostTicks == 0) {
            return " overhead_avg_ms=0.0000 overhead_max_ms=0.0000";
        }
        double averageMillis = captureCostNanos / (double) captureCostTicks / 1_000_000.0;
        double maxMillis = captureCostMaxNanos / 1_000_000.0;
        return String.format(Locale.ROOT, " overhead_avg_ms=%.4f overhead_max_ms=%.4f",
            averageMillis, maxMillis);
    }

    private static CaptureSession.PlayerSnapshot snapshot(IsoPlayer player) {
        boolean prone = player.isProne();
        boolean sneaking = player.isSneaking();
        boolean sprinting = player.isSprinting();
        boolean running = player.isRunning();
        boolean aiming = player.isAiming();
        boolean moving = player.isPlayerMoving();
        boolean inVehicle = player.getVehicle() != null;

        return new CaptureSession.PlayerSnapshot(
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getDirectionAngle(),
            gameSeconds(),
            locomotion(sprinting, running, sneaking, moving),
            stance(prone, sneaking),
            ReplaySample.flags(aiming, sneaking, sprinting, running, prone, moving,
                inVehicle)
        );
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
