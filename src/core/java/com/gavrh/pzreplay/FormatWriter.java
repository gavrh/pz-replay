package com.gavrh.pzreplay;

import java.io.IOException;
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
    static final long MAX_COMPRESSED_BYTES = 1024L * 1024 * 1024;
    static final long MAX_DECOMPRESSED_BYTES = 1024L * 1024 * 1024;
    static final int MAX_FRAMES = 1_000_000;
    static final int MAX_ACTOR_PAYLOAD = 64 * 1024 * 1024;
    static final int MAX_ACTOR_META_PAYLOAD = 1024 * 1024;
    static final int MAX_PREVIEW_PAYLOAD = 2 * 1024 * 1024;
    static final int MAX_MODS_PAYLOAD = 1024 * 1024;
    static final int MAX_MODS = 1024;
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
        if (metadata.formatVersion() != 1) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "cannot write format version " + metadata.formatVersion());
        }
        if (frames.isEmpty() || frames.size() > MAX_FRAMES) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor frame count is outside the cap");
        }

        List<FormatSection> sections = new ArrayList<>();
        byte[] actorPayload = encodeFrames(metadata, frames);
        sections.add(new FormatSection("actor/local", true, 1, actorPayload));

        byte[] actorMetadata = FormatActorMetadata.encode(metadata);
        boolean hasActorMetadata = actorMetadata.length > 0;
        if (hasActorMetadata) {
            sections.add(new FormatSection("actor_meta/local", false, 1, actorMetadata));
        }

        List<FormatMod> resolvedMods = mods == null ? List.of() : mods;
        byte[] modsPayload = encodeMods(resolvedMods);
        boolean hasMods = modsPayload.length > 0;
        if (hasMods) {
            sections.add(new FormatSection("mods", false, 1, modsPayload));
        }

        if (preview != null && preview.length > 0) {
            if (preview.length > MAX_PREVIEW_PAYLOAD || !isPng(preview)) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "preview is not a PNG within the cap");
            }
            sections.add(new FormatSection("preview", false, 1, preview));
        }

        FormatManifest manifest = FormatManifest.fromMetadata(metadata, frames.size(),
            hasActorMetadata, resolvedMods.size(), hasMods ? "1" : "", hasMods);
        byte[] container = encodeContainer(manifest, sections);

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
            writePart(part, container);
            FormatReader.readPackage(part);
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
        return payload;
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

    private static byte[] encodeContainer(FormatManifest manifest, List<FormatSection> sections)
        throws FormatException {
        byte[] manifestBytes = manifest.toText().getBytes(StandardCharsets.UTF_8);
        if (manifestBytes.length < 1 || manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new FormatException(FormatReason.MANIFEST_LENGTH,
                "manifest exceeds the cap");
        }
        if (sections.size() > MAX_SECTIONS) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "section count exceeds the cap");
        }

        List<byte[]> hashes = new ArrayList<>(sections.size());
        long payloadBytes = 0L;
        for (FormatSection section : sections) {
            if (!FormatManifest.isValidSectionId(section.id())) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section id is invalid: " + section.id());
            }
            long length = section.payload().length;
            payloadBytes += length;
            if (payloadBytes > MAX_DECOMPRESSED_BYTES) {
                throw new FormatException(FormatReason.TOO_LARGE,
                    "section payloads exceed the container cap");
            }
            hashes.add(sha256(section.payload()));
        }

        long size = 16L + manifestBytes.length + 2L;
        for (FormatSection section : sections) {
            size += 46L + section.id().length();
        }
        size += payloadBytes + 44L;
        if (size > MAX_DECOMPRESSED_BYTES) {
            throw new FormatException(FormatReason.TOO_LARGE,
                "container exceeds the decompressed cap");
        }

        int payloadStart = 16 + manifestBytes.length + 2;
        for (FormatSection section : sections) {
            payloadStart += 46 + section.id().length();
        }
        int footerOffset = payloadStart + (int) payloadBytes;

        ByteBuffer buffer = ByteBuffer.allocate((int) size).order(ByteOrder.BIG_ENDIAN);
        buffer.put(MAGIC);
        buffer.putShort((short) CONTAINER_VERSION);
        buffer.putShort((short) 0);
        buffer.putInt(manifestBytes.length);
        buffer.put(manifestBytes);
        buffer.putShort((short) sections.size());
        for (int index = 0; index < sections.size(); index++) {
            FormatSection section = sections.get(index);
            byte[] id = section.id().getBytes(StandardCharsets.US_ASCII);
            buffer.putShort((short) id.length);
            buffer.put(id);
            buffer.putShort((short) (section.required() ? 1 : 0));
            buffer.putShort((short) section.schemaVersion());
            buffer.putLong(section.payload().length);
            buffer.put(hashes.get(index));
        }
        for (FormatSection section : sections) {
            buffer.put(section.payload());
        }
        int actualFooter = buffer.position();
        if (actualFooter != footerOffset) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "container layout arithmetic does not match");
        }
        buffer.put(sha256(manifestBytes));
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(buffer.array(), 0, footerOffset);
        buffer.putInt((int) crc.getValue());
        buffer.put(FOOTER_MAGIC);
        if (buffer.position() != size) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "container size arithmetic does not match");
        }
        return buffer.array();
    }

    private static void writePart(Path part, byte[] container) throws IOException {
        byte[] compressed = FormatGzip.deflate(container);
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(compressed);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "SHA-256 is unavailable", exception);
        }
    }
}
