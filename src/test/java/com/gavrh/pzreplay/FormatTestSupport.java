package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

final class FormatTestSupport {
    record Section(String id, boolean required, int schemaVersion, byte[] payload) {
        Section {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private FormatTestSupport() {
    }

    static String manifest(ReplayMetadata metadata, int frameCount, boolean actorMetadata,
        int modsCount) {
        return FormatManifest.fromMetadata(metadata, frameCount, actorMetadata, modsCount,
            modsCount == 0 ? "" : "1", modsCount > 0).toText();
    }

    static String worldManifest(ReplayMetadata metadata, int frameCount, boolean actorMetadata,
        byte[] worldBase, byte[] worldLog) {
        String text = manifest(metadata, frameCount, actorMetadata, 0);
        text = text.replace("world_mode=off\n", "");
        StringBuilder builder = new StringBuilder(text);
        builder.append("world_mode=padded\n");
        builder.append("world_padding_tiles=16\n");
        builder.append("world_sample_rate_hz=2\n");
        builder.append("world_coverage_status=complete\n");
        builder.append("world_coverage_squares=1\n");
        builder.append("world_coverage_objects=0\n");
        builder.append("world_log_changes=0\n");
        builder.append("world_log_bytes=").append(worldLog.length).append('\n');
        builder.append("world_base_bytes=").append(worldBase.length).append('\n');
        builder.append("world_base_format=zip-store-1\n");
        builder.append("world_log_format=pzwlog-1\n");
        builder.append("world_first_sample=full\n");
        return builder.toString();
    }

    static byte[] actorPayload(List<CaptureSample> frames) {
        ByteBuffer buffer = ByteBuffer.allocate(frames.size() * FormatWriter.RECORD_BYTES)
            .order(ByteOrder.BIG_ENDIAN);
        for (CaptureSample frame : frames) {
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
        return buffer.array();
    }

    static byte[] raw(String manifestText, List<Section> sections) throws Exception {
        byte[] manifest = manifestText.getBytes(StandardCharsets.UTF_8);
        int payloadBytes = 0;
        for (Section section : sections) {
            payloadBytes += section.payload().length;
        }
        int size = 16 + manifest.length + 2 + payloadBytes + 44;
        for (Section section : sections) {
            size += 46 + section.id().length();
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.put(FormatWriter.MAGIC);
        buffer.putShort((short) 1);
        buffer.putShort((short) 0);
        buffer.putInt(manifest.length);
        buffer.put(manifest);
        buffer.putShort((short) sections.size());
        for (Section section : sections) {
            byte[] id = section.id().getBytes(StandardCharsets.US_ASCII);
            buffer.putShort((short) id.length);
            buffer.put(id);
            buffer.putShort((short) (section.required() ? 1 : 0));
            buffer.putShort((short) section.schemaVersion());
            buffer.putLong(section.payload().length);
            buffer.put(FormatWriter.sha256(section.payload()));
        }
        for (Section section : sections) {
            buffer.put(section.payload());
        }
        int footer = buffer.position();
        buffer.put(FormatWriter.sha256(manifest));
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, footer);
        buffer.putInt((int) crc.getValue());
        buffer.put(FormatWriter.FOOTER_MAGIC);
        return buffer.array();
    }

    static void write(Path target, String manifestText, List<Section> sections) throws Exception {
        Files.write(target, FormatGzip.deflate(raw(manifestText, sections)));
    }

    static void write(Path target, byte[] raw) throws IOException {
        Files.write(target, FormatGzip.deflate(raw));
    }

    static List<Section> sections(byte[] actorPayload, byte[] actorMetadata) {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("actor/local", true, 1, actorPayload));
        if (actorMetadata != null) {
            sections.add(new Section("actor_meta/local", false, 1, actorMetadata));
        }
        return sections;
    }

    static int sectionHashOffset(byte[] raw, String wanted) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int manifestLength = buffer.getInt(12);
        buffer.position(16 + manifestLength);
        int count = buffer.getShort() & 0xFFFF;
        for (int index = 0; index < count; index++) {
            int idLength = buffer.getShort() & 0xFFFF;
            byte[] idBytes = new byte[idLength];
            buffer.get(idBytes);
            String id = new String(idBytes, StandardCharsets.US_ASCII);
            buffer.position(buffer.position() + 4);
            buffer.getLong();
            int hashOffset = buffer.position();
            buffer.position(buffer.position() + 32);
            if (id.equals(wanted)) {
                return hashOffset;
            }
        }
        throw new IllegalArgumentException("section not found: " + wanted);
    }

    static void refreshCrc(byte[] raw) {
        int footer = raw.length - 44;
        CRC32 crc = new CRC32();
        crc.update(raw, 0, footer);
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            .putInt(footer + 32, (int) crc.getValue());
    }

    static void setManifestHash(byte[] raw, byte[] replacement) {
        System.arraycopy(replacement, 0, raw, raw.length - 44, 32);
    }

    static void setFooterMagic(byte[] raw, byte[] replacement) {
        System.arraycopy(replacement, 0, raw, raw.length - 8, 8);
    }

    static byte[] actorMetadata() {
        return "appearance_version=1\nappearance_data=c25hcHNob3Q=\n"
            .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    static Map<String, String> values(ReplayMetadata metadata) {
        return metadata.toValues();
    }
}
