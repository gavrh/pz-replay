package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEventsTest {
    @TempDir
    Path tempDir;

    private static ActionEvent event(long sequence, float time, ActionEvent.Kind kind,
        boolean end, String detail) {
        return new ActionEvent(sequence, time, kind, end, detail, 0f, 0f);
    }

    private static CaptureSample frame(float time) {
        return new CaptureSample(0L, time, 0f, 0f, 0f, 0f, 0f, 0f,
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND, 0);
    }

    private static ReplayMetadata metadata(ActionEvents events) {
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            "0.0.0-dev",
            "42.21.0",
            "save",
            "Sandbox",
            "map",
            "player",
            0f,
            0f,
            0f,
            30.0,
            1L,
            2L,
            Appearance.none(),
            Equipment.none(),
            events
        );
    }

    @Test
    void missingEventsAreCompatibleWithOldReplays() throws Exception {
        ActionEvents events = ActionEvents.none();

        assertFalse(events.isPresent());
        assertTrue(events.warnings().isEmpty());
        assertThrows(ReplayFormatException.class, events::decode);

        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(events),
            List.of(frame(0f), frame(1f))));
        assertEquals(0, timeline.getEventCount());
        assertTrue(timeline.getEventWarnings().isEmpty());
    }

    @Test
    void newReplayRoundTripsEventsThroughTheBinaryFormat() throws Exception {
        ActionEvents events = ActionEvents.encode(List.of(
            event(1, 0.25f, ActionEvent.Kind.MELEE_ATTACK, false, "1handed"),
            event(2, 0.5f, ActionEvent.Kind.SIT, false, "ground"),
            event(3, 1.5f, ActionEvent.Kind.SIT, true, "ground")
        ));
        ReplayMetadata metadata = metadata(events);
        Path path = tempDir.resolve("events.pzrp.gz");

        BinaryReplayCodec.write(path, metadata, List.of(frame(0f), frame(2f)), true);
        ReplayData data = ReplayReader.read(path);
        PlaybackTimeline timeline = new PlaybackTimeline(data);

        assertEquals(events, data.metadata().events());
        assertEquals(3, timeline.getEventCount());
        assertEquals(List.of(1L, 2L, 3L),
            timeline.getEvents().stream().map(ActionEvent::sequence).toList());
        assertTrue(timeline.getEventWarnings().isEmpty());
        assertFalse(timeline.getEventTimeline().persistentAt(1.0).isEmpty());
    }

    @Test
    void versionOneEventsRemainReadable() throws Exception {
        ActionEvents events = new ActionEvents(1,
            Base64.getEncoder().encodeToString(versionOneEvent()));

        assertTrue(events.isCurrent());
        assertTrue(events.warnings().isEmpty());
        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(events),
            List.of(frame(0f), frame(1f))));
        assertEquals(1, timeline.getEventCount());
        assertEquals(ActionEventContext.none(),
            timeline.getEvents().get(0).context());
        assertTrue(timeline.getEventWarnings().isEmpty());
    }

    @Test
    void corruptOrUnsupportedEventDataWarnsWithoutBreakingFrames() throws Exception {
        ActionEvents corrupt = new ActionEvents(ActionEvents.CURRENT_VERSION, "not base64!");
        ActionEvents unsupported = new ActionEvents(ActionEvents.CURRENT_VERSION + 1, "AQ==");

        assertEquals(1, corrupt.warnings().size());
        assertTrue(corrupt.warnings().get(0).contains("corrupt or incomplete"));
        assertEquals(1, unsupported.warnings().size());
        assertTrue(unsupported.warnings().get(0).contains("version is unsupported"));

        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(corrupt),
            List.of(frame(0f), frame(1f))));
        assertEquals(2, timeline.getFrameCount());
        assertEquals(0, timeline.getEventCount());
        assertFalse(timeline.getEventWarnings().isEmpty());
    }

    @Test
    void parseFailureIsTreatedAsCorruptData() {
        ActionEvents events = ActionEvents.fromValues(
            Map.of("events_version", "not-a-number", "events_data", "AQ=="));

        assertTrue(events.isPresent());
        assertEquals(-1, events.version());
        assertTrue(events.warnings().get(0).contains("corrupt or incomplete"));
    }

    private static byte[] versionOneEvent() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            byte[] detail = "1handed".getBytes(StandardCharsets.UTF_8);
            out.writeShort(1);
            out.writeInt(1);
            out.writeInt(1);
            out.writeFloat(0.5f);
            out.writeByte(ActionEvent.Kind.MELEE_ATTACK.code());
            out.writeByte(0);
            out.writeFloat(0f);
            out.writeFloat(1f);
            out.writeShort(detail.length);
            out.write(detail);
        }
        return bytes.toByteArray();
    }

    @Test
    void metadataOmitsEventKeysWhenMissingAndPreservesThemOnEndUpdate() throws Exception {
        ReplayMetadata missing = metadata(ActionEvents.none());
        assertFalse(missing.toValues().containsKey("events_version"));
        assertFalse(missing.toValues().containsKey("events_data"));

        ActionEvents events = ActionEvents.encode(List.of(
            event(1, 0.5f, ActionEvent.Kind.DOOR, false, "door")));
        ReplayMetadata present = metadata(events);
        assertEquals(events, present.withEnd(99L).events());
        assertEquals(events, ReplayMetadata.fromValues(present.toValues()).events());
    }
}
