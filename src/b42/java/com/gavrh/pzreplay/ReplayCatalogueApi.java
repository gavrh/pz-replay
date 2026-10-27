package com.gavrh.pzreplay;

import java.util.List;
import me.zed_0xff.zombie_buddy.Exposer;

@Exposer.LuaClass(name = "PZReplayCatalogue")
public final class ReplayCatalogueApi {
    private static ReplayCatalogue.Result result = new ReplayCatalogue.Result(List.of(), "");
    private static String lastReplayPath = "";

    private ReplayCatalogueApi() {
    }

    public static int refresh() {
        lastReplayPath = PZReplayLua.getLastReplayPath();
        try {
            result = ReplayCatalogue.list(ReplayStorage.directory(), lastReplayPath);
        } catch (RuntimeException exception) {
            result = new ReplayCatalogue.Result(List.of(),
                "cannot list replays: " + exception.getMessage());
        }
        System.out.println("[pz-replay] catalogue refreshed count=" + result.entries().size()
            + " error=" + result.error());
        return result.entries().size();
    }

    public static int getCount() {
        return result.entries().size();
    }

    public static String getCatalogueError() {
        return result.error();
    }

    public static String getLastReplayPath() {
        return lastReplayPath == null ? "" : lastReplayPath;
    }

    public static String getDirectory() {
        return ReplayStorage.directory().toString();
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

    private static ReplayCatalogue.Entry entry(int index) {
        if (index < 0 || index >= result.entries().size()) {
            return null;
        }
        return result.entries().get(index);
    }
}
