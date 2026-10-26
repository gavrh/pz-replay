package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Locale;
import me.zed_0xff.zombie_buddy.Exposer;
import zombie.core.Core;
import zombie.iso.IsoWorld;

@Exposer.LuaClass(name = "PZReplayPlayback")
public final class ReplayPlaybackApi {
    private static final boolean DEBUG_ENABLED = Boolean.getBoolean("pzreplay.debug");
    private static ReplayTimeline timeline;
    private static GhostMotionController controller;
    private static String lastPath = "";

    private ReplayPlaybackApi() {
    }

    public static boolean play(String path) {
        if (!DEBUG_ENABLED) {
            return false;
        }
        if (controller != null) {
            logFailure(path, "playback already active");
            return false;
        }
        if (ReplayGhostApi.isSpawned()) {
            logFailure(path, "a ghost is already spawned");
            return false;
        }

        String requested = path == null ? "" : path.trim();
        if (requested.isEmpty()) {
            logFailure(requested, "replay path is empty");
            return false;
        }

        ReplayData data;
        try {
            data = ReplayReader.read(Paths.get(requested));
        } catch (InvalidPathException exception) {
            logFailure(requested, "invalid replay path");
            return false;
        } catch (IOException exception) {
            logFailure(requested, "cannot read replay: " + exception.getMessage());
            return false;
        } catch (ReplayFormatException exception) {
            logFailure(requested, "invalid replay: " + exception.getMessage());
            return false;
        }

        ReplayTimeline loaded;
        try {
            loaded = new ReplayTimeline(data);
        } catch (ReplayFormatException exception) {
            logFailure(requested, "invalid replay timeline: " + exception.getMessage());
            return false;
        }
        if (loaded.isEmpty()) {
            logFailure(requested, "replay has no frames");
            return false;
        }

        logCompatibility(requested, data.metadata());
        System.out.println("[pz-replay] replay loaded path=" + requested + " "
            + ReplayReader.summary(data));

        GhostMotionController loadedController = new GhostMotionController(loaded);
        ReplayTimeline.Sample first = loaded.sampleAt(0.0);
        ReplayGhost ghost = ReplayGhostApi.spawnWithMotion(loadedController, first.x(), first.y(),
            first.z(), first.facing());
        if (ghost == null) {
            logFailure(requested, "ghost spawn failed");
            return false;
        }
        if (!ghost.startMotion()) {
            ReplayGhostApi.despawn();
            logFailure(requested, "ghost motion start failed");
            return false;
        }

        timeline = loaded;
        controller = loadedController;
        lastPath = requested;
        System.out.println("[pz-replay] replay playback started path=" + requested
            + " duration=" + format(loaded.getDuration()) + "s"
            + " frames=" + loaded.getFrameCount());
        return true;
    }

    public static boolean stop() {
        GhostMotionController active = controller;
        if (active == null) {
            return false;
        }
        double currentTime = active.getCurrentTime();
        String path = lastPath;
        controller = null;
        timeline = null;
        lastPath = "";
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

    public static boolean setCameraFollow(boolean enabled) {
        if (controller == null || !controller.isActive()) {
            return false;
        }
        ReplayGhost ghost = ReplayGhostApi.getGhost();
        if (ghost == null) {
            return false;
        }
        return controller.setCameraFollow(enabled, ghost);
    }

    public static boolean isCameraFollow() {
        return controller != null && controller.isCameraFollow();
    }

    public static void tick() {
        if (!DEBUG_ENABLED || controller == null) {
            return;
        }
        if (!ReplayGhostApi.isSpawned()) {
            controller = null;
            timeline = null;
            lastPath = "";
            return;
        }
        if (controller.isActive()) {
            if (controller.isCameraFollow()) {
                ReplayGhost ghost = ReplayGhostApi.getGhost();
                if (ghost != null) {
                    controller.refreshCamera(ghost);
                }
            }
            return;
        }

        double duration = controller.getDuration();
        String path = lastPath;
        controller = null;
        timeline = null;
        lastPath = "";
        boolean removed = ReplayGhostApi.despawn();
        System.out.println("[pz-replay] replay playback finished path=" + path
            + " duration=" + format(duration) + "s"
            + " ghost_removed=" + removed);
    }

    static void onGhostDespawned() {
        controller = null;
        timeline = null;
        lastPath = "";
    }

    private static void logCompatibility(String path, ReplayMetadata metadata) {
        ReplayCompatibility.Result result = ReplayCompatibility.check(metadata, context());
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

    private static void logFailure(String path, String message) {
        System.out.println("[pz-replay] replay playback failed path="
            + (path == null ? "" : path) + " error=" + message);
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.2f", seconds);
    }
}
