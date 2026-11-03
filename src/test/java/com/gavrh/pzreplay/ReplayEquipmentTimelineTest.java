package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEquipmentTimelineTest {
    private static Equipment equipment(String value) {
        return new Equipment(Equipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static CaptureSample frame(float time, float x) {
        return new CaptureSample(0L, time, 0f, x, 0f, 0f, 0f, 0f,
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND,
            CaptureSample.flags(false, false, false, false, false, false));
    }

    private static ReplayMetadata metadata(Equipment initial,
        EquipmentChanges changes, ReplayGaps gaps) {
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
            Appearance.none(),
            initial
        ).withEquipmentChanges(changes).withGaps(gaps);
    }

    private static ReplayTimeline timeline(Equipment initial,
        EquipmentChanges changes) throws ReplayFormatException {
        return new ReplayTimeline(new ReplayData(metadata(initial, changes, ReplayGaps.none()),
            List.of(frame(0f, 0f), frame(1f, 1f), frame(2f, 2f), frame(3f, 3f))));
    }

    private static EquipmentChanges changes(Equipment initial,
        Equipment... values) throws ReplayFormatException {
        List<EquipmentChange> changes = new java.util.ArrayList<>();
        changes.add(new EquipmentChange(1L, 0.0f, initial));
        for (int index = 0; index < values.length; index++) {
            changes.add(new EquipmentChange(index + 2L, index + 1.0f, values[index]));
        }
        return EquipmentChanges.encode(changes);
    }

    @Test
    void appliesTheLatestChangeAtOrBeforeThePlaybackTime() throws Exception {
        Equipment initial = equipment("initial");
        Equipment pistol = equipment("pistol");
        Equipment rifle = equipment("rifle");
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
        Equipment initial = equipment("initial");
        Equipment pistol = equipment("pistol");
        Equipment rifle = equipment("rifle");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol, rifle));
        timeline.start();

        assertTrue(timeline.seek(1.5));
        assertEquals(pistol, timeline.sample().equipment());
        assertTrue(timeline.seek(2.5));
        assertEquals(rifle, timeline.sample().equipment());
    }

    @Test
    void seekBackwardReconstructsTheLatestEarlierChange() throws Exception {
        Equipment initial = equipment("initial");
        Equipment pistol = equipment("pistol");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol));
        timeline.start();
        timeline.seek(2.5);
        assertEquals(pistol, timeline.sample().equipment());

        assertTrue(timeline.seek(0.5));

        assertEquals(initial, timeline.sample().equipment());
    }

    @Test
    void restartReturnsToTheT0Equipment() throws Exception {
        Equipment initial = equipment("initial");
        Equipment pistol = equipment("pistol");
        ReplayTimeline timeline = timeline(initial, changes(initial, pistol));
        timeline.start();
        timeline.seek(2.5);
        assertEquals(pistol, timeline.sample().equipment());

        assertTrue(timeline.restart());

        assertEquals(initial, timeline.sample().equipment());
    }

    @Test
    void changeTimesAreRelativeToTheFirstRecordedFrame() throws Exception {
        Equipment initial = equipment("initial");
        Equipment pistol = equipment("pistol");
        EquipmentChanges changes = EquipmentChanges.encode(List.of(
            new EquipmentChange(1L, 10.0f, initial),
            new EquipmentChange(2L, 10.5f, pistol)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(initial, changes, ReplayGaps.none()),
            List.of(frame(10f, 0f), frame(11f, 1f))));

        assertEquals(initial, timeline.sampleAt(0.25).equipment());
        assertEquals(pistol, timeline.sampleAt(0.5).equipment());
    }

    @Test
    void gapClearsHeldItemsAndReappliesTheDestinationState() throws Exception {
        Equipment initial = equipment("initial");
        Equipment pistol = equipment("pistol");
        ReplayGaps gaps = ReplayGaps.encode(List.of(new ReplayGap(
            0.5f, 1.0f, ReplayGap.Reason.TELEPORT, 0f, 0f, 0f, 10f, 0f, 0f)));
        EquipmentChanges changes = EquipmentChanges.encode(List.of(
            new EquipmentChange(1L, 0.0f, initial),
            new EquipmentChange(2L, 1.0f, pistol)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(initial, changes, gaps),
            List.of(frame(0f, 0f), frame(0.5f, 0f), frame(1.0f, 10f), frame(2f, 12f))));

        assertTrue(timeline.isGapHold(0.75));
        assertEquals(Equipment.none(), timeline.sampleAt(0.75).equipment());
        assertFalse(timeline.isGapHold(1.0));
        assertEquals(pistol, timeline.sampleAt(1.0).equipment());
    }

    @Test
    void gapReappliesTheHeldItemThatWasAlreadyEquippedBeforeTheGap() throws Exception {
        Equipment initial = equipment("initial");
        ReplayGaps gaps = ReplayGaps.encode(List.of(new ReplayGap(
            0.5f, 1.0f, ReplayGap.Reason.TELEPORT, 0f, 0f, 0f, 10f, 0f, 0f)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(
            metadata(initial, EquipmentChanges.none(), gaps),
            List.of(frame(0f, 0f), frame(0.5f, 0f), frame(1.0f, 10f), frame(2f, 12f))));

        assertEquals(Equipment.none(), timeline.sampleAt(0.75).equipment());
        assertEquals(initial, timeline.sampleAt(1.0).equipment());
    }

    @Test
    void replayWithoutChangeDataFallsBackToTheExistingSnapshot() throws Exception {
        Equipment initial = equipment("snapshot");
        ReplayTimeline timeline = timeline(initial, EquipmentChanges.none());

        assertEquals(0, timeline.getEquipmentChangeCount());
        assertTrue(timeline.getEquipmentWarnings().isEmpty());
        assertEquals(initial, timeline.sampleAt(0.0).equipment());
        assertEquals(initial, timeline.sampleAt(2.5).equipment());
    }

    @Test
    void corruptChangeDataWarnsAndFallsBackToTheSnapshot() throws Exception {
        Equipment initial = equipment("snapshot");
        EquipmentChanges corrupt = new EquipmentChanges(
            EquipmentChanges.CURRENT_VERSION, "not base64!");
        ReplayTimeline timeline = timeline(initial, corrupt);

        assertFalse(timeline.getEquipmentWarnings().isEmpty());
        assertEquals(initial, timeline.sampleAt(1.5).equipment());
    }

    @Test
    void unsupportedChangeVersionWarnsAndFallsBackToTheSnapshot() throws Exception {
        Equipment initial = equipment("snapshot");
        EquipmentChanges unsupported = new EquipmentChanges(
            EquipmentChanges.CURRENT_VERSION + 1, "AQ==");
        ReplayTimeline timeline = timeline(initial, unsupported);

        assertFalse(timeline.getEquipmentWarnings().isEmpty());
        assertTrue(timeline.getEquipmentWarnings().get(0).contains("unsupported"));
        assertEquals(initial, timeline.sampleAt(1.5).equipment());
    }

    @Test
    void bothSlotsCanChangeIndependently() throws Exception {
        Equipment initial = equipment("primary=knife;secondary=");
        Equipment primaryOnly = equipment("primary=knife;secondary=flashlight");
        Equipment both = equipment("primary=pistol;secondary=flashlight");
        Equipment secondaryOnly = equipment("primary=pistol;secondary=");
        EquipmentChanges changes = EquipmentChanges.encode(List.of(
            new EquipmentChange(1L, 0.0f, initial),
            new EquipmentChange(2L, 1.0f, primaryOnly),
            new EquipmentChange(3L, 2.0f, both),
            new EquipmentChange(4L, 3.0f, secondaryOnly)));
        ReplayTimeline timeline = timeline(initial, changes);

        assertEquals(initial, timeline.sampleAt(0.5).equipment());
        assertEquals(primaryOnly, timeline.sampleAt(1.0).equipment());
        assertEquals(both, timeline.sampleAt(2.0).equipment());
        assertEquals(secondaryOnly, timeline.sampleAt(3.0).equipment());
    }
}
