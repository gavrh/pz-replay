package com.gavrh.pzreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldCoverageLogTest {
    private static final long START_NANOS = 1_000_000_000L;
    private static final long START_WALL_MILLIS = 1_700_000_000_000L;
    private static final int EXPECTED_33 = 33 * 33;

    @TempDir
    Path tempDir;

    @Test
    void zeroMissingScansEmitNoScanEvents() throws Exception {
        MeasurementLog log = open("run-clean");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 0, 0);
        coverage.scan(EXPECTED_33, 0, 0);
        coverage.scan(EXPECTED_33, 0, 0);
        coverage.summary("finished", "complete");
        log.close();

        List<String> lines = lines(log);
        assertEquals(List.of("session", "coverage_summary"), events(lines));
        String summary = lines.get(1);
        assertEquals("finished", field(summary, "phase"));
        assertEquals("complete", field(summary, "status"));
        assertEquals("3", field(summary, "scans"));
        assertEquals("3267", field(summary, "loaded_total"));
        assertEquals("0", field(summary, "missing_interior_total"));
        assertEquals("0", field(summary, "missing_boundary_total"));
        assertEquals("3267", field(summary, "expected_total"));
        assertEquals("none", field(summary, "worst_scan"));
    }

    @Test
    void firstInteriorMissIsTheWorstPattern() throws Exception {
        MeasurementLog log = open("run-interior");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 1, 0);
        log.close();

        List<String> lines = lines(log);
        assertEquals(List.of("session", "coverage_worst"), events(lines));
        String scan = lines.get(1);
        assertEquals("capture", field(scan, "id"));
        assertEquals("1", field(scan, "scan"));
        assertEquals("1088", field(scan, "loaded"));
        assertEquals("1", field(scan, "missing_interior"));
        assertEquals("0", field(scan, "missing_boundary"));
        assertEquals("1089", field(scan, "expected"));
    }

    @Test
    void firstBoundaryMissIsTheWorstPattern() throws Exception {
        MeasurementLog log = open("run-boundary");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 0, 1);
        log.close();

        List<String> lines = lines(log);
        assertEquals(List.of("session", "coverage_worst"), events(lines));
        assertEquals("1088", field(lines.get(1), "loaded"));
        assertEquals("0", field(lines.get(1), "missing_interior"));
        assertEquals("1", field(lines.get(1), "missing_boundary"));
    }

    @Test
    void lessSeverePatternEmitsScanEvent() throws Exception {
        MeasurementLog log = open("run-scan");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 1, 0);
        coverage.scan(EXPECTED_33, 0, 3);
        log.close();

        List<String> lines = lines(log);
        assertEquals(List.of("session", "coverage_worst", "coverage_scan"), events(lines));
        String scan = lines.get(2);
        assertEquals("2", field(scan, "scan"));
        assertEquals("1086", field(scan, "loaded"));
        assertEquals("0", field(scan, "missing_interior"));
        assertEquals("3", field(scan, "missing_boundary"));
        assertEquals("1089", field(scan, "expected"));
    }

    @Test
    void worstUpdatesOnlyWhenThePatternGetsWorse() throws Exception {
        MeasurementLog log = open("run-worst");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 0, 1);
        coverage.scan(EXPECTED_33, 0, 5);
        coverage.scan(EXPECTED_33, 0, 2);
        coverage.scan(EXPECTED_33, 1, 0);
        coverage.scan(EXPECTED_33, 0, 200);
        coverage.scan(EXPECTED_33, 0, 0);
        log.close();

        List<String> lines = lines(log);
        assertEquals(
                List.of(
                        "session",
                        "coverage_worst",
                        "coverage_worst",
                        "coverage_scan",
                        "coverage_worst",
                        "coverage_scan"),
                events(lines));
        assertEquals("4", field(lines.get(4), "scan"));
        assertEquals("1", field(lines.get(4), "missing_interior"));
        assertEquals("0", field(lines.get(4), "missing_boundary"));
    }

    @Test
    void summaryReportsTotalsAndWorstScanAtFinish() throws Exception {
        MeasurementLog log = open("run-summary");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 0, 4);
        coverage.scan(EXPECTED_33, 2, 3);
        coverage.scan(EXPECTED_33, 0, 0);
        coverage.summary("ready", "partial");
        coverage.summary("finished", "partial");
        log.close();

        List<String> lines = lines(log);
        assertEquals(
                List.of("session", "coverage_worst", "coverage_worst", "coverage_summary", "coverage_summary"),
                events(lines));
        String ready = lines.get(3);
        assertEquals("ready", field(ready, "phase"));
        assertEquals("3", field(ready, "scans"));
        String finished = lines.get(4);
        assertEquals("finished", field(finished, "phase"));
        assertEquals("3", field(finished, "scans"));
        assertEquals("3258", field(finished, "loaded_total"));
        assertEquals("2", field(finished, "missing_interior_total"));
        assertEquals("7", field(finished, "missing_boundary_total"));
        assertEquals("3267", field(finished, "expected_total"));
        assertEquals("2", field(finished, "worst_scan"));
        assertEquals("1084", field(finished, "worst_loaded"));
        assertEquals("2", field(finished, "worst_missing_interior"));
        assertEquals("3", field(finished, "worst_missing_boundary"));
        assertEquals("1089", field(finished, "worst_expected"));
    }

    @Test
    void disabledMeasurementLogWritesNothing() {
        Path directory = tempDir.resolve("measurement");
        MeasurementLog log = MeasurementLog.open(false, directory, "run-disabled");
        WorldCoverageLog coverage = new WorldCoverageLog(log, "capture");

        coverage.scan(EXPECTED_33, 2, 4);
        coverage.summary("finished", "partial");

        assertFalse(log.isEnabled());
        assertFalse(Files.exists(directory));
    }

    @Test
    void countsDoNotChangeClassification() {
        assertEquals("complete", WorldCoverageStatus.classify(EXPECTED_33, 0, 0));
        assertEquals("complete", WorldCoverageStatus.classify(EXPECTED_33, 0, 10));
        assertEquals("partial", WorldCoverageStatus.classify(EXPECTED_33, 0, 11));
        assertEquals("partial", WorldCoverageStatus.classify(EXPECTED_33, 1, 0));
    }

    private MeasurementLog open(String runId) {
        return MeasurementLog.open(tempDir, runId, START_NANOS, START_WALL_MILLIS, MeasurementLog.DEFAULT_MAX_BYTES);
    }

    private static List<String> lines(MeasurementLog log) throws Exception {
        return Files.readAllLines(log.path());
    }

    private static List<String> events(List<String> lines) {
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            events.add(field(line, "event"));
        }
        return events;
    }

    private static String field(String line, String key) {
        String prefix = key + "=";
        for (String token : line.split(" ")) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        return "";
    }
}
