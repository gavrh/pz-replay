package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBaseArchiveTest {
    @TempDir
    Path tempDir;

    @Test
    void packsOnlyKeptFilesWithRelativeNames() throws Exception {
        Path save = tempDir.resolve("save");
        Files.createDirectories(save.resolve("map/1342"));
        Files.createDirectories(save.resolve("zpop"));
        Files.writeString(save.resolve("WorldDictionary.bin"), "dictionary");
        Files.writeString(save.resolve("map/1342/1350.bin"), "tile");
        Files.writeString(save.resolve("map_visited.bin"), "fog");
        Files.writeString(save.resolve("WorldDictionaryLog.lua"), "log");
        Files.writeString(save.resolve("zpop/1.bin"), "population");

        Path archive = tempDir.resolve("base.zip");
        WorldBaseArchive.writeFromDirectory(save, archive);

        WorldBaseArchive.Inventory inventory = WorldBaseArchive.inventory(
            Files.readAllBytes(archive));
        assertEquals(2, inventory.entries().size());
        assertTrue(inventory.entries().stream()
            .anyMatch(entry -> entry.name().equals("WorldDictionary.bin")));
        assertTrue(inventory.entries().stream()
            .anyMatch(entry -> entry.name().equals("map/1342/1350.bin")));
        assertFalse(inventory.entries().stream()
            .anyMatch(entry -> entry.name().contains("zpop")));
    }

    @Test
    void copiesOnlyKeptFilesBeforePacking() throws Exception {
        Path save = tempDir.resolve("save");
        Files.createDirectories(save.resolve("map/1342"));
        Files.createDirectories(save.resolve("zpop"));
        Files.writeString(save.resolve("map/1342/1350.bin"), "tile");
        Files.writeString(save.resolve("WorldDictionaryLog.lua"), "log");
        Files.writeString(save.resolve("zpop/1.bin"), "population");

        Path copy = tempDir.resolve("copy");
        WorldBaseArchive.copyFiltered(save, copy);

        assertTrue(Files.exists(copy.resolve("map/1342/1350.bin")));
        assertFalse(Files.exists(copy.resolve("WorldDictionaryLog.lua")));
        assertFalse(Files.exists(copy.resolve("zpop/1.bin")));
        assertEquals("tile", Files.readString(copy.resolve("map/1342/1350.bin")));
    }

    @Test
    void extractsUnderTheContextRootOnly() throws Exception {
        Path save = tempDir.resolve("save");
        Files.createDirectories(save.resolve("map/1342"));
        Files.writeString(save.resolve("map/1342/1350.bin"), "tile");
        Path archive = tempDir.resolve("base.zip");
        WorldBaseArchive.writeFromDirectory(save, archive);

        Path context = tempDir.resolve("context");
        WorldBaseArchive.extract(archive, context);

        assertEquals("tile", Files.readString(context.resolve("map/1342/1350.bin")));
    }

    @Test
    void rejectsTraversalAndAbsoluteNames() {
        assertFalse(WorldBaseArchive.isValidName("../escape"));
        assertFalse(WorldBaseArchive.isValidName("map/../../escape"));
        assertFalse(WorldBaseArchive.isValidName("/absolute"));
        assertFalse(WorldBaseArchive.isValidName("map//tile"));
        assertFalse(WorldBaseArchive.isValidName("C:/tile"));
        assertFalse(WorldBaseArchive.isValidName("map\\tile"));
        assertTrue(WorldBaseArchive.isValidName("map/1342/1350.bin"));
    }

    @Test
    void rejectsTraversalArchivesBeforeWriting() throws Exception {
        byte[] archive = zip(new Entry("../escape.bin", "bad"));
        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> WorldBaseArchive.extract(archive, tempDir.resolve("context")));
        assertTrue(exception.getMessage().contains("invalid"));
        assertFalse(Files.exists(tempDir.resolve("escape.bin")));
    }

    @Test
    void rejectsCompressedEntries() throws Exception {
        byte[] archive = deflatedZip(new Entry("WorldDictionary.bin", "content"));
        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> WorldBaseArchive.validate(archive));
        assertTrue(exception.getMessage().contains("not stored"));
    }

    @Test
    void rejectsEntriesAboveTheSingleEntryCap() throws Exception {
        Path root = tempDir.resolve("cap-root");
        Files.createDirectories(root);
        java.util.List<WorldBaseArchive.Entry> entries = java.util.List.of(
            new WorldBaseArchive.Entry("WorldDictionary.bin",
                WorldBaseArchive.MAX_ENTRY_BYTES + 1L));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> WorldBaseArchive.write(tempDir.resolve("cap.zip"), entries, root));
        assertTrue(exception.getMessage().contains("cap"));
    }

    @Test
    void rejectsDuplicateEntriesBeforePacking() throws Exception {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);
        Files.writeString(root.resolve("WorldDictionary.bin"), "data");
        java.util.List<WorldBaseArchive.Entry> entries = java.util.List.of(
            new WorldBaseArchive.Entry("WorldDictionary.bin", 4L),
            new WorldBaseArchive.Entry("WorldDictionary.bin", 4L));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> WorldBaseArchive.write(tempDir.resolve("duplicate.zip"), entries, root));
        assertTrue(exception.getMessage().contains("duplicated"));
    }

    private static byte[] zip(Entry... entries) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                byte[] content = entry.content().getBytes(StandardCharsets.UTF_8);
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(content.length);
                zipEntry.setCompressedSize(content.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(content);
                zipEntry.setCrc(crc.getValue());
                zip.putNextEntry(zipEntry);
                zip.write(content);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] deflatedZip(Entry... entries) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setMethod(ZipEntry.DEFLATED);
                zip.putNextEntry(zipEntry);
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Entry(String name, String content) {
    }
}
