package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ReplayCatalogue {
    public static final String EXTENSION = ".pzrp.gz";

    public record Entry(
        String fileName,
        String path,
        long modifiedMillis,
        long sizeBytes,
        boolean valid,
        String error,
        double durationSeconds,
        double sampleRateHz,
        String mapName,
        String saveName,
        String gameMode,
        String playerName,
        boolean lastReplay
    ) {
    }

    public record Result(List<Entry> entries, String error) {
        public Result {
            entries = List.copyOf(entries);
            error = error == null ? "" : error;
        }
    }

    private ReplayCatalogue() {
    }

    public static Result list(Path directory, String lastReplayPath) {
        if (directory == null) {
            return new Result(List.of(), "replay directory is unavailable");
        }
        if (!Files.exists(directory)) {
            return new Result(List.of(), "replay directory not found: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            return new Result(List.of(), "replay path is not a directory: " + directory);
        }
        if (!Files.isReadable(directory)) {
            return new Result(List.of(), "replay directory is not readable: " + directory);
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                .forEach(files::add);
        } catch (IOException | RuntimeException exception) {
            return new Result(List.of(), "cannot list replays: " + message(exception));
        }

        String lastPath = normalize(lastReplayPath);
        List<Entry> entries = new ArrayList<>(files.size());
        for (Path file : files) {
            entries.add(readEntry(file, lastPath));
        }
        entries.sort(Comparator.comparingLong(Entry::modifiedMillis).reversed()
            .thenComparing(Entry::fileName, Comparator.reverseOrder()));
        return new Result(entries, "");
    }

    private static Entry readEntry(Path file, String lastPath) {
        String path = file.toString();
        long modifiedMillis = 0L;
        long sizeBytes = 0L;
        try {
            modifiedMillis = Files.getLastModifiedTime(file).toMillis();
            sizeBytes = Files.size(file);
        } catch (IOException | RuntimeException ignored) {
        }

        boolean lastReplay = !lastPath.isEmpty() && lastPath.equals(normalize(path));
        try {
            ReplayMetadata metadata = BinaryReplayCodec.readMetadata(file);
            long durationMillis = Math.max(0L,
                metadata.endTimeMillis() - metadata.startTimeMillis());
            return new Entry(
                file.getFileName().toString(),
                path,
                modifiedMillis,
                sizeBytes,
                true,
                "",
                durationMillis / 1000.0,
                metadata.sampleRateHz(),
                metadata.mapName(),
                metadata.saveName(),
                metadata.gameMode(),
                metadata.playerName(),
                lastReplay
            );
        } catch (IOException | ReplayFormatException | RuntimeException exception) {
            return new Entry(
                file.getFileName().toString(),
                path,
                modifiedMillis,
                sizeBytes,
                false,
                message(exception),
                0.0,
                0.0,
                "",
                "",
                "",
                "",
                lastReplay
            );
        }
    }

    private static String normalize(String path) {
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
