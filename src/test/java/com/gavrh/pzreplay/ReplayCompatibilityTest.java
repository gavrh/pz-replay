package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCompatibilityTest {
    private static final ReplayCompatibility.Context CURRENT = new ReplayCompatibility.Context(
        "42.21.0", "pz-replay-test", "Muldraugh, KY", "Sandbox");

    private static ReplayMetadata metadata(String gameVersion, String saveName, String gameMode,
        String mapName) {
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            "0.0.0-dev",
            gameVersion,
            saveName,
            gameMode,
            mapName,
            "Replay Tester",
            1000f,
            2000f,
            0f,
            30.0,
            1L,
            2L
        );
    }

    @Test
    void matchingContextHasNoWarnings() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.21.0", "pz-replay-test", "Sandbox", "Muldraugh, KY"), CURRENT);

        assertTrue(result.isCompatible());
        assertEquals(0, result.warnings().size());
    }

    @Test
    void reportsGameVersionMapSaveAndModeMismatches() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.20.0", "other-save", "Apocalypse", "West Point, KY"), CURRENT);

        assertFalse(result.isCompatible());
        assertEquals(4, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("game version mismatch"));
        assertTrue(result.warnings().get(1).contains("map mismatch"));
        assertTrue(result.warnings().get(2).contains("save mismatch"));
        assertTrue(result.warnings().get(3).contains("game mode mismatch"));
    }

    @Test
    void treatsMissingOptionalGameModeAsCompatible() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.21.0", "pz-replay-test", "", "Muldraugh, KY"), CURRENT);

        assertTrue(result.isCompatible());
    }

    @Test
    void warnsWhenRequiredReplayContextIsMissing() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("", "", "Sandbox", ""), CURRENT);

        assertFalse(result.isCompatible());
        assertEquals(3, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("no game version"));
        assertTrue(result.warnings().get(1).contains("no map"));
        assertTrue(result.warnings().get(2).contains("no save name"));
    }

    @Test
    void warnsOnUnknownGameVersion() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("unknown", "pz-replay-test", "Sandbox", "Muldraugh, KY"), CURRENT);

        assertFalse(result.isCompatible());
        assertTrue(result.warnings().get(0).contains("no game version"));
    }

    @Test
    void warnsOnUnsupportedFormatVersion() {
        ReplayMetadata unsupported = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION + 1,
            "0.0.0-dev",
            "42.21.0",
            "pz-replay-test",
            "Sandbox",
            "Muldraugh, KY",
            "Replay Tester",
            1000f,
            2000f,
            0f,
            30.0,
            1L,
            2L
        );

        ReplayCompatibility.Result result = ReplayCompatibility.check(unsupported, CURRENT);

        assertFalse(result.isCompatible());
        assertTrue(result.warnings().get(0).contains("format version mismatch"));
    }
}
