package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentChangesTest {
    private static Equipment equipment(String value) {
        return new Equipment(Equipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Equipment equipment(byte[] value) {
        return new Equipment(Equipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value));
    }

    @Test
    void roundTripsChangesWithEqualTimestampsAndSequenceOrder() throws Exception {
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 0.0f, equipment("initial")),
            new EquipmentChange(2L, 1.5f, equipment("pistol")),
            new EquipmentChange(3L, 1.5f, equipment("rifle")),
            new EquipmentChange(4L, 4.0f, equipment("none")));

        EquipmentChanges encoded = EquipmentChanges.encode(changes);
        EquipmentChangeCodec.Result decoded = encoded.decode();

        assertEquals(changes, decoded.changes());
        assertTrue(decoded.warnings().isEmpty());
        assertTrue(encoded.warnings().isEmpty());
    }

    @Test
    void encodesAndDecodesASingleT0Change() throws Exception {
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 0.0f, equipment("initial")));

        EquipmentChanges encoded = EquipmentChanges.encode(changes);

        assertTrue(encoded.isPresent());
        assertEquals(changes, encoded.decode().changes());
    }

    @Test
    void emptyListEncodesAsAbsentMetadata() throws Exception {
        assertFalse(EquipmentChanges.encode(List.of()).isPresent());
        assertFalse(EquipmentChanges.encode(null).isPresent());
    }

    @Test
    void metadataOmitsKeysWhenNoChangesExistAndRoundTripsWhenPresent() throws Exception {
        ReplayMetadata metadata = metadata(EquipmentChanges.none());

        assertFalse(metadata.toValues().containsKey("equipment_changes_version"));
        assertFalse(metadata.toValues().containsKey("equipment_changes_data"));
        assertTrue(EquipmentChanges.fromValues(Map.of()).warnings().isEmpty());

        EquipmentChanges changes = EquipmentChanges.encode(List.of(
            new EquipmentChange(1L, 0.0f, equipment("initial")),
            new EquipmentChange(2L, 1.0f, equipment("pistol"))));
        ReplayMetadata withChanges = metadata.withEquipmentChanges(changes);

        assertTrue(withChanges.toValues().containsKey("equipment_changes_version"));
        assertTrue(withChanges.toValues().containsKey("equipment_changes_data"));
        assertEquals(changes,
            ReplayMetadata.fromValues(withChanges.toValues()).equipmentChanges());
    }

    @Test
    void unsupportedTimelineVersionWarnsWithoutThrowing() {
        EquipmentChanges changes = new EquipmentChanges(
            EquipmentChanges.CURRENT_VERSION + 1, "AQ==");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("version is unsupported"));
        assertThrows(ReplayFormatException.class, changes::decode);
    }

    @Test
    void corruptBase64WarnsWithoutThrowing() {
        EquipmentChanges changes = new EquipmentChanges(
            EquipmentChanges.CURRENT_VERSION, "not base64!");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void emptyTimelinePayloadIsCorrupt() {
        EquipmentChanges changes = new EquipmentChanges(
            EquipmentChanges.CURRENT_VERSION, "");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void truncatedPayloadIsRejected() throws Exception {
        byte[] bytes = rawPayload(List.of(
            new EquipmentChange(1L, 0.0f, equipment("initial")),
            new EquipmentChange(2L, 1.0f, equipment("pistol"))), -1, null, null);
        byte[] truncated = new byte[bytes.length - 2];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);

        assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.decode(truncated));
    }

    @Test
    void decodeSkipsInvalidTimesAndUnknownSnapshotVersions() throws Exception {
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 1.0f, equipment("valid")),
            new EquipmentChange(2L, 2.0f, equipment("unknown-version")),
            new EquipmentChange(3L, 3.0f, equipment("invalid-time")));

        EquipmentChangeCodec.Result result = EquipmentChangeCodec.decode(
            rawPayload(changes, 1, Equipment.CURRENT_VERSION + 1, Float.NaN));

        assertEquals(1, result.changes().size());
        assertEquals(1L, result.changes().get(0).sequence());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unsupported snapshot version"));
        assertTrue(result.warnings().get(1).contains("invalid equipment change"));
    }

    @Test
    void decodeWarnsWhenChangesAreOutOfOrder() throws Exception {
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 1.0f, equipment("initial")),
            new EquipmentChange(2L, 2.0f, equipment("pistol")),
            new EquipmentChange(3L, 3.0f, equipment("rifle")));
        byte[] bytes = rawPayload(changes, 2, null, 0.5f);

        EquipmentChangeCodec.Result result = EquipmentChangeCodec.decode(bytes);

        assertEquals(3, result.changes().size());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("out of order"));
    }

    @Test
    void decodeRejectsTrailingData() throws Exception {
        byte[] bytes = rawPayload(List.of(
            new EquipmentChange(1L, 0.0f, equipment("initial"))), -1, null, null);
        byte[] extended = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, extended, 0, bytes.length);

        assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.decode(extended));
    }

    @Test
    void encodeRejectsTooManyChanges() {
        List<EquipmentChange> changes = new ArrayList<>();
        for (int index = 0; index < EquipmentChangeCodec.MAX_CHANGES + 1; index++) {
            changes.add(new EquipmentChange(index, index, equipment("x")));
        }

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("too many equipment changes"));
    }

    @Test
    void encodeRejectsOversizedSnapshot() {
        byte[] oversized = new byte[EquipmentChangeCodec.MAX_SNAPSHOT_BYTES + 1];
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 0.0f, equipment(oversized)));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("invalid snapshot size"));
    }

    @Test
    void encodeRejectsTotalSnapshotAboveLimit() {
        byte[] snapshot = new byte[EquipmentChangeCodec.MAX_SNAPSHOT_BYTES];
        byte[] extra = new byte[1];
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 0.0f, equipment(snapshot)),
            new EquipmentChange(2L, 1.0f, equipment(snapshot)),
            new EquipmentChange(3L, 2.0f, equipment(extra)));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("too large"));
    }

    @Test
    void encodeRejectsUnsupportedSnapshotVersion() {
        Equipment unsupported = new Equipment(
            Equipment.CURRENT_VERSION + 1, "AQ==");
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 0.0f, unsupported));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("unsupported snapshot version"));
    }

    @Test
    void encodeRejectsOutOfOrderTimestamps() {
        List<EquipmentChange> changes = List.of(
            new EquipmentChange(1L, 2.0f, equipment("late")),
            new EquipmentChange(2L, 1.0f, equipment("early")));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> EquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("out of order"));
    }

    @Test
    void changeDataContainsOnlySerializableValues() {
        for (RecordComponent component : EquipmentChange.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertTrue(type == long.class || type == float.class || type == Equipment.class,
                component.getName() + " has live type " + type);
        }
        for (RecordComponent component : Equipment.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertTrue(type == int.class || type == String.class,
                component.getName() + " has live type " + type);
        }
    }

    private static ReplayMetadata metadata(EquipmentChanges changes) {
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            "0.0.0-dev",
            "42.21.0",
            "save",
            "Sandbox",
            "map",
            "player",
            1f,
            2f,
            0f,
            30.0,
            1L,
            2L,
            Appearance.none()
        ).withEquipmentChanges(changes);
    }

    private static byte[] rawPayload(List<EquipmentChange> changes,
        int snapshotVersionIndex, Integer snapshotVersionOverride, Float timeOverride)
        throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(EquipmentChangeCodec.CURRENT_VERSION);
            out.writeInt(changes.size());
            for (int index = 0; index < changes.size(); index++) {
                EquipmentChange change = changes.get(index);
                byte[] snapshot = change.equipment().decode();
                int snapshotVersion = index == snapshotVersionIndex && snapshotVersionOverride != null
                    ? snapshotVersionOverride : change.equipment().version();
                float time = index == 2 && timeOverride != null
                    ? timeOverride : change.timeSeconds();
                out.writeInt((int) change.sequence());
                out.writeFloat(time);
                out.writeShort(snapshotVersion);
                out.writeInt(snapshot.length);
                out.write(snapshot);
            }
        }
        return payload.toByteArray();
    }
}
