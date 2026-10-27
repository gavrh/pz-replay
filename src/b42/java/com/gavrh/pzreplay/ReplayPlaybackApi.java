package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
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
        logCompatibility(requested, compatibility);
        System.out.println("[pz-replay] replay loaded path=" + requested + " "
            + ReplayReader.summary(data));

        GhostMotionController loadedController = new GhostMotionController(loaded);
        ReplayTimeline.Sample first = loaded.sampleAt(0.0);
        ReplayGhost ghost = ReplayGhostApi.spawnWithMotion(loadedController, first.x(), first.y(),
            first.z(), first.facing());
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
        state.setCompatibilityWarnings(compatibility.warnings());
        boolean following = settings.isCameraFollowDefault()
            && loadedController.setCameraFollow(true, ghost);
        state.setCameraFollow(following);

        timeline = loaded;
        controller = loadedController;
        lastPath = requested;
        System.out.println("[pz-replay] replay playback started path=" + requested
            + " duration=" + format(loaded.getDuration()) + "s"
            + " frames=" + loaded.getFrameCount()
            + " camera_follow=" + following);
        return true;
    }

    public static boolean stop() {
        GhostMotionController active = controller;
        ReplayControlState state = PZReplayLua.state();
        if (active == null) {
            state.playbackStopped();
            return false;
        }
        double currentTime = active.getCurrentTime();
        String path = lastPath;
        controller = null;
        timeline = null;
        lastPath = "";
        state.playbackStopped();
        ReplayGhostApi.despawn();
        System.out.println("[pz-replay] replay playback stopped path=" + path
            + " time=" + format(currentTime) + "s");
        return true;
    }

    public static boolean isPlaying() {
        return controller != null && controller.isActive() && ReplayGhostApi.isSpawned();
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

    public static boolean setCameraFollow(boolean enabled) {
        if (controller == null || !controller.isActive()) {
            return false;
        }
        ReplayGhost ghost = ReplayGhostApi.getGhost();
        if (ghost == null) {
            return false;
        }
        boolean changed = controller.setCameraFollow(enabled, ghost);
        if (changed) {
            PZReplayLua.state().setCameraFollow(enabled);
        }
        return changed;
    }

    public static boolean isCameraFollow() {
        return controller != null && controller.isCameraFollow();
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
            state.playbackFailed("ghost was removed");
            return;
        }
        if (controller.isActive()) {
            if (controller.isCameraFollow()) {
                ReplayGhost ghost = ReplayGhostApi.getGhost();
                if (ghost != null) {
                    controller.refreshCamera(ghost);
                }
            }
            state.playbackProgress(controller.getCurrentTime(), controller.getDuration(),
                controller.isCameraFollow());
            return;
        }

        double duration = controller.getDuration();
        String path = lastPath;
        controller = null;
        timeline = null;
        lastPath = "";
        state.playbackStopped();
        boolean removed = ReplayGhostApi.despawn();
        System.out.println("[pz-replay] replay playback finished path=" + path
            + " duration=" + format(duration) + "s"
            + " ghost_removed=" + removed);
    }

    static void onGhostDespawned() {
        if (controller != null) {
            PZReplayLua.state().playbackStopped();
        }
        controller = null;
        timeline = null;
        lastPath = "";
    }

    private static void logCompatibility(String path, ReplayCompatibility.Result result) {
        if (result.isCompatible()) {
            System.out.println("[pz-replay] replay compatible path=" + path);
            return;
        }
        for (String warning : result.warnings()) {
            System.out.println("[pz-replay] replay compatibility warning path=" + path
                + " warning=" + warning);
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
