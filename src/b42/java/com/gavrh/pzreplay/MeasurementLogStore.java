package com.gavrh.pzreplay;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class MeasurementLogStore {
    private static final DateTimeFormatter RUN_ID =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private static MeasurementLog log;

    private MeasurementLogStore() {
    }

    static synchronized MeasurementLog get() {
        if (log == null) {
            try {
                ReplaySettings settings = ReplaySettingsStore.get();
                log = settings.isMeasurementLogEnabled()
                    ? MeasurementLog.open(ReplayStorage.measurementDirectory(), newRunId())
                    : MeasurementLog.disabled();
            } catch (RuntimeException exception) {
                log = MeasurementLog.disabled();
            }
        }
        return log;
    }

    static boolean isEnabled() {
        return get().isEnabled();
    }

    static void event(String name, String id, String... values) {
        MeasurementLog active = get();
        if (!active.isEnabled()) {
            return;
        }
        active.event(name, id, System.nanoTime(), System.currentTimeMillis(), values);
    }

    static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String newRunId() {
        return RUN_ID.format(Instant.now()) + "-" + ProcessHandle.current().pid();
    }
}
