package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEventTimelineTest {
    private static ActionEvent event(long sequence, float time, ActionEvent.Kind kind,
        boolean end, String detail) {
        return new ActionEvent(sequence, time, kind, end, detail, 0f, 0f);
    }

    @Test
    void crossingsExcludeTheFromTimeAndIncludeTheToTime() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(List.of(
            event(1, 0.5f, ActionEvent.Kind.MELEE_ATTACK, false, "1handed"),
            event(2, 1.0f, ActionEvent.Kind.RELOAD, false, "handgun"),
            event(3, 1.5f, ActionEvent.Kind.DOOR, false, "door")
        ), 2.0);

        assertEquals(List.of(2L, 3L),
            timeline.eventsBetween(0.5, 1.5).stream().map(ActionEvent::sequence).toList());
        assertEquals(List.of(2L),
            timeline.eventsBetween(0.75, 1.0).stream().map(ActionEvent::sequence).toList());
        assertTrue(timeline.eventsBetween(1.5, 1.5).isEmpty());
    }

    @Test
    void eventsAtIncludesEqualTimestampsInStableOrder() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(List.of(
            event(1, 0.5f, ActionEvent.Kind.MELEE_ATTACK, false, "first"),
            event(2, 0.5f, ActionEvent.Kind.DOOR, false, "second")
        ), 2.0);

        assertEquals(List.of("first", "second"),
            timeline.eventsAt(0.5).stream().map(ActionEvent::detail).toList());
    }

    @Test
    void equalTimestampsKeepInputOrderAfterSorting() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(List.of(
            event(2, 0.5f, ActionEvent.Kind.DOOR, false, "second"),
            event(1, 0.5f, ActionEvent.Kind.RELOAD, false, "first")
        ), 2.0);

        assertEquals(List.of(2L, 1L),
            timeline.events().stream().map(ActionEvent::sequence).toList());
    }

    @Test
    void persistentStateTracksSitStartAndEnd() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(List.of(
            event(1, 0.5f, ActionEvent.Kind.SIT, false, "ground"),
            event(2, 1.0f, ActionEvent.Kind.MELEE_ATTACK, false, "1handed"),
            event(3, 2.0f, ActionEvent.Kind.SIT, true, "ground")
        ), 3.0);

        assertTrue(timeline.persistentAt(0.4).isEmpty());
        assertEquals(List.of(1L),
            timeline.persistentAt(1.0).stream().map(ActionEvent::sequence).toList());
        assertTrue(timeline.persistentAt(2.0).isEmpty());
    }

    @Test
    void outOfBoundsAndMissingEventsAreIgnoredWithWarnings() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(java.util.Arrays.asList(
            event(1, 0.5f, ActionEvent.Kind.DOOR, false, "inside"),
            null,
            event(2, 5.0f, ActionEvent.Kind.DOOR, false, "outside")
        ), 2.0);

        assertEquals(1, timeline.getEventCount());
        assertEquals(2, timeline.warnings().size());
        assertTrue(timeline.warnings().get(0).contains("outside the replay duration")
            || timeline.warnings().get(1).contains("outside the replay duration"));
    }

    @Test
    void eventJustPastTheLastFrameIsClampedToTheReplayEnd() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(List.of(
            event(1, 2.05f, ActionEvent.Kind.RANGED_ATTACK, false, "handgun")
        ), 2.0);

        assertEquals(1, timeline.getEventCount());
        assertEquals(2.0f, timeline.events().get(0).timeSeconds(), 0.0001f);
        assertTrue(timeline.warnings().isEmpty());
    }

    @Test
    void nextEventTimeAdvancesPastTheCurrentEvent() throws Exception {
        ActionEventTimeline timeline = new ActionEventTimeline(List.of(
            event(1, 0.5f, ActionEvent.Kind.DOOR, false, "first"),
            event(2, 1.5f, ActionEvent.Kind.DOOR, false, "second")
        ), 2.0);

        assertEquals(0.5, timeline.nextEventTime(-1.0), 0.0001);
        assertEquals(1.5, timeline.nextEventTime(0.5), 0.0001);
        assertEquals(Double.POSITIVE_INFINITY, timeline.nextEventTime(1.5));
    }
}
