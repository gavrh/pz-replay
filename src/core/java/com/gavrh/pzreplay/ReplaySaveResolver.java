package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplaySaveResolver {
    private static final long SAVE_PROGRESS_TOLERANCE_MILLIS = 5000L;

    public record SaveDescriptor(
        String folder,
        String saveName,
        String gameMode,
        String mapName,
        List<String> players,
        List<String> deadPlayers,
        long lastModifiedMillis
    ) {
        public SaveDescriptor {
            folder = safe(folder);
            saveName = safe(saveName);
            gameMode = safe(gameMode);
            mapName = safe(mapName);
            players = copy(players);
            deadPlayers = copy(deadPlayers);
        }

        public SaveDescriptor(String folder, String saveName, String gameMode, String mapName,
            List<String> players, long lastModifiedMillis) {
            this(folder, saveName, gameMode, mapName, players, List.of(), lastModifiedMillis);
        }
    }

    public record Result(
        boolean resolved,
        String errorCode,
        String errorMessage,
        String saveFolder,
        String saveName,
        String gameMode,
        String mapName,
        String playerName,
        List<String> warnings
    ) {
        public Result {
            errorCode = safe(errorCode);
            errorMessage = safe(errorMessage);
            saveFolder = safe(saveFolder);
            saveName = safe(saveName);
            gameMode = safe(gameMode);
            mapName = safe(mapName);
            playerName = safe(playerName);
            warnings = copy(warnings);
        }

        public static Result error(String code, String message) {
            return new Result(false, code, message, "", "", "", "", "", List.of());
        }
    }

    private ReplaySaveResolver() {
    }

    public static Result resolve(ReplayMetadata metadata, List<SaveDescriptor> saves,
        String currentGameVersion) {
        if (metadata.saveName().isBlank()) {
            return Result.error("replay-invalid", "The replay does not name a save.");
        }
        if (metadata.mapName().isBlank()) {
            return Result.error("replay-invalid", "The replay does not name a map.");
        }
        if (metadata.playerName().isBlank()) {
            return Result.error("replay-invalid", "The replay does not name a player.");
        }

        List<SaveDescriptor> named = new ArrayList<>();
        for (SaveDescriptor save : saves == null ? List.<SaveDescriptor>of() : saves) {
            if (!save.folder().isBlank() && !save.saveName().isBlank()
                && save.saveName().equals(metadata.saveName())) {
                named.add(save);
            }
        }
        if (named.isEmpty()) {
            return Result.error("save-not-found",
                "No save named \"" + metadata.saveName() + "\" was found.");
        }

        List<SaveDescriptor> candidates = named;
        if (!metadata.gameMode().isBlank()) {
            candidates = new ArrayList<>();
            for (SaveDescriptor save : named) {
                if (save.gameMode().equals(metadata.gameMode())) {
                    candidates.add(save);
                }
            }
            if (candidates.isEmpty()) {
                return Result.error("game-mode-mismatch",
                    "The save \"" + metadata.saveName() + "\" exists in "
                        + String.join(", ", gameModes(named))
                        + " but the replay was recorded in " + metadata.gameMode() + ".");
            }
        }

        if (candidates.size() > 1) {
            return Result.error("save-ambiguous",
                "More than one save named \"" + metadata.saveName()
                    + "\" matches the replay.");
        }

        SaveDescriptor save = candidates.get(0);
        if (save.gameMode().isBlank() || save.mapName().isBlank()) {
            return Result.error("save-incomplete",
                "The save \"" + metadata.saveName() + "\" is incomplete.");
        }
        if (!mapMatches(metadata.mapName(), save.mapName())) {
            return Result.error("map-mismatch",
                "The save map \"" + save.mapName() + "\" does not match the replay map \""
                    + metadata.mapName() + "\".");
        }
        if (save.players().isEmpty()) {
            return Result.error("save-incomplete",
                "The save \"" + metadata.saveName() + "\" has no player.");
        }
        if (!save.players().contains(metadata.playerName())) {
            return Result.error("player-mismatch",
                "The recorded player \"" + metadata.playerName()
                    + "\" is not in the save.");
        }
        if (save.deadPlayers().contains(metadata.playerName())) {
            return Result.error("player-dead",
                "The recorded player \"" + metadata.playerName() + "\" is dead in the save.");
        }

        List<String> warnings = new ArrayList<>();
        checkGameVersion(metadata.gameVersion(), currentGameVersion, warnings);
        if (save.lastModifiedMillis() > 0L
            && save.lastModifiedMillis() > metadata.endTimeMillis() + SAVE_PROGRESS_TOLERANCE_MILLIS) {
            warnings.add("save has advanced since the recording");
        }
        warnings.add("active mods could not be verified");
        warnings.add("start position could not be verified");
        warnings.addAll(metadata.appearance().warnings());
        warnings.addAll(metadata.equipment().warnings());

        return new Result(true, "", "", save.folder(), save.saveName(), save.gameMode(),
            save.mapName(), metadata.playerName(), warnings);
    }

    private static void checkGameVersion(String replay, String current, List<String> warnings) {
        if (replay.isBlank() || replay.equals("unknown")) {
            warnings.add("replay has no game version");
        } else if (!current.isBlank() && !current.equals("unknown") && !replay.equals(current)) {
            warnings.add("game version mismatch replay=" + replay + " current=" + current);
        }
    }

    private static boolean mapMatches(String replay, String save) {
        for (String part : save.split(";")) {
            if (part.trim().equals(replay)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> gameModes(List<SaveDescriptor> saves) {
        Set<String> modes = new LinkedHashSet<>();
        for (SaveDescriptor save : saves) {
            if (!save.gameMode().isBlank()) {
                modes.add(save.gameMode());
            }
        }
        return modes.isEmpty() ? List.of("unknown") : List.copyOf(modes);
    }

    private static List<String> copy(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                copy.add(value);
            }
        }
        return List.copyOf(copy);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
