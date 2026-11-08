package com.gavrh.pzreplay;

import java.util.HashMap;
import java.util.Map;

final class WorldApplyDuplicates {
    private static final int STATE_SEEN = 1;
    private static final int DESTROY_SEEN = 2;
    private static final int ADD_SEEN = 4;

    private final Map<String, Integer> seen = new HashMap<>();
    private long currentTime = Long.MIN_VALUE;

    boolean skip(WorldLogRecord record, WorldApplySummary summary) {
        int mark = mark(record.kind());
        if (mark == 0) {
            return false;
        }
        WorldStateRecord state = record.record();
        if (state == null) {
            return false;
        }
        if (record.timeMillis() != currentTime) {
            seen.clear();
            currentTime = record.timeMillis();
        }
        String identity = WorldIdentity.of(state);
        int previous = seen.getOrDefault(identity, 0);
        if (duplicate(mark, previous)) {
            summary.duplicate();
            summary.warnOnce(record.sequence() + ":" + identity,
                "world duplicate identity skipped: " + identity);
            return true;
        }
        seen.put(identity, previous | mark);
        return false;
    }

    private static boolean duplicate(int mark, int previous) {
        if (mark == ADD_SEEN) {
            return (previous & (STATE_SEEN | ADD_SEEN)) != 0;
        }
        return previous != 0;
    }

    private static int mark(int kind) {
        return switch (kind) {
            case WorldLogCodec.KIND_OBJECT_STATE, WorldLogCodec.KIND_VEHICLE_STATE -> STATE_SEEN;
            case WorldLogCodec.KIND_OBJECT_DESTROYED -> DESTROY_SEEN;
            case WorldLogCodec.KIND_OBJECT_ADDED -> ADD_SEEN;
            default -> 0;
        };
    }
}
