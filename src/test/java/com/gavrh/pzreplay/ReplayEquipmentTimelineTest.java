package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEquipmentTimelineTest {
    private static ReplayEquipment equipment(String value) {
        return new ReplayEquipment(ReplayEquipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static CaptureSample frame(float time, float x) {
        return new CaptureSample(0L, time, 0f, x, 0f, 0f, 0f, 0f,
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND,
            CaptureSample.flags(false, false, false, false, false, false));
    }

    private static ReplayMetadata metadata(ReplayEquipment initial,
        ReplayEquipmentChanges changes, ReplayGaps gaps) {
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
            initial
        ).withEquipmentChanges(changes).withGaps(gaps);
    }

    private static ReplayTimeline timeline(ReplayEquipment initial,
        ReplayEquipmentChanges changes) throws ReplayFormatException {
        return new ReplayTimeline(new ReplayData(metadata(initial, changes, ReplayGaps.none()),
            List.of(frame(0f, 0f), frame(1f, 1f), frame(2f, 2f), frame(3f, 3f))));
    }

    private static ReplayEquipmentChanges changes(ReplayEquipment initial,
        ReplayEquipment... values) throws ReplayFormatException {
        List<ReplayEquipmentChange> changes = new java.util.ArrayList<>();
        changes.add(new ReplayEquipmentChange(1L, 0.0f, initial));
        for (int index = 0; index < values.length; index++) {
            changes.add(new ReplayEquipmentChange(index + 2L, index + 1.0f, values[index]));
        }
        return ReplayEquipmentChanges.encode(changes);
    }

    @Test
    void appliesTheLatestChangeAtOrBeforeThePlaybackTime() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayEquipment pistol = equipment("pistol");
        ReplayEquipment rifle = equipment("rifle");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol, rifle));

        assertEquals(initial, timeline.sampleAt(0.0).equipment());
        assertEquals(initial, timeline.sampleAt(0.99).equipment());
        assertEquals(pistol, timeline.sampleAt(1.0).equipment());
        assertEquals(pistol, timeline.sampleAt(1.99).equipment());
        assertEquals(rifle, timeline.sampleAt(2.0).equipment());
        assertEquals(rifle, timeline.sampleAt(3.0).equipment());
    }

    @Test
    void seekForwardUsesOnlyTheChangeCrossedByTheSeek() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayEquipment pistol = equipment("pistol");
        ReplayEquipment rifle = equipment("rifle");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol, rifle));
        timeline.start();

        assertTrue(timeline.seek(1.5));
        assertEquals(pistol, timeline.sample().equipment());
        assertTrue(timeline.seek(2.5));
        assertEquals(rifle, timeline.sample().equipment());
    }

    @Test
    void seekBackwardReconstructsTheLatestEarlierChange() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayEquipment pistol = equipment("pistol");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol));
        timeline.start();
        timeline.seek(2.5);
        assertEquals(pistol, timeline.sample().equipment());

        assertTrue(timeline.seek(0.5));

        assertEquals(initial, timeline.sample().equipment());
    }

    @Test
    void restartReturnsToTheT0Equipment() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayEquipment pistol = equipment("pistol");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol));
        timeline.start();
        timeline.seek(2.5);
        assertEquals(pistol, timeline.sample().equipment());

        assertTrue(timeline.restart());

        assertEquals(initial, timeline.sample().equipment());
    }

    @Test
    void changeTimesAreRelativeToTheFirstRecordedFrame() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayEquipment pistol = equipment("pistol");
        ReplayEquipmentChanges changes = ReplayEquipmentChanges.encode(List.of(
            new ReplayEquipmentChange(1L, 10.0f, initial),
            new ReplayEquipmentChange(2L, 10.5f, pistol)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(initial, changes, ReplayGaps.none()),
            List.of(frame(10f, 0f), frame(11f, 1f))));

        assertEquals(initial, timeline.sampleAt(0.25).equipment());
        assertEquals(pistol, timeline.sampleAt(0.5).equipment());
    }

    @Test
    void gapClearsHeldItemsAndReappliesTheDestinationState() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayEquipment pistol = equipment("pistol");
        ReplayGaps gaps = ReplayGaps.encode(List.of(new ReplayGap(
            0.5f, 1.0f, ReplayGap.Reason.TELEPORT, 0f, 0f, 0f, 10f, 0f, 0f)));
        ReplayEquipmentChanges changes = ReplayEquipmentChanges.encode(List.of(
            new ReplayEquipmentChange(1L, 0.0f, initial),
            new ReplayEquipmentChange(2L, 1.0f, pistol)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(initial, changes, gaps),
            List.of(frame(0f, 0f), frame(0.5f, 0f), frame(1.0f, 10f), frame(2f, 12f))));

        assertTrue(timeline.isGapHold(0.75));
        assertEquals(ReplayEquipment.none(), timeline.sampleAt(0.75).equipment());
        assertFalse(timeline.isGapHold(1.0));
        assertEquals(pistol, timeline.sampleAt(1.0).equipment());
    }

    @Test
    void gapReappliesTheHeldItemThatWasAlreadyEquippedBeforeTheGap() throws Exception {
        ReplayEquipment initial = equipment("initial");
        ReplayGaps gaps = ReplayGaps.encode(List.of(new ReplayGap(
            0.5f, 1.0f, ReplayGap.Reason.TELEPORT, 0f, 0f, 0f, 10f, 0f, 0f)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(initial, ReplayEquipmentChanges.none(), gaps),
            List.of(frame(0f, 0f), frame(0.5f, 0f), frame(1.0f, 10f), frame(2f, 12f))));

        assertEquals(ReplayEquipment.none(), timeline.sampleAt(0.75).equipment());
        assertEquals(initial, timeline.sampleAt(1.0).equipment());
    }

    @Test
    void replayWithoutChangeDataFallsBackToTheExistingSnapshot() throws Exception {
        ReplayEquipment initial = equipment("snapshot");
        ReplayTimeline timeline = timeline(initial, ReplayEquipmentChanges.none());

        assertEquals(0, timeline.getEquipmentChangeCount());
        assertTrue(timeline.getEquipmentWarnings().isEmpty());
        assertEquals(initial, timeline.sampleAt(0.0).equipment());
        assertEquals(initial, timeline.sampleAt(2.5).equipment());
    }

    @Test
    void corruptChangeDataWarnsAndFallsBackToTheSnapshot() throws Exception {
        ReplayEquipment initial = equipment("snapshot");
        ReplayEquipmentChanges corrupt = new ReplayEquipmentChanges(
            ReplayEquipmentChanges.CURRENT_VERSION, "not base64!");
        ReplayTimeline timeline = timeline(initial, corrupt);

        assertFalse(timeline.getEquipmentWarnings().isEmpty());
        assertEquals(initial, timeline.sampleAt(1.5).equipment());
    }

    @Test
    void unsupportedChangeVersionWarnsAndFallsBackToTheSnapshot() throws Exception {
        ReplayEquipment initial = equipment("snapshot");
        ReplayEquipmentChanges unsupported = new ReplayEquipmentChanges(
            ReplayEquipmentChanges.CURRENT_VERSION + 1, "AQ==");
        ReplayTimeline timeline = timeline(initial, unsupported);

        assertFalse(timeline.getEquipmentWarnings().isEmpty());
        assertTrue(timeline.getEquipmentWarnings().get(0).contains("unsupported"));
        assertEquals(initial, timeline.sampleAt(1.5).equipment());
    }

    @Test
    void bothSlotsCanChangeIndependently() throws Exception {
        ReplayEquipment initial = equipment("primary=knife;secondary=");
        ReplayEquipment primaryOnly = equipment("primary=knife;secondary=flashlight");
        ReplayEquipment both = equipment("primary=pistol;secondary=flashlight");
        ReplayEquipment secondaryOnly = equipment("primary=pistol;secondary=");
        ReplayEquipmentChanges changes = ReplayEquipmentChanges.encode(List.of(
            new ReplayEquipmentChange(1L, 0.0f, initial),
            new ReplayEquipmentChange(2L, 1.0f, primaryOnly),
            new ReplayEquipmentChange(3L, 2.0f, both),
            new ReplayEquipmentChange(4L, 3.0f, secondaryOnly)));
        ReplayTimeline timeline = timeline(initial, changes);

        assertEquals(initial, timeline.sampleAt(0.5).equipment());
        assertEquals(primaryOnly, timeline.sampleAt(1.0).equipment());
        assertEquals(both, timeline.sampleAt(2.0).equipment());
        assertEquals(secondaryOnly, timeline.sampleAt(3.0).equipment());
    }
}
