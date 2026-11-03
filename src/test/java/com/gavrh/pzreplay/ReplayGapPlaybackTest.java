package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayGapPlaybackTest {
    private static CaptureSample frame(float time, float x) {
        return new CaptureSample(0L, time, 0f, x, 0f, 0f, 0f, 0f,
            CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND,
            CaptureSample.flags(false, false, false, false, false, true));
    }

    private static ReplayMetadata metadata(ReplayGaps gaps, ReplayVehicles vehicles) {
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
            ReplayAppearanceChanges.none(),
            ReplayEquipment.none(),
            ReplayEvents.none(),
            vehicles,
            gaps
        );
    }

    private static ReplayTimeline teleportTimeline(ReplayVehicles vehicles)
        throws ReplayFormatException {
        ReplayGaps gaps = ReplayGaps.encode(List.of(new ReplayGap(
            10.5f, 11.0f, ReplayGap.Reason.TELEPORT, 5f, 0f, 0f, 100f, 0f, 0f)));
        return new ReplayTimeline(new ReplayData(metadata(gaps, vehicles), List.of(
            frame(10f, 0f),
            frame(10.5f, 5f),
            frame(11f, 100f),
            frame(12f, 102f))));
    }

    @Test
    void positionIsHeldAtTheLastValidSampleInsteadOfInterpolatingAcrossTheGap()
        throws Exception {
        ReplayTimeline timeline = teleportTimeline(ReplayVehicles.none());

        assertEquals(1, timeline.getGapCount());
        assertEquals(2.5f, timeline.sampleAt(0.25).x(), 0.0001f);
        assertEquals(5f, timeline.sampleAt(0.5).x(), 0.0001f);
        assertEquals(5f, timeline.sampleAt(0.75).x(), 0.0001f);
        assertEquals(5f, timeline.sampleAt(0.99).x(), 0.0001f);
        assertEquals(100f, timeline.sampleAt(1.0).x(), 0.0001f);
        assertEquals(101f, timeline.sampleAt(1.5).x(), 0.0001f);
        assertTrue(timeline.isGapHold(0.75));
        assertEquals(ReplayGap.Reason.TELEPORT, timeline.gapReasonAt(0.75));
        assertNull(timeline.gapReasonAt(1.0));
    }

    @Test
    void holdExtendsToTheFirstFrameAfterAnUnalignedGapEnd() throws Exception {
        ReplayGaps gaps = ReplayGaps.encode(List.of(new ReplayGap(
            1.0f, 1.2f, ReplayGap.Reason.TELEPORT, 0f, 0f, 0f, 50f, 0f, 0f)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(gaps, ReplayVehicles.none()), List.of(
                frame(0f, 0f),
                frame(1.0f, 0f),
                frame(1.5f, 50f),
                frame(2.0f, 50f))));

        assertEquals(0f, timeline.sampleAt(1.3).x(), 0.0001f);
        assertTrue(timeline.isGapHold(1.3));
        assertEquals(0f, timeline.sampleAt(1.49).x(), 0.0001f);
        assertEquals(50f, timeline.sampleAt(1.5).x(), 0.0001f);
        assertFalse(timeline.isGapHold(1.5));
    }

    @Test
    void movementDirectionIsNotDerivedAcrossAGap() throws Exception {
        ReplayTimeline timeline = teleportTimeline(ReplayVehicles.none());

        ReplayTimeline.Movement held = timeline.movementAt(0.75);
        ReplayTimeline.Movement before = timeline.movementAt(0.3);
        ReplayTimeline.Movement after = timeline.movementAt(1.5);

        assertFalse(held.isMoving());
        assertTrue(before.isMoving());
        assertTrue(after.isMoving());
    }

    @Test
    void seekReconstructsTheCorrectSideOfTheGap() throws Exception {
        ReplayTimeline timeline = teleportTimeline(ReplayVehicles.none());
        timeline.start();

        assertTrue(timeline.seek(0.75));
        assertEquals(5f, timeline.sample().x(), 0.0001f);
        assertTrue(timeline.isGapHold(timeline.getCurrentTime()));

        assertTrue(timeline.seek(1.0));
        assertEquals(100f, timeline.sample().x(), 0.0001f);
        assertFalse(timeline.isGapHold(timeline.getCurrentTime()));

        assertTrue(timeline.seek(0.0));
        assertEquals(0f, timeline.sample().x(), 0.0001f);

        assertTrue(timeline.restart());
        assertEquals(0.0, timeline.getCurrentTime(), 0.0001);
        assertEquals(0f, timeline.sample().x(), 0.0001f);
    }

    @Test
    void speedAdvanceSnapsToTheDestinationWithoutInterpolating() throws Exception {
        ReplayTimeline timeline = teleportTimeline(ReplayVehicles.none());
        timeline.start();

        assertTrue(timeline.advance(0.2));
        assertEquals(2f, timeline.sample().x(), 0.0001f);

        assertTrue(timeline.setSpeed(4.0));
        assertTrue(timeline.advance(0.2));
        assertEquals(1.0, timeline.getCurrentTime(), 0.0001);

        assertEquals(100f, timeline.sample().x(), 0.0001f);
    }

    @Test
    void vehicleStateIsSuppressedDuringTheGapAndRestoredAtTheDestination()
        throws Exception {
        ReplayVehicles vehicles = ReplayVehicles.encode(List.of(
            new ReplayVehicleSegment(0f, 2f, "Base.Car", "vehicles/car", 0, 0,
                List.of(new ReplayVehicleSample(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
                    new ReplayVehicleSample(2f, 200f, 0f, 0f, 0f, 0f, 0f, 1f)))));
        ReplayTimeline timeline = teleportTimeline(vehicles);

        assertEquals(1, timeline.getVehicleCount());
        assertEquals(50f, timeline.vehicleAt(0.5).x(), 0.0001f);
        assertNull(timeline.vehicleAt(0.75));
        assertEquals(100f, timeline.vehicleAt(1.0).x(), 0.0001f);
    }

    @Test
    void endOfReplayStopsAtTheRecordedEnd() throws Exception {
        ReplayTimeline timeline = teleportTimeline(ReplayVehicles.none());
        timeline.start();

        assertFalse(timeline.advance(10.0));
        assertFalse(timeline.isPlaying());
        assertEquals(timeline.getDuration(), timeline.getCurrentTime(), 0.0001);
        assertEquals(102f, timeline.sample().x(), 0.0001f);

        assertTrue(timeline.restart());
        assertEquals(0f, timeline.sample().x(), 0.0001f);
    }
}
