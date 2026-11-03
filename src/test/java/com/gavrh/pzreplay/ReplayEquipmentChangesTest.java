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

class ReplayEquipmentChangesTest {
    private static ReplayEquipment equipment(String value) {
        return new ReplayEquipment(ReplayEquipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static ReplayEquipment equipment(byte[] value) {
        return new ReplayEquipment(ReplayEquipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value));
    }

    @Test
    void roundTripsChangesWithEqualTimestampsAndSequenceOrder() throws Exception {
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment("initial")),
            new ReplayEquipmentChange(2L, 1.5f, equipment("pistol")),
            new ReplayEquipmentChange(3L, 1.5f, equipment("rifle")),
            new ReplayEquipmentChange(4L, 4.0f, equipment("none")));

        ReplayEquipmentChanges encoded = ReplayEquipmentChanges.encode(changes);
        ReplayEquipmentChangeCodec.Result decoded = encoded.decode();

        assertEquals(changes, decoded.changes());
        assertTrue(decoded.warnings().isEmpty());
        assertTrue(encoded.warnings().isEmpty());
    }

    @Test
    void encodesAndDecodesASingleT0Change() throws Exception {
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment("initial")));

        ReplayEquipmentChanges encoded = ReplayEquipmentChanges.encode(changes);

        assertTrue(encoded.isPresent());
        assertEquals(changes, encoded.decode().changes());
    }

    @Test
    void emptyListEncodesAsAbsentMetadata() throws Exception {
        assertFalse(ReplayEquipmentChanges.encode(List.of()).isPresent());
        assertFalse(ReplayEquipmentChanges.encode(null).isPresent());
    }

    @Test
    void metadataOmitsKeysWhenNoChangesExistAndRoundTripsWhenPresent() throws Exception {
        ReplayMetadata metadata = metadata(ReplayEquipmentChanges.none());

        assertFalse(metadata.toValues().containsKey("equipment_changes_version"));
        assertFalse(metadata.toValues().containsKey("equipment_changes_data"));
        assertTrue(ReplayEquipmentChanges.fromValues(Map.of()).warnings().isEmpty());

        ReplayEquipmentChanges changes = ReplayEquipmentChanges.encode(List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment("initial")),
            new ReplayEquipmentChange(2L, 1.0f, equipment("pistol"))));
        ReplayMetadata withChanges = metadata.withEquipmentChanges(changes);

        assertTrue(withChanges.toValues().containsKey("equipment_changes_version"));
        assertTrue(withChanges.toValues().containsKey("equipment_changes_data"));
        assertEquals(changes,
            ReplayMetadata.fromValues(withChanges.toValues()).equipmentChanges());
    }

    @Test
    void unsupportedTimelineVersionWarnsWithoutThrowing() {
        ReplayEquipmentChanges changes = new ReplayEquipmentChanges(
            ReplayEquipmentChanges.CURRENT_VERSION + 1, "AQ==");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("version is unsupported"));
        assertThrows(ReplayFormatException.class, changes::decode);
    }

    @Test
    void corruptBase64WarnsWithoutThrowing() {
        ReplayEquipmentChanges changes = new ReplayEquipmentChanges(
            ReplayEquipmentChanges.CURRENT_VERSION, "not base64!");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void emptyTimelinePayloadIsCorrupt() {
        ReplayEquipmentChanges changes = new ReplayEquipmentChanges(
            ReplayEquipmentChanges.CURRENT_VERSION, "");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void truncatedPayloadIsRejected() throws Exception {
        byte[] bytes = rawPayload(List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment("initial")),
            new ReplayEquipmentChange(2L, 1.0f, equipment("pistol"))), -1, null, null);
        byte[] truncated = new byte[bytes.length - 2];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);

        assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.decode(truncated));
    }

    @Test
    void decodeSkipsInvalidTimesAndUnknownSnapshotVersions() throws Exception {
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 1.0f, equipment("valid")),
            new ReplayEquipmentChange(2L, 2.0f, equipment("unknown-version")),
            new ReplayEquipmentChange(3L, 3.0f, equipment("invalid-time")));

        ReplayEquipmentChangeCodec.Result result = ReplayEquipmentChangeCodec.decode(
            rawPayload(changes, 1, ReplayEquipment.CURRENT_VERSION + 1, Float.NaN));

        assertEquals(1, result.changes().size());
        assertEquals(1L, result.changes().get(0).sequence());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unsupported snapshot version"));
        assertTrue(result.warnings().get(1).contains("invalid equipment change"));
    }

    @Test
    void decodeWarnsWhenChangesAreOutOfOrder() throws Exception {
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 1.0f, equipment("initial")),
            new ReplayEquipmentChange(2L, 2.0f, equipment("pistol")),
            new ReplayEquipmentChange(3L, 3.0f, equipment("rifle")));
        byte[] bytes = rawPayload(changes, 2, null, 0.5f);

        ReplayEquipmentChangeCodec.Result result = ReplayEquipmentChangeCodec.decode(bytes);

        assertEquals(3, result.changes().size());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("out of order"));
    }

    @Test
    void decodeRejectsTrailingData() throws Exception {
        byte[] bytes = rawPayload(List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment("initial"))), -1, null, null);
        byte[] extended = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, extended, 0, bytes.length);

        assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.decode(extended));
    }

    @Test
    void encodeRejectsTooManyChanges() {
        List<ReplayEquipmentChange> changes = new ArrayList<>();
        for (int index = 0; index < ReplayEquipmentChangeCodec.MAX_CHANGES + 1; index++) {
            changes.add(new ReplayEquipmentChange(index, index, equipment("x")));
        }

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("too many equipment changes"));
    }

    @Test
    void encodeRejectsOversizedSnapshot() {
        byte[] oversized = new byte[ReplayEquipmentChangeCodec.MAX_SNAPSHOT_BYTES + 1];
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment(oversized)));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("invalid snapshot size"));
    }

    @Test
    void encodeRejectsTotalSnapshotAboveLimit() {
        byte[] snapshot = new byte[ReplayEquipmentChangeCodec.MAX_SNAPSHOT_BYTES];
        byte[] extra = new byte[1];
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 0.0f, equipment(snapshot)),
            new ReplayEquipmentChange(2L, 1.0f, equipment(snapshot)),
            new ReplayEquipmentChange(3L, 2.0f, equipment(extra)));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("too large"));
    }

    @Test
    void encodeRejectsUnsupportedSnapshotVersion() {
        ReplayEquipment unsupported = new ReplayEquipment(
            ReplayEquipment.CURRENT_VERSION + 1, "AQ==");
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 0.0f, unsupported));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("unsupported snapshot version"));
    }

    @Test
    void encodeRejectsOutOfOrderTimestamps() {
        List<ReplayEquipmentChange> changes = List.of(
            new ReplayEquipmentChange(1L, 2.0f, equipment("late")),
            new ReplayEquipmentChange(2L, 1.0f, equipment("early")));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> ReplayEquipmentChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("out of order"));
    }

    @Test
    void changeDataContainsOnlySerializableValues() {
        for (RecordComponent component : ReplayEquipmentChange.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertTrue(type == long.class || type == float.class || type == ReplayEquipment.class,
                component.getName() + " has live type " + type);
        }
        for (RecordComponent component : ReplayEquipment.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertTrue(type == int.class || type == String.class,
                component.getName() + " has live type " + type);
        }
    }

    private static ReplayMetadata metadata(ReplayEquipmentChanges changes) {
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
            ReplayAppearance.none()
        ).withEquipmentChanges(changes);
    }

    private static byte[] rawPayload(List<ReplayEquipmentChange> changes,
        int snapshotVersionIndex, Integer snapshotVersionOverride, Float timeOverride)
        throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(ReplayEquipmentChangeCodec.CURRENT_VERSION);
            out.writeInt(changes.size());
            for (int index = 0; index < changes.size(); index++) {
                ReplayEquipmentChange change = changes.get(index);
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
