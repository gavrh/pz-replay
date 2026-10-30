package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;

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
    private boolean captureSitting;
    private String lastError;
    private String lastReplayPath;
    private String lastReplaySummary;
    private String captureResultPath;
    private String captureResultSummary;

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

        String resolvedName = RecordingNames.unique(ReplayStorage.directory(),
            RecordingNames.resolve(name, CaptureMetadataFactory.newCaptureId()));
        if (resolvedName.isEmpty()) {
            lastError = "recording name is unavailable";
            System.out.println("[pz-replay] capture start failed: " + lastError);
            return false;
        }
        if (name != null && !name.isBlank() && !resolvedName.equals(name.trim())) {
            System.out.println("[pz-replay] capture name resolved requested=" + name.trim()
                + " using=" + resolvedName);
        }
        captureName = resolvedName;
        tickCounter = 0;
        captureCostNanos = 0;
        captureCostMaxNanos = 0;
        captureCostTicks = 0;
        stopReported = false;
        capturePaused = false;
        captureInVehicle = false;
        captureSitting = false;
        lastError = null;
        captureResultPath = null;
        captureResultSummary = null;

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
        if (!paused) {
            captureStateEvents(player);
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

    String getCaptureName() {
        return captureName == null ? "" : captureName;
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

    String getCaptureResultPath() {
        return captureResultPath == null ? "" : captureResultPath;
    }

    String getCaptureResultSummary() {
        return captureResultSummary == null ? "" : captureResultSummary;
    }

    CaptureSession.Summary getSummary() {
        return session.status();
    }

    String getLastError() {
        return lastError == null ? "" : lastError;
    }

    boolean recordAction(String kindId, String detail, float direction, float duration) {
        if (!session.isRecording()) {
            return false;
        }
        ReplayEvent.Kind kind = ReplayEvent.Kind.fromId(kindId);
        if (kind == null) {
            return false;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null || player instanceof ReplayGhost) {
            return false;
        }
        String resolvedDetail = detailFor(kind, detail, player);
        boolean recorded = session.recordEvent(kind, false, resolvedDetail, direction, duration);
        if (recorded) {
            logEvent(kind, false, resolvedDetail);
        }
        return recorded;
    }

    private void captureStateEvents(IsoPlayer player) {
        boolean sitting = player.isSitting();
        if (sitting != captureSitting) {
            String detail = sitting
                ? (player.isSitOnGround() ? "ground" : "furniture") : "";
            boolean recorded = session.recordEvent(ReplayEvent.Kind.SIT, !sitting, detail,
                player.getDirectionAngle(), 0f);
            if (recorded) {
                logEvent(ReplayEvent.Kind.SIT, !sitting, detail);
            }
            captureSitting = sitting;
        }
    }

    private static String detailFor(ReplayEvent.Kind kind, String detail, IsoPlayer player) {
        if (kind == ReplayEvent.Kind.MELEE_ATTACK
            || kind == ReplayEvent.Kind.RANGED_ATTACK) {
            return weaponStyle(player);
        }
        if (kind == ReplayEvent.Kind.RELOAD) {
            String type = reloadType(player);
            return detail != null && detail.startsWith("rack")
                ? "rack:" + type : type;
        }
        return detail == null ? "" : detail;
    }

    private static String weaponStyle(IsoPlayer player) {
        try {
            WeaponType type = WeaponType.getWeaponType(player);
            String value = type == null ? "" : type.getType();
            if (value == null || value.isBlank() || "unarmed".equals(value)) {
                return "";
            }
            return value;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String reloadType(IsoPlayer player) {
        InventoryItem item = player.getPrimaryHandItem();
        if (item instanceof HandWeapon weapon) {
            try {
                if (weapon.getWeaponReloadType() != null) {
                    return weapon.getWeaponReloadType().toString();
                }
            } catch (RuntimeException exception) {
                return "handgun";
            }
        }
        return "handgun";
    }

    private void logEvent(ReplayEvent.Kind kind, boolean end, String detail) {
        System.out.println("[pz-replay] capture action kind=" + kind.id()
            + " phase=" + (end ? "end" : "start")
            + " time=" + String.format(Locale.ROOT, "%.3f", session.currentTimeSeconds())
            + " detail=" + detail
            + " events=" + session.events().size());
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

    private ReplayEvents encodeEvents() {
        try {
            ReplayEvents events = ReplayEvents.encode(session.events());
            System.out.println("[pz-replay] capture actions events=" + session.events().size()
                + " bytes=" + events.data().length());
            return events;
        } catch (ReplayFormatException exception) {
            lastError = "action events could not be written: " + exception.getMessage();
            System.out.println("[pz-replay] action events could not be written: " + exception);
            return ReplayEvents.none();
        }
    }

    private void persist(CaptureSession.Summary summary) {
        if (summary.frameCount() == 0 || metadata == null) {
            return;
        }
        long durationMillis = Math.round(summary.durationSeconds() * 1000.0);
        ReplayMetadata finalMetadata = metadata.withEnd(
            metadata.startTimeMillis() + durationMillis).withEvents(encodeEvents());
        String resolvedName = RecordingNames.unique(ReplayStorage.directory(), captureName);
        if (resolvedName.isEmpty()) {
            lastError = "recording name is unavailable";
            System.out.println("[pz-replay] recording failed name=" + captureName
                + " error=" + lastError);
            return;
        }
        if (!resolvedName.equals(captureName)) {
            System.out.println("[pz-replay] capture name collision requested=" + captureName
                + " using=" + resolvedName);
            captureName = resolvedName;
        }
        Path path = ReplayStorage.pathFor(captureName);
        boolean written = false;
        try {
            BinaryReplayCodec.write(path, finalMetadata, session.frames(), true);
            written = true;
            ReplayData data = ReplayReader.read(path);
            lastReplayPath = path.toString();
            lastReplaySummary = ReplayReader.summary(data);
            captureResultPath = lastReplayPath;
            captureResultSummary = lastReplaySummary;
            System.out.println("[pz-replay] recording saved name=" + captureName
                + " path=" + lastReplayPath + " bytes=" + Files.size(path)
                + " " + lastReplaySummary);
        } catch (IOException | ReplayFormatException exception) {
            lastError = "replay write failed: " + exception.getMessage();
            System.out.println("[pz-replay] recording failed name=" + captureName
                + " path=" + path + " error=" + lastError);
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
