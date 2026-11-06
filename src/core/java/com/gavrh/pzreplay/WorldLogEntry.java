package com.gavrh.pzreplay;

public record WorldLogEntry(WorldStateRecord record, int flags) {
    public WorldLogEntry {
        if (record == null) {
            throw new IllegalArgumentException("world log record is missing");
        }
    }

    public WorldLogEntry(WorldStateRecord record) {
        this(record, 0);
    }
}
