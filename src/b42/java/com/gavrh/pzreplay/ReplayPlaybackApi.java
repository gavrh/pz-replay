package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.zed_0xff.zombie_buddy.Exposer;
import zombie.core.Core;
import zombie.iso.IsoWorld;

@Exposer.LuaClass(name = "PZReplayPlayback")
public final class ReplayPlaybackApi {
    private static ReplayTimeline timeline;
    private static GhostMotionController controller;
    private static String lastPath = "";
    private static boolean reportedFinished;

    private ReplayPlaybackApi() {
    }

    public static boolean play(String path) {
        ReplaySettings settings = ReplaySettingsStore.get();
        if (settings.isKillSwitch()) {
            fail(path, "kill switch is enabled");
            return false;
        }
        if (ReplayRecorder.INSTANCE.isRecording()) {
            fail(path, "recording is active");
            return false;
        }
        if (controller != null) {
            fail(path, "playback already active");
            return false;
        }
        if (ReplayGhostApi.isSpawned()) {
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

        ReplayTimeline loaded;
        try {
            loaded = new ReplayTimeline(data);
        } catch (ReplayFormatException exception) {
            fail(requested, "invalid replay timeline: " + exception.getMessage());
            return false;
        }
        if (loaded.isEmpty()) {
            fail(requested, "replay has no frames");
            return false;
        }

        ReplayCompatibility.Result compatibility =
            ReplayCompatibility.check(data.metadata(), context());
        List<String> warnings = new ArrayList<>(compatibility.warnings());
        AppearanceSnapshot appearance = null;
        if (data.metadata().appearance().isCurrent()) {
            try {
                appearance = AppearanceSnapshot.decode(data.metadata().appearance());
            } catch (ReplayFormatException exception) {
                addWarning(warnings, "appearance data is corrupt or incomplete");
            }
        }
        logWarnings(requested, warnings);
        System.out.println("[pz-replay] replay loaded path=" + requested + " "
            + ReplayReader.summary(data));

        GhostMotionController loadedController = new GhostMotionController(loaded);
        ReplayTimeline.Sample first = loaded.sampleAt(0.0);
        ReplayGhost ghost = ReplayGhostApi.spawnWithMotion(loadedController, first.x(), first.y(),
            first.z(), first.facing(), appearance, warning -> addWarning(warnings, warning));
        if (ghost == null) {
            fail(requested, "ghost spawn failed");
            return false;
        }
        if (!ghost.startMotion()) {
            ReplayGhostApi.despawn();
            fail(requested, "ghost motion start failed");
            return false;
        }

        ReplayControlState state = PZReplayLua.state();
        if (!state.playbackStarted(loaded.getDuration())) {
            ReplayGhostApi.despawn();
            fail(requested, "playback already active");
            return false;
        }
        state.setCompatibilityWarnings(warnings);
        ReplayCameraMode initialMode = settings.isCameraFollowDefault()
            ? ReplayCameraMode.FOLLOW_GHOST : ReplayCameraMode.PLAYER;
        if (!loadedController.setCameraMode(initialMode, ghost)
            && initialMode != ReplayCameraMode.PLAYER) {
            loadedController.setCameraMode(ReplayCameraMode.PLAYER, ghost);
        }
        state.setCameraMode(loadedController.getCameraMode());

        timeline = loaded;
        controller = loadedController;
        lastPath = requested;
        reportedFinished = false;
        System.out.println("[pz-replay] replay playback started path=" + requested
            + " duration=" + format(loaded.getDuration()) + "s"
            + " frames=" + loaded.getFrameCount()
            + " appearance=" + (appearance == null ? "current" : "recorded")
            + " camera_mode=" + loadedController.getCameraMode().id());
        return true;
    }

    public static boolean stop() {
        GhostMotionController active = controller;
        ReplayControlState state = PZReplayLua.state();
        if (active != null) {
            double currentTime = active.getCurrentTime();
            String path = lastPath;
            controller = null;
            timeline = null;
            lastPath = "";
            reportedFinished = false;
            state.playbackStopped();
            ReplayGhostApi.despawn();
            System.out.println("[pz-replay] replay playback stopped path=" + path
                + " time=" + format(currentTime) + "s");
        } else {
            state.playbackStopped();
        }
        if (active != null) {
            ReplayCameraController.restorePlayerCamera();
        }
        return active != null;
    }

    public static boolean pause() {
        ReplayGhost ghost = ReplayGhostApi.getGhost();
        if (controller == null || ghost == null || !controller.pause(ghost)) {
            return false;
        }
        syncTransportState();
        return true;
    }

    public static boolean resume() {
        ReplayGhost ghost = ReplayGhostApi.getGhost();
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
        ReplayGhost ghost = ReplayGhostApi.getGhost();
        if (controller == null || ghost == null || !controller.seek(ghost, seconds)) {
            return false;
        }
        if (controller.getCurrentTime() < controller.getDuration()) {
            reportedFinished = false;
        }
        syncTransportState();
        return true;
    }

    public static boolean restart() {
        ReplayGhost ghost = ReplayGhostApi.getGhost();
        if (controller == null || ghost == null || !controller.restart(ghost)) {
            return false;
        }
        reportedFinished = false;
        syncTransportState();
        return true;
    }

    public static boolean setSpeed(double multiplier) {
        ReplayGhost ghost = ReplayGhostApi.getGhost();
        if (controller == null || ghost == null
            || !controller.setSpeed(ghost, multiplier)) {
            return false;
        }
        syncTransportState();
        return true;
    }

    public static boolean isPlaying() {
        return controller != null && controller.isPlaying() && ReplayGhostApi.isSpawned();
    }

    public static boolean isActive() {
        return controller != null && ReplayGhostApi.isSpawned();
    }

    public static boolean isPaused() {
        return controller != null && controller.isPaused() && ReplayGhostApi.isSpawned();
    }

    public static boolean isFinished() {
        return controller != null && controller.isFinished() && ReplayGhostApi.isSpawned();
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
        ReplayCameraMode mode = ReplayCameraMode.fromId(modeId);
        if (mode == null || controller == null) {
            return false;
        }
        ReplayGhost ghost = ReplayGhostApi.getGhost();
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
        ReplayGhost ghost = ReplayGhostApi.getGhost();
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
        ReplayGhost ghost = ReplayGhostApi.getGhost();
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
        if (!ReplayGhostApi.isSpawned()) {
            controller = null;
            timeline = null;
            lastPath = "";
            reportedFinished = false;
            ReplayCameraController.restorePlayerCamera();
            state.playbackFailed("ghost was removed");
            return;
        }
        if (controller.isCameraFollow()) {
            ReplayGhost ghost = ReplayGhostApi.getGhost();
            if (ghost != null) {
                controller.refreshCamera(ghost);
            }
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
    }

    static void onGhostDespawned() {
        if (controller != null) {
            PZReplayLua.state().playbackStopped();
        }
        controller = null;
        timeline = null;
        lastPath = "";
        reportedFinished = false;
        ReplayCameraController.restorePlayerCamera();
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
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds);
    }
}
