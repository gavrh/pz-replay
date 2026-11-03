package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionGapTest {
    private static final int FRAME_FLAGS = CaptureSample.flags(false, false, false, false,
        false, true);

    private static CaptureSession.PlayerSnapshot snapshot(float x, float y, int flags) {
        return new CaptureSession.PlayerSnapshot(x, y, 0f, 0f, 0f,
            CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, flags);
    }

    private static void tick(CaptureSession session, long tick, long wallMillis, float x,
        boolean paused) {
        session.tick(tick, wallMillis, snapshot(x, 0f, FRAME_FLAGS), paused);
    }

    private static CaptureSession started() {
        CaptureSession session = new CaptureSession(30.0, 1000, 60.0);
        session.start(0L);
        return session;
    }

    @Test
    void teleportIsMarkedAsAGapWithBothSidesPreserved() {
        CaptureSession session = started();

        tick(session, 1, 33, 0f, false);
        tick(session, 2, 66, 1f, false);
        tick(session, 3, 99, 60f, false);

        List<TimelineGap> gaps = session.gaps();
        assertEquals(1, gaps.size());
        TimelineGap gap = gaps.get(0);
        assertEquals(TimelineGap.Reason.TELEPORT, gap.reason());
        assertEquals(1.0f, gap.lastX(), 0.0001f);
        assertEquals(60.0f, gap.nextX(), 0.0001f);
        assertTrue(gap.endTimeSeconds() > gap.startTimeSeconds());
        assertEquals(3, session.frames().size());
        assertEquals(60f, session.frames().get(2).x(), 0.0001f);
        assertTrue(session.warnings().isEmpty());
    }

    @Test
    void legitimateFastMovementIsNotATeleport() {
        CaptureSession session = started();

        for (int index = 1; index <= 10; index++) {
            tick(session, index, index * 33L, index * 0.5f, false);
        }

        assertTrue(session.gaps().isEmpty());
        assertTrue(session.warnings().isEmpty());
    }

    @Test
    void fastVehicleMovementIsNotClassifiedAsATeleport() {
        CaptureSession session = started();
        int vehicleFlags = CaptureSample.flags(false, false, false, false, false, true,
            true);

        session.tick(1, 33, snapshot(0f, 0f, vehicleFlags), false);
        session.tick(2, 66, snapshot(40f, 0f, vehicleFlags), false);

        assertTrue(session.gaps().isEmpty());
        assertFalse(session.warnings().isEmpty());
    }

    @Test
    void veryLargeVehicleJumpIsStillMarkedAsATeleportGap() {
        CaptureSession session = started();
        int vehicleFlags = CaptureSample.flags(false, false, false, false, false, true,
            true);

        session.tick(1, 33, snapshot(0f, 0f, vehicleFlags), false);
        session.tick(2, 66, snapshot(100f, 0f, vehicleFlags), false);

        assertEquals(1, session.gaps().size());
        assertEquals(TimelineGap.Reason.TELEPORT, session.gaps().get(0).reason());
        assertEquals(0f, session.gaps().get(0).lastX(), 0.0001f);
        assertEquals(100f, session.gaps().get(0).nextX(), 0.0001f);
    }

    @Test
    void longTickGapIsMarkedAndDoesNotCreateFramesForMissingTime() {
        CaptureSession session = started();

        tick(session, 1, 33, 0f, false);
        tick(session, 2, 1500, 1f, false);

        List<TimelineGap> gaps = session.gaps();
        assertEquals(1, gaps.size());
        assertEquals(TimelineGap.Reason.LONG_TICK, gaps.get(0).reason());
        assertEquals(2, session.frames().size());
        assertTrue(session.getLastSummary().gapSeconds() > 1.0f);
    }

    @Test
    void recordingPauseClosesAtTheFirstFrameAfterResume() {
        CaptureSession session = started();

        tick(session, 1, 33, 0f, false);
        tick(session, 2, 66, 1f, true);
        tick(session, 3, 99, 1f, true);
        tick(session, 4, 132, 2f, false);

        List<TimelineGap> gaps = session.gaps();
        assertEquals(1, gaps.size());
        TimelineGap gap = gaps.get(0);
        assertEquals(TimelineGap.Reason.RECORDING_PAUSE, gap.reason());
        assertEquals(0.033f, gap.startTimeSeconds(), 0.001f);
        assertEquals(0.066f, gap.endTimeSeconds(), 0.001f);
        assertEquals(0.0f, gap.lastX(), 0.0001f);
        assertEquals(2.0f, gap.nextX(), 0.0001f);
        assertEquals(2, session.frames().size());
    }

    @Test
    void deathLoadingAndUnavailablePlayerCreateBoundaryMarkers() {
        CaptureSession death = started();
        tick(death, 1, 33, 0f, false);
        tick(death, 2, 66, 1f, false);
        death.stop(CaptureSession.StopReason.PLAYER_DEAD);

        assertEquals(1, death.gaps().size());
        assertEquals(TimelineGap.Reason.DEATH, death.gaps().get(0).reason());
        assertEquals(death.gaps().get(0).startTimeSeconds(),
            death.gaps().get(0).endTimeSeconds(), 0.0001f);

        CaptureSession loading = started();
        tick(loading, 1, 33, 0f, false);
        loading.stop(CaptureSession.StopReason.LOADING);

        assertEquals(1, loading.gaps().size());
        assertEquals(TimelineGap.Reason.LOADING, loading.gaps().get(0).reason());

        CaptureSession unavailable = started();
        tick(unavailable, 1, 33, 5f, false);
        unavailable.tick(2, 66, null, false);

        assertEquals(1, unavailable.gaps().size());
        assertEquals(TimelineGap.Reason.PLAYER_UNAVAILABLE,
            unavailable.gaps().get(0).reason());
        assertEquals(5.0f, unavailable.gaps().get(0).lastX(), 0.0001f);
        assertNotNull(unavailable.getLastSummary());
    }

    @Test
    void startClearsPreviousGapsAndWarnings() {
        CaptureSession session = started();
        tick(session, 1, 33, 0f, false);
        tick(session, 2, 66, 100f, false);
        assertFalse(session.gaps().isEmpty());
        session.stop(CaptureSession.StopReason.REQUESTED);

        session.start(100L);

        assertTrue(session.gaps().isEmpty());
        assertTrue(session.warnings().isEmpty());
        assertTrue(session.frames().isEmpty());
    }

    @Test
    void gapWarningListIsBoundedAndInputsAreNotMutated() {
        CaptureSession session = started();
        int vehicleFlags = CaptureSample.flags(false, false, false, false, false, true,
            true);
        CaptureSession.PlayerSnapshot first = snapshot(0f, 0f, vehicleFlags);
        CaptureSession.PlayerSnapshot second = snapshot(100f, 0f, vehicleFlags);

        session.tick(1, 33, first, false);
        for (int index = 2; index < 20; index++) {
            float x = index % 2 == 0 ? 40f : 0f;
            session.tick(index, index * 33L, snapshot(x, 0f, vehicleFlags), false);
        }

        assertEquals(CaptureSession.MAX_GAP_WARNINGS, session.warnings().size());
        assertEquals(100f, second.x(), 0.0001f);
        assertEquals(0f, first.x(), 0.0001f);
        assertEquals(vehicleFlags, first.flags());
    }
}
