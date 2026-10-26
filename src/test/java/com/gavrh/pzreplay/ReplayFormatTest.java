package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFormatTest {
    @TempDir
    Path tempDir;

    private static ReplayMetadata metadata() {
        return new ReplayMetadata(
            ReplayMetadata.CURRENT_FORMAT_VERSION,
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
            1_700_000_060_000L
        );
    }

    private static List<ReplaySample> frames() {
        List<ReplaySample> frames = new ArrayList<>();
        frames.add(new ReplaySample(100, 0f, 10f, 1000.5f, 2000.25f, 0f, 45.5f,
            1.25f, ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, 0b100000));
        frames.add(new ReplaySample(130, 1f, 11f, 1001.25f, 2000.75f, 0f, 90.0f,
            2.5f, ReplaySample.Locomotion.RUN, ReplaySample.Stance.STAND, 0b101000));
        frames.add(new ReplaySample(160, 2f, 12f, 1002.0f, 2001.0f, 0.5f, 180.0f,
            0f, ReplaySample.Locomotion.IDLE, ReplaySample.Stance.CROUCH, 0b10));
        return frames;
    }

    @Test
    void binaryRoundTripsUncompressedAndGzip() throws Exception {
        Path plain = tempDir.resolve("roundtrip.pzrp");
        Path gzip = tempDir.resolve("roundtrip.pzrp.gz");
        BinaryReplayCodec.write(plain, metadata(), frames(), false);
        BinaryReplayCodec.write(gzip, metadata(), frames(), true);

        assertBinaryRoundTrip(plain);
        assertBinaryRoundTrip(gzip);
    }

    @Test
    void binaryRejectsMissingFile() {
        Path missing = tempDir.resolve("missing.pzrp.gz");

        assertThrows(IOException.class, () -> ReplayReader.read(missing));
    }

    @Test
    void binaryRejectsTruncatedFile() throws Exception {
        Path file = tempDir.resolve("truncated.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 10));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("truncated"));
    }

    @Test
    void binaryRejectsCorruptFrame() throws Exception {
        Path file = tempDir.resolve("corrupt.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 20] ^= 0x01;
        Files.write(file, bytes);

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("checksum"));
    }

    @Test
    void binaryRejectsUnknownVersion() throws Exception {
        Path file = tempDir.resolve("version.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(4, 99);
        Files.write(file, bytes);

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("unsupported replay format version 99"));
    }

    @Test
    void binaryRejectsBadCrc() throws Exception {
        Path file = tempDir.resolve("crc.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 12] ^= 0x01;
        Files.write(file, bytes);

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("checksum"));
    }

    @Test
    void binaryRejectsBadFooter() throws Exception {
        Path file = tempDir.resolve("footer.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("missing footer"));
    }

    @Test
    void binaryReadsVersionOneMetadataWithoutGameMode() throws Exception {
        Map<String, String> values = metadata().toValues();
        values.remove("game_mode");
        Path file = tempDir.resolve("legacy.pzrp");
        Files.write(file, rawPayload(values));

        ReplayData data = BinaryReplayCodec.read(file);

        assertEquals("", data.metadata().gameMode());
        assertEquals(metadata().saveName(), data.metadata().saveName());
        assertEquals(metadata().mapName(), data.metadata().mapName());
        assertEquals(metadata().playerName(), data.metadata().playerName());
    }

    @Test
    void binaryRejectsMissingMetadataField() throws Exception {
        Map<String, String> values = metadata().toValues();
        values.remove("save_name");
        Path file = tempDir.resolve("missing-field.pzrp");
        Files.write(file, rawPayload(values));

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("missing 'save_name'"));
    }

    @Test
    void binaryRejectsUnknownLocomotionId() throws Exception {
        Path file = tempDir.resolve("unknown-locomotion.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        patchFirstFrameByte(file, 36, 99);

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("locomotion"));
    }

    @Test
    void binaryRejectsUnknownStanceId() throws Exception {
        Path file = tempDir.resolve("unknown-stance.pzrp");
        BinaryReplayCodec.write(file, metadata(), frames(), false);
        patchFirstFrameByte(file, 37, 99);

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("stance"));
    }

    @Test
    void binaryRejectsDecompressedBomb() throws Exception {
        Path file = tempDir.resolve("bomb.pzrp.gz");
        try (OutputStream out = Files.newOutputStream(file);
            GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            byte[] buffer = new byte[8192];
            long remaining = BinaryReplayCodec.MAX_UNCOMPRESSED_BYTES + 1;
            while (remaining > 0) {
                int count = (int) Math.min(buffer.length, remaining);
                gzip.write(buffer, 0, count);
                remaining -= count;
            }
        }

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("decompressed data too large"));
    }

    @Test
    void binaryRejectsFrameCountAboveLimit() throws Exception {
        byte[] metadataBytes = "format=1\n".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(16 + metadataBytes.length + 4)
            .order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(BinaryReplayCodec.MAGIC);
        buffer.putInt(ReplayMetadata.CURRENT_FORMAT_VERSION);
        buffer.putInt(metadataBytes.length);
        buffer.put(metadataBytes);
        buffer.putInt(BinaryReplayCodec.MAX_FRAMES + 1);
        Path file = tempDir.resolve("too-many-frames.pzrp");
        Files.write(file, buffer.array());

        ReplayFormatException exception = assertThrows(ReplayFormatException.class,
            () -> BinaryReplayCodec.read(file));
        assertTrue(exception.getMessage().contains("invalid frame count"));
    }

    @Test
    void sixtySecondCompressedBinaryFitsHourlyBudget() throws Exception {
        Path file = tempDir.resolve("budget.pzrp.gz");
        BinaryReplayCodec.write(file, metadata(), realisticFrames(), true);

        double bytesPerHour = Files.size(file) * 3600.0 / 60.0;
        assertTrue(bytesPerHour < 3.0 * 1024 * 1024,
            "compressed replay would use " + Math.round(bytesPerHour) + " bytes/hour");
    }

    @Test
    void readerReadsCompressedReplay() throws Exception {
        Path file = tempDir.resolve("reader.pzrp.gz");
        BinaryReplayCodec.write(file, metadata(), frames(), true);

        ReplayData data = ReplayReader.read(file);

        assertEquals(3, data.frames().size());
        assertTrue(ReplayReader.summary(data).contains("frames=3"));
    }

    private static byte[] rawPayload(Map<String, String> values) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        byte[] metadataBytes = text.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(metadataBytes);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.writeInt(BinaryReplayCodec.MAGIC);
        out.writeInt(ReplayMetadata.CURRENT_FORMAT_VERSION);
        out.writeInt(metadataBytes.length);
        out.write(metadataBytes);
        out.writeInt(0);
        out.writeInt((int) crc.getValue());
        out.writeLong(BinaryReplayCodec.FOOTER);
        out.flush();
        return payload.toByteArray();
    }

    private static List<ReplaySample> realisticFrames() {
        List<ReplaySample> frames = new ArrayList<>(1800);
        for (int index = 0; index < 1800; index++) {
            float time = index / 30f;
            frames.add(new ReplaySample(
                1000 + index,
                time,
                100f + time,
                10500.125f + index * 0.03125f,
                9800.5f - index * 0.015625f,
                0f,
                (float) (index * 0.01),
                1.25f,
                ReplaySample.Locomotion.WALK,
                ReplaySample.Stance.STAND,
                ReplaySample.FLAG_MOVING
            ));
        }
        return frames;
    }

    private static void patchFirstFrameByte(Path file, int offset, int value) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int metadataLength = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(8);
        int frameStart = 16 + metadataLength;
        bytes[frameStart + offset] = (byte) value;
        Files.write(file, bytes);
    }

    private void assertBinaryRoundTrip(Path file) throws IOException, ReplayFormatException {
        ReplayData data = BinaryReplayCodec.read(file);
        assertEquals(metadata(), data.metadata());
        assertEquals(frames(), data.frames());
    }
}
