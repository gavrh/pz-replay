package com.gavrh.pzreplay;

import zombie.GameTime;
import zombie.characters.IsoPlayer;

final class ReplayRecorder {
    static final ReplayRecorder INSTANCE = new ReplayRecorder();
    private static final long STATUS_LOG_INTERVAL_MILLIS = 5000L;

    private final CaptureSession session = new CaptureSession();
    private ReplayMetadata metadata;
    private String captureName;
    private long tickCounter;
    private long nextStatusLogMillis;
    private long captureCostNanos;
    private long captureCostMaxNanos;
    private long captureCostTicks;
    private boolean stopReported;
    private String lastError;

    private ReplayRecorder() {
    }

    boolean start(String name) {
        if (session.isRecording()) {
            return false;
        }
        try {
            metadata = CaptureMetadataFactory.build(CaptureSession.DEFAULT_SAMPLE_RATE_HZ);
        } catch (RuntimeException exception) {
            lastError = exception.getMessage();
            System.out.println("[pz-replay] capture start failed: " + lastError);
            return false;
        }

        captureName = normalizeName(name);
        tickCounter = 0;
        captureCostNanos = 0;
        captureCostMaxNanos = 0;
        captureCostTicks = 0;
        stopReported = false;
        lastError = null;

        long nowMillis = System.currentTimeMillis();
        session.start(nowMillis);
        nextStatusLogMillis = nowMillis + STATUS_LOG_INTERVAL_MILLIS;
        System.out.println("[pz-replay] capture started name=" + captureName
            + " rate=" + CaptureSession.DEFAULT_SAMPLE_RATE_HZ + "Hz"
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
        if (player == null || !player.isLocalPlayer()) {
            recordCost(tickStartNanos);
            return;
        }

        boolean paused = GameTime.isGamePaused();
        CaptureSession.PlayerSnapshot snapshot = paused ? null : snapshot(player);
        session.tick(tickCounter, nowMillis, snapshot, paused);
        recordCost(tickStartNanos);
        afterTick(nowMillis);
    }

    boolean stop() {
        if (!session.isRecording()) {
            return false;
        }
        session.stop(CaptureSession.StopReason.REQUESTED);
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
        }
        System.out.println("[pz-replay] capture stopped name=" + captureName
            + " " + summary.format() + overhead());
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
        return String.format(" overhead_avg_ms=%.4f overhead_max_ms=%.4f",
            averageMillis, maxMillis);
    }

    private static CaptureSession.PlayerSnapshot snapshot(IsoPlayer player) {
        boolean prone = player.isProne();
        boolean sneaking = player.isSneaking();
        boolean sprinting = player.isSprinting();
        boolean running = player.isRunning();
        boolean aiming = player.isAiming();
        boolean moving = player.isPlayerMoving();

        return new CaptureSession.PlayerSnapshot(
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getDirectionAngle(),
            gameSeconds(),
            locomotion(sprinting, running, sneaking, moving),
            stance(prone, sneaking),
            ReplaySample.flags(aiming, sneaking, sprinting, running, prone, moving)
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
