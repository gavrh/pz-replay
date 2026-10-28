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
public final class ReplayLoadApi {
    private static final List<ReplaySaveResolver.SaveDescriptor> SAVES = new ArrayList<>();
    private static ReplaySaveResolver.Result result =
        ReplaySaveResolver.Result.error("not-resolved", "");
    private static String lastReplayPath = "";
    private static boolean pending;
    private static String pendingPath = "";
    private static long pendingStartedMillis;

    private ReplayLoadApi() {
    }

    public static void beginSaves() {
        SAVES.clear();
    }

    public static void addSave(String folder, String saveName, String gameMode, String mapName,
        String playerNames, String deadPlayerNames) {
        SAVES.add(new ReplaySaveResolver.SaveDescriptor(
            folder, saveName, gameMode, mapName,
            splitLines(playerNames), splitLines(deadPlayerNames),
            saveModifiedMillis(folder)));
    }

    public static boolean resolve(String path) {
        String requested = path == null ? "" : path.trim();
        lastReplayPath = requested;
        if (requested.isEmpty()) {
            return fail("replay-invalid", "The replay path is empty.", requested);
        }

        ReplayData data;
        try {
            data = ReplayReader.read(Paths.get(requested));
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

        result = ReplaySaveResolver.resolve(data.metadata(), SAVES, gameVersion());
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
        if (!pending) {
            return false;
        }
        System.out.println("[pz-replay] load-play pending cancelled path=" + pendingPath
            + " reason=" + (reason == null ? "" : reason));
        pending = false;
        pendingPath = "";
        pendingStartedMillis = 0L;
        return true;
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
        result = ReplaySaveResolver.Result.error(code, message);
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
