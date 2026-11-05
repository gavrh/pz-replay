package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextTest {
    @TempDir
    Path tempDir;

    @Test
    void namesAndRecognizesReservedContexts() {
        assertEquals("~pzrp-ctx-abc123", WorldContext.folderName("abc123"));
        assertTrue(WorldContext.isReservedFolderName("~pzrp-ctx-abc123"));
        assertFalse(WorldContext.isReservedFolderName("~pzrp-ctx-"));
        assertFalse(WorldContext.isReservedFolderName("real-save"));
    }

    @Test
    void markerAndSentinelRoundTrip() {
        WorldContext.Marker marker = marker("abc123", "~pzrp-ctx-abc123",
            System.currentTimeMillis(), 1234L);

        WorldContext.Marker parsed = WorldContext.parseMarker(WorldContext.markerText(marker));

        assertNotNull(parsed);
        assertEquals(marker, parsed);
        assertEquals("abc123",
            WorldContext.sentinelId(WorldContext.sentinelText("abc123")));
        assertNull(WorldContext.parseMarker("context_id=abc123\n"));
    }

    @Test
    void cleanupNeverDeletesARealSave() {
        Path root = tempDir.resolve("Saves/Sandbox");
        Path folder = root.resolve("real-save");
        WorldContext.Cleanup cleanup = WorldContext.cleanup("real-save", null, "",
            System.currentTimeMillis(), false, root, folder);

        assertEquals(WorldContext.Action.KEEP, cleanup.action());
    }

    @Test
    void cleanupReportsMissingOrCorruptMarkersInsteadOfDeleting() {
        Path root = tempDir.resolve("Saves/Sandbox");
        Path folder = root.resolve("~pzrp-ctx-abc123");

        assertEquals(WorldContext.Action.REPORT,
            WorldContext.cleanup("~pzrp-ctx-abc123", null, "", System.currentTimeMillis(),
                false, root, folder).action());

        WorldContext.Marker marker = marker("abc123", "~pzrp-ctx-abc123",
            System.currentTimeMillis() - WorldContext.GRACE_MILLIS - 1L, 1L);
        assertEquals(WorldContext.Action.REPORT,
            WorldContext.cleanup("~pzrp-ctx-abc123", marker, "other",
                System.currentTimeMillis(), false, root, folder).action());
    }

    @Test
    void cleanupHonoursPidLivenessGraceAndContainment() {
        Path root = tempDir.resolve("Saves/Sandbox");
        Path folder = root.resolve("~pzrp-ctx-abc123");
        long now = System.currentTimeMillis();
        WorldContext.Marker marker = marker("abc123", "~pzrp-ctx-abc123", now - 5_000L, 1L);

        assertEquals(WorldContext.Action.KEEP,
            WorldContext.cleanup("~pzrp-ctx-abc123", marker, "abc123", now, true, root,
                folder).action());
        assertEquals(WorldContext.Action.KEEP,
            WorldContext.cleanup("~pzrp-ctx-abc123", marker, "abc123", now, false, root,
                folder).action());

        WorldContext.Marker stale = marker("abc123", "~pzrp-ctx-abc123",
            now - WorldContext.GRACE_MILLIS - 1L, 1L);
        assertEquals(WorldContext.Action.DELETE,
            WorldContext.cleanup("~pzrp-ctx-abc123", stale, "abc123", now, false, root,
                folder).action());

        assertEquals(WorldContext.Action.REPORT,
            WorldContext.cleanup("~pzrp-ctx-abc123", stale, "abc123", now, false, root,
                tempDir.resolve("outside")).action());
    }

    @Test
    void restoresContinueAwayFromADeletedContext() {
        String context = "~pzrp-ctx-abc123\nSandbox\n";
        String backup = "real-save\nSandbox\n";

        assertEquals(backup, WorldContext.restoreLatestSave(context, backup,
            "~pzrp-ctx-abc123"));
        assertEquals("", WorldContext.restoreLatestSave(context, "", "~pzrp-ctx-abc123"));
        assertEquals(backup, WorldContext.restoreLatestSave("", backup,
            "~pzrp-ctx-abc123"));
        assertEquals("real-save\nSandbox\n", WorldContext.restoreLatestSave(backup, "",
            "~pzrp-ctx-abc123"));
    }

    @Test
    void makesAReadOnlyContextWritableBeforeRemoval() throws Exception {
        Path folder = tempDir.resolve("~pzrp-ctx-abc123");
        Files.createDirectories(folder);
        Path file = folder.resolve("WorldDictionary.bin");
        Files.writeString(file, "data");
        file.toFile().setWritable(false);
        assertFalse(Files.isWritable(file));

        WorldContext.makeWritable(folder);

        assertTrue(Files.isWritable(file));
        Files.delete(file);
        Files.delete(folder);
    }

    private static WorldContext.Marker marker(String id, String folder, long started,
        long pid) {
        return new WorldContext.Marker(id, pid, started, "prepared", "Sandbox", folder,
            "package", "");
    }
}
