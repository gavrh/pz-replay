package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public final class FormatWriter {
    public static final int RECORD_BYTES = 40;
    public static final int CONTAINER_VERSION = 1;
    public static final byte[] MAGIC =
        "PZRECPKG".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] FOOTER_MAGIC =
        "PZRECEND".getBytes(StandardCharsets.US_ASCII);

    static final int MAX_MANIFEST_BYTES = 65_536;
    static final int MAX_SECTIONS = 64;
    static final int MAX_SECTION_ID = 64;
    static final long MAX_COMPRESSED_BYTES = 2_000_000_000L;
    static final long MAX_DECOMPRESSED_BYTES = 2_000_000_000L;
    static final int MAX_FRAMES = 8_000_000;
    static final int MAX_ACTOR_PAYLOAD = 320_000_000;
    static final int MAX_ACTOR_META_PAYLOAD = 1024 * 1024;
    static final int MAX_PREVIEW_PAYLOAD = 2 * 1024 * 1024;
    static final int MAX_MODS_PAYLOAD = 1024 * 1024;
    static final int MAX_MODS = 1024;
    static final int MAX_WORLD_BASE_PAYLOAD = 768 * 1024 * 1024;
    static final int MAX_WORLD_LOG_PAYLOAD = 512 * 1024 * 1024;
    static final long ORPHAN_AGE_MILLIS = 24L * 60 * 60 * 1000;

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private FormatWriter() {
    }

    public static void write(Path target, ReplayMetadata metadata, List<CaptureSample> frames)
        throws IOException, ReplayFormatException {
        write(target, metadata, frames, List.of(), null);
    }

    public static void write(Path target, ReplayMetadata metadata, List<CaptureSample> frames,
        List<FormatMod> mods, byte[] preview) throws IOException, ReplayFormatException {
        write(target, metadata, frames, mods, preview, null);
    }

    public static void write(Path target, ReplayMetadata metadata, List<CaptureSample> frames,
        List<FormatMod> mods, byte[] preview, WorldPackage world)
        throws IOException, ReplayFormatException {
        if (metadata.formatVersion() != 1) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "cannot write format version " + metadata.formatVersion());
        }
        if (frames.isEmpty() || frames.size() > MAX_FRAMES) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor frame count is outside the cap");
        }

        List<SectionSource> sections = new ArrayList<>();
        sections.add(memorySource("actor/local", true, 1, encodeFrames(metadata, frames)));

        byte[] actorMetadata = FormatActorMetadata.encode(metadata);
        boolean hasActorMetadata = actorMetadata.length > 0;
        if (hasActorMetadata) {
            sections.add(memorySource("actor_meta/local", false, 1, actorMetadata));
        }

        List<FormatMod> resolvedMods = mods == null ? List.of() : mods;
        byte[] modsPayload = encodeMods(resolvedMods);
        boolean hasMods = modsPayload.length > 0;
        if (hasMods) {
            sections.add(memorySource("mods", false, 1, modsPayload));
        }

        addPreview(sections, preview);

        if (world != null) {
            validateWorldPackage(world);
            sections.add(memorySource("world_base", true, 1, world.base()));
            sections.add(memorySource("world_log", true, 1, world.log()));
        }

        FormatManifest manifest = FormatManifest.fromMetadata(metadata, frames.size(),
            hasActorMetadata, resolvedMods.size(), hasMods ? "1" : "", hasMods, world);
        writeContainer(target, manifest, sections);
    }

    public static void writeStaged(Path target, ReplayMetadata metadata,
        CaptureFrameStage frames, List<FormatMod> mods, byte[] preview, WorldPackageStaged world)
        throws IOException, ReplayFormatException {
        if (metadata.formatVersion() != 1) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "cannot write format version " + metadata.formatVersion());
        }
        if (frames == null) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "staged actor track is missing");
        }
        long frameCount = frames.frameCount();
        if (frameCount < 1L || frameCount > MAX_FRAMES) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor frame count is outside the cap");
        }
        if (frames.bytes() != frameCount * RECORD_BYTES || !Files.isRegularFile(frames.path())) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "staged actor track is not readable");
        }
        double maximumSeconds = Math.max(0L,
            metadata.endTimeMillis() - metadata.startTimeMillis()) / 1000.0;
        float lastTime = lastFrameTime(frames.path(), frameCount);
        if (lastTime < -0.0001f || lastTime > maximumSeconds + 0.001f) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor frame time is outside the recording range");
        }

        List<SectionSource> sections = new ArrayList<>();
        sections.add(pathSource("actor/local", true, 1, frames.path(),
            frameCount * RECORD_BYTES));

        byte[] actorMetadata = FormatActorMetadata.encode(metadata);
        boolean hasActorMetadata = actorMetadata.length > 0;
        if (hasActorMetadata) {
            sections.add(memorySource("actor_meta/local", false, 1, actorMetadata));
        }

        List<FormatMod> resolvedMods = mods == null ? List.of() : mods;
        byte[] modsPayload = encodeMods(resolvedMods);
        boolean hasMods = modsPayload.length > 0;
        if (hasMods) {
            sections.add(memorySource("mods", false, 1, modsPayload));
        }

        addPreview(sections, preview);

        if (world != null) {
            validateWorldPackage(world);
            sections.add(pathSource("world_base", true, 1, world.base(), world.baseBytes()));
            sections.add(pathSource("world_log", true, 1, world.log(), world.logBytes()));
        }

        FormatManifest manifest = FormatManifest.fromMetadata(metadata, (int) frameCount,
            hasActorMetadata, resolvedMods.size(), hasMods ? "1" : "", hasMods, world);
        writeContainer(target, manifest, sections);
    }

    private static void addPreview(List<SectionSource> sections, byte[] preview)
        throws FormatException {
        if (preview == null || preview.length == 0) {
            return;
        }
        if (preview.length > MAX_PREVIEW_PAYLOAD || !isPng(preview)) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "preview is not a PNG within the cap");
        }
        sections.add(memorySource("preview", false, 1, preview));
    }

    private static SectionSource memorySource(String id, boolean required, int schemaVersion,
        byte[] payload) {
        return new SectionSource(id, required, schemaVersion, payload, null, payload.length);
    }

    private static SectionSource pathSource(String id, boolean required, int schemaVersion,
        Path path, long length) {
        return new SectionSource(id, required, schemaVersion, null, path, length);
    }

    private static void writeContainer(Path target, FormatManifest manifest,
        List<SectionSource> sections) throws IOException, ReplayFormatException {
        prepareSources(sections);

        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(absoluteTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(absoluteTarget.toString());
        }

        Path part = absoluteTarget.resolveSibling(
            absoluteTarget.getFileName().toString() + ".part");
        try {
            writePart(part, manifest.toText().getBytes(StandardCharsets.UTF_8), sections);
            FormatReader.verify(part);
            moveIntoPlace(part, absoluteTarget);
            syncDirectory(parent);
        } catch (IOException | ReplayFormatException | RuntimeException exception) {
            deleteQuietly(part);
            throw exception;
        } finally {
            deleteQuietly(part);
        }
    }

    static void cleanupOrphans(Path directory, long nowMillis) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".pzrp.gz.part")
                    && olderThan(entry, nowMillis, ORPHAN_AGE_MILLIS)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
        Path staging = directory.resolve(".pzrp-tmp");
        if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(staging)) {
            for (Path entry : stream) {
                if (olderThan(entry, nowMillis, ORPHAN_AGE_MILLIS)) {
                    deleteRecursively(entry);
                }
            }
        }
    }

    private static byte[] encodeFrames(ReplayMetadata metadata, List<CaptureSample> frames)
        throws FormatException {
        if (frames.size() > MAX_ACTOR_PAYLOAD / RECORD_BYTES) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor payload exceeds the cap");
        }
        double maximumSeconds = Math.max(0L,
            metadata.endTimeMillis() - metadata.startTimeMillis()) / 1000.0;
        byte[] payload = new byte[frames.size() * RECORD_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        float previousTime = -1f;
        for (CaptureSample frame : frames) {
            if (frame.timeSeconds() < previousTime - 0.0001f
                || frame.timeSeconds() < -0.0001f
                || frame.timeSeconds() > maximumSeconds + 0.001f) {
                throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                    "actor frame time is outside the recording range");
            }
            previousTime = frame.timeSeconds();
            writeRecord(buffer, frame);
        }
        return payload;
    }

    static void writeRecord(ByteBuffer buffer, CaptureSample frame) {
        buffer.putLong(frame.tick());
        buffer.putFloat(frame.timeSeconds());
        buffer.putFloat(frame.gameTimeSeconds());
        buffer.putFloat(frame.x());
        buffer.putFloat(frame.y());
        buffer.putFloat(frame.z());
        buffer.putFloat(frame.angle());
        buffer.putFloat(frame.speed());
        buffer.put((byte) frame.locomotion().id());
        buffer.put((byte) frame.stance().id());
        buffer.putShort((short) frame.flags());
    }

    private static void validateWorldPackage(WorldPackageStaged world) throws FormatException {
        if (world.baseBytes() < 1L || world.baseBytes() > MAX_WORLD_BASE_PAYLOAD) {
            throw new FormatException(FormatReason.WORLD_BASE_INVALID,
                "world base is outside the cap");
        }
        if (world.logBytes() < 0L || world.logBytes() > MAX_WORLD_LOG_PAYLOAD) {
            throw new FormatException(FormatReason.WORLD_LOG_INVALID,
                "world log is outside the cap");
        }
        if (!Files.isRegularFile(world.base()) || !Files.isRegularFile(world.log())) {
            throw new FormatException(FormatReason.WORLD_SECTION_MISSING,
                "world staging is missing");
        }
        try {
            if (Files.size(world.base()) != world.baseBytes()
                || Files.size(world.log()) != world.logBytes()) {
                throw new FormatException(FormatReason.WORLD_COUNT_MISMATCH,
                    "world payload lengths do not match the manifest");
            }
        } catch (IOException exception) {
            throw new FormatException(FormatReason.WORLD_SECTION_MISSING,
                "world staging is unreadable", exception);
        }
        validateWorldSettings(world.paddingTiles(), world.sampleRateHz(), world.coverageStatus());
        try {
            WorldBaseArchive.validate(world.base());
        } catch (ReplayFormatException | IOException exception) {
            throw new FormatException(FormatReason.WORLD_BASE_INVALID, exception.getMessage(),
                exception);
        }
        try {
            WorldLogCodec.validate(world.log(), world.logChanges(), world.coverageObjects());
        } catch (ReplayFormatException | IOException exception) {
            String reason = exception.getMessage().contains("count")
                ? FormatReason.WORLD_COUNT_MISMATCH : FormatReason.WORLD_LOG_INVALID;
            throw new FormatException(reason, exception.getMessage(), exception);
        }
    }

    private static void validateWorldPackage(WorldPackage world) throws FormatException {
        byte[] base = world.base();
        byte[] log = world.log();
        if (base.length < 1 || base.length > MAX_WORLD_BASE_PAYLOAD) {
            throw new FormatException(FormatReason.WORLD_BASE_INVALID,
                "world base is outside the cap");
        }
        if (log.length > MAX_WORLD_LOG_PAYLOAD) {
            throw new FormatException(FormatReason.WORLD_LOG_INVALID,
                "world log is outside the cap");
        }
        if (world.baseBytes() != base.length || world.logBytes() != log.length) {
            throw new FormatException(FormatReason.WORLD_COUNT_MISMATCH,
                "world payload lengths do not match the manifest");
        }
        validateWorldSettings(world.paddingTiles(), world.sampleRateHz(), world.coverageStatus());
        try {
            WorldBaseArchive.validate(base);
        } catch (ReplayFormatException exception) {
            throw new FormatException(FormatReason.WORLD_BASE_INVALID, exception.getMessage(),
                exception);
        }
        try {
            WorldLogCodec.validate(log, world.logChanges(), world.coverageObjects());
        } catch (ReplayFormatException exception) {
            String reason = exception.getMessage().contains("count")
                ? FormatReason.WORLD_COUNT_MISMATCH : FormatReason.WORLD_LOG_INVALID;
            throw new FormatException(reason, exception.getMessage(), exception);
        }
    }

    private static void validateWorldSettings(int paddingTiles, int sampleRateHz,
        String coverageStatus) throws FormatException {
        if (paddingTiles < 0 || paddingTiles > 128) {
            throw new FormatException(FormatReason.WORLD_BASE_INVALID,
                "world padding is outside the range");
        }
        if (sampleRateHz < 1 || sampleRateHz > 10) {
            throw new FormatException(FormatReason.WORLD_LOG_INVALID,
                "world sample rate is outside the range");
        }
        if (!"complete".equals(coverageStatus)
            && !"partial".equals(coverageStatus)
            && !"truncated".equals(coverageStatus)) {
            throw new FormatException(FormatReason.WORLD_LOG_INVALID,
                "world coverage status is invalid");
        }
    }

    private static byte[] encodeMods(List<FormatMod> mods) throws FormatException {
        if (mods.isEmpty()) {
            return new byte[0];
        }
        if (mods.size() > MAX_MODS) {
            throw new FormatException(FormatReason.SECTION_TABLE, "mod count exceeds the cap");
        }
        StringBuilder builder = new StringBuilder();
        for (FormatMod mod : mods) {
            if (mod.id().isEmpty() || mod.id().indexOf('\t') >= 0
                || mod.id().indexOf('\n') >= 0 || mod.id().indexOf('\r') >= 0
                || mod.version().indexOf('\t') >= 0 || mod.version().indexOf('\n') >= 0
                || mod.version().indexOf('\r') >= 0) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "mod entry is not tab-separated text");
            }
            builder.append(mod.id()).append('\t').append(mod.version()).append('\n');
        }
        byte[] payload = builder.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_MODS_PAYLOAD) {
            throw new FormatException(FormatReason.SECTION_TABLE, "mods section exceeds the cap");
        }
        return payload;
    }

    private static void prepareSources(List<SectionSource> sections) throws FormatException {
        if (sections.size() > MAX_SECTIONS) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "section count exceeds the cap");
        }
        long payloadBytes = 0L;
        for (SectionSource section : sections) {
            if (!FormatManifest.isValidSectionId(section.id)) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section id is invalid: " + section.id);
            }
            if (section.length < 0L) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section length is invalid: " + section.id);
            }
            payloadBytes += section.length;
            if (payloadBytes > MAX_DECOMPRESSED_BYTES) {
                throw new FormatException(FormatReason.TOO_LARGE,
                    "section payloads exceed the container cap");
            }
            section.hash = section.payload == null
                ? hashFile(section.path, section.length)
                : sha256(section.payload);
        }
    }

    private static void writePart(Path part, byte[] manifestBytes, List<SectionSource> sections)
        throws IOException, FormatException {
        if (manifestBytes.length < 1 || manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new FormatException(FormatReason.MANIFEST_LENGTH,
                "manifest exceeds the cap");
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeHeader(header, manifestBytes.length);
        header.writeBytes(manifestBytes);
        header.write((sections.size() >>> 8) & 0xFF);
        header.write(sections.size() & 0xFF);
        long payloadBytes = 0L;
        for (SectionSource section : sections) {
            byte[] id = section.id.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer descriptor = ByteBuffer.allocate(2 + id.length + 44)
                .order(ByteOrder.BIG_ENDIAN);
            descriptor.putShort((short) id.length);
            descriptor.put(id);
            descriptor.putShort((short) (section.required ? 1 : 0));
            descriptor.putShort((short) section.schemaVersion);
            descriptor.putLong(section.length);
            descriptor.put(section.hash);
            header.writeBytes(descriptor.array());
            payloadBytes += section.length;
        }

        long containerBytes = 16L + manifestBytes.length + 2L + payloadBytes + 44L;
        for (SectionSource section : sections) {
            containerBytes += 46L + section.id.length();
        }
        if (containerBytes > MAX_DECOMPRESSED_BYTES) {
            throw new FormatException(FormatReason.TOO_LARGE,
                "container exceeds the decompressed cap");
        }

        try (OutputStream file = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
            GZIPOutputStream gzip = new GZIPOutputStream(file);
            CrcOutputStream crc = new CrcOutputStream(gzip)) {
            byte[] head = header.toByteArray();
            crc.write(head, 0, head.length);
            for (SectionSource section : sections) {
                if (section.payload != null) {
                    crc.write(section.payload, 0, section.payload.length);
                } else {
                    copy(section.path, section.length, crc);
                }
            }
            long expectedFooter = 16L + manifestBytes.length + 2L + payloadBytes;
            for (SectionSource section : sections) {
                expectedFooter += 46L + section.id.length();
            }
            if (crc.bytes() != expectedFooter) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "container layout arithmetic does not match");
            }
            gzip.write(sha256(manifestBytes));
            gzip.write(intBytes(crc.value()));
            gzip.write(FOOTER_MAGIC);
        }
        forceFile(part);
    }

    private static void writeHeader(ByteArrayOutputStream output, int manifestLength) {
        output.writeBytes(MAGIC);
        output.write((CONTAINER_VERSION >>> 8) & 0xFF);
        output.write(CONTAINER_VERSION & 0xFF);
        output.write(0);
        output.write(0);
        output.write((manifestLength >>> 24) & 0xFF);
        output.write((manifestLength >>> 16) & 0xFF);
        output.write((manifestLength >>> 8) & 0xFF);
        output.write(manifestLength & 0xFF);
    }

    private static byte[] intBytes(long value) {
        return new byte[] {
            (byte) ((value >>> 24) & 0xFF),
            (byte) ((value >>> 16) & 0xFF),
            (byte) ((value >>> 8) & 0xFF),
            (byte) (value & 0xFF)
        };
    }

    private static float lastFrameTime(Path path, long frameCount) throws FormatException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer time = ByteBuffer.allocate(Float.BYTES).order(ByteOrder.BIG_ENDIAN);
            long position = (frameCount - 1L) * RECORD_BYTES + Long.BYTES;
            while (time.hasRemaining()) {
                int count = channel.read(time, position + time.position());
                if (count < 0) {
                    throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                        "staged actor track is truncated");
                }
            }
            return time.flip().getFloat();
        } catch (IOException exception) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "staged actor track is unreadable", exception);
        }
    }

    private static byte[] hashFile(Path path, long length) throws FormatException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[8192];
        long remaining = length;
        try (InputStream input = Files.newInputStream(path)) {
            while (remaining > 0L) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new FormatException(FormatReason.SECTION_TABLE,
                        "staged section is truncated: " + path);
                }
                digest.update(buffer, 0, count);
                remaining -= count;
            }
            if (input.read() >= 0) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "staged section is longer than declared: " + path);
            }
        } catch (IOException exception) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "staged section is unreadable: " + path, exception);
        }
        return digest.digest();
    }

    private static void copy(Path path, long length, OutputStream output)
        throws IOException, FormatException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        try (InputStream input = Files.newInputStream(path)) {
            while (remaining > 0L) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new FormatException(FormatReason.SECTION_TABLE,
                        "staged section is truncated: " + path);
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            if (input.read() >= 0) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "staged section is longer than declared: " + path);
            }
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static final class SectionSource {
        private final String id;
        private final boolean required;
        private final int schemaVersion;
        private final byte[] payload;
        private final Path path;
        private final long length;
        private byte[] hash;

        private SectionSource(String id, boolean required, int schemaVersion, byte[] payload,
            Path path, long length) {
            this.id = id;
            this.required = required;
            this.schemaVersion = schemaVersion;
            this.payload = payload;
            this.path = path;
            this.length = length;
        }
    }

    private static final class CrcOutputStream extends OutputStream {
        private final OutputStream output;
        private final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        private long bytes;

        private CrcOutputStream(OutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) throws IOException {
            output.write(value);
            crc.update(value);
            bytes++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            output.write(buffer, offset, length);
            crc.update(buffer, offset, length);
            bytes += length;
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void close() throws IOException {
            output.flush();
        }

        private long value() {
            return crc.getValue();
        }

        private long bytes() {
            return bytes;
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static boolean olderThan(Path path, long nowMillis, long ageMillis) {
        try {
            FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
            return nowMillis - modified.toMillis() >= ageMillis;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE);
    }

    static byte[] sha256(byte[] bytes) throws FormatException {
        return sha256(bytes, 0, bytes.length);
    }

    static byte[] sha256(byte[] bytes, int offset, int length) throws FormatException {
        MessageDigest digest = digest();
        digest.update(bytes, offset, length);
        return digest.digest();
    }

    private static MessageDigest digest() throws FormatException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "SHA-256 is unavailable", exception);
        }
    }
}
