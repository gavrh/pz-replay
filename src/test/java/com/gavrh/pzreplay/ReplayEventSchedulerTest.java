package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEventSchedulerTest {
    private static ReplayEvent event(long sequence, float time, ReplayEvent.Kind kind,
        boolean end, String detail) {
        return new ReplayEvent(sequence, time, kind, end, detail, 0f, 0f);
    }

    private static ReplayEventScheduler scheduler() throws Exception {
        return new ReplayEventScheduler(new ReplayEventTimeline(List.of(
            event(1, 0.0f, ReplayEvent.Kind.DOOR, false, "start"),
            event(2, 1.0f, ReplayEvent.Kind.MELEE_ATTACK, false, "swing"),
            event(3, 1.0f, ReplayEvent.Kind.RELOAD, false, "reload"),
            event(4, 2.0f, ReplayEvent.Kind.SIT, false, "ground"),
            event(5, 3.0f, ReplayEvent.Kind.SIT, true, "ground"),
            event(6, 4.0f, ReplayEvent.Kind.CLIMB, false, "window")
        ), 5.0));
    }

    @Test
    void startReturnsOnlyEventsAtTheStartTime() throws Exception {
        ReplayEventScheduler scheduler = scheduler();

        assertEquals(List.of(1L),
            scheduler.start().stream().map(ReplayEvent::sequence).toList());
    }

    @Test
    void advanceCrossesEachEventOnceInOrder() throws Exception {
        ReplayEventScheduler scheduler = scheduler();
        scheduler.start();

        assertEquals(List.of(2L, 3L),
            scheduler.advance(1.0).stream().map(ReplayEvent::sequence).toList());
        assertTrue(scheduler.advance(1.0).isEmpty());
        assertEquals(List.of(4L),
            scheduler.advance(2.5).stream().map(ReplayEvent::sequence).toList());
    }

    @Test
    void seekForwardDoesNotReplayPastEvents() throws Exception {
        ReplayEventScheduler scheduler = scheduler();
        scheduler.start();

        scheduler.seek(2.5);
        assertTrue(scheduler.advance(2.5).isEmpty());
        assertEquals(List.of(5L),
            scheduler.advance(3.0).stream().map(ReplayEvent::sequence).toList());
    }

    @Test
    void seekReturnsOnlyPersistentState() throws Exception {
        ReplayEventScheduler scheduler = scheduler();
        scheduler.start();

        assertEquals(List.of(4L),
            scheduler.seek(2.5).stream().map(ReplayEvent::sequence).toList());
        assertTrue(scheduler.seek(3.5).isEmpty());
    }

    @Test
    void seekBackwardAllowsEventsToPlayAgainFromTheNewTime() throws Exception {
        ReplayEventScheduler scheduler = scheduler();
        scheduler.start();
        scheduler.advance(1.0);

        scheduler.seek(0.5);
        assertEquals(List.of(2L, 3L),
            scheduler.advance(1.2).stream().map(ReplayEvent::sequence).toList());
    }

    @Test
    void speedChangesOnlyMoveTheTimelineClock() throws Exception {
        ReplayTimeline timeline = new ReplayTimeline(List.of(
            new ReplaySample(0L, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, 0),
            new ReplaySample(1L, 2f, 0f, 0f, 0f, 0f, 0f, 0f,
                ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, 0)
        ));
        ReplayEventScheduler scheduler = scheduler();
        timeline.start();
        scheduler.start();
        timeline.setSpeed(2.0);

        timeline.advance(0.5);

        assertEquals(1.0, timeline.getCurrentTime(), 0.0001);
        assertEquals(List.of(2L, 3L),
            scheduler.advance(timeline.getCurrentTime()).stream()
                .map(ReplayEvent::sequence).toList());
    }
}
