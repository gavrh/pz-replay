package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingNamesTest {
    @TempDir
    Path tempDir;

    @Test
    void sanitizesToTheRecorderCharacterSet() {
        assertEquals("my_run", RecordingNames.sanitize("my run"));
        assertEquals("My_Replay_", RecordingNames.sanitize("My: Replay?"));
        assertEquals("route", RecordingNames.sanitize("route.pzrp.gz"));
        assertEquals("a-b_c.d", RecordingNames.sanitize("a-b_c.d"));
    }

    @Test
    void stripsPathSeparatorsAndLeadingOrTrailingDots() {
        String name = RecordingNames.sanitize("../../evil/path\\name");

        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.startsWith("."));
        assertFalse(name.endsWith("."));
        assertEquals("_.._evil_path_name", name);
    }

    @Test
    void emptyNamesResolveToTheAutoName() {
        assertEquals("", RecordingNames.sanitize(""));
        assertEquals("", RecordingNames.sanitize("   "));
        assertEquals("", RecordingNames.sanitize("..."));
        assertEquals("auto-name", RecordingNames.resolve("", "auto-name"));
        assertEquals("auto-name", RecordingNames.resolve("   ", "auto-name"));
        assertEquals("custom", RecordingNames.resolve("custom", "auto-name"));
    }

    @Test
    void truncatesLongNames() {
        String name = RecordingNames.sanitize("a".repeat(RecordingNames.MAX_NAME_LENGTH + 30));

        assertEquals(RecordingNames.MAX_NAME_LENGTH, name.length());
    }

    @Test
    void uniqueNameIsUnchangedWhenFree() {
        assertEquals("route", RecordingNames.unique(tempDir, "route"));
    }

    @Test
    void missingDirectoryIsTreatedAsFree() {
        Path missing = tempDir.resolve("missing");

        assertEquals("route", RecordingNames.unique(missing, "route"));
    }

    @Test
    void nonDirectoryFailsClosedInsteadOfAssumingNoCollision() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.createFile(file);

        assertEquals("", RecordingNames.unique(file, "route"));
    }

    @Test
    void collisionAddsASuffixInsteadOfOverwriting() throws Exception {
        Files.createFile(tempDir.resolve("route" + ReplayCatalogue.EXTENSION));

        assertEquals("route-2", RecordingNames.unique(tempDir, "route"));

        Files.createFile(tempDir.resolve("route-2" + ReplayCatalogue.EXTENSION));
        assertEquals("route-3", RecordingNames.unique(tempDir, "route"));
    }

    @Test
    void collisionCheckIsCaseInsensitive() throws Exception {
        Files.createFile(tempDir.resolve("Route" + ReplayCatalogue.EXTENSION));

        assertEquals("route-2", RecordingNames.unique(tempDir, "route"));
    }

    @Test
    void extensionAndSuffixStayWithinTheLengthLimit() throws Exception {
        String base = "b".repeat(RecordingNames.MAX_NAME_LENGTH);
        Files.createFile(tempDir.resolve(base + ReplayCatalogue.EXTENSION));

        String resolved = RecordingNames.unique(tempDir, base);

        assertTrue(resolved.length() <= RecordingNames.MAX_NAME_LENGTH);
        assertTrue(resolved.endsWith("-2"));
    }
}
