package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import me.zed_0xff.zombie_buddy.Exposer;
import zombie.core.Core;

@Exposer.LuaClass(name = "PZReplayLoadApi")
public final class LoadApi {
    private static final List<SaveResolver.SaveDescriptor> SAVES = new ArrayList<>();
    private static SaveResolver.Result result =
        SaveResolver.Result.error("not-resolved", "");
    private static String lastReplayPath = "";
    private static volatile boolean pending;
    private static String pendingPath = "";
    private static long pendingStartedMillis;
    private static boolean worldReplay;
    private static volatile boolean contextPrepared;
    private static List<FormatMod> expectedMods = List.of();
    private static String expectedWorldVersion = "";
    private static final NoSaveWindow VEHICLE_WINDOW = new NoSaveWindow();
    private static final long VEHICLE_WINDOW_LIMIT_MILLIS = 30_000L;
    private static long vehicleWindowOpenedMillis;

    private LoadApi() {
    }

    public static void beginSaves() {
        SAVES.clear();
    }

    public static void addSave(String folder, String saveName, String gameMode, String mapName,
        String playerNames, String deadPlayerNames) {
        SAVES.add(new SaveResolver.SaveDescriptor(
            folder, saveName, gameMode, mapName,
            splitLines(playerNames), splitLines(deadPlayerNames),
            saveModifiedMillis(folder)));
    }

    public static boolean resolve(String path) {
        String requested = path == null ? "" : path.trim();
        lastReplayPath = requested;
        if (contextPrepared) {
            cleanupContext("new resolve");
        }
        worldReplay = false;
        expectedMods = List.of();
        expectedWorldVersion = "";
        if (requested.isEmpty()) {
            return fail("replay-invalid", "The replay path is empty.", requested);
        }

        FormatReadResult data;
        try {
            data = FormatReader.readPackage(Paths.get(requested));
            expectedWorldVersion = FormatReader.readSourceWorldVersion(Paths.get(requested));
        } catch (InvalidPathException exception) {
            return fail("replay-invalid", "The replay path is invalid.", requested);
        } catch (IOException exception) {
            return fail("replay-unreadable",
                "The replay could not be read: " + message(exception), requested);
        } catch (ReplayFormatException exception) {
            return fail("replay-invalid",
                "The replay is invalid: " + exception.getMessage(), requested);
        }

        if (data.frames().isEmpty()) {
            return fail("replay-invalid", "The replay has no frames.", requested);
        }
        if ("unsupported".equals(data.worldStatus())) {
            return fail("replay-world-unavailable",
                "The replay world cannot be loaded: " + data.worldReason(), requested);
        }

        worldReplay = "padded".equals(data.worldStatus());
        contextPrepared = false;
        expectedMods = data.mods();
        if (worldReplay) {
            result = new SaveResolver.Result(true, "", "",
                "", data.metadata().saveName(), data.metadata().gameMode(),
                data.metadata().mapName(), data.metadata().playerName(), data.warnings());
        } else {
            result = SaveResolver.resolve(data.metadata(), SAVES, gameVersion());
        }
        System.out.println("[pz-replay] load-play resolve path=" + requested
            + " saves=" + SAVES.size()
            + " resolved=" + result.resolved()
            + " error=" + result.errorCode()
            + " warning_count=" + result.warnings().size());
        if (!result.resolved()) {
            System.out.println("[pz-replay] load-play error path=" + requested
                + " code=" + result.errorCode()
                + " message=" + result.errorMessage());
            return false;
        }
        System.out.println("[pz-replay] load-play save folder=" + result.saveFolder()
            + " mode=" + result.gameMode()
            + " name=" + result.saveName()
            + " map=" + result.mapName()
            + " player=" + result.playerName());
        for (String warning : result.warnings()) {
            System.out.println("[pz-replay] load-play warning path=" + requested
                + " warning=" + warning);
        }
        return true;
    }

    public static boolean isWorldReplay() {
        return worldReplay;
    }

    public static boolean prepareContext() {
        if (!worldReplay || contextPrepared || !result.resolved()) {
            return contextPrepared;
        }
        try {
            FormatInfo info = FormatReader.readInfo(Paths.get(lastReplayPath));
            LoadingGate.start();
            WorldContextManager.prepare(lastReplayPath, info.metadata(), expectedWorldVersion);
            contextPrepared = true;
            VEHICLE_WINDOW.begin();
            System.out.println("[pz-replay] load-play context prepared replay=" + lastReplayPath
                + " folder=" + WorldContextManager.getFolderName()
                + " mode=" + WorldContextManager.getGameMode());
            return true;
        } catch (IOException | ReplayFormatException | RuntimeException exception) {
            System.out.println("[pz-replay] load-play context failed replay=" + lastReplayPath
                + " error=" + message(exception));
            VEHICLE_WINDOW.end();
            WorldContextManager.cleanup("prepare failed");
            return false;
        }
    }

    public static boolean isContextPrepared() {
        return contextPrepared && WorldContextManager.isPrepared();
    }

    public static String getLoadSaveName() {
        return isContextPrepared() ? WorldContextManager.getFolderName() : result.saveName();
    }

    public static String getLoadGameMode() {
        return isContextPrepared() ? WorldContextManager.getGameMode() : result.gameMode();
    }

    public static String getContextFolder() {
        return WorldContextManager.getFolderName();
    }

    public static String verifyContext() {
        if (!isContextPrepared()) {
            return "context is not prepared";
        }
        try {
            FormatInfo info = FormatReader.readInfo(Paths.get(lastReplayPath));
            List<String> warnings = new ArrayList<>(
                WorldContextManager.verify(info.metadata(), expectedWorldVersion));
            for (String warning : warnings) {
                System.out.println("[pz-replay] load-play context warning " + warning);
            }
            return String.join("; ", warnings);
        } catch (IOException | ReplayFormatException exception) {
            return "context verification failed: " + message(exception);
        }
    }

    public static void markContextLoaded() {
        if (isContextPrepared()) {
            WorldContextManager.markLoaded();
        }
    }

    public static void cleanupContext(String reason) {
        LoadingGate.clear();
        closeVehicleWindow();
        VEHICLE_WINDOW.end();
        WorldContextManager.cleanup(reason == null ? "requested" : reason);
        contextPrepared = false;
    }

    static boolean isContextLoading() {
        return pending && isContextPrepared();
    }

    public static synchronized boolean enterVehicleWindow() {
        if (!isContextPrepared() || !pending || !VEHICLE_WINDOW.open()) {
            return false;
        }
        vehicleWindowOpenedMillis = System.currentTimeMillis();
        Core.getInstance().setNoSave(false);
        System.out.println("[pz-replay] context vehicle window open no_save="
            + Core.getInstance().isNoSave());
        return true;
    }

    public static synchronized void exitVehicleWindow() {
        if (!VEHICLE_WINDOW.close()) {
            return;
        }
        vehicleWindowOpenedMillis = 0L;
        Core.getInstance().setNoSave(true);
        System.out.println("[pz-replay] context vehicle window closed no_save="
            + Core.getInstance().isNoSave());
    }

    static synchronized void guardVehicleWindow() {
        if (!VEHICLE_WINDOW.isOpen() || vehicleWindowOpenedMillis == 0L) {
            return;
        }
        if (System.currentTimeMillis() - vehicleWindowOpenedMillis
            < VEHICLE_WINDOW_LIMIT_MILLIS) {
            return;
        }
        System.out.println("[pz-replay] context vehicle window closed by timeout");
        exitVehicleWindow();
    }

    private static synchronized void closeVehicleWindow() {
        if (VEHICLE_WINDOW.close()) {
            vehicleWindowOpenedMillis = 0L;
            Core.getInstance().setNoSave(true);
            System.out.println("[pz-replay] context vehicle window closed no_save="
                + Core.getInstance().isNoSave());
        }
    }

    public static String verifyContextVehicles() {
        if (!isContextPrepared()) {
            return "context is not prepared";
        }
        return ContextVerifier.verifyVehicles(lastReplayPath);
    }

    public static void cleanupStaleContexts() {
        WorldContextManager.cleanupStale();
    }

    public static int getExpectedModCount() {
        return expectedMods.size();
    }

    public static String getExpectedModId(int index) {
        if (index < 0 || index >= expectedMods.size()) {
            return "";
        }
        return expectedMods.get(index).id();
    }

    public static String getExpectedModVersion(int index) {
        if (index < 0 || index >= expectedMods.size()) {
            return "";
        }
        return expectedMods.get(index).version();
    }

    public static void installLifecycle() {
        LoadingGate.start();
        WorldContextManager.installShutdownHook();
        WorldContextManager.cleanupStale();
    }

    public static boolean beginPending() {
        if (pending) {
            System.out.println("[pz-replay] load-play duplicate request ignored path="
                + lastReplayPath);
            return false;
        }
        if (!result.resolved()) {
            return false;
        }
        if (ReplaySettingsStore.get().isKillSwitch()) {
            System.out.println("[pz-replay] load-play blocked path=" + lastReplayPath
                + " reason=kill-switch");
            return false;
        }
        pending = true;
        pendingPath = lastReplayPath;
        pendingStartedMillis = System.currentTimeMillis();
        System.out.println("[pz-replay] load-play pending path=" + pendingPath
            + " mode=" + result.gameMode()
            + " save=" + result.saveName()
            + " via=MainScreen.continueLatestSave");
        return true;
    }

    public static boolean finishPending() {
        if (!pending) {
            return false;
        }
        System.out.println("[pz-replay] load-play pending finished path=" + pendingPath);
        pending = false;
        pendingPath = "";
        pendingStartedMillis = 0L;
        return true;
    }

    public static boolean cancelPending(String reason) {
        boolean hadPending = pending;
        if (pending) {
            System.out.println("[pz-replay] load-play pending cancelled path=" + pendingPath
                + " reason=" + (reason == null ? "" : reason));
            pending = false;
            pendingPath = "";
            pendingStartedMillis = 0L;
        }
        LoadingGate.clear();
        if (contextPrepared) {
            cleanupContext(reason == null ? "cancelled" : reason);
        }
        return hadPending;
    }

    public static boolean isPending() {
        return pending;
    }

    public static String getPendingPath() {
        return pendingPath;
    }

    public static double getPendingSeconds() {
        if (!pending) {
            return 0.0;
        }
        return (System.currentTimeMillis() - pendingStartedMillis) / 1000.0;
    }

    public static String getLastReplayPath() {
        return lastReplayPath;
    }

    public static boolean isResolved() {
        return result.resolved();
    }

    public static String getErrorCode() {
        return result.errorCode();
    }

    public static String getErrorMessage() {
        return result.errorMessage();
    }

    public static String getSaveFolder() {
        return result.saveFolder();
    }

    public static String getSaveName() {
        return result.saveName();
    }

    public static String getGameMode() {
        return result.gameMode();
    }

    public static String getMapName() {
        return result.mapName();
    }

    public static String getPlayerName() {
        return result.playerName();
    }

    public static int getWarningCount() {
        return result.warnings().size();
    }

    public static String getWarning(int index) {
        List<String> warnings = result.warnings();
        if (index < 0 || index >= warnings.size()) {
            return "";
        }
        return warnings.get(index);
    }

    private static boolean fail(String code, String message, String path) {
        result = SaveResolver.Result.error(code, message);
        System.out.println("[pz-replay] load-play error path=" + path
            + " code=" + code + " message=" + message);
        return false;
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : value.split("\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static long saveModifiedMillis(String folder) {
        if (folder == null || folder.isBlank()) {
            return 0L;
        }
        try {
            Path path = Paths.get(folder);
            if (!path.isAbsolute()) {
                path = Paths.get(Core.getMyDocumentFolder(), "Saves", folder);
            }
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toMillis();
        } catch (IOException | RuntimeException exception) {
            return 0L;
        }
    }

    private static String gameVersion() {
        try {
            return Core.getInstance().getGameAndBuildVersion();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return value;
    }
}
