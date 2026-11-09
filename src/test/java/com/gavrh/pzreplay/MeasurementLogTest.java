package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasurementLogTest {
    private static final long START_NANOS = 1_000_000_000L;
    private static final long START_WALL_MILLIS = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void disabledWritesNothingAndOpensNoFile() {
        Path directory = tempDir.resolve("measurement");
        MeasurementLog log = MeasurementLog.open(false, directory, "run-disabled");

        assertFalse(log.isEnabled());
        assertNull(log.path());
        log.event(MeasurementLog.EVENT_CAPTURE_START, "capture", START_NANOS,
            START_WALL_MILLIS, "world=padded");
        log.close();

        assertFalse(Files.exists(directory));
    }

    @Test
    void enabledWritesEventsInOrder() throws Exception {
        MeasurementLog log = MeasurementLog.open(tempDir, "run-order", START_NANOS,
            START_WALL_MILLIS, MeasurementLog.DEFAULT_MAX_BYTES);

        log.event(MeasurementLog.EVENT_CAPTURE_START, "capture", START_NANOS,
            START_WALL_MILLIS, "world=padded", "padding=16");
        log.event(MeasurementLog.EVENT_BASE_COPY_START, "capture", START_NANOS + 1_000_000L,
            START_WALL_MILLIS);
        log.event(MeasurementLog.EVENT_BASE_COPY_END, "capture", START_NANOS + 6_000_000L,
            START_WALL_MILLIS, "ms=5");
        log.close();

        List<String> lines = Files.readAllLines(log.path());
        assertEquals(4, lines.size());
        assertEquals(List.of("session", "capture_start", "base_copy_start", "base_copy_end"),
            events(lines));
        assertTrue(lines.get(1).contains("id=capture"));
        assertTrue(lines.get(1).contains("world=padded"));
        assertTrue(lines.get(3).contains("mono_ms=6"));
        assertTrue(lines.get(3).contains("ms=5"));
    }

    @Test
    void monotonicTimestampsAreNonNegativeAndOrdered() throws Exception {
        MeasurementLog log = MeasurementLog.open(tempDir, "run-monotonic", START_NANOS,
            START_WALL_MILLIS, MeasurementLog.DEFAULT_MAX_BYTES);

        log.event(MeasurementLog.EVENT_CAPTURE_START, "capture", START_NANOS - 1_000_000L,
            START_WALL_MILLIS);
        log.event(MeasurementLog.EVENT_T0_SCAN_START, "capture", START_NANOS + 2_000_000L,
            START_WALL_MILLIS);
        log.event(MeasurementLog.EVENT_T0_SCAN_END, "capture", START_NANOS + 5_000_000L,
            START_WALL_MILLIS);
        log.close();

        List<Long> values = new ArrayList<>();
        for (String line : Files.readAllLines(log.path())) {
            values.add(monoMillis(line));
        }
        for (long value : values) {
            assertTrue(value >= 0L);
        }
        for (int index = 1; index < values.size(); index++) {
            assertTrue(values.get(index) >= values.get(index - 1));
        }
        assertEquals(0L, values.get(1));
        assertEquals(2L, values.get(2));
        assertEquals(5L, values.get(3));
    }

    @Test
    void fileSizeIsBoundedAndTruncationIsRecorded() throws Exception {
        long maxBytes = 640L;
        MeasurementLog log = MeasurementLog.open(tempDir, "run-bound", START_NANOS,
            START_WALL_MILLIS, maxBytes);

        for (int index = 0; index < 100; index++) {
            log.event(MeasurementLog.EVENT_PLAYBACK_APPLY, "replay",
                START_NANOS + index * 1_000_000L, START_WALL_MILLIS,
                "time_ms=" + index,
                "records=3",
                "apply_ms=1");
        }
        log.close();

        assertTrue(Files.size(log.path()) <= maxBytes);
        List<String> lines = Files.readAllLines(log.path());
        assertEquals(MeasurementLog.EVENT_LOG_TRUNCATED, events(lines).get(lines.size() - 1));
    }

    @Test
    void openFailureDisablesLogging() throws Exception {
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "not a directory");

        MeasurementLog log = MeasurementLog.open(blocked.resolve("measurement"), "run-failed",
            START_NANOS, START_WALL_MILLIS, MeasurementLog.DEFAULT_MAX_BYTES);

        assertFalse(log.isEnabled());
        assertNull(log.path());
        log.event(MeasurementLog.EVENT_CAPTURE_START, "capture", START_NANOS,
            START_WALL_MILLIS);
        log.close();
    }

    @Test
    void writesOnlyUnderTheGivenDirectory() throws Exception {
        Path directory = tempDir.resolve("pz-replay").resolve("measurement");
        Path saves = tempDir.resolve("Saves").resolve("Sandbox");
        Files.createDirectories(saves);

        MeasurementLog log = MeasurementLog.open(directory, "run-path", START_NANOS,
            START_WALL_MILLIS, MeasurementLog.DEFAULT_MAX_BYTES);
        log.event(MeasurementLog.EVENT_CONTEXT_LOAD_START, "replay", START_NANOS,
            START_WALL_MILLIS);
        log.close();

        assertTrue(log.path().toAbsolutePath().startsWith(directory.toAbsolutePath()));
        assertFalse(log.path().toAbsolutePath().startsWith(saves.toAbsolutePath()));
        assertTrue(Files.exists(directory.resolve("measurement-run-path.log")));
    }

    private static List<String> events(List<String> lines) {
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            int start = line.indexOf("event=") + "event=".length();
            int end = line.indexOf(' ', start);
            events.add(end < 0 ? line.substring(start) : line.substring(start, end));
        }
        return events;
    }

    private static long monoMillis(String line) {
        String prefix = "mono_ms=";
        int start = line.indexOf(prefix) + prefix.length();
        int end = line.indexOf(' ', start);
        return Long.parseLong(line.substring(start, end));
    }
}
