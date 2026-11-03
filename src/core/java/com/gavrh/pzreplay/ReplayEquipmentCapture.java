package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ReplayEquipmentCapture {
    private final List<ReplayEquipmentChange> changes = new ArrayList<>();
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

    public boolean accept(float timeSeconds, byte[] payload, ReplayEquipment equipment) {
        if (payload == null || payload.length == 0 || equipment == null
            || !equipment.isCurrent()) {
            return false;
        }
        if (baseline != null && Arrays.equals(baseline, payload)) {
            return false;
        }
        baseline = payload.clone();
        if (dropped) {
            return false;
        }
        if (payload.length > ReplayEquipmentChangeCodec.MAX_SNAPSHOT_BYTES
            || changes.size() >= ReplayEquipmentChangeCodec.MAX_CHANGES
            || totalSnapshotBytes + payload.length
                > ReplayEquipmentChangeCodec.MAX_TOTAL_SNAPSHOT_BYTES) {
            dropped = true;
            return false;
        }
        changes.add(new ReplayEquipmentChange(nextSequence++, timeSeconds, equipment));
        totalSnapshotBytes += payload.length;
        return true;
    }

    public List<ReplayEquipmentChange> changes() {
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
