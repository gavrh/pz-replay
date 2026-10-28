package com.gavrh.pzreplay;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class ReplayDetails {
    public record Row(String label, String value) {
        public Row {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private ReplayDetails() {
    }

    public static List<Row> rows(ReplayCatalogue.Entry entry, boolean favorite) {
        if (entry == null) {
            return List.of();
        }
        return List.of(
            new Row("Name", entry.fileName()),
            new Row("Date / duration",
                formatDate(entry.modifiedMillis()) + " / " + formatDuration(entry.durationSeconds())),
            new Row("Map", fallback(entry.mapName(), "unknown map")),
            new Row("Character", fallback(entry.playerName(), "unknown character")),
            new Row("Save / mode",
                fallback(entry.saveName(), "unknown save") + " / "
                    + fallback(entry.gameMode(), "unknown mode")),
            new Row("Size / rate",
                formatSize(entry.sizeBytes()) + " / " + formatRate(entry.sampleRateHz())),
            new Row("Status", entry.valid()
                ? "valid"
                : "unreadable: " + fallback(entry.error(), "unknown error")),
            new Row("Favorite / last",
                (favorite ? "yes" : "no") + " / " + (entry.lastReplay() ? "yes" : "no"))
        );
    }

    static String formatDate(long millis) {
        if (millis <= 0L) {
            return "unknown date";
        }
        return DATE_FORMAT.format(Instant.ofEpochMilli(millis));
    }

    static String formatDuration(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return "0:00";
        }
        long total = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    static String formatSize(long bytes) {
        if (bytes < 0L) {
            return "unknown size";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kilobytes = bytes / 1024.0;
        if (kilobytes < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kilobytes);
        }
        return String.format(Locale.ROOT, "%.1f MB", kilobytes / 1024.0);
    }

    static String formatRate(double sampleRateHz) {
        if (!Double.isFinite(sampleRateHz) || sampleRateHz <= 0.0) {
            return "unknown";
        }
        if (Math.abs(sampleRateHz - Math.round(sampleRateHz)) < 0.05) {
            return String.format(Locale.ROOT, "%.0f Hz", sampleRateHz);
        }
        return String.format(Locale.ROOT, "%.1f Hz", sampleRateHz);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
