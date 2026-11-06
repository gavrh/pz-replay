package com.gavrh.pzreplay;

public record WorldLogRecord(
    int kind,
    long sequence,
    long timeMillis,
    int flags,
    WorldStateRecord record
) {
}
