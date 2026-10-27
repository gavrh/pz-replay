package com.gavrh.pzreplay;

import java.nio.file.Path;
import java.util.List;
import me.zed_0xff.zombie_buddy.Exposer;

@Exposer.LuaClass(name = "PZReplayCatalogue")
public final class ReplayCatalogueApi {
    private static ReplayCatalogue.Result result =
        new ReplayCatalogue.Result(List.of(), "");
    private static List<ReplayCatalogue.Entry> view = List.of();
    private static String lastReplayPath = "";
    private static String recorderLastPath;
    private static String searchText = "";
    private static String sortField = ReplayCatalogueView.SORT_DATE;
    private static boolean sortAscending;
    private static String lastOperationPath = "";
    private static String lastOperationError = "";

    private ReplayCatalogueApi() {
    }

    public static int refresh() {
        adoptRecorderLastReplay();
        reloadEntries();
        System.out.println("[pz-replay] catalogue refreshed count=" + view.size()
            + " total=" + result.entries().size()
            + " query=" + searchText
            + " error=" + result.error());
        return view.size();
    }

    public static int applyQuery(String query, String sort, boolean ascending) {
        searchText = query == null ? "" : query;
        sortField = ReplayCatalogueView.normalizeSort(sort);
        sortAscending = ascending;
        view = ReplayCatalogueView.apply(result.entries(), searchText, sortField,
            sortAscending);
        return view.size();
    }

    public static int getCount() {
        return view.size();
    }

    public static int getTotalCount() {
        return result.entries().size();
    }

    public static String getCatalogueError() {
        return result.error();
    }

    public static void clearError() {
        result = new ReplayCatalogue.Result(result.entries(), "");
    }

    public static String getLastReplayPath() {
        return lastReplayPath == null ? "" : lastReplayPath;
    }

    public static String getDirectory() {
        return ReplayStorage.directory().toString();
    }

    public static boolean rename(String path, String newName) {
        clearOperation();
        if (PZReplayLua.isRecording()) {
            return failOperation("recording is active");
        }
        String entryPath = existingPath(path);
        if (entryPath == null) {
            return failOperation("replay is no longer available");
        }
        if (isActive(entryPath)) {
            return failOperation("replay is being played");
        }

        ReplayFileManager.Result renamed = ReplayFileManager.rename(
            ReplayStorage.directory(), entryPath, newName);
        if (!renamed.ok()) {
            return failOperation(renamed.error());
        }

        lastOperationPath = renamed.path();
        lastReplayPath = ReplayCatalogue.remapLastReplay(lastReplayPath, entryPath,
            renamed.path());
        reloadEntries();
        System.out.println("[pz-replay] replay renamed from=" + entryPath
            + " to=" + renamed.path());
        return true;
    }

    public static boolean delete(String path) {
        clearOperation();
        if (PZReplayLua.isRecording()) {
            return failOperation("recording is active");
        }
        String entryPath = existingPath(path);
        if (entryPath == null) {
            return failOperation("replay is no longer available");
        }
        if (isActive(entryPath)) {
            return failOperation("replay is being played");
        }

        ReplayFileManager.Result deleted = ReplayFileManager.delete(
            ReplayStorage.directory(), entryPath);
        if (!deleted.ok()) {
            return failOperation(deleted.error());
        }

        lastReplayPath = ReplayCatalogue.remapLastReplay(lastReplayPath, entryPath, "");
        reloadEntries();
        System.out.println("[pz-replay] replay deleted path=" + entryPath);
        return true;
    }

    public static String getLastOperationPath() {
        return lastOperationPath == null ? "" : lastOperationPath;
    }

    public static String getLastOperationError() {
        return lastOperationError == null ? "" : lastOperationError;
    }

    public static boolean hasPath(String path) {
        return existingPath(path) != null;
    }

    public static int selectionIndex(String preferredPath, int fallbackIndex) {
        return ReplayCatalogueView.selectionIndex(view, preferredPath, fallbackIndex);
    }

    public static String getFileName(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.fileName();
    }

    public static String getPath(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.path();
    }

    public static long getModifiedMillis(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? 0L : entry.modifiedMillis();
    }

    public static long getSizeBytes(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? 0L : entry.sizeBytes();
    }

    public static double getDuration(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? 0.0 : entry.durationSeconds();
    }

    public static double getSampleRate(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? 0.0 : entry.sampleRateHz();
    }

    public static String getMap(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.mapName();
    }

    public static String getSave(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.saveName();
    }

    public static String getGameMode(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.gameMode();
    }

    public static String getPlayer(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.playerName();
    }

    public static boolean isValid(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry != null && entry.valid();
    }

    public static String getRowError(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry == null ? "" : entry.error();
    }

    public static boolean isLastReplay(int index) {
        ReplayCatalogue.Entry entry = entry(index);
        return entry != null && entry.lastReplay();
    }

    private static void adoptRecorderLastReplay() {
        String recorderPath = PZReplayLua.getLastReplayPath();
        if (recorderLastPath == null || !recorderLastPath.equals(recorderPath)) {
            recorderLastPath = recorderPath;
            lastReplayPath = recorderPath;
        }
    }

    private static void reloadEntries() {
        try {
            result = ReplayCatalogue.list(ReplayStorage.directory(), lastReplayPath);
        } catch (RuntimeException exception) {
            result = new ReplayCatalogue.Result(List.of(),
                "cannot list replays: " + exception.getMessage());
        }
        view = ReplayCatalogueView.apply(result.entries(), searchText, sortField,
            sortAscending);
    }

    private static String existingPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        for (ReplayCatalogue.Entry entry : result.entries()) {
            if (samePath(entry.path(), path)) {
                return entry.path();
            }
        }
        return null;
    }

    private static boolean isActive(String path) {
        String active = ReplayPlaybackApi.getCurrentPath();
        return !active.isBlank() && samePath(active, path);
    }

    private static boolean samePath(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException exception) {
            return path;
        }
    }

    private static void clearOperation() {
        lastOperationPath = "";
        lastOperationError = "";
    }

    private static boolean failOperation(String error) {
        lastOperationError = error == null ? "" : error;
        System.out.println("[pz-replay] replay management failed error="
            + lastOperationError);
        return false;
    }

    private static ReplayCatalogue.Entry entry(int index) {
        if (index < 0 || index >= view.size()) {
            return null;
        }
        return view.get(index);
    }
}
