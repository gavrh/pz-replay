package com.gavrh.pzreplay;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEventsTest {
    @TempDir
    Path tempDir;

    private static ReplayEvent event(long sequence, float time, ReplayEvent.Kind kind,
        boolean end, String detail) {
        return new ReplayEvent(sequence, time, kind, end, detail, 0f, 0f);
    }

    private static ReplaySample frame(float time) {
        return new ReplaySample(0L, time, 0f, 0f, 0f, 0f, 0f, 0f,
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, 0);
    }

    private static ReplayMetadata metadata(ReplayEvents events) {
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
            ReplayAppearance.none(),
            ReplayEquipment.none(),
            events
        );
    }

    @Test
    void missingEventsAreCompatibleWithOldReplays() throws Exception {
        ReplayEvents events = ReplayEvents.none();

        assertFalse(events.isPresent());
        assertTrue(events.warnings().isEmpty());
        assertThrows(ReplayFormatException.class, events::decode);

        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(metadata(events),
            List.of(frame(0f), frame(1f))));
        assertEquals(0, timeline.getEventCount());
        assertTrue(timeline.getEventWarnings().isEmpty());
    }

    @Test
    void newReplayRoundTripsEventsThroughTheBinaryFormat() throws Exception {
        ReplayEvents events = ReplayEvents.encode(List.of(
            event(1, 0.25f, ReplayEvent.Kind.MELEE_ATTACK, false, "1handed"),
            event(2, 0.5f, ReplayEvent.Kind.SIT, false, "ground"),
            event(3, 1.5f, ReplayEvent.Kind.SIT, true, "ground")
        ));
        ReplayMetadata metadata = metadata(events);
        Path path = tempDir.resolve("events.pzrp.gz");

        BinaryReplayCodec.write(path, metadata, List.of(frame(0f), frame(2f)), true);
        ReplayData data = ReplayReader.read(path);
        ReplayTimeline timeline = new ReplayTimeline(data);

        assertEquals(events, data.metadata().events());
        assertEquals(3, timeline.getEventCount());
        assertEquals(List.of(1L, 2L, 3L),
            timeline.getEvents().stream().map(ReplayEvent::sequence).toList());
        assertTrue(timeline.getEventWarnings().isEmpty());
        assertFalse(timeline.getEventTimeline().persistentAt(1.0).isEmpty());
    }

    @Test
    void corruptOrUnsupportedEventDataWarnsWithoutBreakingFrames() throws Exception {
        ReplayEvents corrupt = new ReplayEvents(ReplayEvents.CURRENT_VERSION, "not base64!");
        ReplayEvents unsupported = new ReplayEvents(ReplayEvents.CURRENT_VERSION + 1, "AQ==");

        assertEquals(1, corrupt.warnings().size());
        assertTrue(corrupt.warnings().get(0).contains("corrupt or incomplete"));
        assertEquals(1, unsupported.warnings().size());
        assertTrue(unsupported.warnings().get(0).contains("version is unsupported"));

        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(metadata(corrupt),
            List.of(frame(0f), frame(1f))));
        assertEquals(2, timeline.getFrameCount());
        assertEquals(0, timeline.getEventCount());
        assertFalse(timeline.getEventWarnings().isEmpty());
    }

    @Test
    void parseFailureIsTreatedAsCorruptData() {
        ReplayEvents events = ReplayEvents.fromValues(
            Map.of("events_version", "not-a-number", "events_data", "AQ=="));

        assertTrue(events.isPresent());
        assertEquals(-1, events.version());
        assertTrue(events.warnings().get(0).contains("corrupt or incomplete"));
    }

    @Test
    void metadataOmitsEventKeysWhenMissingAndPreservesThemOnEndUpdate() throws Exception {
        ReplayMetadata missing = metadata(ReplayEvents.none());
        assertFalse(missing.toValues().containsKey("events_version"));
        assertFalse(missing.toValues().containsKey("events_data"));

        ReplayEvents events = ReplayEvents.encode(List.of(
            event(1, 0.5f, ReplayEvent.Kind.DOOR, false, "door")));
        ReplayMetadata present = metadata(events);
        assertEquals(events, present.withEnd(99L).events());
        assertEquals(events, ReplayMetadata.fromValues(present.toValues()).events());
    }
}
