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

class ReplayGapsTest {
    @TempDir
    Path tempDir;

    private static CaptureSample frame(float time, float x) {
        return new CaptureSample(0L, time, 0f, x, 20f, 0f, 0f, 0f,
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND, 0);
    }

    private static ReplayGap gap(float start, float end, ReplayGap.Reason reason,
        float lastX, float nextX) {
        return new ReplayGap(start, end, reason, lastX, 20f, 0f, nextX, 20f, 0f);
    }

    private static ReplayMetadata metadata(ReplayGaps gaps) {
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
            ReplayVehicles.none(),
            gaps
        );
    }

    @Test
    void roundTripsEveryGapReasonThroughTheBinaryReplayFormat() throws Exception {
        List<ReplayGap> source = new ArrayList<>();
        ReplayGap.Reason[] reasons = ReplayGap.Reason.values();
        for (int index = 0; index < reasons.length; index++) {
            source.add(gap(index, index + 0.5f, reasons[index], index, index + 10f));
        }
        ReplayGaps gaps = ReplayGaps.encode(source);
        Path path = tempDir.resolve("gaps.pzrp.gz");

        BinaryReplayCodec.write(path, metadata(gaps),
            List.of(frame(0f, 0f), frame(1f, 1f), frame(8f, 8f)), true);
        ReplayData data = ReplayReader.read(path);
        ReplayTimeline timeline = new ReplayTimeline(data);

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
        ReplayGaps gaps = ReplayGaps.none();
        ReplayMetadata metadata = metadata(gaps);

        assertFalse(gaps.isPresent());
        assertTrue(gaps.warnings().isEmpty());
        assertFalse(metadata.toValues().containsKey("gaps_version"));
        assertFalse(metadata.toValues().containsKey("gaps_data"));

        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(metadata,
            List.of(frame(0f, 0f), frame(1f, 1f))));
        assertEquals(0, timeline.getGapCount());
        assertFalse(timeline.isGapHold(0.5));
        assertTrue(timeline.getGapWarnings().isEmpty());
        assertEquals(0.5f, timeline.sampleAt(0.5).x(), 0.0001f);
    }

    @Test
    void corruptUnsupportedAndMissingDataDegradeWithWarnings() throws Exception {
        ReplayGaps corrupt = new ReplayGaps(ReplayGaps.CURRENT_VERSION, "not base64!");
        ReplayGaps unsupported = new ReplayGaps(
            ReplayGaps.CURRENT_VERSION + 1, "AQ==");
        ReplayGaps missingPayload = new ReplayGaps(
            ReplayGaps.CURRENT_VERSION, "");

        assertTrue(corrupt.warnings().get(0).contains("corrupt or incomplete"));
        assertTrue(unsupported.warnings().get(0).contains("version is unsupported"));
        assertFalse(missingPayload.warnings().isEmpty());

        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(metadata(corrupt),
            List.of(frame(0f, 0f), frame(1f, 10f))));
        assertEquals(2, timeline.getFrameCount());
        assertEquals(0, timeline.getGapCount());
        assertFalse(timeline.getGapWarnings().isEmpty());
        assertEquals(5f, timeline.sampleAt(0.5).x(), 0.0001f);
    }

    @Test
    void invalidAndOverlappingGapsAreSkippedWithoutBreakingOtherGaps()
        throws Exception {
        List<ReplayGap> gaps = new ArrayList<>();
        gaps.add(null);
        gaps.add(gap(0.2f, 0.4f, ReplayGap.Reason.TELEPORT, 1f, 2f));
        gaps.add(gap(0.3f, 0.5f, ReplayGap.Reason.LOADING, 3f, 4f));
        gaps.add(gap(1.0f, 2.0f, null, 5f, 6f));

        ReplayGapTimeline timeline = new ReplayGapTimeline(gaps, 3.0);

        assertFalse(timeline.warnings().isEmpty());
        assertFalse(timeline.gaps().isEmpty());
        for (ReplayGap gap : timeline.gaps()) {
            assertNotNull(gap.reason());
        }
    }

    @Test
    void codecRejectsTooManyGapsAndOverlappingInput() throws Exception {
        List<ReplayGap> tooMany = new ArrayList<>();
        for (int index = 0; index < ReplayGapCodec.MAX_GAPS + 1; index++) {
            tooMany.add(gap(index, index + 0.5f, ReplayGap.Reason.LONG_TICK, 0f, 1f));
        }
        assertThrows(ReplayFormatException.class, () -> ReplayGapCodec.encode(tooMany));

        assertThrows(ReplayFormatException.class, () -> ReplayGapCodec.encode(List.of(
            gap(0.0f, 1.0f, ReplayGap.Reason.TELEPORT, 0f, 1f),
            gap(0.5f, 2.0f, ReplayGap.Reason.LOADING, 0f, 1f))));
    }

    @Test
    void decodeSkipsUnknownReasonsAndInvalidRows() throws Exception {
        byte[] valid = ReplayGapCodec.encode(List.of(
            gap(0.0f, 1.0f, ReplayGap.Reason.TELEPORT, 0f, 1f),
            gap(2.0f, 3.0f, ReplayGap.Reason.DEATH, 0f, 1f)));
        valid[14] = (byte) 99;

        ReplayGapCodec.Result result = ReplayGapCodec.decode(valid);

        assertEquals(1, result.gaps().size());
        assertEquals(ReplayGap.Reason.DEATH, result.gaps().get(0).reason());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void metadataOmitsGapKeysWhenMissingAndPreservesThemOnUpdates()
        throws Exception {
        ReplayGaps gaps = ReplayGaps.encode(List.of(
            gap(0f, 1f, ReplayGap.Reason.TELEPORT, 0f, 1f)));
        ReplayMetadata present = metadata(gaps);

        assertEquals(gaps, present.withEnd(99L).gaps());
        assertEquals(gaps, present.withVehicles(ReplayVehicles.none()).gaps());
        assertEquals(gaps, ReplayMetadata.fromValues(present.toValues()).gaps());
        assertEquals(ReplayGaps.none(),
            ReplayMetadata.fromValues(metadata(ReplayGaps.none()).toValues()).gaps());
    }

    @Test
    void metadataParsesGapValuesFromTheHeader() throws Exception {
        ReplayGaps gaps = ReplayGaps.encode(List.of(
            gap(0f, 1f, ReplayGap.Reason.RECORDING_PAUSE, 0f, 1f)));
        Map<String, String> values = metadata(gaps).toValues();

        assertEquals(Integer.toString(ReplayGaps.CURRENT_VERSION),
            values.get("gaps_version"));
        assertEquals(gaps.data(), values.get("gaps_data"));
        assertEquals(Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(values.get("gaps_data"))), gaps.data());
    }

    @Test
    void compatibilityReportsOnlyActualGapDataProblems() {
        ReplayCompatibility.Context context = new ReplayCompatibility.Context(
            "42.21.0", "save", "map", "Sandbox");

        assertFalse(ReplayCompatibility.check(metadata(ReplayGaps.none()), context)
            .warnings().stream().anyMatch(warning -> warning.contains("timeline gap")));
        assertTrue(ReplayCompatibility.check(metadata(new ReplayGaps(
            ReplayGaps.CURRENT_VERSION, "not base64!")), context)
            .warnings().stream().anyMatch(warning -> warning.contains("timeline gap")));
    }

    @Test
    void endBoundaryGapSurvivesFractionalFrameTimes() throws Exception {
        ReplayGaps gaps = ReplayGaps.encode(List.of(
            gap(1.974f, 1.974f, ReplayGap.Reason.DEATH, 10f, 10f)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(metadata(gaps),
            List.of(frame(0.007f, 0f), frame(1.974f, 10f))));

        assertEquals(1, timeline.getGapCount());
        assertTrue(timeline.getGapWarnings().isEmpty());
        assertEquals(ReplayGap.Reason.DEATH, timeline.getGaps().get(0).reason());
        assertFalse(timeline.isGapHold(1.0));
    }

    @Test
    void boundaryOnlyGapDoesNotHoldOrInterpolate() throws Exception {
        ReplayGaps gaps = ReplayGaps.encode(List.of(
            gap(1.0f, 1.0f, ReplayGap.Reason.DEATH, 10f, 10f)));
        ReplayTimeline timeline = new ReplayTimeline(new ReplayData(metadata(gaps),
            List.of(frame(0f, 0f), frame(1f, 10f))));

        assertEquals(1, timeline.getGapCount());
        assertFalse(timeline.isGapHold(0.5));
        assertEquals(5f, timeline.sampleAt(0.5).x(), 0.0001f);
        assertNull(timeline.gapReasonAt(0.5));
    }
}
