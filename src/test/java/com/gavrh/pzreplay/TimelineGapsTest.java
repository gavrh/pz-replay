package com.gavrh.pzreplay;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineGapsTest {
    @TempDir
    Path tempDir;

    private static CaptureSample frame(float time, float x) {
        return new CaptureSample(0L, time, 0f, x, 20f, 0f, 0f, 0f,
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND, 0);
    }

    private static TimelineGap gap(float start, float end, TimelineGap.Reason reason,
        float lastX, float nextX) {
        return new TimelineGap(start, end, reason, lastX, 20f, 0f, nextX, 20f, 0f);
    }

    private static ReplayMetadata metadata(TimelineGaps gaps) {
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
            0L,
            60_000L,
            Appearance.none(),
            AppearanceChanges.none(),
            Equipment.none(),
            ActionEvents.none(),
            Vehicles.none(),
            gaps
        );
    }

    @Test
    void roundTripsEveryGapReasonThroughTheBinaryReplayFormat() throws Exception {
        List<TimelineGap> source = new ArrayList<>();
        TimelineGap.Reason[] reasons = TimelineGap.Reason.values();
        for (int index = 0; index < reasons.length; index++) {
            source.add(gap(index, index + 0.5f, reasons[index], index, index + 10f));
        }
        TimelineGaps gaps = TimelineGaps.encode(source);
        Path path = tempDir.resolve("gaps.pzrp.gz");

        FormatWriter.write(path, metadata(gaps),
            List.of(frame(0f, 0f), frame(1f, 1f), frame(8f, 8f)));
        ReplayData data = ReplayReader.read(path);
        PlaybackTimeline timeline = new PlaybackTimeline(data);

        assertEquals(gaps, data.metadata().gaps());
        assertEquals(reasons.length, timeline.getGapCount());
        assertTrue(timeline.getGapWarnings().isEmpty());
        assertEquals(reasons.length, timeline.getGaps().size());
        for (int index = 0; index < reasons.length; index++) {
            assertEquals(reasons[index], timeline.getGaps().get(index).reason());
        }
    }

    @Test
    void oldReplaysWithoutGapDataRemainReadable() throws Exception {
        TimelineGaps gaps = TimelineGaps.none();
        ReplayMetadata metadata = metadata(gaps);

        assertFalse(gaps.isPresent());
        assertTrue(gaps.warnings().isEmpty());
        assertFalse(metadata.toValues().containsKey("gaps_version"));
        assertFalse(metadata.toValues().containsKey("gaps_data"));

        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata,
            List.of(frame(0f, 0f), frame(1f, 1f))));
        assertEquals(0, timeline.getGapCount());
        assertFalse(timeline.isGapHold(0.5));
        assertTrue(timeline.getGapWarnings().isEmpty());
        assertEquals(0.5f, timeline.sampleAt(0.5).x(), 0.0001f);
    }

    @Test
    void corruptUnsupportedAndMissingDataDegradeWithWarnings() throws Exception {
        TimelineGaps corrupt = new TimelineGaps(TimelineGaps.CURRENT_VERSION, "not base64!");
        TimelineGaps unsupported = new TimelineGaps(
            TimelineGaps.CURRENT_VERSION + 1, "AQ==");
        TimelineGaps missingPayload = new TimelineGaps(
            TimelineGaps.CURRENT_VERSION, "");

        assertTrue(corrupt.warnings().get(0).contains("corrupt or incomplete"));
        assertTrue(unsupported.warnings().get(0).contains("version is unsupported"));
        assertFalse(missingPayload.warnings().isEmpty());

        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(corrupt),
            List.of(frame(0f, 0f), frame(1f, 10f))));
        assertEquals(2, timeline.getFrameCount());
        assertEquals(0, timeline.getGapCount());
        assertFalse(timeline.getGapWarnings().isEmpty());
        assertEquals(5f, timeline.sampleAt(0.5).x(), 0.0001f);
    }

    @Test
    void invalidAndOverlappingGapsAreSkippedWithoutBreakingOtherGaps()
        throws Exception {
        List<TimelineGap> gaps = new ArrayList<>();
        gaps.add(null);
        gaps.add(gap(0.2f, 0.4f, TimelineGap.Reason.TELEPORT, 1f, 2f));
        gaps.add(gap(0.3f, 0.5f, TimelineGap.Reason.LOADING, 3f, 4f));
        gaps.add(gap(1.0f, 2.0f, null, 5f, 6f));

        TimelineGapPlayback timeline = new TimelineGapPlayback(gaps, 3.0);

        assertFalse(timeline.warnings().isEmpty());
        assertFalse(timeline.gaps().isEmpty());
        for (TimelineGap gap : timeline.gaps()) {
            assertNotNull(gap.reason());
        }
    }

    @Test
    void codecRejectsTooManyGapsAndOverlappingInput() throws Exception {
        List<TimelineGap> tooMany = new ArrayList<>();
        for (int index = 0; index < TimelineGapCodec.MAX_GAPS + 1; index++) {
            tooMany.add(gap(index, index + 0.5f, TimelineGap.Reason.LONG_TICK, 0f, 1f));
        }
        assertThrows(ReplayFormatException.class, () -> TimelineGapCodec.encode(tooMany));

        assertThrows(ReplayFormatException.class, () -> TimelineGapCodec.encode(List.of(
            gap(0.0f, 1.0f, TimelineGap.Reason.TELEPORT, 0f, 1f),
            gap(0.5f, 2.0f, TimelineGap.Reason.LOADING, 0f, 1f))));
    }

    @Test
    void decodeSkipsUnknownReasonsAndInvalidRows() throws Exception {
        byte[] valid = TimelineGapCodec.encode(List.of(
            gap(0.0f, 1.0f, TimelineGap.Reason.TELEPORT, 0f, 1f),
            gap(2.0f, 3.0f, TimelineGap.Reason.DEATH, 0f, 1f)));
        valid[14] = (byte) 99;

        TimelineGapCodec.Result result = TimelineGapCodec.decode(valid);

        assertEquals(1, result.gaps().size());
        assertEquals(TimelineGap.Reason.DEATH, result.gaps().get(0).reason());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void metadataOmitsGapKeysWhenMissingAndPreservesThemOnUpdates()
        throws Exception {
        TimelineGaps gaps = TimelineGaps.encode(List.of(
            gap(0f, 1f, TimelineGap.Reason.TELEPORT, 0f, 1f)));
        ReplayMetadata present = metadata(gaps);

        assertEquals(gaps, present.withEnd(99L).gaps());
        assertEquals(gaps, present.withVehicles(Vehicles.none()).gaps());
        assertEquals(gaps, ReplayMetadata.fromValues(present.toValues()).gaps());
        assertEquals(TimelineGaps.none(),
            ReplayMetadata.fromValues(metadata(TimelineGaps.none()).toValues()).gaps());
    }

    @Test
    void metadataParsesGapValuesFromTheHeader() throws Exception {
        TimelineGaps gaps = TimelineGaps.encode(List.of(
            gap(0f, 1f, TimelineGap.Reason.RECORDING_PAUSE, 0f, 1f)));
        Map<String, String> values = metadata(gaps).toValues();

        assertEquals(Integer.toString(TimelineGaps.CURRENT_VERSION),
            values.get("gaps_version"));
        assertEquals(gaps.data(), values.get("gaps_data"));
        assertEquals(Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(values.get("gaps_data"))), gaps.data());
    }

    @Test
    void compatibilityReportsOnlyActualGapDataProblems() {
        ReplayCompatibility.Context context = new ReplayCompatibility.Context(
            "42.21.0", "save", "map", "Sandbox");

        assertFalse(ReplayCompatibility.check(metadata(TimelineGaps.none()), context)
            .warnings().stream().anyMatch(warning -> warning.contains("timeline gap")));
        assertTrue(ReplayCompatibility.check(metadata(new TimelineGaps(
            TimelineGaps.CURRENT_VERSION, "not base64!")), context)
            .warnings().stream().anyMatch(warning -> warning.contains("timeline gap")));
    }

    @Test
    void endBoundaryGapSurvivesFractionalFrameTimes() throws Exception {
        TimelineGaps gaps = TimelineGaps.encode(List.of(
            gap(1.974f, 1.974f, TimelineGap.Reason.DEATH, 10f, 10f)));
        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(gaps),
            List.of(frame(0.007f, 0f), frame(1.974f, 10f))));

        assertEquals(1, timeline.getGapCount());
        assertTrue(timeline.getGapWarnings().isEmpty());
        assertEquals(TimelineGap.Reason.DEATH, timeline.getGaps().get(0).reason());
        assertFalse(timeline.isGapHold(1.0));
    }

    @Test
    void boundaryOnlyGapDoesNotHoldOrInterpolate() throws Exception {
        TimelineGaps gaps = TimelineGaps.encode(List.of(
            gap(1.0f, 1.0f, TimelineGap.Reason.DEATH, 10f, 10f)));
        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(gaps),
            List.of(frame(0f, 0f), frame(1f, 10f))));

        assertEquals(1, timeline.getGapCount());
        assertFalse(timeline.isGapHold(0.5));
        assertEquals(5f, timeline.sampleAt(0.5).x(), 0.0001f);
        assertNull(timeline.gapReasonAt(0.5));
    }
}
