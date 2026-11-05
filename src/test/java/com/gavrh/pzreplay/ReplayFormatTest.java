package com.gavrh.pzreplay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFormatTest {
    @TempDir
    Path tempDir;

    private static ReplayMetadata metadata() {
        return new ReplayMetadata(
            1,
            "0.0.0-dev",
            "42.21.0",
            "pz-replay-test",
            "Sandbox",
            "Muldraugh, KY",
            "Replay Tester",
            1000.5f,
            2000.25f,
            0f,
            30.0,
            1_700_000_000_000L,
            1_700_000_060_000L,
            new Appearance(Appearance.CURRENT_VERSION,
                Base64.getEncoder().encodeToString("snapshot".getBytes(StandardCharsets.UTF_8))),
            new Equipment(Equipment.CURRENT_VERSION,
                Base64.getEncoder().encodeToString("items".getBytes(StandardCharsets.UTF_8)))
        );
    }

    private static List<CaptureSample> frames() {
        List<CaptureSample> frames = new ArrayList<>();
        frames.add(new CaptureSample(100, 0f, 10f, 1000.5f, 2000.25f, 0f, 45.5f,
            1.25f, CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, 0b100000));
        frames.add(new CaptureSample(130, 1f, 11f, 1001.25f, 2000.75f, 0f, 90.0f,
            2.5f, CaptureSample.Locomotion.RUN, CaptureSample.Stance.STAND, 0b101000));
        frames.add(new CaptureSample(160, 2f, 12f, 1002.0f, 2001.0f, 0.5f, 180.0f,
            0f, CaptureSample.Locomotion.IDLE, CaptureSample.Stance.CROUCH, 0b10));
        return frames;
    }

    private static String manifestText() {
        return FormatTestSupport.manifest(metadata(), frames().size(), true, 0);
    }

    private static List<FormatTestSupport.Section> validSections() {
        return FormatTestSupport.sections(FormatTestSupport.actorPayload(frames()),
            FormatTestSupport.actorMetadata());
    }

    private static byte[] rawPackage() throws Exception {
        return FormatTestSupport.raw(manifestText(), validSections());
    }

    private static void writeRaw(Path path, byte[] raw) throws Exception {
        FormatTestSupport.write(path, raw);
    }

    @Test
    void roundTripsEverySupportedSection() throws Exception {
        Path file = tempDir.resolve("roundtrip.pzrp.gz");
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        FormatWriter.write(file, metadata(), frames(),
            List.of(new FormatMod("mod.a", "1.0"), new FormatMod("mod.b", "2.0")), png);

        FormatReadResult result = FormatReader.readPackage(file);

        assertEquals(frames(), result.frames());
        assertEquals(metadata().appearance(), result.metadata().appearance());
        assertEquals(metadata().equipment(), result.metadata().equipment());
        assertEquals(2, result.mods().size());
        assertEquals("mod.b", result.mods().get(1).id());
        assertArrayEquals(png, result.preview());
        assertEquals("none", result.worldStatus());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals(1_700_000_060_000L, result.metadata().endTimeMillis());
        assertEquals(30, result.metadata().sampleRateHz(), 0.0001);
    }

    @Test
    void manifestAcceptsUnknownKeysWithAWarning() throws Exception {
        byte[] bytes = (manifestText() + "future_key=ignored\n")
            .getBytes(StandardCharsets.UTF_8);

        FormatManifest manifest = FormatManifest.parse(bytes);

        assertTrue(manifest.warnings.stream()
            .anyMatch(warning -> warning.contains("future_key")));
    }

    @Test
    void manifestRejectsDuplicateKeys() {
        byte[] bytes = (manifestText() + "game_version=42.21.0\n")
            .getBytes(StandardCharsets.UTF_8);

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatManifest.parse(bytes));
        assertEquals(FormatReason.MANIFEST_DUPLICATE_KEY, exception.reason());
    }

    @Test
    void manifestRejectsMissingRequiredFields() {
        byte[] bytes = removeLine(manifestText(), "source_map_name")
            .getBytes(StandardCharsets.UTF_8);

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatManifest.parse(bytes));
        assertEquals(FormatReason.MANIFEST_MISSING_FIELD, exception.reason());
    }

    @Test
    void manifestRejectsBadEnumsAndNumbers() {
        FormatException enumError = assertThrows(FormatException.class,
            () -> FormatManifest.parse(replace(manifestText(), "role=local", "role=captain")
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(FormatReason.MANIFEST_FIELD_INVALID, enumError.reason());

        FormatException numberError = assertThrows(FormatException.class,
            () -> FormatManifest.parse(replace(manifestText(),
                "actor_sample_rate_hz=30", "actor_sample_rate_hz=030")
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(FormatReason.MANIFEST_FIELD_INVALID, numberError.reason());
    }

    @Test
    void manifestRejectsBlankLinesAndControlCharacters() {
        FormatException blank = assertThrows(FormatException.class,
            () -> FormatManifest.parse((manifestText() + "\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals(FormatReason.MANIFEST_GRAMMAR, blank.reason());

        FormatException control = assertThrows(FormatException.class,
            () -> FormatManifest.parse((manifestText() + "bad=tab\there\n")
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(FormatReason.MANIFEST_GRAMMAR, control.reason());
    }

    @Test
    void manifestRejectsOversizedAndOverCountInput() {
        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < 600; index++) {
            oversized.append("unknown").append(index).append('=')
                .append("x".repeat(100)).append('\n');
        }
        FormatException tooLarge = assertThrows(FormatException.class,
            () -> FormatManifest.parse(oversized.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(FormatReason.MANIFEST_LENGTH, tooLarge.reason());

        String tooManyActors = replace(manifestText(), "actor_count=1", "actor_count=17");
        FormatException tooMany = assertThrows(FormatException.class,
            () -> FormatManifest.parse(tooManyActors.getBytes(StandardCharsets.UTF_8)));
        assertEquals(FormatReason.MANIFEST_FIELD_INVALID, tooMany.reason());
    }

    @Test
    void readerRejectsSectionCountAboveTheCap() throws Exception {
        List<FormatTestSupport.Section> sections = new ArrayList<>(validSections());
        for (int index = 0; index < 64; index++) {
            sections.add(new FormatTestSupport.Section("extra/s" + index, false, 1,
                new byte[] {(byte) index}));
        }
        Path file = tempDir.resolve("sections.pzrp.gz");
        writeRaw(file, FormatTestSupport.raw(manifestText(), sections));

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatReader.readInfo(file));
        assertEquals(FormatReason.SECTION_TABLE, exception.reason());
    }

    @Test
    void readerRejectsGzipCrcTrailingBytesAndTruncation() throws Exception {
        Path good = tempDir.resolve("good.pzrp.gz");
        writeRaw(good, rawPackage());
        byte[] gzip = Files.readAllBytes(good);

        Path badCrc = tempDir.resolve("bad-crc.pzrp.gz");
        byte[] corrupt = gzip.clone();
        corrupt[corrupt.length - 1] ^= 0x01;
        Files.write(badCrc, corrupt);
        assertEquals(FormatReason.GZIP_CRC, readError(badCrc));

        Path trailing = tempDir.resolve("trailing.pzrp.gz");
        Files.write(trailing, FormatTestSupport.concat(gzip, new byte[] {1}));
        assertEquals(FormatReason.TRAILING_BYTES, readError(trailing));

        Path truncated = tempDir.resolve("truncated.pzrp.gz");
        Files.write(truncated, Arrays.copyOf(gzip, gzip.length - 5));
        assertEquals(FormatReason.TRUNCATED, readError(truncated));
    }

    @Test
    void readerRejectsBadManifestHashSectionHashAndFooter() throws Exception {
        Path badManifest = tempDir.resolve("bad-manifest.pzrp.gz");
        byte[] raw = rawPackage();
        FormatTestSupport.setManifestHash(raw, new byte[32]);
        writeRaw(badManifest, raw);
        assertEquals(FormatReason.MANIFEST_HASH, readError(badManifest));

        Path badSection = tempDir.resolve("bad-section.pzrp.gz");
        raw = rawPackage();
        int hashOffset = FormatTestSupport.sectionHashOffset(raw, "actor/local");
        raw[hashOffset] ^= 0x01;
        FormatTestSupport.refreshCrc(raw);
        writeRaw(badSection, raw);
        assertEquals(FormatReason.SECTION_HASH, playbackError(badSection));

        Path badFooter = tempDir.resolve("bad-footer.pzrp.gz");
        raw = rawPackage();
        raw[raw.length - 12] ^= 0x01;
        writeRaw(badFooter, raw);
        assertEquals(FormatReason.FOOTER, readError(badFooter));

        Path badMagic = tempDir.resolve("bad-magic.pzrp.gz");
        raw = rawPackage();
        FormatTestSupport.setFooterMagic(raw, "PZRECEND".getBytes(StandardCharsets.US_ASCII));
        raw[raw.length - 8] ^= 0x01;
        writeRaw(badMagic, raw);
        assertEquals(FormatReason.FOOTER, readError(badMagic));
    }

    @Test
    void oldInternalLayoutIsRejectedWithoutMigration() throws Exception {
        byte[] old = {'P', 'Z', 'R', 'P', 0, 0, 0, 1, 0, 0, 0, 0};

        Path raw = tempDir.resolve("old-raw.pzrp.gz");
        Files.write(raw, old);
        assertEquals(FormatReason.OLD_LAYOUT, readError(raw));

        Path gzip = tempDir.resolve("old-gzip.pzrp.gz");
        Files.write(gzip, FormatGzip.deflate(old));
        assertEquals(FormatReason.OLD_LAYOUT, readError(gzip));
    }

    @Test
    void readerRejectsUnsupportedVersionsFlagsAndReaderFormat() throws Exception {
        Path versionZero = tempDir.resolve("version-0.pzrp.gz");
        byte[] raw = rawPackage();
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putShort(8, (short) 0);
        FormatTestSupport.refreshCrc(raw);
        writeRaw(versionZero, raw);
        assertEquals(FormatReason.UNSUPPORTED_VERSION, readError(versionZero));

        Path versionTwo = tempDir.resolve("version-2.pzrp.gz");
        raw = rawPackage();
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putShort(8, (short) 2);
        FormatTestSupport.refreshCrc(raw);
        writeRaw(versionTwo, raw);
        assertEquals(FormatReason.UNSUPPORTED_VERSION, readError(versionTwo));

        Path flags = tempDir.resolve("flags.pzrp.gz");
        raw = rawPackage();
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putShort(10, (short) 1);
        FormatTestSupport.refreshCrc(raw);
        writeRaw(flags, raw);
        assertEquals(FormatReason.UNSUPPORTED_FLAGS, readError(flags));

        Path minReader = tempDir.resolve("min-reader.pzrp.gz");
        writeRaw(minReader, FormatTestSupport.raw(
            replace(manifestText(), "min_reader_format=1", "min_reader_format=2"),
            validSections()));
        assertEquals(FormatReason.UNSUPPORTED_VERSION, readError(minReader));

        Path manifestVersion = tempDir.resolve("manifest-version.pzrp.gz");
        writeRaw(manifestVersion, FormatTestSupport.raw(
            replace(manifestText(), "manifest_version=1", "manifest_version=2"),
            validSections()));
        assertEquals(FormatReason.UNSUPPORTED_VERSION, readError(manifestVersion));
    }

    @Test
    void unknownOptionalSectionIsSkippedAndUnknownRequiredSectionBlocks() throws Exception {
        Path optional = tempDir.resolve("optional.pzrp.gz");
        List<FormatTestSupport.Section> sections = new ArrayList<>(validSections());
        sections.add(new FormatTestSupport.Section("custom/blob", false, 1, new byte[] {1}));
        writeRaw(optional, FormatTestSupport.raw(manifestText(), sections));

        FormatReadResult result = FormatReader.readPackage(optional);
        assertEquals(frames(), result.frames());
        assertTrue(result.warnings().stream()
            .anyMatch(warning -> warning.contains("unknown optional section")));

        Path required = tempDir.resolve("required.pzrp.gz");
        sections = new ArrayList<>(validSections());
        sections.add(new FormatTestSupport.Section("custom/blob", true, 1, new byte[] {1}));
        writeRaw(required, FormatTestSupport.raw(manifestText(), sections));

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatReader.readPackage(required));
        assertEquals(FormatReason.UNKNOWN_REQUIRED_SECTION, exception.reason());
    }

    @Test
    void missingActorSectionBlocksPlaybackButNotTheCatalogue() throws Exception {
        Path file = tempDir.resolve("missing-actor.pzrp.gz");
        writeRaw(file, FormatTestSupport.raw(manifestText(), List.of()));

        FormatInfo info = FormatReader.readInfo(file);
        assertEquals("Replay Tester", info.metadata().playerName());

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatReader.readPackage(file));
        assertEquals(FormatReason.ACTOR_SECTION_MISSING, exception.reason());
    }

    @Test
    void newerActorSchemaBlocksActorsAndNewerMetadataOnlyWarns() throws Exception {
        Path actorNewer = tempDir.resolve("actor-newer.pzrp.gz");
        List<FormatTestSupport.Section> sections = new ArrayList<>();
        sections.add(new FormatTestSupport.Section("actor/local", true, 2,
            FormatTestSupport.actorPayload(frames())));
        sections.add(new FormatTestSupport.Section("actor_meta/local", false, 1,
            FormatTestSupport.actorMetadata()));
        writeRaw(actorNewer, FormatTestSupport.raw(manifestText(), sections));

        FormatException exception = assertThrows(FormatException.class,
            () -> FormatReader.readPackage(actorNewer));
        assertEquals(FormatReason.SECTION_SCHEMA_NEWER, exception.reason());

        Path metaNewer = tempDir.resolve("meta-newer.pzrp.gz");
        sections = new ArrayList<>();
        sections.add(new FormatTestSupport.Section("actor/local", true, 1,
            FormatTestSupport.actorPayload(frames())));
        sections.add(new FormatTestSupport.Section("actor_meta/local", false, 2,
            FormatTestSupport.actorMetadata()));
        writeRaw(metaNewer, FormatTestSupport.raw(manifestText(), sections));

        FormatReadResult result = FormatReader.readPackage(metaNewer);
        assertEquals(frames(), result.frames());
        assertEquals(Appearance.none(), result.metadata().appearance());
        assertTrue(result.warnings().stream()
            .anyMatch(warning -> warning.contains("actor metadata ignored")));
    }

    @Test
    void worldSectionsAreRejectedReadOnlyWhileActorsStillPlay() throws Exception {
        byte[] worldBase = "BASE".getBytes(StandardCharsets.UTF_8);
        byte[] worldLog = "LOG!".getBytes(StandardCharsets.UTF_8);
        String manifest = FormatTestSupport.worldManifest(metadata(), frames().size(), true,
            worldBase, worldLog);
        List<FormatTestSupport.Section> sections = new ArrayList<>(validSections());
        sections.add(new FormatTestSupport.Section("world_base", true, 1, worldBase));
        sections.add(new FormatTestSupport.Section("world_log", true, 1, worldLog));

        Path validWorld = tempDir.resolve("world.pzrp.gz");
        writeRaw(validWorld, FormatTestSupport.raw(manifest, sections));
        FormatReadResult result = FormatReader.readPackage(validWorld);
        assertEquals(frames(), result.frames());
        assertEquals("unsupported", result.worldStatus());
        assertEquals(FormatReason.WORLD_UNSUPPORTED, result.worldReason());

        Path corruptWorld = tempDir.resolve("world-corrupt.pzrp.gz");
        byte[] raw = FormatTestSupport.raw(manifest, sections);
        int hashOffset = FormatTestSupport.sectionHashOffset(raw, "world_log");
        raw[hashOffset] ^= 0x01;
        FormatTestSupport.refreshCrc(raw);
        writeRaw(corruptWorld, raw);
        result = FormatReader.readPackage(corruptWorld);
        assertEquals(frames(), result.frames());
        assertEquals(FormatReason.WORLD_SECTION_HASH, result.worldReason());
    }

    @Test
    void recordCountAndPayloadShapeAreChecked() throws Exception {
        Path badLength = tempDir.resolve("bad-length.pzrp.gz");
        List<FormatTestSupport.Section> sections = List.of(
            new FormatTestSupport.Section("actor/local", true, 1, new byte[41]));
        writeRaw(badLength, FormatTestSupport.raw(manifestText(), sections));
        assertEquals(FormatReason.ACTOR_PAYLOAD_INVALID,
            playbackError(badLength));

        Path badCount = tempDir.resolve("bad-count.pzrp.gz");
        writeRaw(badCount, FormatTestSupport.raw(
            replace(manifestText(), "actor.0.frame_count=3", "actor.0.frame_count=2"),
            validSections()));
        assertEquals(FormatReason.ACTOR_PAYLOAD_INVALID, playbackError(badCount));
    }

    @Test
    void catalogueReadsManifestOnlyAndPlaybackVerifiesPayload() throws Exception {
        Path file = tempDir.resolve("catalogue.pzrp.gz");
        List<FormatTestSupport.Section> sections = List.of(
            new FormatTestSupport.Section("actor/local", true, 1, new byte[41]));
        writeRaw(file, FormatTestSupport.raw(manifestText(), sections));

        ReplayCatalogue.Result result = ReplayCatalogue.list(tempDir, "");
        ReplayCatalogue.Entry entry = result.entries().stream()
            .filter(candidate -> candidate.fileName().equals("catalogue.pzrp.gz"))
            .findFirst().orElseThrow();
        assertTrue(entry.valid());
        assertEquals("none", entry.worldStatus());

        assertEquals(FormatReason.ACTOR_PAYLOAD_INVALID,
            assertThrows(FormatException.class, () -> FormatReader.readPackage(file)).reason());
    }

    @Test
    void identicalInputProducesIdenticalBytes() throws Exception {
        Path first = tempDir.resolve("first.pzrp.gz");
        Path second = tempDir.resolve("second.pzrp.gz");

        FormatWriter.write(first, metadata(), frames());
        FormatWriter.write(second, metadata(), frames());

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void atomicWritePublishesOnlyAfterReadbackAndLeavesNoPartFile() throws Exception {
        Path file = tempDir.resolve("atomic.pzrp.gz");
        FormatWriter.write(file, metadata(), frames());

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(tempDir.resolve("atomic.pzrp.gz.part")));
        assertEquals(frames(), FormatReader.readPackage(file).frames());
    }

    @Test
    void atomicWriteRefusesToOverwriteAndLeavesNoPartFile() throws Exception {
        Path file = tempDir.resolve("existing.pzrp.gz");
        Files.writeString(file, "keep");

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> FormatWriter.write(file, metadata(), frames()));
        assertEquals("keep", Files.readString(file));
        assertFalse(Files.exists(tempDir.resolve("existing.pzrp.gz.part")));
    }

    @Test
    void cleanupRemovesOnlyOldPartAndStagingEntries() throws Exception {
        Path oldPart = tempDir.resolve("old.pzrp.gz.part");
        Path newPart = tempDir.resolve("new.pzrp.gz.part");
        Path staging = tempDir.resolve(".pzrp-tmp");
        Path oldStage = staging.resolve("old");
        Path newStage = staging.resolve("new");
        Files.writeString(oldPart, "old");
        Files.writeString(newPart, "new");
        Files.createDirectories(oldStage);
        Files.createDirectories(newStage);
        Files.setLastModifiedTime(oldPart,
            java.nio.file.attribute.FileTime.fromMillis(0L));
        Files.setLastModifiedTime(oldStage,
            java.nio.file.attribute.FileTime.fromMillis(0L));

        FormatWriter.cleanupOrphans(tempDir, 2L * 24 * 60 * 60 * 1000);

        assertFalse(Files.exists(oldPart));
        assertTrue(Files.exists(newPart));
        assertFalse(Files.exists(oldStage));
        assertTrue(Files.exists(newStage));
    }

    @Test
    void migrationWritesListsPlaysAndDeletesTheNewFormat() throws Exception {
        Path file = tempDir.resolve("migration.pzrp.gz");
        FormatWriter.write(file, metadata(), frames());

        ReplayCatalogue.Result catalogue = ReplayCatalogue.list(tempDir, file.toString());
        assertEquals(1, catalogue.entries().size());
        assertTrue(catalogue.entries().get(0).valid());
        assertTrue(catalogue.entries().get(0).lastReplay());

        ReplayData data = ReplayReader.read(file);
        PlaybackTimeline timeline = new PlaybackTimeline(data);
        assertEquals(frames().size(), timeline.getFrameCount());
        assertEquals(metadata().appearance(), data.metadata().appearance());

        ReplayFileManager.Result deleted = ReplayFileManager.delete(tempDir, file.toString());
        assertTrue(deleted.ok(), deleted.error());
        assertFalse(Files.exists(file));
        assertTrue(ReplayCatalogue.list(tempDir, "").entries().isEmpty());
    }

    private static String readError(Path file) {
        FormatException exception = assertThrows(FormatException.class,
            () -> FormatReader.readInfo(file));
        return exception.reason();
    }

    private static String playbackError(Path file) {
        FormatException exception = assertThrows(FormatException.class,
            () -> FormatReader.readPackage(file));
        return exception.reason();
    }

    private static String replace(String text, String from, String to) {
        return text.replace(from, to);
    }

    private static String removeLine(String text, String key) {
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.startsWith(key + "=")) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
