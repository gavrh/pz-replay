package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class WorldLogBuilder implements Closeable {
    private final ByteArrayOutputStream memory;
    private final Path path;
    private final OutputStream output;
    private final long maxChanges;
    private final long maxBytes;
    private long changes;
    private long bytes;
    private long lastTimeMillis;
    private boolean t0Written;
    private boolean truncated;

    public WorldLogBuilder() {
        this(WorldLogCodec.MAX_RECORDS, WorldLogCodec.MAX_PAYLOAD_BYTES);
    }

    public WorldLogBuilder(long maxChanges, long maxBytes) {
        if (maxChanges < 1L || maxBytes < 1L) {
            throw new IllegalArgumentException("world log caps must be positive");
        }
        this.maxChanges = maxChanges;
        this.maxBytes = maxBytes;
        this.memory = new ByteArrayOutputStream();
        this.path = null;
        this.output = null;
    }

    public WorldLogBuilder(Path path) throws IOException {
        this(path, WorldLogCodec.MAX_RECORDS, WorldLogCodec.MAX_PAYLOAD_BYTES);
    }

    public WorldLogBuilder(Path path, long maxChanges, long maxBytes) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("world log stage path is required");
        }
        if (maxChanges < 1L || maxBytes < 1L) {
            throw new IllegalArgumentException("world log caps must be positive");
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.maxChanges = maxChanges;
        this.maxBytes = maxBytes;
        this.memory = null;
        this.path = path;
        this.output = Files.newOutputStream(path, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public void appendT0(List<WorldStateRecord> records) throws ReplayFormatException {
        if (t0Written) {
            throw new ReplayFormatException("T0 sample is already written");
        }
        byte[] encoded = WorldLogCodec.encodeT0(records);
        long count = WorldLogCodec.countRecords(encoded);
        if (count > maxChanges || encoded.length > maxBytes) {
            throw new ReplayFormatException("T0 sample exceeds the world log cap");
        }
        write(encoded);
        changes = count;
        lastTimeMillis = 0L;
        t0Written = true;
    }

    public boolean append(List<WorldLogEntry> entries, long timeMillis)
        throws ReplayFormatException {
        if (!t0Written) {
            throw new ReplayFormatException("T0 sample is missing");
        }
        if (truncated) {
            return false;
        }
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        if (timeMillis < lastTimeMillis) {
            throw new ReplayFormatException("world log time is decreasing");
        }
        byte[] encoded = WorldLogCodec.encodeDiff(entries, changes + 1L, timeMillis);
        if (changes + entries.size() > maxChanges || bytes + encoded.length > maxBytes) {
            truncated = true;
            return false;
        }
        write(encoded);
        changes += entries.size();
        lastTimeMillis = timeMillis;
        return true;
    }

    public byte[] payload() {
        if (memory == null) {
            throw new IllegalStateException("world log is staged on disk");
        }
        return memory.toByteArray();
    }

    public Path path() {
        return path;
    }

    public long changes() {
        return changes;
    }

    public long bytes() {
        return bytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public void close() throws IOException {
        if (output != null) {
            output.close();
        }
    }

    private void write(byte[] encoded) throws ReplayFormatException {
        try {
            if (memory != null) {
                memory.writeBytes(encoded);
            } else {
                output.write(encoded);
            }
            bytes += encoded.length;
        } catch (IOException exception) {
            throw new ReplayFormatException("world log could not be written", exception);
        }
    }
}
