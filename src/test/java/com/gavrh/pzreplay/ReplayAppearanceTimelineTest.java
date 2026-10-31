package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayAppearanceTimelineTest {
    private static ReplayAppearance appearance(String value) {
        return new ReplayAppearance(ReplayAppearance.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static ReplayAppearanceChanges changes(ReplayAppearanceChange... entries)
        throws ReplayFormatException {
        return ReplayAppearanceChanges.encode(List.of(entries));
    }

    private static List<ReplaySample> frames(int count) {
        List<ReplaySample> frames = new ArrayList<>();
        for (int index = 0; index <= count; index++) {
            frames.add(new ReplaySample(index, index, 0f, index, 0f, 0f, 0f, 0f,
                ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, 0));
        }
        return frames;
    }

    private static ReplayTimeline timeline(ReplayAppearance initial, ReplayAppearanceChanges changes)
        throws ReplayFormatException {
        ReplayMetadata metadata = new ReplayMetadata(
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
            initial,
            changes,
            ReplayEquipment.none(),
            ReplayEvents.none()
        );
        return new ReplayTimeline(new ReplayData(metadata, frames(10)));
    }

    @Test
    void selectsLatestChangeAtOrBeforeTime() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearance first = appearance("first");
        ReplayAppearance second = appearance("second");
        ReplayTimeline timeline = timeline(initial, changes(
            new ReplayAppearanceChange(1L, 2.0f, first),
            new ReplayAppearanceChange(2L, 6.0f, second)));

        assertEquals(initial, timeline.appearanceAt(0.0));
        assertEquals(initial, timeline.appearanceAt(1.999));
        assertEquals(first, timeline.appearanceAt(2.0));
        assertEquals(first, timeline.appearanceAt(5.999));
        assertEquals(second, timeline.appearanceAt(6.0));
        assertEquals(second, timeline.appearanceAt(60.0));
    }

    @Test
    void equalTimestampsUseSequenceOrder() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearance first = appearance("first");
        ReplayAppearance second = appearance("second");
        ReplayTimeline timeline = timeline(initial, changes(
            new ReplayAppearanceChange(2L, 2.0f, second),
            new ReplayAppearanceChange(1L, 2.0f, first)));

        assertEquals(second, timeline.appearanceAt(2.0));
    }

    @Test
    void sortsOutOfOrderChanges() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearance first = appearance("first");
        ReplayAppearance second = appearance("second");
        ReplayTimeline timeline = timeline(initial, changes(
            new ReplayAppearanceChange(2L, 6.0f, second),
            new ReplayAppearanceChange(1L, 2.0f, first)));

        assertEquals(first, timeline.appearanceAt(5.0));
        assertEquals(second, timeline.appearanceAt(7.0));
    }

    @Test
    void ignoresChangesOutsideDurationAndClampsSmallTails() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearance tail = appearance("tail");
        ReplayAppearance late = appearance("late");
        ReplayTimeline timeline = timeline(initial, changes(
            new ReplayAppearanceChange(1L, 10.1f, tail),
            new ReplayAppearanceChange(2L, 20.0f, late)));

        assertEquals(1, timeline.getAppearanceChangeCount());
        assertEquals(initial, timeline.appearanceAt(9.0));
        assertEquals(tail, timeline.appearanceAt(10.0));
        assertTrue(timeline.getAppearanceWarnings().stream()
            .anyMatch(warning -> warning.contains("outside the replay duration")));
    }

    @Test
    void seekForwardAndBackwardSelectsTheCorrectAppearance() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearance first = appearance("first");
        ReplayAppearance second = appearance("second");
        ReplayTimeline timeline = timeline(initial, changes(
            new ReplayAppearanceChange(1L, 2.0f, first),
            new ReplayAppearanceChange(2L, 6.0f, second)));

        assertTrue(timeline.start());
        assertTrue(timeline.seek(8.0));
        assertEquals(second, timeline.appearanceAt(timeline.getCurrentTime()));
        assertTrue(timeline.seek(1.0));
        assertEquals(initial, timeline.appearanceAt(timeline.getCurrentTime()));
        assertTrue(timeline.seek(3.0));
        assertEquals(first, timeline.appearanceAt(timeline.getCurrentTime()));
        assertTrue(timeline.pause());
        assertTrue(timeline.seek(8.0));
        assertEquals(second, timeline.appearanceAt(timeline.getCurrentTime()));
        assertTrue(timeline.setSpeed(4.0));
        assertEquals(second, timeline.appearanceAt(timeline.getCurrentTime()));
        assertTrue(timeline.restart());
        assertEquals(initial, timeline.appearanceAt(timeline.getCurrentTime()));
    }

    @Test
    void oldReplayWithoutTimelineUsesInitialAppearanceForWholeReplay() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayTimeline timeline = timeline(initial, ReplayAppearanceChanges.none());

        assertEquals(0, timeline.getAppearanceChangeCount());
        assertEquals(initial, timeline.appearanceAt(0.0));
        assertEquals(initial, timeline.appearanceAt(5.0));
        assertEquals(initial, timeline.appearanceAt(10.0));
        assertTrue(timeline.getAppearanceWarnings().isEmpty());
    }

    @Test
    void corruptTimelineWarnsAndUsesInitialAppearance() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearanceChanges corrupt = new ReplayAppearanceChanges(
            ReplayAppearanceChanges.CURRENT_VERSION, "not base64!");
        ReplayTimeline timeline = timeline(initial, corrupt);

        assertEquals(0, timeline.getAppearanceChangeCount());
        assertEquals(initial, timeline.appearanceAt(5.0));
        assertTrue(timeline.getAppearanceWarnings().stream()
            .anyMatch(warning -> warning.contains("corrupt or incomplete")));
    }

    @Test
    void unsupportedChangeIsSkippedWithWarning() throws Exception {
        ReplayAppearance initial = appearance("initial");
        ReplayAppearanceChange unsupported = new ReplayAppearanceChange(1L, 2.0f,
            new ReplayAppearance(ReplayAppearance.CURRENT_VERSION + 1, "AQ=="));
        ReplayAppearanceTimeline timeline = new ReplayAppearanceTimeline(initial,
            List.of(unsupported), 10.0);

        assertEquals(0, timeline.getChangeCount());
        assertEquals(initial, timeline.appearanceAt(5.0));
        assertFalse(timeline.warnings().isEmpty());
        assertTrue(timeline.warnings().get(0).contains("unsupported"));
    }
}
