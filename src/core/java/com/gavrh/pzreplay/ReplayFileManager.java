package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ReplayFileManager {
    public record Result(boolean ok, String path, String error) {
        public Result {
            path = path == null ? "" : path;
            error = error == null ? "" : error;
        }

        static Result success(Path path) {
            return new Result(true, path == null ? "" : path.toString(), "");
        }

        static Result failure(String error) {
            return new Result(false, "", error);
        }
    }

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _.-]+");
    private static final int MAX_NAME_LENGTH = 120;

    private ReplayFileManager() {
    }

    public static Result rename(Path directory, String currentPath, String newName) {
        Path source;
        try {
            source = validateSource(directory, currentPath);
        } catch (ReplayOperationException exception) {
            return Result.failure(exception.getMessage());
        }

        String base;
        try {
            base = normalizeName(newName);
        } catch (ReplayOperationException exception) {
            return Result.failure(exception.getMessage());
        }

        Path target = source.resolveSibling(base + ReplayCatalogue.EXTENSION);
        if (target.equals(source)) {
            return Result.success(source);
        }

        try {
            Path collision = findCollision(source, target);
            if (collision != null) {
                return Result.failure("a replay named \"" + base
                    + "\" already exists");
            }
            move(source, target);
            return Result.success(target);
        } catch (AccessDeniedException exception) {
            return Result.failure("permission denied: " + source.getFileName());
        } catch (FileAlreadyExistsException exception) {
            return Result.failure("a replay named \"" + base + "\" already exists");
        } catch (IOException exception) {
            return Result.failure("cannot rename replay: " + message(exception));
        }
    }

    public static Result delete(Path directory, String currentPath) {
        Path file;
        try {
            file = validateSource(directory, currentPath);
        } catch (ReplayOperationException exception) {
            return Result.failure(exception.getMessage());
        }

        try {
            Files.delete(file);
            return Result.success(null);
        } catch (AccessDeniedException exception) {
            return Result.failure("permission denied: " + file.getFileName());
        } catch (IOException exception) {
            return Result.failure("cannot delete replay: " + message(exception));
        }
    }

    private static Path validateSource(Path directory, String currentPath)
        throws ReplayOperationException {
        if (directory == null) {
            throw new ReplayOperationException("replay directory is unavailable");
        }
        Path parent = directory.toAbsolutePath().normalize();
        String value = currentPath == null ? "" : currentPath.trim();
        if (value.isEmpty()) {
            throw new ReplayOperationException("replay path is empty");
        }

        Path file;
        try {
            file = Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new ReplayOperationException("invalid replay path");
        }
        if (!parent.equals(file.getParent())) {
            throw new ReplayOperationException("replay path is outside the replay directory");
        }

        String name = file.getFileName().toString();
        if (!name.endsWith(ReplayCatalogue.EXTENSION)) {
            throw new ReplayOperationException("not a replay file: " + name);
        }
        if (Files.isSymbolicLink(file)) {
            throw new ReplayOperationException("not a replay file: " + name);
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReplayOperationException("replay file not found: " + name);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReplayOperationException("not a replay file: " + name);
        }
        return file;
    }

    private static String normalizeName(String newName) throws ReplayOperationException {
        String value = newName == null ? "" : newName.trim();
        if (value.isEmpty()) {
            throw new ReplayOperationException("replay name is empty");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new ReplayOperationException(
                "replay name cannot contain path separators");
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(ReplayCatalogue.EXTENSION)) {
            value = value.substring(0, value.length() - ReplayCatalogue.EXTENSION.length())
                .trim();
        }
        if (value.isEmpty()) {
            throw new ReplayOperationException("replay name is empty");
        }
        if (value.equals(".") || value.equals("..") || value.startsWith(".")
            || value.endsWith(".")) {
            throw new ReplayOperationException("replay name is not valid");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new ReplayOperationException("replay name is too long");
        }
        if (!VALID_NAME.matcher(value).matches()) {
            throw new ReplayOperationException(
                "replay name contains invalid characters");
        }
        return value;
    }

    private static Path findCollision(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Files.isSameFile(source, target) ? null : target;
        }

        String targetName = target.getFileName().toString();
        try (Stream<Path> stream = Files.list(target.getParent())) {
            for (Path entry : stream.toList()) {
                if (!entry.getFileName().toString().equalsIgnoreCase(targetName)) {
                    continue;
                }
                if (Files.isSameFile(source, entry)) {
                    continue;
                }
                return entry;
            }
        }
        return null;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String message(IOException exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return value;
    }

    private static final class ReplayOperationException extends Exception {
        private ReplayOperationException(String message) {
            super(message);
        }
    }
}
