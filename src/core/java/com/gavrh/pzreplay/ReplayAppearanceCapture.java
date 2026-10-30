package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ReplayAppearanceCapture {
    private final List<ReplayAppearanceChange> changes = new ArrayList<>();
    private byte[] baseline;
    private long nextSequence = 1L;
    private int totalSnapshotBytes;
    private boolean dropped;

    public void reset() {
        changes.clear();
        baseline = null;
        nextSequence = 1L;
        totalSnapshotBytes = 0;
        dropped = false;
    }

    public void markBaseline(byte[] payload) {
        baseline = payload == null ? null : payload.clone();
    }

    public boolean accept(float timeSeconds, byte[] payload, ReplayAppearance appearance) {
        if (payload == null || payload.length == 0 || appearance == null
            || !appearance.isCurrent()) {
            return false;
        }
        if (baseline != null && Arrays.equals(baseline, payload)) {
            return false;
        }
        baseline = payload.clone();
        if (dropped) {
            return false;
        }
        if (changes.size() >= ReplayAppearanceChangeCodec.MAX_CHANGES
            || totalSnapshotBytes + payload.length
                > ReplayAppearanceChangeCodec.MAX_TOTAL_SNAPSHOT_BYTES) {
            dropped = true;
            return false;
        }
        changes.add(new ReplayAppearanceChange(nextSequence++, timeSeconds, appearance));
        totalSnapshotBytes += payload.length;
        return true;
    }

    public List<ReplayAppearanceChange> changes() {
        return List.copyOf(changes);
    }

    public int size() {
        return changes.size();
    }

    public int getTotalSnapshotBytes() {
        return totalSnapshotBytes;
    }

    public boolean isDropped() {
        return dropped;
    }
}
