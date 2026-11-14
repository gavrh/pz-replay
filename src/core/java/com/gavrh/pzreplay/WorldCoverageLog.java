package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class WorldCoverageLog {
    private final MeasurementLog log;
    private final String id;

    private long scans;
    private long loadedTotal;
    private long missingInteriorTotal;
    private long missingBoundaryTotal;
    private long expectedTotal;
    private boolean hasWorst;
    private long worstScan;
    private int worstLoaded;
    private int worstMissingInterior;
    private int worstMissingBoundary;
    private int worstExpected;

    public WorldCoverageLog(MeasurementLog log, String id) {
        this.log = log == null ? MeasurementLog.disabled() : log;
        this.id = id == null ? "" : id;
    }

    public void scan(int expected, int missingInterior, int missingBoundary) {
        scans++;
        int loaded = expected - missingInterior - missingBoundary;
        loadedTotal += loaded;
        missingInteriorTotal += missingInterior;
        missingBoundaryTotal += missingBoundary;
        expectedTotal += expected;

        if (missingInterior == 0 && missingBoundary == 0) {
            return;
        }
        boolean worse = !hasWorst || isWorse(missingInterior, missingBoundary);
        if (worse) {
            hasWorst = true;
            worstScan = scans;
            worstLoaded = loaded;
            worstMissingInterior = missingInterior;
            worstMissingBoundary = missingBoundary;
            worstExpected = expected;
        }
        log.event(
                worse ? MeasurementLog.EVENT_COVERAGE_WORST : MeasurementLog.EVENT_COVERAGE_SCAN,
                id,
                System.nanoTime(),
                System.currentTimeMillis(),
                "scan=" + scans,
                "loaded=" + loaded,
                "missing_interior=" + missingInterior,
                "missing_boundary=" + missingBoundary,
                "expected=" + expected);
    }

    public void summary(String phase, String status) {
        List<String> values = new ArrayList<>();
        values.add("phase=" + phase);
        values.add("status=" + status);
        values.add("scans=" + scans);
        values.add("loaded_total=" + loadedTotal);
        values.add("missing_interior_total=" + missingInteriorTotal);
        values.add("missing_boundary_total=" + missingBoundaryTotal);
        values.add("expected_total=" + expectedTotal);
        if (hasWorst) {
            values.add("worst_scan=" + worstScan);
            values.add("worst_loaded=" + worstLoaded);
            values.add("worst_missing_interior=" + worstMissingInterior);
            values.add("worst_missing_boundary=" + worstMissingBoundary);
            values.add("worst_expected=" + worstExpected);
        } else {
            values.add("worst_scan=none");
        }
        log.event(
                MeasurementLog.EVENT_COVERAGE_SUMMARY,
                id,
                System.nanoTime(),
                System.currentTimeMillis(),
                values.toArray(new String[0]));
    }

    private boolean isWorse(int missingInterior, int missingBoundary) {
        if (missingInterior != worstMissingInterior) {
            return missingInterior > worstMissingInterior;
        }
        return missingBoundary > worstMissingBoundary;
    }
}
