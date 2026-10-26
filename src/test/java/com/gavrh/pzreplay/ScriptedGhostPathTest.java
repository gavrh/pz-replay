package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedGhostPathTest {
    private static ScriptedGhostPath.Point point(double time, float x, float y, float facing,
        ReplaySample.Locomotion locomotion) {
        return new ScriptedGhostPath.Point(time, x, y, 0.0f, facing, locomotion);
    }

    private static ScriptedGhostPath path(boolean loop) {
        return new ScriptedGhostPath(List.of(
            point(0.0, 10.0f, 20.0f, 0.0f, ReplaySample.Locomotion.IDLE),
            point(2.0, 14.0f, 24.0f, 90.0f, ReplaySample.Locomotion.WALK),
            point(4.0, 14.0f, 20.0f, 180.0f, ReplaySample.Locomotion.RUN)
        ), loop);
    }

    @Test
    void samplesExactPointsWithoutError() {
        ScriptedGhostPath path = path(false);

        assertSample(path.sample(0.0), 0.0, 10.0f, 20.0f, 0.0f,
            ReplaySample.Locomotion.IDLE);
        assertSample(path.sample(2.0), 2.0, 14.0f, 24.0f, 90.0f,
            ReplaySample.Locomotion.WALK);
        assertSample(path.sample(4.0), 4.0, 14.0f, 20.0f, 180.0f,
            ReplaySample.Locomotion.RUN);
    }

    @Test
    void interpolatesPositionAndUsesSegmentLocomotion() {
        ScriptedGhostPath.Sample sample = path(false).sample(1.0);

        assertSample(sample, 1.0, 12.0f, 22.0f, 45.0f, ReplaySample.Locomotion.IDLE);
    }

    @Test
    void interpolatesFacingOnTheShortestArc() {
        ScriptedGhostPath path = new ScriptedGhostPath(List.of(
            point(0.0, 0.0f, 0.0f, 170.0f, ReplaySample.Locomotion.WALK),
            point(2.0, 0.0f, 0.0f, -170.0f, ReplaySample.Locomotion.WALK)
        ), false);

        assertEquals(180.0f, path.sample(1.0).facing(), 0.0001f);
        assertEquals(185.0f - 360.0f, path.sample(1.5).facing(), 0.0001f);
    }

    @Test
    void loopsTimeAndClampsWhenNotLooping() {
        ScriptedGhostPath looping = path(true);
        ScriptedGhostPath bounded = path(false);

        assertSample(looping.sample(4.0), 0.0, 10.0f, 20.0f, 0.0f,
            ReplaySample.Locomotion.IDLE);
        assertSample(looping.sample(5.0), 1.0, 12.0f, 22.0f, 45.0f,
            ReplaySample.Locomotion.IDLE);
        assertSample(looping.sample(-1.0), 3.0, 14.0f, 22.0f, 135.0f,
            ReplaySample.Locomotion.WALK);
        assertSample(bounded.sample(-1.0), 0.0, 10.0f, 20.0f, 0.0f,
            ReplaySample.Locomotion.IDLE);
        assertSample(bounded.sample(9.0), 4.0, 14.0f, 20.0f, 180.0f,
            ReplaySample.Locomotion.RUN);
    }

    @Test
    void rejectsInvalidPoints() {
        assertThrows(IllegalArgumentException.class,
            () -> new ScriptedGhostPath(List.of(), false));
        assertThrows(IllegalArgumentException.class,
            () -> new ScriptedGhostPath(List.of(
                point(0.0, 0.0f, 0.0f, 0.0f, ReplaySample.Locomotion.IDLE)
            ), false));
        assertThrows(IllegalArgumentException.class,
            () -> new ScriptedGhostPath(List.of(
                point(1.0, 0.0f, 0.0f, 0.0f, ReplaySample.Locomotion.IDLE),
                point(2.0, 1.0f, 0.0f, 0.0f, ReplaySample.Locomotion.WALK)
            ), false));
        assertThrows(IllegalArgumentException.class,
            () -> new ScriptedGhostPath(List.of(
                point(0.0, 0.0f, 0.0f, 0.0f, ReplaySample.Locomotion.IDLE),
                point(0.0, 1.0f, 0.0f, 0.0f, ReplaySample.Locomotion.WALK)
            ), false));
        assertThrows(IllegalArgumentException.class,
            () -> new ScriptedGhostPath.Point(0.0, Float.NaN, 0.0f, 0.0f, 0.0f,
                ReplaySample.Locomotion.IDLE));
        assertThrows(IllegalArgumentException.class,
            () -> new ScriptedGhostPath.Point(0.0, 0.0f, 0.0f, 0.0f, 0.0f, null));
    }

    @Test
    void defaultRouteCoversEveryLocomotionState() {
        ScriptedGhostPath route = ScriptedGhostPath.defaultRoute(100.0f, 200.0f, 0.0f);

        assertSample(route.sample(0.5), 0.5, 100.0f, 200.0f, 0.0f,
            ReplaySample.Locomotion.IDLE);
        assertSample(route.sample(2.5), 2.5, 101.5f, 200.0f, 0.0f,
            ReplaySample.Locomotion.WALK);
        assertSample(route.sample(5.3), 5.3, 103.0f, 201.5f, 90.0f,
            ReplaySample.Locomotion.RUN);
        assertSample(route.sample(7.15), 7.15, 101.5f, 203.0f, 180.0f,
            ReplaySample.Locomotion.SPRINT);
        assertSample(route.sample(9.9), 9.9, 100.0f, 201.5f, -90.0f,
            ReplaySample.Locomotion.SNEAK);
        assertTrue(route.isLoop());
        assertEquals(13.1, route.getDuration(), 0.0001);
        assertSample(route.sample(route.getDuration()), 0.0, 100.0f, 200.0f, 0.0f,
            ReplaySample.Locomotion.IDLE);
    }

    private static void assertSample(ScriptedGhostPath.Sample sample, double time, float x,
        float y, float facing, ReplaySample.Locomotion locomotion) {
        assertEquals(time, sample.time(), 0.0001);
        assertEquals(x, sample.x(), 0.0001f);
        assertEquals(y, sample.y(), 0.0001f);
        assertEquals(facing, sample.facing(), 0.0001f);
        assertEquals(locomotion, sample.locomotion());
    }
}
