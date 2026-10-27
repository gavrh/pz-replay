package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReplayFileManagerTest {
    private static final byte[] CONTENT = {1, 2, 3, 4, 5};

    @TempDir
    Path tempDir;

    @Test
    void renameMovesFileAndPreservesContentAndMetadata() throws Exception {
        Path source = tempDir.resolve("original.pzrp.gz");
        Files.write(source, CONTENT);
        FileTime modified = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(source, modified);

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "renamed replay");

        assertTrue(result.ok(), result.error());
        assertEquals(tempDir.resolve("renamed replay.pzrp.gz").toString(), result.path());
        Path target = Path.of(result.path());
        assertArrayEquals(CONTENT, Files.readAllBytes(target));
        assertEquals(modified.toMillis(), Files.getLastModifiedTime(target).toMillis());
        assertFalse(Files.exists(source));
    }

    @Test
    void renamePreservesPermissions() throws Exception {
        Path source = tempDir.resolve("original.pzrp.gz");
        Files.write(source, CONTENT);
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> permissions = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(source, permissions);

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "renamed");

        assertTrue(result.ok(), result.error());
        assertEquals(permissions, Files.getPosixFilePermissions(Path.of(result.path())));
    }

    @Test
    void renameRejectsEmptyAndUnsafeNames() throws Exception {
        Path source = validReplay("source.pzrp.gz");

        for (String name : new String[] {"", "   ", ".", "..", ".hidden",
            "trailing.", "bad:name", "bad?name", "bad/name", "bad\\name"}) {
            ReplayFileManager.Result result =
                ReplayFileManager.rename(tempDir, source.toString(), name);
            assertFalse(result.ok(), name);
            assertFalse(result.error().isBlank(), name);
        }
        assertTrue(Files.exists(source));
    }

    @Test
    void renameRejectsPathTraversal() throws Exception {
        Path source = validReplay("source.pzrp.gz");

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "../escaped");

        assertFalse(result.ok());
        assertTrue(result.error().contains("path separators"), result.error());
        assertFalse(Files.exists(tempDir.resolveSibling("escaped.pzrp.gz")));
    }

    @Test
    void renameRejectsSourceOutsideReplayDirectory() throws Exception {
        Path outside = tempDir.resolveSibling("outside.pzrp.gz");
        Files.write(outside, CONTENT);
        try {
            ReplayFileManager.Result result =
                ReplayFileManager.rename(tempDir, outside.toString(), "moved");
            assertFalse(result.ok());
            assertTrue(result.error().contains("outside"), result.error());
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void renameRejectsExistingTargetWithoutOverwriting() throws Exception {
        Path source = validReplay("source.pzrp.gz");
        Path existing = validReplay("existing.pzrp.gz");

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "existing");

        assertFalse(result.ok());
        assertTrue(result.error().contains("already exists"), result.error());
        assertArrayEquals(CONTENT, Files.readAllBytes(source));
        assertArrayEquals(CONTENT, Files.readAllBytes(existing));
        assertFalse(Files.exists(tempDir.resolve("source.pzrp.gz.part")));
    }

    @Test
    void renameRejectsCaseInsensitiveCollision() throws Exception {
        Path source = validReplay("source.pzrp.gz");
        validReplay("Existing.pzrp.gz");

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "existing");

        assertFalse(result.ok());
        assertTrue(result.error().contains("already exists"), result.error());
        assertTrue(Files.exists(source));
    }

    @Test
    void renameAllowsCaseOnlyChange() throws Exception {
        Path source = validReplay("Take One.pzrp.gz");

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "take one");

        assertTrue(result.ok(), result.error());
        assertEquals(tempDir.resolve("take one.pzrp.gz").toString(), result.path());
        assertArrayEquals(CONTENT, Files.readAllBytes(Path.of(result.path())));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void renameToSameNameIsANoOp() throws Exception {
        Path source = validReplay("same name.pzrp.gz");

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, source.toString(), "same name");

        assertTrue(result.ok(), result.error());
        assertEquals(source.toString(), result.path());
        assertArrayEquals(CONTENT, Files.readAllBytes(source));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void renameAddsExtensionAndDoesNotDuplicateIt() throws Exception {
        Path source = validReplay("source.pzrp.gz");

        ReplayFileManager.Result withoutExtension =
            ReplayFileManager.rename(tempDir, source.toString(), "first");
        assertTrue(withoutExtension.ok(), withoutExtension.error());
        assertTrue(withoutExtension.path().endsWith("first.pzrp.gz"));

        ReplayFileManager.Result withExtension = ReplayFileManager.rename(tempDir,
            withoutExtension.path(), "second.pzrp.gz");
        assertTrue(withExtension.ok(), withExtension.error());
        assertTrue(withExtension.path().endsWith("second.pzrp.gz"));
        assertFalse(withExtension.path().endsWith(".pzrp.gz.pzrp.gz"));
    }

    @Test
    void renameRejectsSymlinkSource() throws Exception {
        Path target = tempDir.resolve("target.pzrp.gz");
        Files.write(target, CONTENT);
        Path link = tempDir.resolve("link.pzrp.gz");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links are unavailable");
            return;
        }

        ReplayFileManager.Result result =
            ReplayFileManager.rename(tempDir, link.toString(), "renamed");

        assertFalse(result.ok());
        assertTrue(result.error().contains("not a replay file"), result.error());
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void deleteRemovesOnlyTheReplayFile() throws Exception {
        Path source = validReplay("source.pzrp.gz");
        Path other = validReplay("other.pzrp.gz");

        ReplayFileManager.Result result =
            ReplayFileManager.delete(tempDir, source.toString());

        assertTrue(result.ok(), result.error());
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(other));
    }

    @Test
    void deleteReportsMissingFile() {
        ReplayFileManager.Result result =
            ReplayFileManager.delete(tempDir, tempDir.resolve("missing.pzrp.gz").toString());

        assertFalse(result.ok());
        assertTrue(result.error().contains("not found"), result.error());
    }

    @Test
    void deleteRejectsDirectoryWithReplaySuffix() throws Exception {
        Path directory = tempDir.resolve("folder.pzrp.gz");
        Files.createDirectory(directory);

        ReplayFileManager.Result result =
            ReplayFileManager.delete(tempDir, directory.toString());

        assertFalse(result.ok());
        assertTrue(result.error().contains("not a replay file"), result.error());
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void deleteRejectsFileOutsideReplayDirectory() throws Exception {
        Path outside = tempDir.resolveSibling("outside-delete.pzrp.gz");
        Files.write(outside, CONTENT);
        try {
            ReplayFileManager.Result result =
                ReplayFileManager.delete(tempDir, outside.toString());

            assertFalse(result.ok());
            assertTrue(result.error().contains("outside"), result.error());
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void deleteRejectsSymlink() throws Exception {
        Path target = tempDir.resolve("target.pzrp.gz");
        Files.write(target, CONTENT);
        Path link = tempDir.resolve("link.pzrp.gz");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            assumeTrue(false, "symbolic links are unavailable");
            return;
        }

        ReplayFileManager.Result result =
            ReplayFileManager.delete(tempDir, link.toString());

        assertFalse(result.ok());
        assertTrue(result.error().contains("not a replay file"), result.error());
        assertTrue(Files.exists(target));
        assertTrue(Files.isSymbolicLink(link));
    }

    private Path validReplay(String name) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, CONTENT);
        return path;
    }
}
