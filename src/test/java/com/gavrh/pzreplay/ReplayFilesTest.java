package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void writeAtomicallyFinalizesWithoutPartFile() throws IOException {
        Path target = tempDir.resolve("replay.pzrp.gz");
        byte[] content = {1, 2, 3, 4};

        ReplayFiles.writeAtomically(target, content);

        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(tempDir.resolve("replay.pzrp.gz.part")));
    }

    @Test
    void writeAtomicallyRemovesPartFileWhenMoveFails() throws IOException {
        Path target = tempDir.resolve("target");
        Files.createDirectory(target);
        Files.writeString(target.resolve("child"), "x");

        assertThrows(IOException.class,
            () -> ReplayFiles.writeAtomically(target, new byte[] {1, 2, 3}));
        assertFalse(Files.exists(tempDir.resolve("target.part")));
    }
}
