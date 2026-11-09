package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.zed_0xff.zombie_buddy.Exposer;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;

@Exposer.LuaClass(name = "PZReplayPlayback")
public final class PlaybackApi {
    private static PlaybackTimeline timeline;
    private static GhostMotionController controller;
    private static WorldPlayback worldPlayback;
    private static String lastPath = "";
    private static boolean reportedFinished;
    private static boolean reportedWorldFinished;

    private PlaybackApi() {
    }

    public static boolean play(String path) {
        ReplaySettings settings = ReplaySettingsStore.get();
        if (settings.isKillSwitch()) {
            fail(path, "kill switch is enabled");
            return false;
        }
        if (CaptureRecorder.INSTANCE.isRecording()) {
            fail(path, "recording is active");
            return false;
        }
        if (controller != null) {
            fail(path, "playback already active");
            return false;
        }
        if (GhostApi.isSpawned()) {
            fail(path, "a ghost is already spawned");
            return false;
        }

        String requested = path == null ? "" : path.trim();
        if (requested.isEmpty()) {
            fail(requested, "replay path is empty");
            return false;
        }

        ReplayData data;
        try {
            data = ReplayReader.read(Paths.get(requested));
        } catch (InvalidPathException exception) {
            fail(requested, "invalid replay path");
            return false;
        } catch (IOException exception) {
            fail(requested, "cannot read replay: " + exception.getMessage());
            return false;
        } catch (ReplayFormatException exception) {
            fail(requested, "invalid replay: " + exception.getMessage());
            return false;
        }

        PlaybackTimeline loaded;
        try {
            loaded = new PlaybackTimeline(data);
        } catch (ReplayFormatException exception) {
            fail(requested, "invalid replay timeline: " + exception.getMessage());
            return false;
        }
        if (loaded.isEmpty()) {
            fail(requested, "replay has no frames");
            return false;
        }

        Path replayPath = Paths.get(requested);
        ReplayCompatibility.Context compatibilityContext = context();
        if (LoadApi.isContextPrepared()) {
            compatibilityContext = new ReplayCompatibility.Context(
                compatibilityContext.gameVersion(), data.metadata().saveName(),
                compatibilityContext.mapName(), compatibilityContext.gameMode());
        }
        ReplayCompatibility.Result compatibility =
            ReplayCompatibility.check(data.metadata(), compatibilityContext);
        List<String> warnings = new ArrayList<>(compatibility.warnings());
        for (String formatWarning : data.warnings()) {
            addWarning(warnings, formatWarning);
        }
        for (String eventWarning : loaded.getEventWarnings()) {
            addWarning(warnings, eventWarning);
        }
        for (String appearanceWarning : loaded.getAppearanceWarnings()) {
            addWarning(warnings, appearanceWarning);
        }
        for (String equipmentWarning : loaded.getEquipmentWarnings()) {
            addWarning(warnings, equipmentWarning);
        }
        for (String vehicleWarning : loaded.getVehicleWarnings()) {
            addWarning(warnings, vehicleWarning);
        }
        for (String gapWarning : loaded.getGapWarnings()) {
            addWarning(warnings, gapWarning);
        }
        AppearanceSnapshot appearance = null;
        if (data.metadata().appearance().isCurrent()) {
            try {
                appearance = AppearanceSnapshot.decode(data.metadata().appearance());
            } catch (ReplayFormatException exception) {
                addWarning(warnings, "appearance data is corrupt or incomplete");
            }
        }
        System.out.println("[pz-replay] replay loaded path=" + requested + " "
            + ReplayReader.summary(data));

        WorldPlayback loadedWorld = null;
        try {
            FormatInfo info = FormatReader.readInfo(replayPath);
            if ("padded".equals(info.worldStatus())) {
                if (!LoadApi.isContextPrepared()) {
                    addWarning(warnings, "world apply requires the playback context");
                } else {
                    loadedWorld = WorldPlayback.open(replayPath, info.worldCoverageStatus(),
                        info.worldPaddingTiles(),
                        warning -> addPlayingWarning(warnings, warning));
                    for (String worldWarning : loadedWorld.summary().warnings()) {
                        addWarning(warnings, worldWarning);
                    }
                }
            }
        } catch (IOException | ReplayFormatException exception) {
            fail(requested, "cannot read world log: " + exception.getMessage());
            return false;
        }

        GhostMotionController loadedController = new GhostMotionController(loaded,
            warning -> addPlayingWarning(warnings, warning));
        loadedController.setCameraBounds(
            loadedWorld == null ? null : loadedWorld.cameraBounds());
        PlaybackTimeline.Sample first = loaded.sampleAt(0.0);
        GhostActor ghost = GhostApi.spawnWithMotion(loadedController, first.x(), first.y(),
            first.z(), first.facing(), appearance,
            warning -> addPlayingWarning(warnings, warning));
        if (ghost == null) {
            fail(requested, "ghost spawn failed");
            return false;
        }
        if (!ghost.startMotion()) {
            GhostApi.despawn();
            fail(requested, "ghost motion start failed");
            return false;
        }
        logWarnings(requested, warnings);

        ReplayControlState state = PZReplayLua.state();
        if (!state.playbackStarted(loaded.getDuration())) {
            GhostApi.despawn();
            fail(requested, "playback already active");
            return false;
        }
        state.setCompatibilityWarnings(warnings);
        CameraMode initialMode = settings.isCameraFollowDefault()
            ? CameraMode.FOLLOW_GHOST : CameraMode.PLAYER;
        if (!loadedController.setCameraMode(initialMode, ghost)
            && initialMode != CameraMode.PLAYER) {
            loadedController.setCameraMode(CameraMode.PLAYER, ghost);
        }
        state.setCameraMode(loadedController.getCameraMode());

        timeline = loaded;
        controller = loadedController;
        worldPlayback = loadedWorld;
        lastPath = requested;
        reportedFinished = false;
        reportedWorldFinished = false;
        if (worldPlayback != null) {
            System.out.println("[pz-replay] world playback apply " + worldPlayback.summaryText());
        }
        System.out.println("[pz-replay] replay playback started path=" + requested
            + " duration=" + format(loaded.getDuration()) + "s"
            + " frames=" + loaded.getFrameCount()
            + " events=" + loaded.getEventCount()
            + " appearance=" + (appearance == null ? "current" : "recorded")
            + " appearance_changes=" + loaded.getAppearanceChangeCount()
            + " equipment_changes=" + loaded.getEquipmentChangeCount()
            + " vehicles=" + loaded.getVehicleCount()
            + " gaps=" + loaded.getGapCount()
            + " items=" + (data.metadata().equipment().isPresent() ? "recorded" : "none")
            + " camera_mode=" + loadedController.getCameraMode().id());
        MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_START, requested,
            "duration_ms=" + Math.round(loaded.getDuration() * 1000.0),
            "frames=" + loaded.getFrameCount(),
            "world=" + (loadedWorld == null ? "off" : "padded"));
        return true;
    }

    public static boolean stop() {
        GhostMotionController active = controller;
        ReplayControlState state = PZReplayLua.state();
        if (active != null) {
            double currentTime = active.getCurrentTime();
            String path = lastPath;
            WorldPlayback stoppingWorld = worldPlayback;
            String worldSummary = stoppingWorld == null ? null : stoppingWorld.summaryText();
            controller = null;
            timeline = null;
            worldPlayback = null;
            lastPath = "";
            reportedFinished = false;
            reportedWorldFinished = false;
            state.playbackStopped();
            GhostApi.despawn();
            System.out.println("[pz-replay] replay playback stopped path=" + path
                + " time=" + format(currentTime) + "s");
            if (worldSummary != null) {
                System.out.println("[pz-replay] world playback summary " + worldSummary);
            }
            MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_STOP, path,
                "time_ms=" + Math.round(currentTime * 1000.0));
            if (stoppingWorld != null) {
                measureSummary(MeasurementLog.EVENT_PLAYBACK_SUMMARY, path, stoppingWorld);
            }
        } else {
            state.playbackStopped();
        }
        if (active != null) {
            CameraController.restorePlayerCamera();
        }
        return active != null;
    }

    public static boolean pause() {
        GhostActor ghost = GhostApi.getGhost();
        if (controller == null || ghost == null || !controller.pause(ghost)) {
            return false;
        }
        syncTransportState();
        return true;
    }

    public static boolean resume() {
        GhostActor ghost = GhostApi.getGhost();
        if (controller == null || ghost == null || !controller.resume(ghost)) {
            return false;
        }
        reportedFinished = false;
        syncTransportState();
        return true;
    }

    public static boolean togglePause() {
        if (controller == null) {
            return false;
        }
        if (controller.isPaused() || !controller.isPlaying()) {
            return resume();
        }
        return pause();
    }

    public static boolean seek(double seconds) {
        GhostActor ghost = GhostApi.getGhost();
        if (controller == null) {
            return false;
        }
        if (worldPlayback != null && WorldSeekDecision.seek(true, controller.getCurrentTime(),
            seconds) == WorldSeekDecision.Action.RELOAD_REQUIRED) {
            MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_SEEK, lastPath,
                "from_ms=" + Math.round(controller.getCurrentTime() * 1000.0),
                "to_ms=" + Math.round(seconds * 1000.0),
                "mode=reload");
            LoadApi.requestReload(lastPath, seconds);
            return true;
        }
        if (ghost == null || !controller.seek(ghost, seconds)) {
            return false;
        }
        if (worldPlayback != null) {
            worldPlayback.applyTo(seconds, true);
        }
        if (controller.getCurrentTime() < controller.getDuration()) {
            reportedFinished = false;
        }
        syncTransportState();
        return true;
    }

    public static boolean restart() {
        GhostActor ghost = GhostApi.getGhost();
        if (controller == null) {
            return false;
        }
        if (worldPlayback != null
            && WorldSeekDecision.restart(true) == WorldSeekDecision.Action.RELOAD_REQUIRED) {
            MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_RESTART, lastPath,
                "target_ms=0");
            LoadApi.requestReload(lastPath, 0.0);
            return true;
        }
        if (ghost == null || !controller.restart(ghost)) {
            return false;
        }
        reportedFinished = false;
        syncTransportState();
        MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_RESTART, lastPath,
            "target_ms=0");
        return true;
    }

    public static boolean setSpeed(double multiplier) {
        GhostActor ghost = GhostApi.getGhost();
        if (controller == null || ghost == null
            || !controller.setSpeed(ghost, multiplier)) {
            return false;
        }
        syncTransportState();
        return true;
    }

    public static boolean isPlaying() {
        return controller != null && controller.isPlaying() && GhostApi.isSpawned();
    }

    public static boolean isActive() {
        return controller != null && GhostApi.isSpawned();
    }

    public static boolean isPaused() {
        return controller != null && controller.isPaused() && GhostApi.isSpawned();
    }

    public static boolean isFinished() {
        return controller != null && controller.isFinished() && GhostApi.isSpawned();
    }

    public static double getSpeed() {
        return controller == null ? PZReplayLua.state().getPlaybackSpeed()
            : controller.getSpeed();
    }

    public static double getCurrentTime() {
        return controller == null ? 0.0 : controller.getCurrentTime();
    }

    public static double getDuration() {
        return timeline == null ? 0.0 : timeline.getDuration();
    }

    public static String getCurrentPath() {
        return lastPath;
    }

    public static boolean setCameraMode(String modeId) {
        CameraMode mode = CameraMode.fromId(modeId);
        if (mode == null || controller == null) {
            return false;
        }
        GhostActor ghost = GhostApi.getGhost();
        if (ghost == null) {
            return false;
        }
        if (!controller.setCameraMode(mode, ghost)) {
            return false;
        }
        PZReplayLua.state().setCameraMode(controller.getCameraMode());
        syncTransportState();
        return true;
    }

    public static boolean setCameraFollow(boolean enabled) {
        if (controller == null) {
            return false;
        }
        GhostActor ghost = GhostApi.getGhost();
        if (ghost == null) {
            return false;
        }
        if (!controller.setCameraFollow(enabled, ghost)) {
            return false;
        }
        PZReplayLua.state().setCameraMode(controller.getCameraMode());
        syncTransportState();
        return true;
    }

    public static boolean exitFreeCamera() {
        if (controller == null) {
            return false;
        }
        GhostActor ghost = GhostApi.getGhost();
        if (ghost == null || !controller.exitFreeCamera(ghost)) {
            return false;
        }
        PZReplayLua.state().setCameraMode(controller.getCameraMode());
        syncTransportState();
        return true;
    }

    public static boolean isCameraFollow() {
        return controller != null && controller.isCameraFollow();
    }

    public static boolean isCameraFree() {
        return controller != null && controller.isCameraFree();
    }

    public static String getCameraMode() {
        return controller == null ? PZReplayLua.state().getCameraModeId()
            : controller.getCameraMode().id();
    }

    public static String getCameraModeLabel() {
        return controller == null ? PZReplayLua.state().getCameraModeLabel()
            : controller.getCameraMode().label();
    }

    public static String getLastPlaybackError() {
        return PZReplayLua.state().getPlaybackError();
    }

    public static int getCompatibilityWarningCount() {
        return PZReplayLua.state().getCompatibilityWarnings().size();
    }

    public static String getCompatibilityWarning(int index) {
        List<String> warnings = PZReplayLua.state().getCompatibilityWarnings();
        if (index < 0 || index >= warnings.size()) {
            return "";
        }
        return warnings.get(index);
    }

    public static void tick() {
        ReplayControlState state = PZReplayLua.state();
        if (controller == null) {
            return;
        }
        if (!GhostApi.isSpawned()) {
            controller = null;
            timeline = null;
            lastPath = "";
            reportedFinished = false;
            CameraController.restorePlayerCamera();
            state.playbackFailed("ghost was removed");
            return;
        }
        if (controller.isCameraFollow()) {
            GhostActor ghost = GhostApi.getGhost();
            if (ghost != null) {
                controller.refreshCamera(ghost);
            }
        }
        if (worldPlayback != null) {
            GhostActor ghost = GhostApi.getGhost();
            followAnchor(ghost);
            worldPlayback.applyTo(controller.getCurrentTime(), false);
        }
        state.playbackProgress(controller.getCurrentTime(), controller.getDuration(),
            controller.getCameraMode());
        state.setCameraMode(controller.getCameraMode());
        state.setPlaybackPaused(controller.isPaused());
        state.setPlaybackSpeed(controller.getSpeed());
        if (!controller.isPlaying() && controller.isFinished() && !reportedFinished) {
            reportedFinished = true;
            System.out.println("[pz-replay] replay playback finished path=" + lastPath
                + " duration=" + format(controller.getDuration()) + "s"
                + " ghost_removed=false");
        }
        if (controller.isFinished() && worldPlayback != null && !reportedWorldFinished) {
            reportedWorldFinished = true;
            System.out.println("[pz-replay] world playback finished "
                + worldPlayback.summaryText());
            measureSummary(MeasurementLog.EVENT_PLAYBACK_FINISHED, lastPath, worldPlayback);
        }
    }

    static void onGhostDespawned() {
        if (controller != null) {
            PZReplayLua.state().playbackStopped();
        }
        controller = null;
        timeline = null;
        worldPlayback = null;
        lastPath = "";
        reportedFinished = false;
        reportedWorldFinished = false;
        CameraController.restorePlayerCamera();
    }

    public static String getWorldApplySummary() {
        return worldPlayback == null ? "" : worldPlayback.summaryText();
    }

    private static void followAnchor(GhostActor ghost) {
        if (ghost == null || !LoadApi.isContextPrepared()) {
            return;
        }
        IsoPlayer anchor = IsoPlayer.getInstance();
        if (anchor == null || anchor == ghost) {
            return;
        }
        float x = ghost.getX();
        float y = ghost.getY();
        float z = ghost.getZ();
        if (Math.abs(anchor.getX() - x) < 0.001f
            && Math.abs(anchor.getY() - y) < 0.001f
            && Math.abs(anchor.getZ() - z) < 0.001f) {
            return;
        }
        anchor.setX(x);
        anchor.setY(y);
        anchor.setZ(z);
        anchor.setCurrentSquareFromPosition();
        IsoWorld world = IsoWorld.instance;
        if (world != null && world.currentCell != null) {
            IsoChunkMap chunkMap = world.currentCell.getChunkMap(0);
            if (chunkMap != null) {
                chunkMap.ProcessChunkPos(anchor);
            }
        }
    }

    private static void syncTransportState() {
        ReplayControlState state = PZReplayLua.state();
        state.playbackProgress(controller.getCurrentTime(), controller.getDuration(),
            controller.getCameraMode());
        state.setCameraMode(controller.getCameraMode());
        state.setPlaybackPaused(controller.isPaused());
        state.setPlaybackSpeed(controller.getSpeed());
    }

    private static void logWarnings(String path, List<String> warnings) {
        if (warnings.isEmpty()) {
            System.out.println("[pz-replay] replay compatible path=" + path);
            return;
        }
        for (String warning : warnings) {
            System.out.println("[pz-replay] replay compatibility warning path=" + path
                + " warning=" + warning);
        }
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private static void addPlayingWarning(List<String> warnings, String warning) {
        addWarning(warnings, warning);
        PZReplayLua.state().setCompatibilityWarnings(warnings);
    }

    private static ReplayCompatibility.Context context() {
        IsoWorld world = IsoWorld.instance;
        return new ReplayCompatibility.Context(
            gameVersion(),
            world == null ? "" : world.getWorld(),
            world == null ? "" : world.getMap(),
            gameMode()
        );
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

    private static void fail(String path, String message) {
        PZReplayLua.state().playbackFailed(message);
        System.out.println("[pz-replay] replay playback failed path="
            + (path == null ? "" : path) + " error=" + message);
        MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_FAILED, path,
            "reason=" + message);
    }

    private static void measureSummary(String event, String path, WorldPlayback world) {
        WorldApplySummary summary = world.summary();
        MeasurementLogStore.event(event, path,
            "applied=" + summary.appliedCount(),
            "recorded_only=" + summary.recordedOnlyCount(),
            "partial=" + summary.partialCount(),
            "skipped=" + summary.skippedCount(),
            "missing=" + summary.missingCount(),
            "mismatched=" + summary.mismatchedCount(),
            "unsupported=" + summary.unsupportedCount(),
            "unknown=" + summary.unknownKindCount(),
            "reload_required=" + summary.reloadRequiredCount(),
            "duplicate=" + summary.duplicateCount());
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds);
    }
}
