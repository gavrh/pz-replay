package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class WorldPlayback {
    private final WorldApplyPlan plan;
    private final WorldApplier applier;
    private final WorldApplySummary summary = new WorldApplySummary();
    private final WorldApplyDuplicates duplicates = new WorldApplyDuplicates();
    private final Consumer<String> warning;
    private final String coverageStatus;
    private final CameraBounds cameraBounds;
    private final String replayId;
    private long applyNanos;
    private long diffRecords;
    private long seekCount;

    private WorldPlayback(List<WorldLogRecord> records, String coverageStatus,
        int paddingTiles, Map<Integer, Integer> kinds, Consumer<String> warning,
        String replayId) {
        this.plan = new WorldApplyPlan(records);
        this.applier = new WorldApplier(warning);
        this.coverageStatus = coverageStatus == null ? "complete" : coverageStatus;
        this.warning = warning;
        this.replayId = replayId == null ? "" : replayId;
        this.cameraBounds = CameraBounds.fromRecords(records, paddingTiles);
        applyKinds(kinds);
        applyCoverage();
        applyCameraBounds(paddingTiles, records.size());
        applyRecords(plan.advanceTo(0L));
        if (summary.appliedCount() > 0 || plan.size() > 0) {
            System.out.println("[pz-replay] world apply T0 " + summary.text()
                + " records=" + plan.size() + " last_ms=" + plan.lastRecordMillis());
        }
        MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_T0_APPLY, this.replayId,
            "records=" + plan.size(),
            "apply_ms=" + (applyNanos / 1_000_000L),
            "last_ms=" + plan.lastRecordMillis());
    }

    static WorldPlayback open(Path path, String coverageStatus, int paddingTiles,
        Consumer<String> warning) throws IOException, ReplayFormatException {
        byte[] log = FormatReader.readWorldLog(path);
        Map<Integer, Integer> kinds = WorldLogCodec.countKinds(log);
        List<WorldLogRecord> records = WorldLogCodec.decodeAll(log);
        return new WorldPlayback(records, coverageStatus, paddingTiles, kinds, warning,
            path.toString());
    }

    void applyTo(double seconds, boolean seek) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            return;
        }
        long target = Math.max(plan.appliedThroughMillis(), Math.round(seconds * 1000.0));
        long from = plan.appliedThroughMillis();
        List<WorldLogRecord> records = plan.advanceTo(target);
        if (records.isEmpty()) {
            return;
        }
        if (seek) {
            seekCount++;
        }
        boolean measure = MeasurementLogStore.isEnabled();
        long applyStartNanos = measure ? System.nanoTime() : 0L;
        applyRecords(records);
        System.out.println("[pz-replay] world apply time=" + target + "ms"
            + " records=" + records.size() + " " + summary.text()
            + " apply_ms=" + (applyNanos / 1_000_000L)
            + " seek=" + seek);
        if (measure) {
            MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_APPLY, replayId,
                "time_ms=" + target,
                "records=" + records.size(),
                "apply_ms=" + MeasurementLogStore.elapsedMillis(applyStartNanos));
            if (seek) {
                MeasurementLogStore.event(MeasurementLog.EVENT_PLAYBACK_SEEK, replayId,
                    "from_ms=" + from,
                    "to_ms=" + target,
                    "records=" + records.size(),
                    "mode=memory");
            }
        }
    }

    long lastRecordMillis() {
        return plan.lastRecordMillis();
    }

    boolean isTruncated() {
        return "truncated".equals(coverageStatus);
    }

    WorldApplySummary summary() {
        return summary;
    }

    CameraBounds cameraBounds() {
        return cameraBounds;
    }

    String summaryText() {
        return "world=" + summary.text()
            + " last_ms=" + plan.lastRecordMillis()
            + " apply_ms=" + (applyNanos / 1_000_000L)
            + " diff_records=" + diffRecords
            + " seeks=" + seekCount
            + " status=" + coverageStatus;
    }

    private void applyRecords(List<WorldLogRecord> records) {
        long started = System.nanoTime();
        for (WorldLogRecord record : records) {
            if (record.timeMillis() > 0L) {
                diffRecords++;
            }
            if (duplicates.skip(record, summary)) {
                continue;
            }
            try {
                applier.apply(WorldApplyPlanner.command(record), summary);
            } catch (RuntimeException exception) {
                summary.unsupported();
                StackTraceElement frame = exception.getStackTrace().length == 0 ? null
                    : exception.getStackTrace()[0];
                summary.warnOnce(record.sequence() + ":" + record.kind(),
                    "world record could not be applied: "
                        + exception.getClass().getSimpleName()
                        + (exception.getMessage() == null ? "" : " " + exception.getMessage())
                        + (frame == null ? "" : " at " + frame));
            }
        }
        applyNanos += System.nanoTime() - started;
    }

    private void applyKinds(Map<Integer, Integer> kinds) {
        int unknown = 0;
        for (Map.Entry<Integer, Integer> entry : kinds.entrySet()) {
            int kind = entry.getKey();
            if (kind == WorldLogCodec.KIND_OBJECT_STATE
                || kind == WorldLogCodec.KIND_VEHICLE_STATE
                || kind == WorldLogCodec.KIND_OBJECT_DESTROYED
                || kind == WorldLogCodec.KIND_OBJECT_ADDED
                || kind == WorldLogCodec.KIND_CHECKPOINT) {
                continue;
            }
            unknown += entry.getValue();
        }
        summary.unknownKind(unknown);
        if (unknown > 0) {
            warn("world log skipped " + unknown + " unknown or unsupported record kinds");
        }
    }

    private void applyCoverage() {
        String message = WorldCoverageWarning.message(coverageStatus,
            plan.lastRecordMillis());
        if (!message.isEmpty()) {
            warn(message);
        }
    }

    private void applyCameraBounds(int paddingTiles, int recordCount) {
        System.out.println("[pz-replay] world camera bounds " + cameraBounds.text()
            + " padding=" + paddingTiles + " margin=0.000"
            + " records=" + recordCount);
        if (!cameraBounds.isBounded()) {
            warn("world camera coverage is empty; using the default camera radius");
        }
    }

    private void warn(String message) {
        summary.warn(message);
        if (warning != null) {
            warning.accept(message);
        }
    }
}
