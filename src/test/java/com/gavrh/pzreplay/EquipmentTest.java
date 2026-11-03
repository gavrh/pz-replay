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

class EquipmentTest {
    private static Equipment equipment(String value) {
        return new Equipment(Equipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingHeldItemsWarnButDecodeNothing() {
        Equipment equipment = Equipment.none();

        assertFalse(equipment.isPresent());
        assertEquals(1, equipment.warnings().size());
        assertTrue(equipment.warnings().get(0).contains("no held-item data"));
        assertThrows(ReplayFormatException.class, equipment::decode);
    }

    @Test
    void currentHeldItemsDecodeAndHaveNoWarnings() throws Exception {
        Equipment equipment = equipment("snapshot");

        assertTrue(equipment.isPresent());
        assertTrue(equipment.isCurrent());
        assertEquals("snapshot",
            new String(equipment.decode(), StandardCharsets.UTF_8));
        assertTrue(equipment.warnings().isEmpty());
    }

    @Test
    void emptyCurrentPayloadIsIncomplete() {
        Equipment equipment = new Equipment(Equipment.CURRENT_VERSION, "");

        assertEquals(1, equipment.warnings().size());
        assertTrue(equipment.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void unsupportedVersionWarnsWithoutDecoding() {
        Equipment equipment = new Equipment(
            Equipment.CURRENT_VERSION + 1, "AQ==");

        assertTrue(equipment.isPresent());
        assertFalse(equipment.isCurrent());
        assertEquals(1, equipment.warnings().size());
        assertTrue(equipment.warnings().get(0).contains("version is unsupported"));
        assertThrows(ReplayFormatException.class, equipment::decode);
    }

    @Test
    void corruptBase64WarnsWithoutCrashing() {
        Equipment equipment = new Equipment(
            Equipment.CURRENT_VERSION, "not base64!");

        assertEquals(1, equipment.warnings().size());
        assertTrue(equipment.warnings().get(0).contains("corrupt or incomplete"));
        assertThrows(ReplayFormatException.class, equipment::decode);
    }

    @Test
    void unparseableVersionIsCorruptRatherThanAFatalHeaderError() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("equipment_version", "not-a-number");
        values.put("equipment_data", "AQ==");

        Equipment equipment = Equipment.fromValues(values);

        assertTrue(equipment.isPresent());
        assertEquals(-1, equipment.version());
        assertEquals(1, equipment.warnings().size());
        assertTrue(equipment.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void metadataKeepsHeldItemsWhenEndTimeIsUpdated() {
        Equipment equipment = equipment("snapshot");
        ReplayMetadata metadata = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION, "0.0.0-dev", "42.21.0", "save", "Sandbox",
            "map", "player", 1f, 2f, 0f, 30.0, 1L, 2L, Appearance.none(), equipment);

        assertEquals(equipment, metadata.withEnd(99L).equipment());
    }

    @Test
    void metadataOmitsHeldItemKeysWhenMissing() {
        ReplayMetadata metadata = new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION, "0.0.0-dev", "42.21.0", "save", "Sandbox",
            "map", "player", 1f, 2f, 0f, 30.0, 1L, 2L, Appearance.none(),
            Equipment.none());

        assertFalse(metadata.toValues().containsKey("equipment_version"));
        assertFalse(metadata.toValues().containsKey("equipment_data"));
        assertEquals(Equipment.none(), Equipment.fromValues(Map.of()));
    }
}
