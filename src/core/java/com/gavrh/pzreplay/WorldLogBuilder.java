package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.util.List;

public final class WorldLogBuilder {
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
    private final long maxChanges;
    private final long maxBytes;
    private long changes;
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
        payload.writeBytes(encoded);
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
        if (changes + entries.size() > maxChanges
            || payload.size() + encoded.length > maxBytes) {
            truncated = true;
            return false;
        }
        payload.writeBytes(encoded);
        changes += entries.size();
        lastTimeMillis = timeMillis;
        return true;
    }

    public byte[] payload() {
        return payload.toByteArray();
    }

    public long changes() {
        return changes;
    }

    public long bytes() {
        return payload.size();
    }

    public boolean isTruncated() {
        return truncated;
    }
}
