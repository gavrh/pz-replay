package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class WorldContext {
    public static final String FOLDER_PREFIX = "~pzrp-ctx-";
    public static final long GRACE_MILLIS = 60_000L;
    public static final String SENTINEL_NAME = ".pzrp-context.properties";

    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final String[] MARKER_KEYS = {
        "context_id", "pid", "started_at_ms", "state", "game_mode", "folder",
        "package_id", "replay_path"
    };

    public record Marker(
        String contextId,
        long pid,
        long startedAtMillis,
        String state,
        String gameMode,
        String folderName,
        String packageId,
        String replayPath
    ) {
        public Marker {
            state = value(state);
            gameMode = value(gameMode);
            folderName = value(folderName);
            packageId = value(packageId);
            replayPath = value(replayPath);
        }
    }

    public enum Action {
        DELETE,
        KEEP,
        REPORT
    }

    public record Cleanup(Action action, String reason) {
        public Cleanup {
            reason = reason == null ? "" : reason;
        }
    }

    private WorldContext() {
    }

    public static String folderName(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("context id is invalid");
        }
        return FOLDER_PREFIX + id;
    }

    public static boolean isReservedFolderName(String folderName) {
        return folderName != null && folderName.startsWith(FOLDER_PREFIX)
            && folderName.length() > FOLDER_PREFIX.length();
    }

    public static String sentinelText(String contextId) {
        return "context_id=" + value(contextId) + "\n";
    }

    public static String sentinelId(String text) {
        if (text == null) {
            return "";
        }
        Map<String, String> values = parseLines(text);
        return values.getOrDefault("context_id", "");
    }

    public static String markerText(Marker marker) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("context_id", marker.contextId());
        values.put("pid", Long.toString(marker.pid()));
        values.put("started_at_ms", Long.toString(marker.startedAtMillis()));
        values.put("state", marker.state());
        values.put("game_mode", marker.gameMode());
        values.put("folder", marker.folderName());
        values.put("package_id", marker.packageId());
        values.put("replay_path", marker.replayPath());
        StringBuilder builder = new StringBuilder();
        for (String key : MARKER_KEYS) {
            builder.append(key).append('=').append(values.getOrDefault(key, "")).append('\n');
        }
        return builder.toString();
    }

    public static Marker parseMarker(String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> values = parseLines(text);
        for (String key : MARKER_KEYS) {
            if (!values.containsKey(key)) {
                return null;
            }
        }
        try {
            String contextId = values.get("context_id");
            String folderName = values.get("folder");
            if (!ID.matcher(contextId).matches() || !folderName.equals(folderName(contextId))) {
                return null;
            }
            return new Marker(contextId,
                Long.parseLong(values.get("pid")),
                Long.parseLong(values.get("started_at_ms")),
                values.get("state"),
                values.get("game_mode"),
                folderName,
                values.get("package_id"),
                values.get("replay_path"));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static Cleanup cleanup(String folderName, Marker marker, String sentinelId,
        long nowMillis, boolean pidAlive, Path saveRoot, Path folder) {
        if (!isReservedFolderName(folderName)) {
            return new Cleanup(Action.KEEP, "not a reserved context folder");
        }
        if (folder == null || saveRoot == null || saveRoot.getRoot() == null) {
            return new Cleanup(Action.REPORT, "context path is unavailable");
        }
        Path normalizedRoot = saveRoot.toAbsolutePath().normalize();
        Path normalizedFolder = folder.toAbsolutePath().normalize();
        if (!normalizedFolder.startsWith(normalizedRoot)
            || normalizedFolder.equals(normalizedRoot)) {
            return new Cleanup(Action.REPORT, "context path is outside the save root");
        }
        if (Files.isSymbolicLink(normalizedFolder)) {
            return new Cleanup(Action.REPORT, "context folder is a symlink");
        }
        if (marker == null) {
            return new Cleanup(Action.REPORT, "marker is missing or corrupt");
        }
        if (!marker.folderName().equals(folderName)) {
            return new Cleanup(Action.REPORT, "marker folder does not match");
        }
        if (sentinelId == null || sentinelId.isBlank()
            || !sentinelId.equals(marker.contextId())) {
            return new Cleanup(Action.REPORT, "sentinel is missing or does not match");
        }
        if (pidAlive) {
            return new Cleanup(Action.KEEP, "context process is alive");
        }
        if (nowMillis - marker.startedAtMillis() < GRACE_MILLIS) {
            return new Cleanup(Action.KEEP, "context is within the grace period");
        }
        return new Cleanup(Action.DELETE, "context is stale");
    }

    public static String restoreLatestSave(String current, String backup, String folderName) {
        String resolvedBackup = backup == null ? "" : backup;
        if (current == null || current.isBlank()) {
            return resolvedBackup;
        }
        String firstLine = current.lines().findFirst().orElse("");
        if (firstLine.equals(folderName)) {
            return resolvedBackup;
        }
        return current;
    }

    public static boolean pidAlive(long pid) {
        if (pid <= 0L) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static void makeWritable(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                setWritable(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                setWritable(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void setWritable(Path path) throws IOException {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException exception) {
            path.toFile().setWritable(true, true);
        }
    }

    private static Map<String, String> parseLines(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                return Map.of();
            }
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private static String value(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
