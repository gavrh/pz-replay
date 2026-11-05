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

class VehiclesTest {
    @TempDir
    Path tempDir;

    private static CaptureSample frame(float time, boolean inVehicle) {
        return new CaptureSample(0L, time, 0f, 10f, 20f, 0f, 0f, 0f,
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND,
            CaptureSample.flags(false, false, false, false, false, false, inVehicle));
    }

    private static VehicleSample sample(float time, float x) {
        return new VehicleSample(time, x, 20f, 0f, 0f, 0f, 0f, 1f);
    }

    private static VehicleSegment segment(float start, float end, String script,
        int seat, VehicleSample... samples) {
        return new VehicleSegment(start, end, script, "vehicles/script/model",
            0, seat, List.of(samples));
    }

    private static ReplayMetadata metadata(Vehicles vehicles) {
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
            vehicles
        );
    }

    @Test
    void roundTripsSegmentsThroughTheBinaryReplayFormat() throws Exception {
        Vehicles vehicles = Vehicles.encode(List.of(
            segment(0.0f, 2.0f, "Base.ModernCar", 0, sample(0f, 100f), sample(2f, 110f)),
            segment(2.5f, 3.0f, "Base.Van", 1, sample(2.5f, 200f))
        ));
        Path path = tempDir.resolve("vehicles.pzrp.gz");

        FormatWriter.write(path, metadata(vehicles),
            List.of(frame(0f, true), frame(3f, true)));
        ReplayData data = ReplayReader.read(path);
        PlaybackTimeline timeline = new PlaybackTimeline(data);

        assertEquals(vehicles, data.metadata().vehicles());
        assertEquals(2, timeline.getVehicleCount());
        assertTrue(timeline.getVehicleWarnings().isEmpty());
        assertEquals("Base.ModernCar", timeline.vehicleAt(1.0).segment().scriptName());
        assertEquals(105f, timeline.vehicleAt(1.0).x(), 0.0001f);
        assertEquals(1, timeline.vehicleAt(2.5).segment().seat());
        assertNull(timeline.vehicleAt(2.25));
    }

    @Test
    void oldReplaysWithoutVehicleDataRemainReadable() throws Exception {
        Vehicles vehicles = Vehicles.none();
        ReplayMetadata metadata = metadata(vehicles);

        assertFalse(vehicles.isPresent());
        assertTrue(vehicles.warnings().isEmpty());
        assertFalse(metadata.toValues().containsKey("vehicles_version"));
        assertFalse(metadata.toValues().containsKey("vehicles_data"));

        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata,
            List.of(frame(0f, false), frame(1f, false))));
        assertEquals(0, timeline.getVehicleCount());
        assertNull(timeline.vehicleAt(0.5));
        assertTrue(timeline.getVehicleWarnings().isEmpty());
    }

    @Test
    void corruptUnsupportedAndOversizedDataDegradeWithWarnings() throws Exception {
        Vehicles corrupt = new Vehicles(Vehicles.CURRENT_VERSION,
            "not base64!");
        Vehicles unsupported = new Vehicles(
            Vehicles.CURRENT_VERSION + 1, "AQ==");
        Vehicles missingPayload = new Vehicles(
            Vehicles.CURRENT_VERSION, "");

        assertTrue(corrupt.warnings().get(0).contains("corrupt or incomplete"));
        assertTrue(unsupported.warnings().get(0).contains("version is unsupported"));
        assertFalse(missingPayload.warnings().isEmpty());

        PlaybackTimeline timeline = new PlaybackTimeline(new ReplayData(metadata(corrupt),
            List.of(frame(0f, true), frame(1f, true))));
        assertEquals(2, timeline.getFrameCount());
        assertEquals(0, timeline.getVehicleCount());
        assertFalse(timeline.getVehicleWarnings().isEmpty());
        assertTrue(timeline.sampleAt(0.0).inVehicle());
    }

    @Test
    void invalidSegmentsAreSkippedWithoutBreakingOtherSegments() throws Exception {
        List<VehicleSegment> segments = new ArrayList<>();
        segments.add(null);
        segments.add(segment(0f, 1f, "", 0, sample(0f, 10f)));
        segments.add(segment(1f, 2f, "Base.Car", 0, sample(2.5f, 20f)));
        segments.add(segment(2f, 3f, "Base.Car", 0, sample(2f, 30f), sample(1.5f, 20f)));
        segments.add(segment(3f, 4f, "Base.Car", 0, sample(3f, 40f)));

        VehicleTimeline timeline = new VehicleTimeline(segments, 4.0);

        assertEquals(1, timeline.getSegmentCount());
        assertEquals(40f, timeline.vehicleAt(3.5).x(), 0.0001f);
        assertFalse(timeline.warnings().isEmpty());
    }

    @Test
    void timelineClampsAndSelectsTheCorrectSegment() throws Exception {
        VehicleTimeline timeline = new VehicleTimeline(List.of(
            segment(0f, 1f, "Base.First", 0, sample(0f, 0f), sample(1f, 10f)),
            segment(2f, 3f, "Base.Second", 2, sample(2f, 20f), sample(3f, 30f))
        ), 3.0);

        assertEquals(5f, timeline.vehicleAt(0.5).x(), 0.0001f);
        assertNull(timeline.vehicleAt(1.5));
        assertEquals("Base.Second", timeline.vehicleAt(2.5).segment().scriptName());
        assertEquals(2, timeline.vehicleAt(2.5).segment().seat());
        assertEquals(30f, timeline.vehicleAt(9.0).x(), 0.0001f);
        assertEquals(0f, timeline.vehicleAt(-5.0).x(), 0.0001f);
    }

    @Test
    void captureRecordsEnterExitAndSeatChanges() {
        VehicleCapture capture = new VehicleCapture();

        capture.record(0.0f, snapshot(0f, 0f, "Base.Car", 0));
        capture.record(0.5f, snapshot(1f, 0f, "Base.Car", 0));
        capture.record(1.0f, snapshot(2f, 0f, "Base.Car", 1));
        capture.record(1.5f, snapshot(2f, 0f, "Base.Car", 1));
        capture.finish(2.0f);

        Vehicles vehicles = capture.encode();
        assertTrue(vehicles.isPresent());
        VehicleCodec.Result result = assertDoesNotThrowDecode(vehicles);
        assertEquals(2, result.segments().size());
        assertEquals(0, result.segments().get(0).seat());
        assertEquals(0.0f, result.segments().get(0).startTimeSeconds(), 0.0001f);
        assertEquals(1.0f, result.segments().get(0).endTimeSeconds(), 0.0001f);
        assertEquals(1, result.segments().get(1).seat());
        assertEquals(1.0f, result.segments().get(1).startTimeSeconds(), 0.0001f);
        assertEquals(2.0f, result.segments().get(1).endTimeSeconds(), 0.0001f);
    }

    @Test
    void captureDeduplicatesStationaryTransformsButKeepsAHeartbeat() {
        VehicleCapture capture = new VehicleCapture();

        capture.record(0.0f, snapshot(0f, 0f, "Base.Car", 0));
        for (int step = 1; step < 10; step++) {
            capture.record(step * 0.1f, snapshot(0f, 0f, "Base.Car", 0));
        }
        capture.record(1.0f, snapshot(0f, 0f, "Base.Car", 0));
        capture.record(1.1f, snapshot(5f, 0f, "Base.Car", 0));
        capture.finish(1.2f);

        VehicleCodec.Result result = assertDoesNotThrowDecode(capture.encode());
        assertEquals(1, result.segments().size());
        assertEquals(3, result.segments().get(0).samples().size());
    }

    @Test
    void captureSkipsVehiclesWithoutASafeIdentity() {
        VehicleCapture capture = new VehicleCapture();

        capture.record(0.0f, snapshot(0f, 0f, "", 0));
        capture.record(0.2f, snapshot(1f, 0f, "Base.Car", 0));
        capture.finish(0.3f);

        assertTrue(capture.isSkipped());
        assertFalse(capture.getWarning().isBlank());
        VehicleCodec.Result result = assertDoesNotThrowDecode(capture.encode());
        assertEquals(1, result.segments().size());
    }

    @Test
    void metadataOmitsVehicleKeysWhenMissingAndPreservesThemOnEndUpdate()
        throws Exception {
        Vehicles vehicles = Vehicles.encode(List.of(
            segment(0f, 1f, "Base.Car", 0, sample(0f, 1f))));
        ReplayMetadata present = metadata(vehicles);

        assertEquals(vehicles, present.withEnd(99L).vehicles());
        assertEquals(vehicles, ReplayMetadata.fromValues(present.toValues()).vehicles());
        assertEquals(Vehicles.none(),
            ReplayMetadata.fromValues(metadata(Vehicles.none()).toValues()).vehicles());
    }

    @Test
    void codecRejectsPayloadsBeyondTheDocumentedBounds() throws Exception {
        List<VehicleSegment> tooMany = new ArrayList<>();
        for (int index = 0; index < VehicleCodec.MAX_SEGMENTS + 1; index++) {
            tooMany.add(segment(index, index + 1, "Base.Car", 0, sample(index, index)));
        }
        assertThrows(ReplayFormatException.class, () -> VehicleCodec.encode(tooMany));

        List<VehicleSample> tooManySamples = new ArrayList<>();
        for (int index = 0; index < VehicleCodec.MAX_SAMPLES; index++) {
            tooManySamples.add(sample(index, index));
        }
        VehicleSegment large = new VehicleSegment(0f,
            VehicleCodec.MAX_SAMPLES, "Base.Car", "", 0, 0, tooManySamples);
        assertThrows(ReplayFormatException.class, () -> VehicleCodec.encode(
            List.of(large, large)));
    }

    @Test
    void compatibilityReportsOnlyActualVehicleDataProblems() {
        ReplayCompatibility.Context context = new ReplayCompatibility.Context(
            "42.21.0", "save", "map", "Sandbox");

        assertFalse(ReplayCompatibility.check(metadata(Vehicles.none()), context)
            .warnings().stream().anyMatch(warning -> warning.contains("vehicle")));
        assertTrue(ReplayCompatibility.check(metadata(new Vehicles(
            Vehicles.CURRENT_VERSION, "not base64!")), context)
            .warnings().stream().anyMatch(warning -> warning.contains("vehicle")));
    }

    private static VehicleCapture.VehicleSnapshot snapshot(float x, float y,
        String script, int seat) {
        return new VehicleCapture.VehicleSnapshot(x, y, 0f, 0f, 0f, 0f, 1f,
            script, "vehicles/model", 0, seat);
    }

    private static VehicleCodec.Result assertDoesNotThrowDecode(Vehicles vehicles) {
        try {
            return vehicles.decode();
        } catch (ReplayFormatException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void decodedSegmentsDoNotRetainMutableInputLists() throws Exception {
        List<VehicleSample> samples = new ArrayList<>(List.of(sample(0f, 1f)));
        VehicleSegment segment = new VehicleSegment(0f, 1f, "Base.Car", "", 0, 0,
            samples);
        samples.add(sample(0.5f, 2f));

        assertEquals(1, segment.samples().size());
    }

    @Test
    void metadataParsesVehicleValuesFromTheHeader() throws Exception {
        Vehicles vehicles = Vehicles.encode(List.of(
            segment(0f, 1f, "Base.Car", 0, sample(0f, 1f))));
        Map<String, String> values = metadata(vehicles).toValues();

        assertEquals(Integer.toString(Vehicles.CURRENT_VERSION),
            values.get("vehicles_version"));
        assertEquals(vehicles.data(), values.get("vehicles_data"));
        assertNotNull(ReplayMetadata.fromValues(values).vehicles());
        assertEquals(Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(values.get("vehicles_data"))), vehicles.data());
    }
}
