package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplayFavorites {
    private static final String VERSION_LINE = "favorites_version=1";
    private static final String ENTRY_PREFIX = "favorite=";

    private final Set<String> paths = new LinkedHashSet<>();
    private String error = "";

    public static ReplayFavorites load(Path path) {
        ReplayFavorites favorites = new ReplayFavorites();
        if (path == null) {
            favorites.error = "favorites path is unavailable";
            return favorites;
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return favorites;
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(path)) {
            favorites.error = "favorites file is unreadable: " + path;
            return favorites;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            favorites.error = "cannot read favorites: " + message(exception);
            return favorites;
        }

        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#") || value.equals(VERSION_LINE)) {
                continue;
            }
            if (!value.startsWith(ENTRY_PREFIX)) {
                favorites.paths.clear();
                favorites.error = "favorites file is corrupt: " + path;
                return favorites;
            }
            String entry = normalize(value.substring(ENTRY_PREFIX.length()));
            if (!entry.isEmpty()) {
                favorites.paths.add(entry);
            }
        }
        return favorites;
    }

    public void save(Path path) throws IOException {
        if (path == null) {
            throw new IOException("favorites path is unavailable");
        }
        StringBuilder text = new StringBuilder();
        text.append(VERSION_LINE).append('\n');
        for (String entry : paths) {
            text.append(ENTRY_PREFIX).append(entry).append('\n');
        }
        ReplayFiles.writeAtomically(path, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public boolean contains(String path) {
        String value = normalize(path);
        return !value.isEmpty() && paths.contains(value);
    }

    public boolean toggle(String path) {
        String value = normalize(path);
        if (value.isEmpty()) {
            return false;
        }
        if (paths.remove(value)) {
            return false;
        }
        paths.add(value);
        return true;
    }

    public boolean remove(String path) {
        String value = normalize(path);
        return !value.isEmpty() && paths.remove(value);
    }

    public boolean remap(String from, String to) {
        String source = normalize(from);
        String target = normalize(to);
        if (source.isEmpty() || target.isEmpty() || source.equals(target)
            || !paths.remove(source)) {
            return false;
        }
        paths.add(target);
        return true;
    }

    public int prune(Collection<String> knownPaths) {
        if (knownPaths == null) {
            return 0;
        }
        Set<String> known = new LinkedHashSet<>();
        for (String path : knownPaths) {
            String value = normalize(path);
            if (!value.isEmpty()) {
                known.add(value);
            }
        }
        int before = paths.size();
        paths.retainAll(known);
        return before - paths.size();
    }

    public Set<String> paths() {
        return Set.copyOf(paths);
    }

    public int size() {
        return paths.size();
    }

    public String error() {
        return error;
    }

    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException exception) {
            return path;
        }
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return value;
    }
}
