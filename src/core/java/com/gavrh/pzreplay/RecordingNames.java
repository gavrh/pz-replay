package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

public final class RecordingNames {
    public static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_COLLISION_SUFFIX = 9999;

    private RecordingNames() {
    }

    public static String resolve(String requested, String autoName) {
        String sanitized = sanitize(requested);
        if (!sanitized.isEmpty()) {
            return sanitized;
        }
        return sanitize(autoName);
    }

    public static String sanitize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.toLowerCase(Locale.ROOT).endsWith(ReplayCatalogue.EXTENSION)) {
            value = value.substring(0, value.length() - ReplayCatalogue.EXTENSION.length())
                .trim();
        }
        value = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        value = value.replaceAll("^\\.+", "").replaceAll("\\.+$", "");
        if (value.length() > MAX_NAME_LENGTH) {
            value = value.substring(0, MAX_NAME_LENGTH);
        }
        return value.replaceAll("\\.+$", "");
    }

    public static String unique(Path directory, String base) {
        String stem = sanitize(base);
        if (stem.isEmpty()) {
            return "";
        }
        try {
            if (!isTaken(directory, stem)) {
                return stem;
            }
            for (int suffix = 2; suffix <= MAX_COLLISION_SUFFIX; suffix++) {
                String candidate = withSuffix(stem, suffix);
                if (!isTaken(directory, candidate)) {
                    return candidate;
                }
            }
        } catch (IOException | RuntimeException exception) {
            return "";
        }
        return "";
    }

    static boolean isTaken(Path directory, String stem) throws IOException {
        if (directory == null) {
            return false;
        }
        String fileName = stem + ReplayCatalogue.EXTENSION;
        Path target = directory.resolve(fileName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(path -> path.getFileName().toString()
                .equalsIgnoreCase(fileName));
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private static String withSuffix(String stem, int suffix) {
        String suffixText = "-" + suffix;
        int available = MAX_NAME_LENGTH - suffixText.length();
        String trimmed = stem.length() > available ? stem.substring(0, available) : stem;
        return trimmed + suffixText;
    }
}
