package com.gavrh.pzreplay;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class MeasurementLog {
    public static final long DEFAULT_MAX_BYTES = 256L * 1024L;

    public static final String EVENT_SESSION = "session";
    public static final String EVENT_LOG_TRUNCATED = "log_truncated";
    public static final String EVENT_CAPTURE_START = "capture_start";
    public static final String EVENT_CAPTURE_READY = "capture_ready";
    public static final String EVENT_CAPTURE_FAILED = "capture_failed";
    public static final String EVENT_BASE_COPY_START = "base_copy_start";
    public static final String EVENT_BASE_COPY_END = "base_copy_end";
    public static final String EVENT_BASE_PACK_START = "base_pack_start";
    public static final String EVENT_BASE_PACK_END = "base_pack_end";
    public static final String EVENT_T0_SCAN_START = "t0_scan_start";
    public static final String EVENT_T0_SCAN_END = "t0_scan_end";
    public static final String EVENT_RECORDING_SAVED = "recording_saved";
    public static final String EVENT_CONTEXT_PREPARE_START = "context_prepare_start";
    public static final String EVENT_CONTEXT_PREPARE_END = "context_prepare_end";
    public static final String EVENT_CONTEXT_PREPARE_FAILED = "context_prepare_failed";
    public static final String EVENT_CONTEXT_LOAD_START = "context_load_start";
    public static final String EVENT_CONTEXT_LOAD_END = "context_load_end";
    public static final String EVENT_LOADING_GATE_CLICK = "loading_gate_click";
    public static final String EVENT_CONTEXT_CLEANUP_START = "context_cleanup_start";
    public static final String EVENT_CONTEXT_CLEANUP_END = "context_cleanup_end";
    public static final String EVENT_RELOAD_REQUESTED = "reload_requested";
    public static final String EVENT_RELOAD_STARTED = "reload_started";
    public static final String EVENT_RELOAD_FINISHED = "reload_finished";
    public static final String EVENT_RELOAD_FAILED = "reload_failed";
    public static final String EVENT_PLAYBACK_START = "playback_start";
    public static final String EVENT_PLAYBACK_STOP = "playback_stop";
    public static final String EVENT_PLAYBACK_FAILED = "playback_failed";
    public static final String EVENT_PLAYBACK_FINISHED = "playback_finished";
    public static final String EVENT_PLAYBACK_T0_APPLY = "playback_t0_apply";
    public static final String EVENT_PLAYBACK_APPLY = "playback_apply";
    public static final String EVENT_PLAYBACK_SEEK = "playback_seek";
    public static final String EVENT_PLAYBACK_RESTART = "playback_restart";
    public static final String EVENT_PLAYBACK_SUMMARY = "playback_summary";

    private static final String FILE_PREFIX = "measurement-";
    private static final String FILE_EXTENSION = ".log";
    private static final int TRUNCATION_RESERVE_BYTES = 96;

    private final Path path;
    private final long startNanos;
    private final long maxBytes;
    private OutputStream stream;
    private long bytes;
    private boolean truncated;
    private long lastMonotonicNanos;
    private long lastWallMillis;

    private MeasurementLog(Path path, long startNanos, long maxBytes, OutputStream stream,
        long bytes) {
        this.path = path;
        this.startNanos = startNanos;
        this.maxBytes = maxBytes;
        this.stream = stream;
        this.bytes = bytes;
    }

    public static MeasurementLog disabled() {
        return new MeasurementLog(null, 0L, 0L, null, 0L);
    }

    public static MeasurementLog open(boolean enabled, Path directory, String runId) {
        if (!enabled) {
            return disabled();
        }
        return open(directory, runId, System.nanoTime(), System.currentTimeMillis(),
            DEFAULT_MAX_BYTES);
    }

    public static MeasurementLog open(Path directory, String runId) {
        return open(directory, runId, System.nanoTime(), System.currentTimeMillis(),
            DEFAULT_MAX_BYTES);
    }

    public static MeasurementLog open(Path directory, String runId, long startNanos,
        long startWallMillis, long maxBytes) {
        if (directory == null || maxBytes <= 0L) {
            return disabled();
        }
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(FILE_PREFIX + token(runId) + FILE_EXTENSION);
            OutputStream stream = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            long existingBytes = Files.size(target);
            MeasurementLog log = new MeasurementLog(target, startNanos, maxBytes, stream,
                existingBytes);
            log.event(EVENT_SESSION, runId, startNanos, startWallMillis,
                "run=" + runId);
            return log;
        } catch (IOException | RuntimeException exception) {
            return disabled();
        }
    }

    public boolean isEnabled() {
        return stream != null;
    }

    public Path path() {
        return path;
    }

    public synchronized void event(String name, String id, long monotonicNanos,
        long wallClockMillis, String... values) {
        if (stream == null) {
            return;
        }
        lastMonotonicNanos = monotonicNanos;
        lastWallMillis = wallClockMillis;
        StringBuilder line = new StringBuilder("mono_ms=")
            .append(Math.max(0L, (monotonicNanos - startNanos) / 1_000_000L))
            .append(" wall_ms=").append(wallClockMillis)
            .append(" event=").append(token(name));
        if (id != null && !id.isBlank()) {
            line.append(" id=").append(token(id));
        }
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    line.append(' ').append(token(value));
                }
            }
        }
        line.append('\n');
        append(line.toString().getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void close() {
        if (stream == null) {
            return;
        }
        try {
            stream.flush();
            stream.close();
        } catch (IOException | RuntimeException ignored) {
        }
        stream = null;
    }

    private void append(byte[] encoded) {
        if (bytes + encoded.length + TRUNCATION_RESERVE_BYTES > maxBytes) {
            appendTruncated();
            return;
        }
        try {
            stream.write(encoded);
            stream.flush();
            bytes += encoded.length;
        } catch (IOException | RuntimeException exception) {
            close();
        }
    }

    private void appendTruncated() {
        if (!truncated) {
            truncated = true;
            byte[] marker = (EVENT_LOG_TRUNCATED + " reason=size_limit\n")
                .getBytes(StandardCharsets.UTF_8);
            byte[] line = ("mono_ms=" + Math.max(0L, (lastMonotonicNanos - startNanos)
                / 1_000_000L) + " wall_ms=" + lastWallMillis + " event=")
                .getBytes(StandardCharsets.UTF_8);
            if (bytes + line.length + marker.length <= maxBytes) {
                try {
                    stream.write(line);
                    stream.write(marker);
                    stream.flush();
                    bytes += line.length + marker.length;
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        close();
    }

    private static String token(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            builder.append(character <= ' ' ? '_' : character);
        }
        return builder.toString();
    }
}
