package com.gavrh.pzreplay;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldFormatTest {
    @TempDir
    Path tempDir;

    @Test
    void writesReadsAndCountsTheWorldSections() throws Exception {
        byte[] base = FormatTestSupport.worldBaseArchive("WorldDictionary.bin",
            "dictionary".getBytes(StandardCharsets.UTF_8));
        byte[] log = FormatTestSupport.t0Log(new WorldDoorState(
            WorldTarget.door(10, 20, 0, 0), "door", 0, true, false, false, 100, 7L,
            true));
        WorldPackage world = new WorldPackage(base, log, 16, 2, "complete", 1089L, 1L, 1L,
            249);
        Path file = tempDir.resolve("world.pzrp.gz");

        FormatWriter.write(file, metadata(), frames(), List.of(), null, world);

        FormatReadResult result = FormatReader.readPackage(file);
        assertEquals("padded", result.worldStatus());
        assertEquals("", result.worldReason());
        assertEquals(log.length, FormatReader.readWorldLog(file).length);
        assertEquals(base.length, FormatReader.readWorldBase(file).length);
        assertEquals(1, WorldLogCodec.decodeT0(FormatReader.readWorldLog(file)).size());

        FormatInfo info = FormatReader.readInfo(file);
        assertEquals("padded", info.worldStatus());
        assertEquals(metadata().mapName(), info.metadata().mapName());
        assertEquals("249", FormatReader.readSourceWorldVersion(file));
    }

    @Test
    void rejectsManifestCountsThatDisagreeWithTheLog() throws Exception {
        byte[] base = FormatTestSupport.worldBaseArchive("WorldDictionary.bin",
            "dictionary".getBytes(StandardCharsets.UTF_8));
        byte[] log = FormatTestSupport.t0Log(new WorldDoorState(
            WorldTarget.door(10, 20, 0, 0), "door", 0, false, false, false, 100, 0L,
            false));
        WorldPackage world = new WorldPackage(base, log, 16, 2, "complete", 1L, 1L, 2L);

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatWriter.write(tempDir.resolve("bad.pzrp.gz"), metadata(), frames(),
                List.of(), null, world));
        assertEquals(FormatReason.WORLD_COUNT_MISMATCH, exception.reason());
    }

    @Test
    void rejectsABaseThatIsNotAStoredZip() throws Exception {
        byte[] log = FormatTestSupport.t0Log(new WorldDoorState(
            WorldTarget.door(10, 20, 0, 0), "door", 0, false, false, false, 100, 0L,
            false));
        WorldPackage world = new WorldPackage("not a zip".getBytes(StandardCharsets.UTF_8),
            log, 16, 2, "complete", 1L, 1L, 1L);

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatWriter.write(tempDir.resolve("bad-base.pzrp.gz"), metadata(),
                frames(), List.of(), null, world));
        assertEquals(FormatReason.WORLD_BASE_INVALID, exception.reason());
    }

    private static ReplayMetadata metadata() {
        return new ReplayMetadata(1, "0.0.0-dev", "42.21.0", "pz-replay-test",
            "Sandbox", "Muldraugh, KY", "Replay Tester", 1000.5f, 2000.25f, 0f, 30.0,
            1_700_000_000_000L, 1_700_000_060_000L);
    }

    private static List<CaptureSample> frames() {
        return List.of(new CaptureSample(100, 0f, 10f, 1000.5f, 2000.25f, 0f, 45.5f,
            1.25f, CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, 0));
    }
}
