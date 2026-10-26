package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayTimelineTest {
    private static ReplaySample frame(float time, float x, float y, float z, float angle,
        ReplaySample.Locomotion locomotion, ReplaySample.Stance stance) {
        return new ReplaySample(0L, time, 0f, x, y, z, angle, 0f, locomotion, stance, 0);
    }

    private static ReplayTimeline timeline() throws ReplayFormatException {
        return new ReplayTimeline(List.of(
            frame(10f, 100f, 200f, 0f, 170f, ReplaySample.Locomotion.WALK,
                ReplaySample.Stance.STAND),
            frame(11f, 110f, 220f, 0.5f, -170f, ReplaySample.Locomotion.RUN,
                ReplaySample.Stance.CROUCH),
            frame(12f, 110f, 230f, 0f, 90f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.STAND)
        ));
    }

    @Test
    void samplesExactFramesByRelativeTime() throws Exception {
        ReplayTimeline timeline = timeline();

        assertSample(timeline.sampleAt(0.0), 0.0, 100f, 200f, 0f, 170f,
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND);
        assertSample(timeline.sampleAt(1.0), 1.0, 110f, 220f, 0.5f, -170f,
            ReplaySample.Locomotion.RUN, ReplaySample.Stance.CROUCH);
        assertSample(timeline.sampleAt(2.0), 2.0, 110f, 230f, 0f, 90f,
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND);
    }

    @Test
    void interpolatesPositionOnTheShortestFacingArc() throws Exception {
        ReplayTimeline.Sample sample = timeline().sampleAt(0.5);

        assertSample(sample, 0.5, 105f, 210f, 0.25f, 180f,
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND);
    }

    @Test
    void interpolatesFacingAcrossZero() throws Exception {
        ReplayTimeline timeline = new ReplayTimeline(List.of(
            frame(0f, 0f, 0f, 0f, 350f, ReplaySample.Locomotion.WALK,
                ReplaySample.Stance.STAND),
            frame(1f, 0f, 0f, 0f, 10f, ReplaySample.Locomotion.WALK,
                ReplaySample.Stance.STAND)
        ));

        assertEquals(0f, timeline.sampleAt(0.5).facing(), 0.0001f);
    }

    @Test
    void clampsToFirstAndLastFrame() throws Exception {
        ReplayTimeline timeline = timeline();

        assertSample(timeline.sampleAt(-5.0), 0.0, 100f, 200f, 0f, 170f,
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND);
        assertSample(timeline.sampleAt(50.0), 2.0, 110f, 230f, 0f, 90f,
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND);
    }

    @Test
    void startPauseResumeStopAndEndAreDistinct() throws Exception {
        ReplayTimeline timeline = timeline();

        assertTrue(timeline.start());
        assertTrue(timeline.isPlaying());
        assertTrue(timeline.advance(0.4));
        assertEquals(0.4, timeline.getCurrentTime(), 0.0001);

        assertFalse(timeline.start());
        assertEquals(0.4, timeline.getCurrentTime(), 0.0001);

        assertTrue(timeline.pause());
        assertTrue(timeline.isPaused());
        assertFalse(timeline.advance(0.2));
        assertEquals(0.4, timeline.getCurrentTime(), 0.0001);

        assertTrue(timeline.start());
        assertTrue(timeline.advance(1.5));
        assertEquals(1.9, timeline.getCurrentTime(), 0.0001);
        assertFalse(timeline.advance(0.1));
        assertFalse(timeline.isPlaying());
        assertEquals(2.0, timeline.getCurrentTime(), 0.0001);

        assertTrue(timeline.start());
        assertEquals(0.0, timeline.getCurrentTime(), 0.0001);
    }

    @Test
    void stopResetsTimeAndState() throws Exception {
        ReplayTimeline timeline = timeline();
        timeline.start();
        timeline.advance(0.5);

        timeline.stop();

        assertFalse(timeline.isActive());
        assertEquals(0.0, timeline.getCurrentTime(), 0.0001);
    }

    @Test
    void emptyTimelineHasNoPlayableFrames() throws Exception {
        ReplayTimeline timeline = new ReplayTimeline(List.of());

        assertTrue(timeline.isEmpty());
        assertEquals(0.0, timeline.getDuration(), 0.0001);
        assertFalse(timeline.start());
        assertFalse(timeline.isPlaying());
        assertThrows(IllegalStateException.class, timeline::sample);
        assertThrows(IllegalStateException.class, () -> timeline.sampleAt(0.0));
    }

    @Test
    void singleFrameTimelineHoldsItsPoseAndEndsImmediately() throws Exception {
        ReplayTimeline timeline = new ReplayTimeline(List.of(
            frame(5f, 7f, 8f, 9f, 45f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.CROUCH)
        ));

        assertEquals(0.0, timeline.getDuration(), 0.0001);
        assertSample(timeline.sampleAt(0.0), 0.0, 7f, 8f, 9f, 45f,
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.CROUCH);
        assertTrue(timeline.start());
        assertFalse(timeline.advance(0.1));
        assertFalse(timeline.isPlaying());
    }

    @Test
    void rejectsInvalidFrames() {
        assertThrows(ReplayFormatException.class, () -> new ReplayTimeline(
            (List<ReplaySample>) null));
        assertThrows(ReplayFormatException.class, () -> new ReplayTimeline(List.of(
            frame(1f, 0f, 0f, 0f, 0f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.STAND),
            frame(1f, 0f, 0f, 0f, 0f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.STAND)
        )));
        assertThrows(ReplayFormatException.class, () -> new ReplayTimeline(List.of(
            frame(1f, 0f, 0f, 0f, 0f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.STAND),
            frame(0f, 0f, 0f, 0f, 0f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.STAND)
        )));
        assertThrows(ReplayFormatException.class, () -> new ReplayTimeline(List.of(
            frame(Float.NaN, 0f, 0f, 0f, 0f, ReplaySample.Locomotion.IDLE,
                ReplaySample.Stance.STAND)
        )));
        assertThrows(ReplayFormatException.class, () -> new ReplayTimeline(List.of(
            frame(0f, 0f, 0f, 0f, 0f, null, ReplaySample.Stance.STAND)
        )));
        assertThrows(ReplayFormatException.class, () -> new ReplayTimeline(List.of(
            frame(0f, 0f, 0f, 0f, 0f, ReplaySample.Locomotion.IDLE, null)
        )));
    }

    private static void assertSample(ReplayTimeline.Sample sample, double time, float x, float y,
        float z, float facing, ReplaySample.Locomotion locomotion, ReplaySample.Stance stance) {
        assertEquals(time, sample.time(), 0.0001);
        assertEquals(x, sample.x(), 0.0001f);
        assertEquals(y, sample.y(), 0.0001f);
        assertEquals(z, sample.z(), 0.0001f);
        assertEquals(facing, sample.facing(), 0.0001f);
        assertEquals(locomotion, sample.locomotion());
        assertEquals(stance, sample.stance());
    }
}
