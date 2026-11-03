package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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

class AppearanceChangesTest {
    private static Appearance appearance(String value) {
        return new Appearance(Appearance.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Appearance appearance(byte[] value) {
        return new Appearance(Appearance.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value));
    }

    @Test
    void roundTripsChangesWithEqualTimestampsAndSequenceOrder() throws Exception {
        List<AppearanceChange> changes = List.of(
            new AppearanceChange(1L, 1.5f, appearance("first")),
            new AppearanceChange(2L, 1.5f, appearance("second")),
            new AppearanceChange(3L, 4.0f, appearance("third")));

        AppearanceChanges encoded = AppearanceChanges.encode(changes);
        AppearanceChangeCodec.Result decoded = encoded.decode();

        assertEquals(changes, decoded.changes());
        assertTrue(decoded.warnings().isEmpty());
        assertTrue(encoded.warnings().isEmpty());
    }

    @Test
    void metadataOmitsKeysWhenNoChangesExist() {
        ReplayMetadata metadata = metadata(AppearanceChanges.none());

        assertFalse(metadata.toValues().containsKey("appearance_changes_version"));
        assertFalse(metadata.toValues().containsKey("appearance_changes_data"));
        assertTrue(AppearanceChanges.fromValues(Map.of()).warnings().isEmpty());
    }

    @Test
    void unsupportedTimelineVersionWarnsWithoutThrowing() {
        AppearanceChanges changes = new AppearanceChanges(
            AppearanceChanges.CURRENT_VERSION + 1, "AQ==");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("version is unsupported"));
        assertThrows(ReplayFormatException.class, changes::decode);
    }

    @Test
    void corruptBase64WarnsWithoutThrowing() {
        AppearanceChanges changes = new AppearanceChanges(
            AppearanceChanges.CURRENT_VERSION, "not base64!");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void emptyTimelinePayloadIsCorrupt() {
        AppearanceChanges changes = new AppearanceChanges(
            AppearanceChanges.CURRENT_VERSION, "");

        assertEquals(1, changes.warnings().size());
        assertTrue(changes.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void decodeSkipsInvalidTimesAndUnknownSnapshotVersions() throws Exception {
        List<AppearanceChange> changes = List.of(
            new AppearanceChange(1L, 1.0f, appearance("valid")),
            new AppearanceChange(2L, 2.0f, appearance("unknown-version")),
            new AppearanceChange(3L, Float.NaN, appearance("invalid-time")));

        AppearanceChangeCodec.Result result =
            AppearanceChangeCodec.decode(rawPayload(changes, 2, Float.NaN));

        assertEquals(1, result.changes().size());
        assertEquals(1L, result.changes().get(0).sequence());
        assertEquals(2, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unsupported snapshot version"));
        assertTrue(result.warnings().get(1).contains("invalid appearance change"));
    }

    @Test
    void decodeRejectsTrailingData() throws Exception {
        byte[] bytes = rawPayload(List.of(
            new AppearanceChange(1L, 1.0f, appearance("valid"))), 1, null);
        byte[] extended = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, extended, 0, bytes.length);

        assertThrows(ReplayFormatException.class,
            () -> AppearanceChangeCodec.decode(extended));
    }

    @Test
    void encodeRejectsTooManyChanges() {
        List<AppearanceChange> changes = new ArrayList<>();
        for (int index = 0; index < AppearanceChangeCodec.MAX_CHANGES + 1; index++) {
            changes.add(new AppearanceChange(index, index, appearance("x")));
        }

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> AppearanceChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("too many appearance changes"));
    }

    @Test
    void encodeRejectsOversizedSnapshot() {
        byte[] oversized = new byte[AppearanceChangeCodec.MAX_SNAPSHOT_BYTES + 1];
        List<AppearanceChange> changes = List.of(
            new AppearanceChange(1L, 1.0f, appearance(oversized)));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> AppearanceChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("invalid snapshot size"));
    }

    @Test
    void encodeRejectsTotalSnapshotAboveLimit() {
        byte[] half = new byte[AppearanceChangeCodec.MAX_TOTAL_SNAPSHOT_BYTES / 2 + 1];
        List<AppearanceChange> changes = List.of(
            new AppearanceChange(1L, 1.0f, appearance(half)),
            new AppearanceChange(2L, 2.0f, appearance(half)));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> AppearanceChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("too large"));
    }

    @Test
    void encodeRejectsUnsupportedSnapshotVersion() {
        Appearance unsupported = new Appearance(
            Appearance.CURRENT_VERSION + 1, "AQ==");
        List<AppearanceChange> changes = List.of(
            new AppearanceChange(1L, 1.0f, unsupported));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> AppearanceChangeCodec.encode(changes));
        assertTrue(exception.getMessage().contains("unsupported snapshot version"));
    }

    private static ReplayMetadata metadata(AppearanceChanges changes) {
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
            appearance("initial"),
            changes,
            Equipment.none(),
            ActionEvents.none()
        );
    }

    private static byte[] rawPayload(List<AppearanceChange> changes,
        int snapshotVersionOverride, Float timeOverride) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(AppearanceChangeCodec.CURRENT_VERSION);
            out.writeInt(changes.size());
            for (int index = 0; index < changes.size(); index++) {
                AppearanceChange change = changes.get(index);
                byte[] snapshot = change.appearance().decode();
                int snapshotVersion = index == 1
                    ? snapshotVersionOverride : change.appearance().version();
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
