package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private void assertBinaryRoundTrip(Path file) throws IOException, ReplayFormatException {
        ReplayData data = BinaryReplayCodec.read(file);
        assertEquals(metadata(), data.metadata());
        assertEquals(frames(), data.frames());
    }
}
