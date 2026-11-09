package com.gavrh.pzreplay;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class CaptureFrameStage implements Closeable {
    private final Path path;
    private final ByteBuffer record =
        ByteBuffer.allocate(FormatWriter.RECORD_BYTES).order(ByteOrder.BIG_ENDIAN);
    private OutputStream output;
    private long frameCount;
    private float previousTime = -1f;
    private boolean closed;

    public CaptureFrameStage(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("frame stage path is required");
        }
        this.path = path;
        open();
    }

    public synchronized void append(CaptureSample frame) throws IOException, ReplayFormatException {
        if (frame == null) {
            throw new IllegalArgumentException("frame is required");
        }
        if (closed) {
            throw new IOException("frame stage is closed");
        }
        if (frame.timeSeconds() < previousTime - 0.0001f) {
            throw new ReplayFormatException("actor frame time is decreasing");
        }
        record.clear();
        FormatWriter.writeRecord(record, frame);
        output.write(record.array(), 0, FormatWriter.RECORD_BYTES);
        previousTime = frame.timeSeconds();
        frameCount++;
    }

    public synchronized void reset() throws IOException {
        close();
        open();
    }

    public Path path() {
        return path;
    }

    public synchronized long frameCount() {
        return frameCount;
    }

    public synchronized long bytes() {
        return frameCount * FormatWriter.RECORD_BYTES;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        output.close();
        output = null;
    }

    private void open() throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        output = Files.newOutputStream(path, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        frameCount = 0L;
        previousTime = -1f;
        closed = false;
    }
}
