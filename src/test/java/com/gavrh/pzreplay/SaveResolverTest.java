package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveResolverTest {
    private static final String GAME_VERSION = "42.21.0";
    private static final String SAVE_NAME = "pz-replay-test";
    private static final String MAP_NAME = "Muldraugh, KY";
    private static final String PLAYER_NAME = "Replay Tester";
    private static final long RECORDING_END_MILLIS = 1_000_000L;
    private static final Appearance APPEARANCE = new Appearance(
        Appearance.CURRENT_VERSION, Base64.getEncoder().encodeToString(new byte[] { 1 }));

    private static ReplayMetadata metadata(String gameMode) {
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            "0.0.0-dev",
            GAME_VERSION,
            SAVE_NAME,
            gameMode,
            MAP_NAME,
            PLAYER_NAME,
            1000f,
            2000f,
            0f,
            30.0,
            0L,
            RECORDING_END_MILLIS,
            APPEARANCE
        );
    }

    private static SaveResolver.SaveDescriptor save(String folder, String gameMode,
        String mapName, List<String> players, long modifiedMillis) {
        return new SaveResolver.SaveDescriptor(folder, SAVE_NAME, gameMode, mapName,
            players, modifiedMillis);
    }

    private static SaveResolver.SaveDescriptor save(String folder, String gameMode) {
        return save(folder, gameMode, MAP_NAME, List.of(PLAYER_NAME), 0L);
    }

    @Test
    void resolvesSaveByNameAndGameMode() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox")), GAME_VERSION);

        assertTrue(result.resolved());
        assertEquals("Sandbox/pz-replay-test", result.saveFolder());
        assertEquals(SAVE_NAME, result.saveName());
        assertEquals("Sandbox", result.gameMode());
        assertEquals(MAP_NAME, result.mapName());
        assertEquals(PLAYER_NAME, result.playerName());
        assertEquals("", result.errorCode());
        assertEquals("", result.errorMessage());
    }

    @Test
    void resolvesOlderReplayWithoutGameModeWhenOnlyOneSaveMatches() {
        SaveResolver.Result result = SaveResolver.resolve(metadata(""),
            List.of(save("Sandbox/pz-replay-test", "Sandbox")), GAME_VERSION);

        assertTrue(result.resolved());
        assertEquals("Sandbox", result.gameMode());
    }

    @Test
    void rejectsOlderReplayWithoutGameModeWhenMultipleSavesMatch() {
        SaveResolver.Result result = SaveResolver.resolve(metadata(""),
            List.of(save("Sandbox/pz-replay-test", "Sandbox"),
                save("Apocalypse/pz-replay-test", "Apocalypse")), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("save-ambiguous", result.errorCode());
        assertTrue(result.errorMessage().contains(SAVE_NAME));
    }

    @Test
    void rejectsMissingSaveWithoutFallingBackToSimilarName() {
        SaveResolver.SaveDescriptor similar = new SaveResolver.SaveDescriptor(
            "Sandbox/pz-replay-test-2", SAVE_NAME + "-2", "Sandbox", MAP_NAME,
            List.of(PLAYER_NAME), 0L);

        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(similar), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("save-not-found", result.errorCode());
        assertTrue(result.errorMessage().contains(SAVE_NAME));
    }

    @Test
    void rejectsDuplicateSaveDirectories() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox"),
                save("Sandbox/pz-replay-test", "Sandbox")), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("save-ambiguous", result.errorCode());
    }

    @Test
    void ignoresStructurallyInvalidSaveEntries() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(
                new SaveResolver.SaveDescriptor("", SAVE_NAME, "Sandbox", MAP_NAME,
                    List.of(PLAYER_NAME), 0L),
                new SaveResolver.SaveDescriptor("Sandbox/empty", "", "Sandbox", MAP_NAME,
                    List.of(PLAYER_NAME), 0L),
                save("Sandbox/pz-replay-test", "Sandbox")
            ), GAME_VERSION);

        assertTrue(result.resolved());
        assertEquals("Sandbox/pz-replay-test", result.saveFolder());
    }

    @Test
    void rejectsSaveInADifferentGameMode() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Apocalypse/pz-replay-test", "Apocalypse")), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("game-mode-mismatch", result.errorCode());
        assertTrue(result.errorMessage().contains("Apocalypse"));
        assertTrue(result.errorMessage().contains("Sandbox"));
    }

    @Test
    void rejectsMapMismatch() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox", "West Point, KY",
                List.of(PLAYER_NAME), 0L)), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("map-mismatch", result.errorCode());
    }

    @Test
    void acceptsReplayMapWhenSaveListsMultipleMaps() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox",
                "Muldraugh, KY;West Point, KY", List.of(PLAYER_NAME), 0L)), GAME_VERSION);

        assertTrue(result.resolved());
    }

    @Test
    void rejectsPlayerMismatch() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox", MAP_NAME,
                List.of("Someone Else"), 0L)), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("player-mismatch", result.errorCode());
        assertTrue(result.errorMessage().contains(PLAYER_NAME));
    }

    @Test
    void rejectsSaveWithoutPlayers() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox", MAP_NAME, List.of(), 0L)),
            GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("save-incomplete", result.errorCode());
        assertTrue(result.errorMessage().contains("no player"));
    }

    @Test
    void rejectsDeadRecordedPlayer() {
        SaveResolver.SaveDescriptor save = new SaveResolver.SaveDescriptor(
            "Sandbox/pz-replay-test", SAVE_NAME, "Sandbox", MAP_NAME,
            List.of(PLAYER_NAME, "Other"), List.of(PLAYER_NAME), 0L);

        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save), GAME_VERSION);

        assertFalse(result.resolved());
        assertEquals("player-dead", result.errorCode());
    }

    @Test
    void warnsWhenGameVersionDiffers() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox")), "42.20.0");

        assertTrue(result.resolved());
        assertTrue(result.warnings().stream()
            .anyMatch(warning -> warning.contains("game version mismatch")));
    }

    @Test
    void warnsWhenSaveHasAdvancedSinceRecording() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox", MAP_NAME,
                List.of(PLAYER_NAME), RECORDING_END_MILLIS + 10_000L)), GAME_VERSION);

        assertTrue(result.resolved());
        assertTrue(result.warnings().contains("save has advanced since the recording"));
    }

    @Test
    void reportsWarningsWithoutBlockingPlayback() {
        SaveResolver.Result result = SaveResolver.resolve(metadata("Sandbox"),
            List.of(save("Sandbox/pz-replay-test", "Sandbox")), "42.20.0");

        assertTrue(result.resolved());
        assertTrue(result.warnings().contains("active mods could not be verified"));
        assertTrue(result.warnings().contains("start position could not be verified"));
    }

    @Test
    void reportsMissingHeldItemDataWithoutBlockingPlayback() {
        ReplayMetadata metadata = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            "0.0.0-dev",
            GAME_VERSION,
            SAVE_NAME,
            "Sandbox",
            MAP_NAME,
            PLAYER_NAME,
            1000f,
            2000f,
            0f,
            30.0,
            0L,
            RECORDING_END_MILLIS,
            APPEARANCE,
            Equipment.none());
        SaveResolver.Result result = SaveResolver.resolve(metadata,
            List.of(save("Sandbox/pz-replay-test", "Sandbox")), GAME_VERSION);

        assertTrue(result.resolved());
        assertTrue(result.warnings().contains("replay has no held-item data"));
    }

    @Test
    void rejectsReplayWithoutSaveMapOrPlayerMetadata() {
        ReplayMetadata missingSave = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION, "0.0.0-dev", GAME_VERSION, "", "Sandbox",
            MAP_NAME, PLAYER_NAME, 0f, 0f, 0f, 30.0, 0L, RECORDING_END_MILLIS, APPEARANCE);
        ReplayMetadata missingMap = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION, "0.0.0-dev", GAME_VERSION, SAVE_NAME,
            "Sandbox", "", PLAYER_NAME, 0f, 0f, 0f, 30.0, 0L, RECORDING_END_MILLIS, APPEARANCE);
        ReplayMetadata missingPlayer = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION, "0.0.0-dev", GAME_VERSION, SAVE_NAME,
            "Sandbox", MAP_NAME, "", 0f, 0f, 0f, 30.0, 0L, RECORDING_END_MILLIS, APPEARANCE);

        assertEquals("replay-invalid",
            SaveResolver.resolve(missingSave, List.of(), GAME_VERSION).errorCode());
        assertEquals("replay-invalid",
            SaveResolver.resolve(missingMap, List.of(), GAME_VERSION).errorCode());
        assertEquals("replay-invalid",
            SaveResolver.resolve(missingPlayer, List.of(), GAME_VERSION).errorCode());
    }
}
