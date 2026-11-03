package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEventSchedulerTest {
    private static ActionEvent event(long sequence, float time, ActionEvent.Kind kind,
        boolean end, String detail) {
        return new ActionEvent(sequence, time, kind, end, detail, 0f, 0f);
    }

    private static ActionEventScheduler scheduler() throws Exception {
        return new ActionEventScheduler(new ActionEventTimeline(List.of(
            event(1, 0.0f, ActionEvent.Kind.DOOR, false, "start"),
            event(2, 1.0f, ActionEvent.Kind.MELEE_ATTACK, false, "swing"),
            event(3, 1.0f, ActionEvent.Kind.RELOAD, false, "reload"),
            event(4, 2.0f, ActionEvent.Kind.SIT, false, "ground"),
            event(5, 3.0f, ActionEvent.Kind.SIT, true, "ground"),
            event(6, 4.0f, ActionEvent.Kind.CLIMB, false, "window")
        ), 5.0));
    }

    @Test
    void startReturnsOnlyEventsAtTheStartTime() throws Exception {
        ActionEventScheduler scheduler = scheduler();

        assertEquals(List.of(1L),
            scheduler.start().stream().map(ActionEvent::sequence).toList());
    }

    @Test
    void advanceCrossesEachEventOnceInOrder() throws Exception {
        ActionEventScheduler scheduler = scheduler();
        scheduler.start();

        assertEquals(List.of(2L, 3L),
            scheduler.advance(1.0).stream().map(ActionEvent::sequence).toList());
        assertTrue(scheduler.advance(1.0).isEmpty());
        assertEquals(List.of(4L),
            scheduler.advance(2.5).stream().map(ActionEvent::sequence).toList());
    }

    @Test
    void seekForwardDoesNotReplayPastEvents() throws Exception {
        ActionEventScheduler scheduler = scheduler();
        scheduler.start();

        scheduler.seek(2.5);
        assertTrue(scheduler.advance(2.5).isEmpty());
        assertEquals(List.of(5L),
            scheduler.advance(3.0).stream().map(ActionEvent::sequence).toList());
    }

    @Test
    void seekReturnsOnlyPersistentState() throws Exception {
        ActionEventScheduler scheduler = scheduler();
        scheduler.start();

        assertEquals(List.of(4L),
            scheduler.seek(2.5).stream().map(ActionEvent::sequence).toList());
        assertTrue(scheduler.seek(3.5).isEmpty());
    }

    @Test
    void seekBackwardAllowsEventsToPlayAgainFromTheNewTime() throws Exception {
        ActionEventScheduler scheduler = scheduler();
        scheduler.start();
        scheduler.advance(1.0);

        scheduler.seek(0.5);
        assertEquals(List.of(2L, 3L),
            scheduler.advance(1.2).stream().map(ActionEvent::sequence).toList());
    }

    @Test
    void speedChangesOnlyMoveTheTimelineClock() throws Exception {
        PlaybackTimeline timeline = new PlaybackTimeline(List.of(
            new CaptureSample(0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND, 0),
            new CaptureSample(1L, 2f, 0f, 0f, 0f, 0f, 0f, 0f,
                CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND, 0)
        ));
        ActionEventScheduler scheduler = scheduler();
        timeline.start();
        scheduler.start();
        timeline.setSpeed(2.0);

        timeline.advance(0.5);

        assertEquals(1.0, timeline.getCurrentTime(), 0.0001);
        assertEquals(List.of(2L, 3L),
            scheduler.advance(timeline.getCurrentTime()).stream()
                .map(ActionEvent::sequence).toList());
    }
}
