package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class ReplayCompatibility {
    public record Context(
        String gameVersion,
        String saveName,
        String mapName,
        String gameMode
    ) {
        public Context {
            gameVersion = safe(gameVersion);
            saveName = safe(saveName);
            mapName = safe(mapName);
            gameMode = safe(gameMode);
        }
    }

    public record Result(List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }

        public boolean isCompatible() {
            return warnings.isEmpty();
        }
    }

    private ReplayCompatibility() {
    }

    public static Result check(ReplayMetadata metadata, Context current) {
        List<String> warnings = new ArrayList<>();
        if (metadata.formatVersion() != ReplayMetadata.CURRENT_FORMAT_VERSION) {
            warnings.add("format version mismatch replay=" + metadata.formatVersion()
                + " current=" + ReplayMetadata.CURRENT_FORMAT_VERSION);
        }
        checkGameVersion(metadata.gameVersion(), current.gameVersion(), warnings);
        checkMap(metadata.mapName(), current.mapName(), warnings);
        checkSave(metadata.saveName(), current.saveName(), warnings);
        checkGameMode(metadata.gameMode(), current.gameMode(), warnings);
        warnings.addAll(metadata.appearance().warnings());
        warnings.addAll(metadata.appearanceChanges().warnings());
        warnings.addAll(metadata.equipment().warnings());
        warnings.addAll(metadata.events().warnings());
        return new Result(warnings);
    }

    private static void checkGameVersion(String replay, String current, List<String> warnings) {
        if (replay.isEmpty() || replay.equals("unknown")) {
            warnings.add("replay has no game version");
        } else if (!current.isEmpty() && !current.equals("unknown") && !replay.equals(current)) {
            warnings.add("game version mismatch replay=" + replay + " current=" + current);
        }
    }

    private static void checkMap(String replay, String current, List<String> warnings) {
        if (replay.isEmpty()) {
            warnings.add("replay has no map");
        } else if (!current.isEmpty() && !replay.equals(current)) {
            warnings.add("map mismatch replay=" + replay + " current=" + current);
        }
    }

    private static void checkSave(String replay, String current, List<String> warnings) {
        if (replay.isEmpty()) {
            warnings.add("replay has no save name");
        } else if (!current.isEmpty() && !replay.equals(current)) {
            warnings.add("save mismatch replay=" + replay + " current=" + current);
        }
    }

    private static void checkGameMode(String replay, String current, List<String> warnings) {
        if (!replay.isEmpty() && !current.isEmpty() && !replay.equals(current)) {
            warnings.add("game mode mismatch replay=" + replay + " current=" + current);
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
