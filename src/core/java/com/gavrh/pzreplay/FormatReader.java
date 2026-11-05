package com.gavrh.pzreplay;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

public final class FormatReader {
    private static final byte[] OLD_MAGIC = {0x50, 0x5A, 0x52, 0x50};
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private enum Kind {
        ACTOR,
        ACTOR_META,
        WORLD,
        PREVIEW,
        MODS,
        META,
        UNKNOWN
    }

    private record Descriptor(
        String id,
        boolean required,
        boolean flagsValid,
        int schemaVersion,
        long length,
        byte[] hash,
        int payloadOffset
    ) {
    }

    private record Container(
        byte[] bytes,
        FormatManifest manifest,
        List<Descriptor> descriptors,
        int footerOffset
    ) {
    }

    private FormatReader() {
    }

    public static ReplayData read(Path source) throws IOException, ReplayFormatException {
        FormatReadResult result = readPackage(source);
        return new ReplayData(result.metadata(), result.frames(), result.warnings());
    }

    public static ReplayMetadata readMetadata(Path source)
        throws IOException, ReplayFormatException {
        return readInfo(source).metadata();
    }

    public static FormatInfo readInfo(Path source) throws IOException, ReplayFormatException {
        FormatManifest manifest = readCatalogueManifest(source);
        ReplayMetadata metadata = FormatMetadata.fromManifest(
            manifest, Map.of(), List.of());
        return new FormatInfo(metadata, manifest.worldStatus(),
            manifest.worldIssue, manifest.warnings);
    }

    public static FormatReadResult readPackage(Path source)
        throws IOException, ReplayFormatException {
        Container container = readContainer(source);
        FormatManifest manifest = container.manifest();
        List<String> warnings = new ArrayList<>(manifest.warnings);
        List<CaptureSample> frames = new ArrayList<>();
        Map<String, String> actorMetadata = new LinkedHashMap<>();

        if (container.descriptors().size() < manifest.actors.size()) {
            throw new FormatException(FormatReason.ACTOR_SECTION_MISSING,
                "section table holds fewer sections than the actor count");
        }

        for (FormatManifest.Actor actor : manifest.actors) {
            Descriptor descriptor = find(container.descriptors(), actor.trackSection());
            if (descriptor == null) {
                throw new FormatException(FormatReason.ACTOR_SECTION_MISSING,
                    "actor section is missing: " + actor.trackSection());
            }
            validateActorDescriptor(descriptor, actor);
            byte[] payload = payload(container, descriptor);
            if (!Arrays.equals(FormatWriter.sha256(payload), descriptor.hash())) {
                throw new FormatException(FormatReason.SECTION_HASH,
                    "actor section hash does not match: " + descriptor.id());
            }
            frames.addAll(decodeFrames(payload, actor, manifest));
        }

        for (FormatManifest.Actor actor : manifest.actors) {
            if (actor.metaSection().isEmpty()) {
                continue;
            }
            Descriptor descriptor = find(container.descriptors(), actor.metaSection());
            if (descriptor == null) {
                warnings.add("actor metadata is missing: " + actor.metaSection());
                continue;
            }
            if (!descriptor.flagsValid() || descriptor.schemaVersion() > 1
                || descriptor.length() > FormatWriter.MAX_ACTOR_META_PAYLOAD) {
                warnings.add("actor metadata ignored: " + actor.metaSection());
                continue;
            }
            byte[] payload = payload(container, descriptor);
            if (!Arrays.equals(FormatWriter.sha256(payload), descriptor.hash())) {
                warnings.add("actor metadata ignored: hash mismatch");
                continue;
            }
            try {
                FormatActorMetadata.Result parsed = FormatActorMetadata.parse(payload);
                actorMetadata.putAll(parsed.values());
                warnings.addAll(parsed.warnings());
            } catch (FormatException exception) {
                warnings.add("actor metadata ignored: " + exception.getMessage());
            }
        }

        WorldState world = validateWorld(container, warnings);
        byte[] preview = validatePreview(container, warnings);
        List<FormatMod> mods = validateMods(container, warnings);
        validateUnknownSections(container, warnings);

        ReplayMetadata metadata = FormatMetadata.fromManifest(manifest, actorMetadata, frames);
        return new FormatReadResult(metadata, frames, warnings, world.status(),
            world.reason(), preview, mods);
    }

    private static void validateActorDescriptor(Descriptor descriptor,
        FormatManifest.Actor actor) throws FormatException {
        if (!descriptor.flagsValid()) {
            throw new FormatException(FormatReason.SECTION_FLAG,
                "actor section uses a reserved flag: " + descriptor.id());
        }
        if (descriptor.schemaVersion() > 1) {
            throw new FormatException(FormatReason.SECTION_SCHEMA_NEWER,
                "actor section schema is newer: " + descriptor.id());
        }
        if (descriptor.length() > FormatWriter.MAX_ACTOR_PAYLOAD) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor payload exceeds the cap: " + descriptor.id());
        }
    }

    private static List<CaptureSample> decodeFrames(byte[] payload, FormatManifest.Actor actor,
        FormatManifest manifest) throws FormatException {
        if (payload.length % FormatWriter.RECORD_BYTES != 0) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor payload is not a whole number of records");
        }
        long frameCount = payload.length / FormatWriter.RECORD_BYTES;
        if (frameCount != actor.frameCount()) {
            throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                "actor frame count does not match the manifest");
        }
        List<CaptureSample> frames = new ArrayList<>((int) frameCount);
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        float previousTime = -1f;
        double maximumSeconds = Math.max(0L,
            manifest.endTimeMillis - manifest.startTimeMillis) / 1000.0;
        for (int index = 0; index < frameCount; index++) {
            long tick = buffer.getLong();
            float time = buffer.getFloat();
            float gameTime = buffer.getFloat();
            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();
            float angle = buffer.getFloat();
            float speed = buffer.getFloat();
            int locomotionId = buffer.get() & 0xFF;
            int stanceId = buffer.get() & 0xFF;
            int flags = buffer.getShort() & 0xFFFF;
            if (time < previousTime - 0.0001f || time < -0.0001f
                || time > maximumSeconds + 0.001f) {
                throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                    "actor frame time is outside the recording range");
            }
            previousTime = time;
            CaptureSample.Locomotion locomotion;
            CaptureSample.Stance stance;
            try {
                locomotion = CaptureSample.Locomotion.fromId(locomotionId);
                stance = CaptureSample.Stance.fromId(stanceId);
            } catch (IllegalArgumentException exception) {
                throw new FormatException(FormatReason.ACTOR_PAYLOAD_INVALID,
                    "actor frame has an unknown state id", exception);
            }
            frames.add(new CaptureSample(tick, time, gameTime, x, y, z, angle, speed,
                locomotion, stance, flags));
        }
        return frames;
    }

    private record WorldState(String status, String reason) {
    }

    private static WorldState validateWorld(Container container, List<String> warnings)
        throws FormatException {
        FormatManifest manifest = container.manifest();
        List<Descriptor> worldDescriptors = new ArrayList<>();
        for (Descriptor descriptor : container.descriptors()) {
            if (kind(descriptor.id()) == Kind.WORLD) {
                worldDescriptors.add(descriptor);
            }
        }
        if ("off".equals(manifest.worldMode)) {
            if (!worldDescriptors.isEmpty()) {
                warnings.add("world sections ignored while world_mode=off");
            }
            return new WorldState("none", "");
        }

        String reason = manifest.worldIssue;
        Descriptor base = find(container.descriptors(), "world_base");
        Descriptor log = find(container.descriptors(), "world_log");
        if (reason.isEmpty() && "padded".equals(manifest.worldMode)
            && (base == null || log == null)) {
            reason = FormatReason.WORLD_SECTION_MISSING;
        }
        if (reason.isEmpty()) {
            for (Descriptor descriptor : worldDescriptors) {
                if (!descriptor.flagsValid()) {
                    reason = FormatReason.SECTION_FLAG;
                    break;
                }
                if (descriptor.schemaVersion() > 1) {
                    reason = FormatReason.WORLD_SCHEMA_NEWER;
                    break;
                }
            }
        }
        if (reason.isEmpty() && base != null
            && base.length() != manifest.worldBaseBytes) {
            reason = FormatReason.WORLD_COUNT_MISMATCH;
        }
        if (reason.isEmpty() && log != null
            && log.length() != manifest.worldLogBytes) {
            reason = FormatReason.WORLD_COUNT_MISMATCH;
        }
        if (reason.isEmpty()) {
            for (Descriptor descriptor : worldDescriptors) {
                byte[] hash = FormatWriter.sha256(container.bytes(),
                    descriptor.payloadOffset(), (int) descriptor.length());
                if (!Arrays.equals(hash, descriptor.hash())) {
                    reason = FormatReason.WORLD_SECTION_HASH;
                    break;
                }
            }
        }
        if (reason.isEmpty()) {
            reason = FormatReason.WORLD_UNSUPPORTED;
        }
        warnings.add("world playback unavailable: " + reason);
        return new WorldState("unsupported", reason);
    }

    private static byte[] validatePreview(Container container, List<String> warnings)
        throws FormatException {
        Descriptor descriptor = find(container.descriptors(), "preview");
        if (descriptor == null) {
            return new byte[0];
        }
        if (!descriptor.flagsValid() || descriptor.schemaVersion() > 1
            || descriptor.length() > FormatWriter.MAX_PREVIEW_PAYLOAD) {
            warnings.add("preview ignored");
            return new byte[0];
        }
        byte[] payload = payload(container, descriptor);
        if (!Arrays.equals(FormatWriter.sha256(payload), descriptor.hash())) {
            warnings.add("preview ignored: hash mismatch");
            return new byte[0];
        }
        if (payload.length < PNG_SIGNATURE.length
            || !Arrays.equals(Arrays.copyOf(payload, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            warnings.add("preview ignored: not a PNG");
            return new byte[0];
        }
        return payload;
    }

    private static List<FormatMod> validateMods(Container container, List<String> warnings)
        throws FormatException {
        Descriptor descriptor = find(container.descriptors(), "mods");
        FormatManifest manifest = container.manifest();
        if (descriptor == null) {
            if (!manifest.modsSection.isEmpty()) {
                warnings.add("mods section is missing");
            }
            return List.of();
        }
        if (!descriptor.flagsValid() || descriptor.schemaVersion() > 1
            || descriptor.length() > FormatWriter.MAX_MODS_PAYLOAD) {
            warnings.add("mods section ignored");
            return List.of();
        }
        byte[] payload = payload(container, descriptor);
        if (!Arrays.equals(FormatWriter.sha256(payload), descriptor.hash())) {
            warnings.add("mods section ignored: hash mismatch");
            return List.of();
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        List<FormatMod> mods = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                warnings.add("mods section ignored: malformed line");
                return List.of();
            }
            String line = text.substring(start, end);
            int separator = line.indexOf('\t');
            if (separator <= 0) {
                warnings.add("mods section ignored: malformed line");
                return List.of();
            }
            mods.add(new FormatMod(line.substring(0, separator),
                line.substring(separator + 1)));
            if (mods.size() > FormatWriter.MAX_MODS) {
                warnings.add("mods section ignored: line count exceeds the cap");
                return List.of();
            }
            start = end + 1;
        }
        if (mods.size() != manifest.modsCount) {
            warnings.add("mods count does not match the manifest");
        }
        return mods;
    }

    private static void validateUnknownSections(Container container, List<String> warnings)
        throws FormatException {
        for (Descriptor descriptor : container.descriptors()) {
            Kind kind = kind(descriptor.id());
            if (kind == Kind.UNKNOWN) {
                if (descriptor.required()) {
                    throw new FormatException(FormatReason.UNKNOWN_REQUIRED_SECTION,
                        "unknown required section: " + descriptor.id());
                }
                warnings.add("unknown optional section ignored: " + descriptor.id());
            } else if (kind == Kind.META) {
                warnings.add("meta section ignored: " + descriptor.id());
            }
        }
    }

    private static FormatManifest readCatalogueManifest(Path source)
        throws IOException, ReplayFormatException {
        if (Files.size(source) > FormatWriter.MAX_COMPRESSED_BYTES) {
            throw new FormatException(FormatReason.TOO_LARGE,
                "replay file exceeds the compressed cap");
        }
        try (InputStream file = Files.newInputStream(source);
            BufferedInputStream input = new BufferedInputStream(file)) {
            input.mark(OLD_MAGIC.length);
            byte[] prefix = input.readNBytes(OLD_MAGIC.length);
            input.reset();
            if (prefix.length >= OLD_MAGIC.length
                && Arrays.equals(prefix, OLD_MAGIC)) {
                throw new FormatException(FormatReason.OLD_LAYOUT,
                    "unsupported pre-release replay (old internal layout)");
            }
            if (prefix.length < 2
                || (prefix[0] & 0xFF) != 0x1F || (prefix[1] & 0xFF) != 0x8B) {
                throw new FormatException(FormatReason.NOT_CONTAINER,
                    "not a replay container");
            }
            try (FormatGzip.Stream gzip = FormatGzip.stream(input)) {
                return readCatalogueStream(gzip);
            }
        }
    }

    private static FormatManifest readCatalogueStream(FormatGzip.Stream gzip)
        throws IOException, ReplayFormatException {
        CrcInput input = new CrcInput(gzip);
        byte[] magic = input.read(FormatWriter.MAGIC.length);
        if (Arrays.equals(Arrays.copyOf(magic, OLD_MAGIC.length), OLD_MAGIC)) {
            throw new FormatException(FormatReason.OLD_LAYOUT,
                "unsupported pre-release replay (old internal layout)");
        }
        if (!Arrays.equals(magic, FormatWriter.MAGIC)) {
            throw new FormatException(FormatReason.NOT_CONTAINER,
                "container magic does not match");
        }
        byte[] headerFields = input.read(8);
        int version = u16(headerFields, 0);
        if (version != FormatWriter.CONTAINER_VERSION) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "unsupported container version " + version);
        }
        int flags = u16(headerFields, 2);
        if (flags != 0) {
            throw new FormatException(FormatReason.UNSUPPORTED_FLAGS,
                "unsupported container flags " + flags);
        }
        long manifestLength = u32(headerFields, 4);
        if (manifestLength < 1 || manifestLength > FormatWriter.MAX_MANIFEST_BYTES) {
            throw new FormatException(FormatReason.MANIFEST_LENGTH,
                "manifest length is outside the cap");
        }
        byte[] manifestBytes = input.read((int) manifestLength);
        FormatManifest manifest = FormatManifest.parse(manifestBytes);

        byte[] countBytes = input.read(2);
        int sectionCount = u16(countBytes, 0);
        if (sectionCount > FormatWriter.MAX_SECTIONS) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "section count exceeds the cap");
        }
        Set<String> seen = new LinkedHashSet<>();
        long payloadBytes = 0L;
        for (int index = 0; index < sectionCount; index++) {
            int idLength = u16(input.read(2), 0);
            if (idLength < 1 || idLength > FormatWriter.MAX_SECTION_ID) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section id length is outside the cap");
            }
            String id = new String(input.read(idLength), StandardCharsets.US_ASCII);
            if (!FormatManifest.isValidSectionId(id)) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section id is invalid: " + id);
            }
            byte[] descriptor = input.read(44);
            int sectionFlags = u16(descriptor, 0);
            boolean required = (sectionFlags & 0x0001) != 0;
            int schemaVersion = u16(descriptor, 2);
            if (schemaVersion < 1) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section schema version is below 1: " + id);
            }
            long length = longAt(descriptor, 4);
            if (length < 0L) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section length is negative: " + id);
            }
            if (!seen.add(id)) {
                throw new FormatException(FormatReason.SECTION_DUPLICATE,
                    "duplicate section id: " + id);
            }
            if ((id.startsWith("actor/") || isRequiredWorld(manifest, id)) && !required) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "required section is not marked required: " + id);
            }
            long newTotal = payloadBytes + length;
            if (newTotal < payloadBytes || newTotal > FormatWriter.MAX_DECOMPRESSED_BYTES) {
                throw new FormatException(FormatReason.TOO_LARGE,
                    "section payloads exceed the container cap");
            }
            payloadBytes = newTotal;
        }

        input.skip(payloadBytes);
        long packageCrc = input.crcValue();
        byte[] footer = readExact(gzip, 44);
        if (!Arrays.equals(Arrays.copyOfRange(footer, 36, 44),
            FormatWriter.FOOTER_MAGIC)) {
            throw new FormatException(FormatReason.FOOTER,
                "footer magic does not match");
        }
        if (!Arrays.equals(Arrays.copyOfRange(footer, 0, 32),
            FormatWriter.sha256(manifestBytes))) {
            throw new FormatException(FormatReason.MANIFEST_HASH,
                "manifest hash does not match");
        }
        if (u32(footer, 32) != packageCrc) {
            throw new FormatException(FormatReason.FOOTER,
                "package checksum does not match");
        }
        byte[] extra = new byte[1];
        if (gzip.read(extra, 0, 1) >= 0) {
            throw new FormatException(FormatReason.TRUNCATED,
                "footer offset does not land at the end of the file");
        }
        return manifest;
    }

    private static final class CrcInput {
        private final FormatGzip.Stream stream;
        private final CRC32 crc = new CRC32();

        private CrcInput(FormatGzip.Stream stream) {
            this.stream = stream;
        }

        private byte[] read(int length) throws IOException, FormatException {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = stream.read(bytes, offset, length - offset);
                if (count < 0) {
                    throw new FormatException(FormatReason.TRUNCATED,
                        "container is truncated");
                }
                crc.update(bytes, offset, count);
                offset += count;
            }
            return bytes;
        }

        private void skip(long length) throws IOException, FormatException {
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0L) {
                int count = stream.read(buffer, 0,
                    (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new FormatException(FormatReason.TRUNCATED,
                        "container payload is truncated");
                }
                crc.update(buffer, 0, count);
                remaining -= count;
            }
        }

        private long crcValue() {
            return crc.getValue();
        }
    }

    private static byte[] readExact(FormatGzip.Stream stream, int length)
        throws IOException, FormatException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = stream.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "footer is truncated");
            }
            offset += count;
        }
        return bytes;
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
            | ((long) (bytes[offset + 1] & 0xFF) << 16)
            | ((long) (bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static long longAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static Kind kind(String id) {
        if (id.startsWith("actor/")) {
            return Kind.ACTOR;
        }
        if (id.startsWith("actor_meta/")) {
            return Kind.ACTOR_META;
        }
        if ("world_base".equals(id) || "world_log".equals(id)
            || "world_index".equals(id)) {
            return Kind.WORLD;
        }
        if ("preview".equals(id)) {
            return Kind.PREVIEW;
        }
        if ("mods".equals(id)) {
            return Kind.MODS;
        }
        if (id.startsWith("meta/")) {
            return Kind.META;
        }
        return Kind.UNKNOWN;
    }

    private static Descriptor find(List<Descriptor> descriptors, String id) {
        for (Descriptor descriptor : descriptors) {
            if (descriptor.id().equals(id)) {
                return descriptor;
            }
        }
        return null;
    }

    private static byte[] payload(Container container, Descriptor descriptor) {
        return Arrays.copyOfRange(container.bytes(), descriptor.payloadOffset(),
            descriptor.payloadOffset() + (int) descriptor.length());
    }

    private static Container readContainer(Path source) throws IOException, ReplayFormatException {
        long fileSize = Files.size(source);
        if (fileSize > FormatWriter.MAX_COMPRESSED_BYTES) {
            throw new FormatException(FormatReason.TOO_LARGE,
                "replay file exceeds the compressed cap");
        }
        byte[] compressed = Files.readAllBytes(source);
        if (compressed.length >= OLD_MAGIC.length
            && Arrays.equals(Arrays.copyOf(compressed, OLD_MAGIC.length), OLD_MAGIC)) {
            throw new FormatException(FormatReason.OLD_LAYOUT,
                "unsupported pre-release replay (old internal layout)");
        }
        if (compressed.length < 2
            || (compressed[0] & 0xFF) != 0x1F || (compressed[1] & 0xFF) != 0x8B) {
            throw new FormatException(FormatReason.NOT_CONTAINER,
                "not a replay container");
        }

        byte[] raw = FormatGzip.inflate(compressed);
        if (raw.length >= OLD_MAGIC.length
            && Arrays.equals(Arrays.copyOf(raw, OLD_MAGIC.length), OLD_MAGIC)) {
            throw new FormatException(FormatReason.OLD_LAYOUT,
                "unsupported pre-release replay (old internal layout)");
        }
        if (raw.length < 16) {
            throw new FormatException(FormatReason.TRUNCATED, "container is truncated");
        }
        if (raw.length < FormatWriter.MAGIC.length
            || !Arrays.equals(Arrays.copyOf(raw, FormatWriter.MAGIC.length),
                FormatWriter.MAGIC)) {
            throw new FormatException(FormatReason.NOT_CONTAINER,
                "container magic does not match");
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        buffer.position(8);
        int version = buffer.getShort() & 0xFFFF;
        if (version != FormatWriter.CONTAINER_VERSION) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "unsupported container version " + version);
        }
        int flags = buffer.getShort() & 0xFFFF;
        if (flags != 0) {
            throw new FormatException(FormatReason.UNSUPPORTED_FLAGS,
                "unsupported container flags " + flags);
        }
        long manifestLength = buffer.getInt() & 0xFFFFFFFFL;
        if (manifestLength < 1 || manifestLength > FormatWriter.MAX_MANIFEST_BYTES) {
            throw new FormatException(FormatReason.MANIFEST_LENGTH,
                "manifest length is outside the cap");
        }
        if (buffer.position() + manifestLength > raw.length) {
            throw new FormatException(FormatReason.TRUNCATED, "manifest is truncated");
        }
        byte[] manifestBytes = new byte[(int) manifestLength];
        buffer.get(manifestBytes);
        FormatManifest manifest = FormatManifest.parse(manifestBytes);

        if (buffer.remaining() < 2) {
            throw new FormatException(FormatReason.TRUNCATED, "section table is truncated");
        }
        int sectionCount = buffer.getShort() & 0xFFFF;
        if (sectionCount > FormatWriter.MAX_SECTIONS) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "section count exceeds the cap");
        }
        List<Descriptor> descriptors = new ArrayList<>(sectionCount);
        Map<String, Boolean> seen = new LinkedHashMap<>();
        long payloadBytes = 0L;
        for (int index = 0; index < sectionCount; index++) {
            if (buffer.remaining() < 2) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "section descriptor is truncated");
            }
            int idLength = buffer.getShort() & 0xFFFF;
            if (idLength < 1 || idLength > FormatWriter.MAX_SECTION_ID) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section id length is outside the cap");
            }
            if (buffer.remaining() < idLength + 44) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "section descriptor is truncated");
            }
            byte[] idBytes = new byte[idLength];
            buffer.get(idBytes);
            String id = new String(idBytes, StandardCharsets.US_ASCII);
            if (!FormatManifest.isValidSectionId(id)) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section id is invalid: " + id);
            }
            int sectionFlags = buffer.getShort() & 0xFFFF;
            boolean required = (sectionFlags & 0x0001) != 0;
            boolean flagsValid = (sectionFlags & 0xFFFE) == 0;
            int schemaVersion = buffer.getShort() & 0xFFFF;
            if (schemaVersion < 1) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section schema version is below 1: " + id);
            }
            long length = buffer.getLong();
            if (length < 0L) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "section length is negative: " + id);
            }
            byte[] hash = new byte[32];
            buffer.get(hash);
            if (seen.put(id, Boolean.TRUE) != null) {
                throw new FormatException(FormatReason.SECTION_DUPLICATE,
                    "duplicate section id: " + id);
            }
            if ((id.startsWith("actor/") || isRequiredWorld(manifest, id)) && !required) {
                throw new FormatException(FormatReason.SECTION_TABLE,
                    "required section is not marked required: " + id);
            }
            long newTotal = payloadBytes + length;
            if (newTotal < payloadBytes || newTotal > FormatWriter.MAX_DECOMPRESSED_BYTES) {
                throw new FormatException(FormatReason.TOO_LARGE,
                    "section payloads exceed the container cap");
            }
            payloadBytes = newTotal;
            descriptors.add(new Descriptor(id, required, flagsValid, schemaVersion, length,
                hash, 0));
        }

        long payloadStart = buffer.position();
        long footerOffset = payloadStart + payloadBytes;
        if (footerOffset + 44L != raw.length) {
            throw new FormatException(FormatReason.TRUNCATED,
                "footer offset does not land at the end of the file");
        }

        List<Descriptor> offsetDescriptors = new ArrayList<>(descriptors.size());
        int offset = (int) payloadStart;
        for (Descriptor descriptor : descriptors) {
            offsetDescriptors.add(new Descriptor(descriptor.id(), descriptor.required(),
                descriptor.flagsValid(), descriptor.schemaVersion(), descriptor.length(),
                descriptor.hash(), offset));
            offset += (int) descriptor.length();
        }

        int footer = (int) footerOffset;
        byte[] footerMagic = Arrays.copyOfRange(raw, footer + 36, footer + 44);
        if (!Arrays.equals(footerMagic, FormatWriter.FOOTER_MAGIC)) {
            throw new FormatException(FormatReason.FOOTER, "footer magic does not match");
        }
        byte[] storedManifestHash = Arrays.copyOfRange(raw, footer, footer + 32);
        if (!Arrays.equals(storedManifestHash, FormatWriter.sha256(manifestBytes))) {
            throw new FormatException(FormatReason.MANIFEST_HASH,
                "manifest hash does not match");
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, footer);
        long storedCrc = ByteBuffer.wrap(raw, footer + 32, 4)
            .order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
        if (crc.getValue() != storedCrc) {
            throw new FormatException(FormatReason.FOOTER,
                "package checksum does not match");
        }

        return new Container(raw, manifest, List.copyOf(offsetDescriptors), footer);
    }

    private static boolean isRequiredWorld(FormatManifest manifest, String id) {
        return "padded".equals(manifest.worldMode)
            && ("world_base".equals(id) || "world_log".equals(id));
    }
}
