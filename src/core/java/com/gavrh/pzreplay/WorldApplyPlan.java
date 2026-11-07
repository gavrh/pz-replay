package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class WorldApplyPlan {
    private final List<WorldLogRecord> records;
    private int appliedIndex;

    public WorldApplyPlan(List<WorldLogRecord> records) {
        this.records = List.copyOf(records == null ? List.of() : records);
        long previousTime = 0L;
        long previousSequence = 0L;
        for (WorldLogRecord record : this.records) {
            if (record.sequence() <= previousSequence) {
                throw new IllegalArgumentException("world log sequence is not increasing");
            }
            if (record.timeMillis() < previousTime) {
                throw new IllegalArgumentException("world log time is decreasing");
            }
            previousSequence = record.sequence();
            previousTime = record.timeMillis();
        }
    }

    public List<WorldLogRecord> advanceTo(long targetMillis) {
        if (targetMillis < appliedThroughMillis()) {
            throw new IllegalArgumentException("world apply cannot move backward");
        }
        List<WorldLogRecord> selected = new ArrayList<>();
        while (appliedIndex < records.size()
            && records.get(appliedIndex).timeMillis() <= targetMillis) {
            selected.add(records.get(appliedIndex));
            appliedIndex++;
        }
        return List.copyOf(selected);
    }

    public List<WorldLogRecord> recordsTo(long targetMillis) {
        List<WorldLogRecord> selected = new ArrayList<>();
        for (WorldLogRecord record : records) {
            if (record.timeMillis() > targetMillis) {
                break;
            }
            selected.add(record);
        }
        return List.copyOf(selected);
    }

    public int appliedIndex() {
        return appliedIndex;
    }

    public int size() {
        return records.size();
    }

    public long lastRecordMillis() {
        return records.isEmpty() ? 0L : records.get(records.size() - 1).timeMillis();
    }

    public long lastAppliedMillis() {
        if (appliedIndex == 0) {
            return -1L;
        }
        return records.get(appliedIndex - 1).timeMillis();
    }

    public long appliedThroughMillis() {
        return Math.max(0L, lastAppliedMillis());
    }

    public void reset() {
        appliedIndex = 0;
    }
}
