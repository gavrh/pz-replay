package com.gavrh.pzreplay;

import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCompatibilityTest {
    private static final ReplayCompatibility.Context CURRENT = new ReplayCompatibility.Context(
        "42.21.0", "pz-replay-test", "Muldraugh, KY", "Sandbox");
    private static final ReplayAppearance APPEARANCE = new ReplayAppearance(
        ReplayAppearance.CURRENT_VERSION, Base64.getEncoder().encodeToString(new byte[] { 1 }));

    private static ReplayMetadata metadata(String gameVersion, String saveName, String gameMode,
        String mapName) {
        return metadata(gameVersion, saveName, gameMode, mapName, APPEARANCE);
    }

    private static ReplayMetadata metadata(String gameVersion, String saveName, String gameMode,
        String mapName, ReplayAppearance appearance) {
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
            2L,
            appearance
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
            2L,
            APPEARANCE
        );

        ReplayCompatibility.Result result = ReplayCompatibility.check(unsupported, CURRENT);

        assertFalse(result.isCompatible());
        assertTrue(result.warnings().get(0).contains("format version mismatch"));
    }

    @Test
    void warnsWhenAppearanceIsMissing() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.21.0", "pz-replay-test", "Sandbox", "Muldraugh, KY",
                ReplayAppearance.none()), CURRENT);

        assertFalse(result.isCompatible());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("no appearance data"));
    }

    @Test
    void warnsOnUnsupportedAppearanceVersion() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.21.0", "pz-replay-test", "Sandbox", "Muldraugh, KY",
                new ReplayAppearance(ReplayAppearance.CURRENT_VERSION + 1, "AQ==")), CURRENT);

        assertFalse(result.isCompatible());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("appearance data version is unsupported"));
    }

    @Test
    void warnsOnCorruptAppearance() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.21.0", "pz-replay-test", "Sandbox", "Muldraugh, KY",
                new ReplayAppearance(ReplayAppearance.CURRENT_VERSION, "not base64!")), CURRENT);

        assertFalse(result.isCompatible());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("appearance data is corrupt or incomplete"));
    }

    @Test
    void keepsContextWarningsWhenAppearanceIsMissing() {
        ReplayCompatibility.Result result = ReplayCompatibility.check(
            metadata("42.20.0", "other-save", "Apocalypse", "West Point, KY",
                ReplayAppearance.none()), CURRENT);

        assertEquals(5, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("game version mismatch"));
        assertTrue(result.warnings().get(1).contains("map mismatch"));
        assertTrue(result.warnings().get(2).contains("save mismatch"));
        assertTrue(result.warnings().get(3).contains("game mode mismatch"));
        assertTrue(result.warnings().get(4).contains("no appearance data"));
    }
}
