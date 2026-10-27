package com.gavrh.pzreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BinaryReplayCodec {
    public static final int MAGIC = 0x505A5250;
    public static final long FOOTER = 0x505A5250454E4421L;
    public static final int RECORD_BYTES = 40;
    private static final int LOCOMOTION_OFFSET = 36;
    private static final int STANCE_OFFSET = 37;
    private static final int FLAGS_OFFSET = 38;
    private static final int MAX_METADATA_BYTES = 1 << 20;
    static final int MAX_FRAMES = 54_000;
    static final long MAX_UNCOMPRESSED_BYTES =
        (long) MAX_METADATA_BYTES + (long) MAX_FRAMES * RECORD_BYTES + 28;
    private static final long MAX_COMPRESSED_BYTES = MAX_UNCOMPRESSED_BYTES + 64 * 1024;

    private BinaryReplayCodec() {
    }

    public static void write(Path target, ReplayMetadata metadata, List<ReplaySample> frames,
        boolean gzip) throws IOException, ReplayFormatException {
        if (metadata.formatVersion() != ReplayMetadata.CURRENT_FORMAT_VERSION) {
            throw new ReplayFormatException("cannot write format version "
                + metadata.formatVersion());
        }
        if (frames.size() > MAX_FRAMES) {
            throw new ReplayFormatException("too many frames: " + frames.size());
        }

        byte[] metadataBytes = metadata.toText().getBytes(StandardCharsets.UTF_8);
        if (metadataBytes.length > MAX_METADATA_BYTES) {
            throw new ReplayFormatException("replay metadata is too large");
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);

        out.writeInt(MAGIC);
        out.writeInt(metadata.formatVersion());
        out.writeInt(metadataBytes.length);
        out.write(metadataBytes);
        out.writeInt(frames.size());

        CRC32 crc = new CRC32();
        crc.update(metadataBytes);
        byte[] record = new byte[RECORD_BYTES];
        for (ReplaySample frame : frames) {
            ByteBuffer buffer = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
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
            crc.update(record);
            out.write(record);
        }
        out.writeInt((int) crc.getValue());
        out.writeLong(FOOTER);
        out.flush();

        byte[] encoded;
        if (gzip) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOut = new GZIPOutputStream(compressed)) {
                gzipOut.write(payload.toByteArray());
            }
            encoded = compressed.toByteArray();
        } else {
            encoded = payload.toByteArray();
        }
        ReplayFiles.writeAtomically(target, encoded);
    }

    public static ReplayData read(Path source) throws IOException, ReplayFormatException {
        return decode(source, true);
    }

    public static ReplayMetadata readMetadata(Path source)
        throws IOException, ReplayFormatException {
        return decode(source, false).metadata();
    }

    private static ReplayData decode(Path source, boolean keepFrames)
        throws IOException, ReplayFormatException {
        byte[] bytes = readPayload(source);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new ReplayFormatException("not a binary replay (bad magic)");
            }
            int version = in.readInt();
            if (version != ReplayMetadata.CURRENT_FORMAT_VERSION) {
                throw new ReplayFormatException(
                    "unsupported replay format version " + version
                        + " (supported: " + ReplayMetadata.CURRENT_FORMAT_VERSION + ")");
            }
            int metadataLength = in.readInt();
            if (metadataLength < 0 || metadataLength > MAX_METADATA_BYTES) {
                throw new ReplayFormatException("corrupt replay: invalid metadata length "
                    + metadataLength);
            }
            byte[] metadataBytes = new byte[metadataLength];
            in.readFully(metadataBytes);
            int frameCount = in.readInt();
            if (frameCount < 0 || frameCount > MAX_FRAMES) {
                throw new ReplayFormatException("corrupt replay: invalid frame count "
                    + frameCount);
            }

            Map<String, String> values = parseMetadata(metadataBytes);
            ReplayMetadata metadata = ReplayMetadata.fromValues(values);

            CRC32 crc = new CRC32();
            crc.update(metadataBytes);
            List<ReplaySample> frames = keepFrames ? new ArrayList<>(frameCount) : List.of();
            byte[] record = new byte[RECORD_BYTES];
            for (int frame = 0; frame < frameCount; frame++) {
                in.readFully(record);
                crc.update(record);
                ReplaySample.Locomotion locomotion =
                    locomotion(record[LOCOMOTION_OFFSET] & 0xFF, frame);
                ReplaySample.Stance stance = stance(record[STANCE_OFFSET] & 0xFF, frame);
                if (keepFrames) {
                    ByteBuffer buffer = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
                    frames.add(new ReplaySample(
                        buffer.getLong(),
                        buffer.getFloat(),
                        buffer.getFloat(),
                        buffer.getFloat(),
                        buffer.getFloat(),
                        buffer.getFloat(),
                        buffer.getFloat(),
                        buffer.getFloat(),
                        locomotion,
                        stance,
                        buffer.getShort(FLAGS_OFFSET) & 0xFFFF
                    ));
                }
            }

            int storedCrc = in.readInt();
            if (storedCrc != (int) crc.getValue()) {
                throw new ReplayFormatException("replay is corrupt (checksum mismatch)");
            }
            long footer = in.readLong();
            if (footer != FOOTER) {
                throw new ReplayFormatException("replay is corrupt (missing footer)");
            }
            if (in.available() > 0) {
                throw new ReplayFormatException("replay is corrupt (trailing data)");
            }
            return new ReplayData(metadata, frames);
        } catch (EOFException exception) {
            throw new ReplayFormatException("replay is truncated", exception);
        }
    }

    private static byte[] readPayload(Path source) throws IOException, ReplayFormatException {
        if (Files.size(source) > MAX_COMPRESSED_BYTES) {
            throw new ReplayFormatException("replay is too large");
        }
        byte[] bytes = Files.readAllBytes(source);
        if (bytes.length == 0) {
            throw new ReplayFormatException("replay is empty");
        }
        if (isGzip(bytes)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                byte[] decoded = gzip.readNBytes((int) (MAX_UNCOMPRESSED_BYTES + 1));
                if (decoded.length > MAX_UNCOMPRESSED_BYTES) {
                    throw new ReplayFormatException(
                        "replay is corrupt (decompressed data too large)");
                }
                bytes = decoded;
            } catch (EOFException exception) {
                throw new ReplayFormatException("replay is truncated (incomplete gzip stream)",
                    exception);
            } catch (IOException exception) {
                throw new ReplayFormatException("replay is corrupt (invalid gzip stream)",
                    exception);
            }
        }
        if (bytes.length < 20) {
            throw new ReplayFormatException("replay is truncated");
        }
        return bytes;
    }

    private static ReplaySample.Locomotion locomotion(int id, int frame)
        throws ReplayFormatException {
        try {
            return ReplaySample.Locomotion.fromId(id);
        } catch (IllegalArgumentException exception) {
            throw new ReplayFormatException("corrupt frame " + frame
                + ": invalid locomotion id " + id, exception);
        }
    }

    private static ReplaySample.Stance stance(int id, int frame)
        throws ReplayFormatException {
        try {
            return ReplaySample.Stance.fromId(id);
        } catch (IllegalArgumentException exception) {
            throw new ReplayFormatException("corrupt frame " + frame
                + ": invalid stance id " + id, exception);
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static Map<String, String> parseMetadata(byte[] bytes) throws ReplayFormatException {
        Map<String, String> values = new LinkedHashMap<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                throw new ReplayFormatException("corrupt replay metadata: " + trimmed);
            }
            values.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        }
        return values;
    }
}
