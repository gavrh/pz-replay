package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCatalogueTest {
    @TempDir
    Path tempDir;

    @Test
    void listsNewestFirstWithMetadataAndLastReplayMarker() throws Exception {
        Path older = tempDir.resolve("older.pzrp.gz");
        Path newer = tempDir.resolve("newer.pzrp.gz");
        BinaryReplayCodec.write(older, metadata("Older", 1_700_000_000_000L,
            1_700_000_060_000L), frames(), true);
        BinaryReplayCodec.write(newer, metadata("Newer", 1_700_000_100_000L,
            1_700_000_190_000L), frames(), true);
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000L));

        ReplayCatalogue.Result result = ReplayCatalogue.list(tempDir, newer.toString());

        assertEquals(2, result.entries().size());
        assertEquals("newer.pzrp.gz", result.entries().get(0).fileName());
        assertTrue(result.entries().get(0).lastReplay());
        assertFalse(result.entries().get(1).lastReplay());
        ReplayCatalogue.Entry entry = result.entries().get(1);
        assertTrue(entry.valid());
        assertEquals(60.0, entry.durationSeconds());
        assertEquals(30.0, entry.sampleRateHz());
        assertEquals("Muldraugh, KY", entry.mapName());
        assertEquals("pz-replay-test", entry.saveName());
        assertEquals("Sandbox", entry.gameMode());
        assertEquals("Older", entry.playerName());
        assertTrue(entry.sizeBytes() > 0);
    }

    @Test
    void corruptAndTruncatedFilesBecomeUnreadableRows() throws Exception {
        Path corrupt = tempDir.resolve("corrupt.pzrp.gz");
        Files.writeString(corrupt, "not a replay");
        Path truncated = tempDir.resolve("truncated.pzrp.gz");
        BinaryReplayCodec.write(truncated, metadata("Truncated", 1L, 2L), frames(), true);
        byte[] bytes = Files.readAllBytes(truncated);
        Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length - 8));

        ReplayCatalogue.Result result = ReplayCatalogue.list(tempDir, "");

        assertEquals(2, result.entries().size());
        for (ReplayCatalogue.Entry entry : result.entries()) {
            assertFalse(entry.valid(), entry.fileName());
            assertFalse(entry.error().isBlank(), entry.fileName());
        }
    }

    @Test
    void emptyDirectoryIsAnEmptyResult() {
        ReplayCatalogue.Result empty = ReplayCatalogue.list(tempDir, "");
        assertTrue(empty.entries().isEmpty());
        assertEquals("", empty.error());
    }

    @Test
    void missingDirectoryReportsAnError() {
        ReplayCatalogue.Result missing =
            ReplayCatalogue.list(tempDir.resolve("missing"), "");
        assertTrue(missing.entries().isEmpty());
        assertTrue(missing.error().contains("not found"), missing.error());
    }

    @Test
    void filePathInsteadOfDirectoryReportsAnError() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "x");

        ReplayCatalogue.Result result = ReplayCatalogue.list(file, "");

        assertTrue(result.entries().isEmpty());
        assertTrue(result.error().contains("not a directory"), result.error());
    }

    @Test
    void ignoresNonReplayFilesAndPartFiles() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "ignored");
        Files.writeString(tempDir.resolve("unfinished.pzrp.gz.part"), "ignored");
        BinaryReplayCodec.write(tempDir.resolve("valid.pzrp.gz"),
            metadata("Valid", 1L, 2L), frames(), true);

        ReplayCatalogue.Result result = ReplayCatalogue.list(tempDir, "");

        assertEquals(1, result.entries().size());
        assertEquals("valid.pzrp.gz", result.entries().get(0).fileName());
        assertTrue(result.entries().get(0).valid());
    }

    private static ReplayMetadata metadata(String player, long start, long end) {
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
            "0.0.0-dev",
            "42.21.0",
            "pz-replay-test",
            "Sandbox",
            "Muldraugh, KY",
            player,
            1000f,
            2000f,
            0f,
            30.0,
            start,
            end
        );
    }

    private static List<ReplaySample> frames() {
        List<ReplaySample> frames = new ArrayList<>();
        frames.add(new ReplaySample(1L, 0f, 0f, 1f, 2f, 0f, 0f, 0f,
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, 0));
        frames.add(new ReplaySample(2L, 1f, 1f, 1.1f, 2f, 0f, 0f, 0f,
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, 0));
        return frames;
    }
}
