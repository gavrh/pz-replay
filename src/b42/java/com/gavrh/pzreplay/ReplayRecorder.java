package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.joml.Quaternionf;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;
import zombie.vehicles.BaseVehicle;

final class ReplayRecorder {
    static final ReplayRecorder INSTANCE = new ReplayRecorder();
    private static final long STATUS_LOG_INTERVAL_MILLIS = 5000L;
    private static final long APPEARANCE_CHECK_INTERVAL_MILLIS = 1000L;
    private static final long EQUIPMENT_CHECK_INTERVAL_MILLIS = 1000L;

    private final ReplayAppearanceCapture appearanceCapture = new ReplayAppearanceCapture();
    private final ReplayEquipmentCapture equipmentCapture = new ReplayEquipmentCapture();
    private final ReplayVehicleCapture vehicleCapture = new ReplayVehicleCapture();
    private final Quaternionf vehicleRotation = new Quaternionf();
    private CaptureSession session = new CaptureSession();
    private ReplayMetadata metadata;
    private String captureName;
    private long tickCounter;
    private long nextStatusLogMillis;
    private long nextAppearanceCheckMillis;
    private long nextEquipmentCheckMillis;
    private String lastEquipmentKey = "";
    private long captureCostNanos;
    private long captureCostMaxNanos;
    private long captureCostTicks;
    private boolean stopReported;
    private boolean capturePaused;
    private boolean captureInVehicle;
    private boolean captureSitting;
    private boolean appearanceCaptureFailed;
    private boolean appearanceDropReported;
    private boolean equipmentCaptureFailed;
    private boolean equipmentDropReported;
    private String lastError;
    private String lastReplayPath;
    private String lastReplaySummary;
    private boolean eventsDropped;
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
        appearanceCapture.reset();
        equipmentCapture.reset();
        vehicleCapture.reset();
        appearanceCaptureFailed = false;
        appearanceDropReported = false;
        equipmentCaptureFailed = false;
        equipmentDropReported = false;
        lastEquipmentKey = "";
        lastError = null;
        eventsDropped = false;
        captureResultPath = null;
        captureResultSummary = null;

        long nowMillis = System.currentTimeMillis();
        session.start(nowMillis);
        try {
            if (metadata.appearance().isCurrent()) {
                appearanceCapture.markBaseline(metadata.appearance().decode());
            }
        } catch (ReplayFormatException exception) {
            appearanceCaptureFailed = true;
            System.out.println("[pz-replay] appearance change baseline unavailable: "
                + exception);
        }
        IsoPlayer initialPlayer = IsoPlayer.getInstance();
        if (initialPlayer != null) {
            lastEquipmentKey = EquipmentSnapshot.visualKey(initialPlayer);
        }
        try {
            if (metadata.equipment().isCurrent()) {
                equipmentCapture.accept(0f, metadata.equipment().decode(),
                    metadata.equipment());
            }
        } catch (ReplayFormatException exception) {
            equipmentCaptureFailed = true;
            System.out.println("[pz-replay] equipment change baseline unavailable: "
                + exception);
        }
        nextAppearanceCheckMillis = nowMillis + APPEARANCE_CHECK_INTERVAL_MILLIS;
        nextEquipmentCheckMillis = nowMillis;
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
            captureVehicle(player);
        }
        CaptureSession.PlayerSnapshot snapshot = paused ? null : snapshot(player);
        session.tick(tickCounter, nowMillis, snapshot, paused);
        if (!paused && session.isRecording()) {
            captureAppearance(player, false);
            captureEquipment(player, false);
        }
        recordCost(tickStartNanos);
        afterTick(nowMillis);
    }

    void tickPaused() {
        if (!session.isRecording() || !GameTime.isGamePaused()) {
            return;
        }
        long tickStartNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        tickCounter++;
        session.tick(tickCounter, nowMillis, null, true);
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
        ReplayActionContext context = actionContext(kind, player, resolvedDetail);
        boolean recorded = session.recordEvent(kind, false, resolvedDetail, direction,
            duration, context);
        if (recorded) {
            logEvent(kind, false, resolvedDetail, context);
        }
        return recorded;
    }

    boolean recordAppearanceChange() {
        if (!session.isRecording()) {
            return false;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null || player instanceof ReplayGhost || player.isDead()) {
            return false;
        }
        if (GameTime.isGamePaused()) {
            return false;
        }
        return captureAppearance(player, true);
    }

    private boolean captureAppearance(IsoPlayer player, boolean force) {
        if (!session.isRecording() || appearanceCapture.isDropped()) {
            return false;
        }
        long nowMillis = System.currentTimeMillis();
        if (!force && nowMillis < nextAppearanceCheckMillis) {
            return false;
        }
        nextAppearanceCheckMillis = nowMillis + APPEARANCE_CHECK_INTERVAL_MILLIS;
        try {
            AppearanceSnapshot.Captured captured = AppearanceSnapshot.captureWithPayload(player);
            boolean accepted = appearanceCapture.accept(session.currentTimeSeconds(),
                captured.payload(), captured.appearance());
            appearanceCaptureFailed = false;
            if (accepted) {
                System.out.println("[pz-replay] capture appearance change time="
                    + String.format(Locale.ROOT, "%.3f", session.currentTimeSeconds())
                    + " sequence=" + appearanceCapture.size()
                    + " bytes=" + captured.payload().length
                    + " changes=" + appearanceCapture.size());
            }
            if (appearanceCapture.isDropped() && !appearanceDropReported) {
                appearanceDropReported = true;
                System.out.println("[pz-replay] capture appearance changes dropped"
                    + " reason=limit changes=" + appearanceCapture.size());
            }
            return accepted;
        } catch (IOException | RuntimeException exception) {
            if (!appearanceCaptureFailed) {
                appearanceCaptureFailed = true;
                System.out.println("[pz-replay] appearance change capture failed: "
                    + exception);
            }
            return false;
        }
    }

    private boolean captureEquipment(IsoPlayer player, boolean force) {
        if (!session.isRecording() || equipmentCapture.isDropped()) {
            return false;
        }
        long nowMillis = System.currentTimeMillis();
        String key = EquipmentSnapshot.visualKey(player);
        boolean changed = !key.equals(lastEquipmentKey);
        if (!force && !changed && nowMillis < nextEquipmentCheckMillis) {
            return false;
        }
        nextEquipmentCheckMillis = nowMillis + EQUIPMENT_CHECK_INTERVAL_MILLIS;
        lastEquipmentKey = key;
        try {
            EquipmentSnapshot.Captured captured = EquipmentSnapshot.captureWithPayload(player);
            boolean accepted = equipmentCapture.accept(session.currentTimeSeconds(),
                captured.payload(), captured.equipment());
            equipmentCaptureFailed = false;
            if (accepted) {
                System.out.println("[pz-replay] capture equipment change time="
                    + String.format(Locale.ROOT, "%.3f", session.currentTimeSeconds())
                    + " sequence=" + equipmentCapture.size()
                    + " bytes=" + captured.payload().length
                    + " changes=" + equipmentCapture.size());
            }
            if (equipmentCapture.isDropped() && !equipmentDropReported) {
                equipmentDropReported = true;
                System.out.println("[pz-replay] capture equipment changes dropped"
                    + " reason=limit changes=" + equipmentCapture.size());
            }
            return accepted;
        } catch (IOException | RuntimeException exception) {
            if (!equipmentCaptureFailed) {
                equipmentCaptureFailed = true;
                System.out.println("[pz-replay] equipment change capture failed: "
                    + exception);
            }
            return false;
        }
    }

    private void captureVehicle(IsoPlayer player) {
        BaseVehicle vehicle = player.getVehicle();
        if (vehicle == null) {
            vehicleCapture.exit(session.currentTimeSeconds());
            return;
        }
        String scriptName;
        String modelFile = "";
        try {
            scriptName = vehicle.getScriptName();
            if (vehicle.getScript() != null && vehicle.getScript().getModel() != null) {
                modelFile = vehicle.getScript().getModel().file;
            }
        } catch (RuntimeException exception) {
            vehicleCapture.exit(session.currentTimeSeconds());
            System.out.println("[pz-replay] vehicle capture skipped: " + exception);
            return;
        }
        if (scriptName == null || scriptName.isBlank()) {
            vehicleCapture.exit(session.currentTimeSeconds());
            return;
        }
        vehicle.jniTransform.getRotation(vehicleRotation);
        vehicleCapture.record(session.currentTimeSeconds(),
            new ReplayVehicleCapture.VehicleSnapshot(
                vehicle.getX(),
                vehicle.getY(),
                vehicle.getZ(),
                vehicleRotation.x,
                vehicleRotation.y,
                vehicleRotation.z,
                vehicleRotation.w,
                scriptName,
                modelFile,
                vehicle.getSkinIndex(),
                vehicle.getSeat(player)
            ));
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

    private static ReplayActionContext actionContext(ReplayEvent.Kind kind, IsoPlayer player,
        String detail) {
        if (kind != ReplayEvent.Kind.MELEE_ATTACK
            && kind != ReplayEvent.Kind.RANGED_ATTACK) {
            return ReplayActionContext.none();
        }
        try {
            return new ReplayActionContext(
                detail,
                player.getAttackType() == null ? "" : player.getAttackType().toString(),
                player.getFireMode(),
                player.isAiming(),
                player.isDoShove(),
                player.isAimAtFloor(),
                player.isRangedWeaponEquipped(),
                player.isRangedWeaponEmpty(),
                player.isCriticalHit(),
                player.isAttackFromBehind()
            );
        } catch (RuntimeException exception) {
            System.out.println("[pz-replay] action context capture failed: " + exception);
            return ReplayActionContext.none();
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
        logEvent(kind, end, detail, ReplayActionContext.none());
    }

    private void logEvent(ReplayEvent.Kind kind, boolean end, String detail,
        ReplayActionContext context) {
        System.out.println("[pz-replay] capture action kind=" + kind.id()
            + " phase=" + (end ? "end" : "start")
            + " time=" + String.format(Locale.ROOT, "%.3f", session.currentTimeSeconds())
            + " detail=" + detail
            + " attack=" + context.attackType()
            + " weapon=" + context.weaponStyle()
            + " aiming=" + context.aiming()
            + " shove=" + context.shove()
            + " stomp=" + context.stomp()
            + " ranged=" + context.ranged()
            + " critical=" + context.critical()
            + " behind=" + context.attackFromBehind()
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
        vehicleCapture.finish(session.currentTimeSeconds());
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
        for (String warning : session.warnings()) {
            System.out.println("[pz-replay] capture gap warning: " + warning);
        }
        System.out.println("[pz-replay] capture stopped name=" + captureName
            + " " + summary.format() + overhead());
        persist(summary);
    }

    private ReplayAppearanceChanges encodeAppearanceChanges() {
        List<ReplayAppearanceChange> changes = appearanceCapture.changes();
        if (changes.isEmpty()) {
            if (appearanceCapture.isDropped() && !appearanceDropReported) {
                appearanceDropReported = true;
                System.out.println("[pz-replay] capture appearance changes dropped"
                    + " reason=limit changes=0");
            }
            return ReplayAppearanceChanges.none();
        }
        try {
            ReplayAppearanceChanges encoded = ReplayAppearanceChanges.encode(changes);
            System.out.println("[pz-replay] capture appearance changes count="
                + changes.size() + " bytes=" + encoded.data().length()
                + (appearanceCapture.isDropped() ? " dropped=true" : ""));
            return encoded;
        } catch (ReplayFormatException exception) {
            System.out.println("[pz-replay] appearance changes could not be written: "
                + exception);
            return ReplayAppearanceChanges.none();
        }
    }

    private ReplayEquipmentChanges encodeEquipmentChanges() {
        List<ReplayEquipmentChange> changes = equipmentCapture.changes();
        if (changes.size() <= 1 && metadata.equipment().isCurrent()) {
            if (equipmentCapture.isDropped() && !equipmentDropReported) {
                equipmentDropReported = true;
                System.out.println("[pz-replay] capture equipment changes dropped"
                    + " reason=limit changes=" + changes.size());
            }
            return ReplayEquipmentChanges.none();
        }
        try {
            ReplayEquipmentChanges encoded = ReplayEquipmentChanges.encode(changes);
            System.out.println("[pz-replay] capture equipment changes count="
                + changes.size() + " bytes=" + encoded.data().length()
                + (equipmentCapture.isDropped() ? " dropped=true" : ""));
            return encoded;
        } catch (ReplayFormatException exception) {
            System.out.println("[pz-replay] equipment changes could not be written: "
                + exception);
            return ReplayEquipmentChanges.none();
        }
    }

    private ReplayEvents encodeEvents() {
        try {
            ReplayEvents events = ReplayEvents.encode(session.events());
            eventsDropped = false;
            System.out.println("[pz-replay] capture actions events=" + session.events().size()
                + " bytes=" + events.data().length());
            return events;
        } catch (ReplayFormatException exception) {
            eventsDropped = true;
            lastError = "action events could not be written: " + exception.getMessage();
            System.out.println("[pz-replay] action events could not be written: " + exception);
            return ReplayEvents.none();
        }
    }

    private ReplayGaps encodeGaps() {
        List<ReplayGap> gaps = session.gaps();
        if (gaps.isEmpty()) {
            return ReplayGaps.none();
        }
        try {
            ReplayGaps encoded = ReplayGaps.encode(gaps);
            System.out.println("[pz-replay] capture gaps count=" + gaps.size()
                + " bytes=" + encoded.data().length());
            return encoded;
        } catch (ReplayFormatException exception) {
            lastError = "timeline gaps could not be written: " + exception.getMessage();
            System.out.println("[pz-replay] timeline gaps could not be written: " + exception);
            return ReplayGaps.none();
        }
    }

    private ReplayVehicles encodeVehicles() {
        ReplayVehicles vehicles = vehicleCapture.encode();
        int segments = vehicleCapture.getSegmentCount();
        int samples = vehicleCapture.getSampleCount();
        if (!vehicleCapture.getWarning().isBlank()) {
            System.out.println("[pz-replay] vehicle data warning: "
                + vehicleCapture.getWarning());
        }
        System.out.println("[pz-replay] capture vehicles segments=" + segments
            + " samples=" + samples + " bytes=" + vehicles.data().length()
            + (vehicleCapture.isDropped() ? " dropped=true" : ""));
        return vehicles;
    }

    private void persist(CaptureSession.Summary summary) {
        if (summary.frameCount() == 0 || metadata == null) {
            return;
        }
        long durationMillis = Math.round(summary.durationSeconds() * 1000.0);
        ReplayMetadata finalMetadata = metadata.withEnd(
            metadata.startTimeMillis() + durationMillis)
            .withAppearanceChanges(encodeAppearanceChanges())
            .withEquipmentChanges(encodeEquipmentChanges())
            .withEvents(encodeEvents())
            .withVehicles(encodeVehicles())
            .withGaps(encodeGaps());
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
            lastReplaySummary = ReplayReader.summary(data, eventsDropped);
            captureResultPath = lastReplayPath;
            captureResultSummary = lastReplaySummary;
            System.out.println("[pz-replay] recording saved"
                + (eventsDropped ? " without action events" : "")
                + " name=" + captureName
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
        return ReplaySample.Locomotion.fromState(sprinting, running, sneaking, moving);
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
