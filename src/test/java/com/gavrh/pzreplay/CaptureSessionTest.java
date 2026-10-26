package com.gavrh.pzreplay;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionTest {
    private static CaptureSession.PlayerSnapshot player(float x, float y, float gameTime) {
        return new CaptureSession.PlayerSnapshot(
            x,
            y,
            0f,
            45f,
            gameTime,
            ReplaySample.Locomotion.IDLE,
            ReplaySample.Stance.STAND,
            ReplaySample.FLAG_MOVING
        );
    }

    @Test
    void samplesAtConfiguredRateWithoutDuplicateTicks() {
        CaptureSession session = new CaptureSession(30.0);
        session.start(1_000_000L);

        long wall = 1_000_000L;
        float gameTime = 0f;
        for (int tick = 1; tick <= 1000; tick++) {
            wall += 10L;
            gameTime += 0.01f;
            session.tick(tick, wall, player(tick * 0.01f, 0f, gameTime), false);
        }

        CaptureSession.Summary summary = session.status();
        assertTrue(summary.frameCount() >= 295 && summary.frameCount() <= 305,
            "frames=" + summary.frameCount());
        assertEquals(30.0, summary.configuredRateHz());
        assertEquals(30.0, summary.actualRateHz(), 0.5);

        List<ReplaySample> frames = session.frames();
        Set<Long> ticks = new HashSet<>();
        float previousTime = -1f;
        for (ReplaySample frame : frames) {
            assertTrue(ticks.add(frame.tick()), "duplicate tick " + frame.tick());
            if (previousTime >= 0f) {
                float delta = frame.timeSeconds() - previousTime;
                assertTrue(delta >= 0.029f && delta <= 0.041f, "delta=" + delta);
            }
            previousTime = frame.timeSeconds();
        }
    }

    @Test
    void samplesAtTwentyHertzWhenConfigured() {
        CaptureSession session = new CaptureSession(20.0);
        session.start(0L);

        for (int tick = 1; tick <= 1000; tick++) {
            session.tick(tick, tick * 10L, player(0f, 0f, tick * 0.01f), false);
        }

        CaptureSession.Summary summary = session.status();
        assertTrue(summary.frameCount() >= 195 && summary.frameCount() <= 205,
            "frames=" + summary.frameCount());
        assertEquals(20.0, summary.actualRateHz(), 0.5);
    }

    @Test
    void derivesSpeedFromPositionDelta() {
        CaptureSession session = new CaptureSession(30.0);
        session.start(0L);

        session.tick(1, 10L, player(0f, 0f, 0f), false);
        session.tick(2, 20L, player(0f, 0f, 0f), false);
        session.tick(3, 40L, player(1f, 0f, 0f), false);

        List<ReplaySample> frames = session.frames();
        assertEquals(2, frames.size());
        assertEquals(0f, frames.get(0).speed(), 0.0001f);
        assertEquals(1f / 0.03f, frames.get(1).speed(), 0.01f);
    }

    @Test
    void reportsDurationAndEstimatedBytes() {
        CaptureSession session = new CaptureSession(30.0, 1000, 30.0);
        session.start(0L);

        for (int tick = 1; tick <= 200; tick++) {
            session.tick(tick, tick * 10L, player(0f, 0f, tick * 0.01f), false);
        }
        CaptureSession.Summary summary = session.stop(CaptureSession.StopReason.REQUESTED);

        assertEquals(2.0f, summary.durationSeconds(), 0.02f);
        assertTrue(summary.frameCount() >= 59 && summary.frameCount() <= 61,
            "frames=" + summary.frameCount());
        assertEquals(summary.frameCount() * (long) CaptureSession.ESTIMATED_BYTES_PER_FRAME,
            summary.estimatedBytes());
        assertEquals(CaptureSession.StopReason.REQUESTED, summary.stopReason());
    }

    @Test
    void stopsCleanlyAtMaxFrames() {
        CaptureSession session = new CaptureSession(30.0, 10, 30.0);
        session.start(0L);

        for (int tick = 1; tick <= 100; tick++) {
            session.tick(tick, tick * 10L, player(0f, 0f, tick * 0.01f), false);
        }

        CaptureSession.Summary summary = session.getLastSummary();
        assertFalse(session.isRecording());
        assertEquals(10, summary.frameCount());
        assertEquals(CaptureSession.StopReason.MAX_FRAMES, summary.stopReason());

        session.tick(101, 1010L, player(0f, 0f, 1.01f), false);
        assertEquals(10, session.frames().size());
    }

    @Test
    void stopsCleanlyAtMaxDuration() {
        CaptureSession session = new CaptureSession(30.0, 1000, 0.5);
        session.start(0L);

        for (int tick = 1; tick <= 100; tick++) {
            session.tick(tick, tick * 10L, player(0f, 0f, tick * 0.01f), false);
        }

        CaptureSession.Summary summary = session.getLastSummary();
        assertFalse(session.isRecording());
        assertEquals(CaptureSession.StopReason.MAX_DURATION, summary.stopReason());
        assertTrue(summary.durationSeconds() >= 0.5f && summary.durationSeconds() < 0.53f,
            "duration=" + summary.durationSeconds());
    }

    @Test
    void thirtyMinuteCaptureStopsAtDocumentedFrameCap() {
        CaptureSession session = new CaptureSession();
        session.start(0L);

        long wall = 0L;
        for (int tick = 1; tick <= 60_000 && session.isRecording(); tick++) {
            wall += 33L;
            session.tick(tick, wall, player(0f, 0f, tick * 0.033f), false);
        }

        CaptureSession.Summary summary = session.getLastSummary();
        assertFalse(session.isRecording());
        assertEquals(CaptureSession.MAX_FRAMES, summary.frameCount());
        assertEquals((long) CaptureSession.MAX_FRAMES * CaptureSession.ESTIMATED_BYTES_PER_FRAME,
            summary.estimatedBytes());
        assertEquals(CaptureSession.StopReason.MAX_FRAMES, summary.stopReason());
    }

    @Test
    void pauseIsExcludedFromTimelineAndCadence() {
        CaptureSession session = new CaptureSession(30.0);
        session.start(0L);

        long wall = 0L;
        for (int tick = 1; tick <= 100; tick++) {
            wall += 10L;
            session.tick(tick, wall, player(0f, 0f, tick * 0.01f), false);
        }
        int framesBeforePause = session.frames().size();

        for (int tick = 101; tick <= 600; tick++) {
            wall += 10L;
            session.tick(tick, wall, null, true);
        }
        assertEquals(framesBeforePause, session.frames().size());

        for (int tick = 601; tick <= 700; tick++) {
            wall += 10L;
            session.tick(tick, wall, player(0f, 0f, tick * 0.01f), false);
        }

        CaptureSession.Summary summary = session.status();
        assertEquals(2.0f, summary.durationSeconds(), 0.02f);
        assertEquals(5.0f, summary.pausedSeconds(), 0.02f);
        assertEquals(30.0, summary.actualRateHz(), 0.5);
        assertTrue(session.frames().get(session.frames().size() - 1).timeSeconds() < 2.05f);
    }

    @Test
    void longWallGapIsCountedWithoutCorruptingTimeline() {
        CaptureSession session = new CaptureSession(30.0);
        session.start(0L);
        session.tick(1, 10L, player(0f, 0f, 0f), false);
        session.tick(2, 60_010L, player(0f, 0f, 0.016f), false);

        CaptureSession.Summary summary = session.status();
        assertEquals(0.26f, summary.durationSeconds(), 0.001f);
        assertEquals(59.75f, summary.gapSeconds(), 0.001f);
        assertTrue(session.frames().get(session.frames().size() - 1).timeSeconds() < 0.3f);
    }

    @Test
    void gameTimeAdvancesIndependentlyAtThreeTimesSpeed() {
        CaptureSession session = new CaptureSession(30.0);
        session.start(0L);

        session.tick(1, 10L, player(0f, 0f, 0f), false);
        session.tick(2, 40L, player(0f, 0f, 0.09f), false);

        List<ReplaySample> frames = session.frames();
        assertEquals(2, frames.size());
        assertEquals(0.09f, frames.get(1).gameTimeSeconds(), 0.0001f);
        assertEquals(0.04f, frames.get(1).timeSeconds(), 0.0001f);
    }

    @Test
    void stopWithNoFramesReportsNoFrames() {
        CaptureSession session = new CaptureSession();
        session.start(0L);

        CaptureSession.Summary summary = session.stop(CaptureSession.StopReason.REQUESTED);

        assertEquals(0, summary.frameCount());
        assertEquals(CaptureSession.StopReason.NO_FRAMES, summary.stopReason());
        assertTrue(summary.format().contains("stop=no_frames"));
    }

    @Test
    void stopKeepsSamplesInMemoryAndFormatsSummary() {
        CaptureSession session = new CaptureSession(30.0);
        session.start(0L);
        for (int tick = 1; tick <= 100; tick++) {
            session.tick(tick, tick * 10L, player(0f, 0f, tick * 0.01f), false);
        }

        CaptureSession.Summary summary = session.stop(CaptureSession.StopReason.REQUESTED);
        List<ReplaySample> frames = session.frames();

        assertFalse(session.isRecording());
        assertEquals(frames.size(), summary.frameCount());
        assertEquals(frames.size(), session.getLastSummary().frameCount());
        String text = summary.format();
        assertTrue(text.contains("frames="));
        assertTrue(text.contains("duration="));
        assertTrue(text.contains("rate=30.0Hz"));
        assertTrue(text.contains("actual="));
        assertTrue(text.contains("estimated_bytes="));
        assertTrue(text.contains("stop=requested"));
    }

    @Test
    void flagsCoverAimingAndVehicleState() {
        int flags = ReplaySample.flags(true, false, false, false, false, true, true);
        ReplaySample sample = new ReplaySample(1, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, flags);

        assertTrue(sample.aiming());
        assertTrue(sample.inVehicle());
        assertFalse(new ReplaySample(2, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, 0).aiming());
    }

    @Test
    void snapshotDefaultsMissingStates() {
        CaptureSession.PlayerSnapshot snapshot = new CaptureSession.PlayerSnapshot(
            1f, 2f, 3f, 4f, 5f, null, null, 0);

        assertEquals(ReplaySample.Locomotion.IDLE, snapshot.locomotion());
        assertEquals(ReplaySample.Stance.STAND, snapshot.stance());
    }
}
