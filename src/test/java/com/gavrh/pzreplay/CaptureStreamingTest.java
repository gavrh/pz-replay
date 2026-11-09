package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureStreamingTest {
    @TempDir
    Path tempDir;

    private static CaptureSession.PlayerSnapshot player(int tick) {
        return new CaptureSession.PlayerSnapshot(
            tick * 0.01f,
            tick * 0.02f,
            0f,
            45f,
            tick * 0.01f,
            CaptureSample.Locomotion.WALK,
            CaptureSample.Stance.STAND,
            CaptureSample.FLAG_MOVING
        );
    }

    private static void run(CaptureSession session, int ticks) {
        session.start(0L);
        for (int tick = 1; tick <= ticks; tick++) {
            session.tick(tick, tick * 10L, player(tick), false);
        }
    }

    @Test
    void framesStreamToStagingInOrder() throws Exception {
        Path track = tempDir.resolve("actor.track");
        CaptureFrameStage stage = new CaptureFrameStage(track);
        CaptureSession streamed = new CaptureSession(30.0, 1000, stage);
        CaptureSession memory = new CaptureSession(30.0, 1000);

        run(streamed, 100);
        run(memory, 100);
        streamed.stop(CaptureSession.StopReason.REQUESTED);
        memory.stop(CaptureSession.StopReason.REQUESTED);

        assertTrue(streamed.frames().isEmpty());
        assertEquals(memory.frames().size(), stage.frameCount());
        assertEquals(memory.frames().size() * FormatWriter.RECORD_BYTES, stage.bytes());
        assertArrayEquals(FormatTestSupport.actorPayload(memory.frames()),
            Files.readAllBytes(track));
    }

    @Test
    void streamingKeepsTheSessionBoundedAsFrameCountGrows() throws Exception {
        Path track = tempDir.resolve("long.track");
        CaptureFrameStage stage = new CaptureFrameStage(track);
        CaptureSession session = new CaptureSession(30.0, CaptureSession.MAX_FRAMES, stage);

        run(session, 100_000);

        assertTrue(session.isRecording());
        assertTrue(session.frames().isEmpty());
        assertTrue(session.frameCount() >= 29_000L);
        assertEquals(session.frameCount(), stage.frameCount());
        assertEquals(stage.frameCount() * FormatWriter.RECORD_BYTES, stage.bytes());
        assertEquals(stage.bytes(), Files.size(track));
        session.stop(CaptureSession.StopReason.REQUESTED);
    }

    @Test
    void writeFailureStopsTheSessionWithoutFrames() throws Exception {
        Path track = tempDir.resolve("closed.track");
        CaptureFrameStage stage = new CaptureFrameStage(track);
        CaptureSession session = new CaptureSession(30.0, 1000, stage);
        session.start(0L);
        stage.close();

        session.tick(1, 10L, player(1), false);

        CaptureSession.Summary summary = session.getLastSummary();
        assertFalse(session.isRecording());
        assertEquals(CaptureSession.StopReason.WRITE_FAILED, summary.stopReason());
        assertEquals(0, summary.frameCount());
        assertFalse(session.getWriteFailure().isEmpty());
    }

    @Test
    void diskGuardUsesTheCurrentUsableSpace() {
        CaptureDiskGuard empty = new CaptureDiskGuard(() -> 100L, 200L);
        CaptureDiskGuard ready = new CaptureDiskGuard(() -> 300L, 200L);
        CaptureDiskGuard unavailable = new CaptureDiskGuard(() -> {
            throw new IllegalStateException("no store");
        }, 200L);

        assertFalse(empty.hasSpace());
        assertTrue(ready.hasSpace());
        assertFalse(unavailable.hasSpace());
    }

    @Test
    void stagedWriterMatchesTheInMemoryOutput() throws Exception {
        Path memoryFile = tempDir.resolve("memory.pzrp.gz");
        Path stagedFile = tempDir.resolve("staged.pzrp.gz");
        ReplayMetadata metadata = metadata();
        CaptureSession memory = new CaptureSession(30.0, 1000);
        run(memory, 100);
        memory.stop(CaptureSession.StopReason.REQUESTED);

        Path track = tempDir.resolve("staged.track");
        CaptureFrameStage stage = new CaptureFrameStage(track);
        CaptureSession streamed = new CaptureSession(30.0, 1000, stage);
        run(streamed, 100);
        streamed.stop(CaptureSession.StopReason.REQUESTED);

        FormatWriter.write(memoryFile, metadata, memory.frames());
        FormatWriter.writeStaged(stagedFile, metadata, stage, List.of(), null, null);

        assertArrayEquals(Files.readAllBytes(memoryFile), Files.readAllBytes(stagedFile));
        assertEquals(memory.frames(), FormatReader.readPackage(stagedFile).frames());
    }

    @Test
    void stagedWriteRejectsFramesOutsideTheMetadataRange() throws Exception {
        Path track = tempDir.resolve("range.track");
        CaptureFrameStage stage = new CaptureFrameStage(track);
        CaptureSession session = new CaptureSession(30.0, 1000, stage);
        run(session, 1000);
        session.stop(CaptureSession.StopReason.REQUESTED);
        ReplayMetadata shortRange = new ReplayMetadata(1, "0.0.0-dev", "42.21.0",
            "pz-replay-test", "Sandbox", "Muldraugh, KY", "Replay Tester", 1000.5f,
            2000.25f, 0f, 30.0, 1_700_000_000_000L, 1_700_000_000_500L);
        Path target = tempDir.resolve("range.pzrp.gz");

        assertThrows(FormatException.class,
            () -> FormatWriter.writeStaged(target, shortRange, stage, List.of(), null, null));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(tempDir.resolve("range.pzrp.gz.part")));
    }

    @Test
    void stagedWriteFailureLeavesNoFinalPackageOrPartFile() throws Exception {
        Path track = tempDir.resolve("missing.track");
        CaptureFrameStage stage = new CaptureFrameStage(track);
        CaptureSession session = new CaptureSession(30.0, 1000, stage);
        run(session, 10);
        session.stop(CaptureSession.StopReason.REQUESTED);
        Files.delete(track);
        Path target = tempDir.resolve("failed.pzrp.gz");

        assertThrows(FormatException.class,
            () -> FormatWriter.writeStaged(target, metadata(), stage, List.of(), null, null));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(tempDir.resolve("failed.pzrp.gz.part")));
    }

    @Test
    void readerAcceptsTheRaisedCaps() throws Exception {
        ReplayMetadata metadata = metadata();
        String manifest = FormatTestSupport.manifest(metadata, 8_000_000, false, 0)
            .replace("world_mode=off\n", "")
            + "world_mode=padded\n"
            + "world_padding_tiles=16\n"
            + "world_sample_rate_hz=2\n"
            + "world_coverage_status=complete\n"
            + "world_coverage_squares=1\n"
            + "world_coverage_objects=1\n"
            + "world_log_changes=4000000\n"
            + "world_log_bytes=536870912\n"
            + "world_base_bytes=1\n"
            + "world_base_format=zip-store-1\n"
            + "world_log_format=pzwlog-1\n"
            + "world_first_sample=full\n";

        FormatManifest parsed = FormatManifest.parse(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(8_000_000L, parsed.actors.get(0).frameCount());
        assertEquals(4_000_000L, parsed.worldLogChanges);
        assertEquals(536_870_912L, parsed.worldLogBytes);

        String overFrames = FormatTestSupport.manifest(metadata, 8_000_001, false, 0);
        assertThrows(FormatException.class, () -> FormatManifest.parse(
            overFrames.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static ReplayMetadata metadata() {
        return new ReplayMetadata(1, "0.0.0-dev", "42.21.0", "pz-replay-test",
            "Sandbox", "Muldraugh, KY", "Replay Tester", 1000.5f, 2000.25f, 0f, 30.0,
            1_700_000_000_000L, 1_700_000_060_000L);
    }
}
