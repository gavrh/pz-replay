package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceTest {
    private static Appearance appearance(String value) {
        return new Appearance(Appearance.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingAppearanceWarnsButDecodesNothing() {
        Appearance appearance = Appearance.none();

        assertFalse(appearance.isPresent());
        assertEquals(1, appearance.warnings().size());
        assertTrue(appearance.warnings().get(0).contains("no appearance data"));
        assertThrows(ReplayFormatException.class, appearance::decode);
    }

    @Test
    void currentAppearanceDecodesAndHasNoWarnings() throws Exception {
        Appearance appearance = appearance("snapshot");

        assertTrue(appearance.isPresent());
        assertTrue(appearance.isCurrent());
        assertEquals("snapshot",
            new String(appearance.decode(), StandardCharsets.UTF_8));
        assertTrue(appearance.warnings().isEmpty());
    }

    @Test
    void unsupportedVersionWarnsWithoutDecoding() {
        Appearance appearance = new Appearance(
            Appearance.CURRENT_VERSION + 1, "AQ==");

        assertTrue(appearance.isPresent());
        assertFalse(appearance.isCurrent());
        assertEquals(1, appearance.warnings().size());
        assertTrue(appearance.warnings().get(0).contains("version is unsupported"));
        assertThrows(ReplayFormatException.class, appearance::decode);
    }

    @Test
    void corruptBase64WarnsWithoutCrashing() {
        Appearance appearance = new Appearance(
            Appearance.CURRENT_VERSION, "not base64!");

        assertEquals(1, appearance.warnings().size());
        assertTrue(appearance.warnings().get(0).contains("corrupt or incomplete"));
        assertThrows(ReplayFormatException.class, appearance::decode);
    }

    @Test
    void emptyDataIsIncomplete() {
        Appearance appearance = new Appearance(Appearance.CURRENT_VERSION, "");

        assertEquals(1, appearance.warnings().size());
        assertTrue(appearance.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void unparseableVersionIsCorruptRatherThanAFatalHeaderError() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("appearance_version", "not-a-number");
        values.put("appearance_data", "AQ==");

        Appearance appearance = Appearance.fromValues(values);

        assertTrue(appearance.isPresent());
        assertEquals(-1, appearance.version());
        assertEquals(1, appearance.warnings().size());
        assertTrue(appearance.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void metadataOmitsAppearanceKeysWhenMissing() {
        ReplayMetadata metadata = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION, "0.0.0-dev", "42.21.0", "save", "Sandbox",
            "map", "player", 1f, 2f, 0f, 30.0, 1L, 2L, Appearance.none());

        assertFalse(metadata.toValues().containsKey("appearance_version"));
        assertFalse(metadata.toValues().containsKey("appearance_data"));
        assertEquals(Appearance.none(), Appearance.fromValues(Map.of()));
    }
}
