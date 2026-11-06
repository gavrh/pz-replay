package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoWorld;

final class WorldCapture {
    private static final long FINISH_WAIT_MILLIS = 30_000L;
    private static final long DISK_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final long SAMPLE_INTERVAL_MILLIS =
        1_000L / WorldPackage.DEFAULT_SAMPLE_RATE_HZ;

    private record Build(byte[] base, long sourceBytes, long copyMillis, long packMillis,
        String failure) {
        static Build failed(String message) {
            return new Build(new byte[0], 0L, 0L, 0L, message);
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pz-replay-world-copy");
        thread.setDaemon(true);
        return thread;
    });
    private final WorldScanner scanner = new WorldScanner();
    private final WorldDiffSampler sampler = new WorldDiffSampler();
    private final Set<Long> coveredSquares = new HashSet<>();
    private final List<Long> scanDurations = new ArrayList<>();

    private Future<Build> future;
    private Path staging;
    private int paddingTiles;
    private boolean requested;
    private boolean t0Complete;
    private String failure = "";
    private WorldPackage packageData;
    private Build readyBuild;
    private WorldLogBuilder logBuilder;
    private long coverageObjects;
    private long nextSampleMillis;
    private long lastRecordingMillis;
    private long sampleCount;
    private long duplicateReported;
    private boolean samplingStopped;
    private String coverageStatus = "complete";
    private String summary = "world=off";

    synchronized void start(boolean enabled, int padding, String captureName) {
        reset();
        if (!enabled) {
            return;
        }
        requested = true;
        paddingTiles = padding;
        Path replayDirectory = ReplayStorage.directory();
        try {
            Files.createDirectories(replayDirectory);
            staging = replayDirectory.resolve(".pzrp-tmp")
                .resolve("world-" + safeName(captureName));
            Files.createDirectories(staging);
        } catch (IOException | RuntimeException exception) {
            fail("staging is unavailable: " + message(exception));
            return;
        }
        Path archive = staging.resolve("world_base.zip");
        String saveDirectory = currentSaveDirectory();
        if (saveDirectory.isBlank()) {
            fail("current save directory is unavailable");
            return;
        }
        Path source = Paths.get(saveDirectory);
        if (!Files.isDirectory(source)) {
            fail("current save directory does not exist");
            return;
        }
        future = executor.submit(() -> build(source, archive, replayDirectory));
    }

    synchronized void tick(long recordingMillis) {
        lastRecordingMillis = Math.max(lastRecordingMillis, recordingMillis);
        if (!requested || packageData != null || !failure.isEmpty()) {
            return;
        }
        if (!t0Complete) {
            poll(recordingMillis);
            return;
        }
        if (samplingStopped || logBuilder.isTruncated()
            || recordingMillis < nextSampleMillis) {
            return;
        }
        sample();
    }

    synchronized WorldPackage finish() {
        if (!requested) {
            return null;
        }
        if (packageData != null) {
            return packageData;
        }
        long deadline = System.currentTimeMillis() + FINISH_WAIT_MILLIS;
        while (!t0Complete && failure.isEmpty()
            && System.currentTimeMillis() < deadline) {
            poll(lastRecordingMillis);
            if (!t0Complete && failure.isEmpty()) {
                try {
                    wait(50L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (failure.isEmpty() && t0Complete) {
            packageData = buildPackage();
            summary = formatSummary();
            System.out.println("[pz-replay] world capture finished " + summary);
        }
        return packageData;
    }

    synchronized boolean isRequested() {
        return requested;
    }

    synchronized String getFailure() {
        return failure;
    }

    synchronized String getSummary() {
        return summary;
    }

    synchronized void reset() {
        if (future != null) {
            future.cancel(true);
            future = null;
        }
        if (staging != null) {
            deleteRecursively(staging);
            staging = null;
        }
        requested = false;
        t0Complete = false;
        failure = "";
        packageData = null;
        readyBuild = null;
        logBuilder = null;
        coverageObjects = 0L;
        nextSampleMillis = 0L;
        lastRecordingMillis = 0L;
        sampleCount = 0L;
        duplicateReported = 0L;
        samplingStopped = false;
        coverageStatus = "complete";
        summary = "world=off";
        paddingTiles = 0;
        coveredSquares.clear();
        scanDurations.clear();
        sampler.seedT0(List.of());
    }

    private void poll(long recordingMillis) {
        if (future == null || !future.isDone()) {
            return;
        }
        Build build;
        try {
            build = future.get();
        } catch (Exception exception) {
            fail("base capture failed: " + message(exception));
            return;
        }
        future = null;
        if (!build.failure().isEmpty()) {
            fail(build.failure());
            return;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            fail("local player is unavailable for the T0 sample");
            return;
        }
        WorldScanner.Result scan;
        try {
            scan = scanner.scan(player, paddingTiles);
        } catch (RuntimeException exception) {
            fail("T0 sample failed: " + message(exception));
            return;
        }
        List<WorldStateRecord> t0Records = new ArrayList<>();
        for (WorldStateRecord record : scan.records()) {
            if (record.classId() >= WorldLogCodec.CLASS_DOOR
                && record.classId() <= WorldLogCodec.CLASS_VEHICLE) {
                t0Records.add(record);
            }
        }
        try {
            logBuilder = new WorldLogBuilder();
            logBuilder.appendT0(t0Records);
        } catch (ReplayFormatException exception) {
            fail("T0 sample could not be encoded: " + exception.getMessage());
            return;
        }
        readyBuild = build;
        sampler.seedT0(scan.records());
        coveredSquares.addAll(scan.observableSquares());
        coverageObjects = t0Records.size();
        coverageStatus = scan.coverageStatus();
        nextSampleMillis = nextInterval(recordingMillis);
        t0Complete = true;
        summary = formatSummary();
        System.out.println("[pz-replay] world capture ready " + summary
            + " t0_ms=" + scan.durationMillis());
        deleteStaging();
    }

    private void sample() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        long sampleTime = nextSampleMillis;
        nextSampleMillis += SAMPLE_INTERVAL_MILLIS;
        if (nextSampleMillis <= lastRecordingMillis) {
            nextSampleMillis = nextInterval(lastRecordingMillis);
        }
        WorldScanner.Result scan;
        try {
            scan = scanner.scan(player, paddingTiles);
        } catch (RuntimeException exception) {
            truncate("world sample failed: " + message(exception));
            return;
        }
        Set<Long> newlyCovered = new HashSet<>(scan.observableSquares());
        newlyCovered.removeAll(coveredSquares);
        coveredSquares.addAll(scan.observableSquares());
        List<WorldLogEntry> entries = sampler.diff(new WorldDiffSample(scan.records(),
            scan.observableSquares(), newlyCovered, scan.presentVehicleIdentities(),
            scan.duplicateIdentities()));
        try {
            if (!logBuilder.append(entries, sampleTime)) {
                samplingStopped = true;
                coverageStatus = "truncated";
                System.out.println("[pz-replay] world log truncated time_ms=" + sampleTime
                    + " changes=" + logBuilder.changes() + " bytes=" + logBuilder.bytes());
            } else if ("partial".equals(scan.coverageStatus())
                && !"truncated".equals(coverageStatus)) {
                coverageStatus = "partial";
            }
        } catch (ReplayFormatException exception) {
            truncate("world sample could not be encoded: " + exception.getMessage());
            return;
        }
        scanDurations.add(scan.durationMillis());
        sampleCount++;
        long duplicates = sampler.getDuplicateIdentities();
        if (duplicates > duplicateReported) {
            duplicateReported = duplicates;
            System.out.println("[pz-replay] world duplicate identities count=" + duplicates);
        }
    }

    private WorldPackage buildPackage() {
        if (readyBuild == null || logBuilder == null) {
            return null;
        }
        byte[] log = logBuilder.payload();
        return new WorldPackage(readyBuild.base(), log, paddingTiles,
            WorldPackage.DEFAULT_SAMPLE_RATE_HZ, coverageStatus, coveredSquares.size(),
            coverageObjects, logBuilder.changes(), worldVersion());
    }

    private String formatSummary() {
        if (readyBuild == null || logBuilder == null) {
            return "world=off";
        }
        StringBuilder builder = new StringBuilder("world=padded")
            .append(" base_bytes=").append(readyBuild.base().length)
            .append(" base_copy_ms=").append(readyBuild.copyMillis())
            .append(" base_pack_ms=").append(readyBuild.packMillis())
            .append(" t0_objects=").append(coverageObjects)
            .append(" coverage_squares=").append(coveredSquares.size())
            .append(" samples=").append(sampleCount)
            .append(" changes=").append(logBuilder.changes())
            .append(" log_bytes=").append(logBuilder.bytes())
            .append(" duplicates=").append(sampler.getDuplicateIdentities())
            .append(" status=").append(coverageStatus);
        if (logBuilder.isTruncated() || "truncated".equals(coverageStatus)) {
            builder.append(" truncated=true");
        }
        appendScanDurations(builder);
        return builder.toString();
    }

    private void appendScanDurations(StringBuilder builder) {
        if (scanDurations.isEmpty()) {
            builder.append(" scan_ms=none");
            return;
        }
        List<Long> sorted = new ArrayList<>(scanDurations);
        sorted.sort(Long::compareTo);
        long p50 = sorted.get(percentileIndex(sorted.size(), 0.50));
        long p95 = sorted.get(percentileIndex(sorted.size(), 0.95));
        long max = sorted.get(sorted.size() - 1);
        builder.append(" scan_ms_p50=").append(p50)
            .append(" scan_ms_p95=").append(p95)
            .append(" scan_ms_max=").append(max);
    }

    private static int percentileIndex(int size, double percentile) {
        int index = (int) Math.ceil(percentile * size) - 1;
        return Math.max(0, Math.min(size - 1, index));
    }

    private static long nextInterval(long recordingMillis) {
        return ((recordingMillis / SAMPLE_INTERVAL_MILLIS) + 1L) * SAMPLE_INTERVAL_MILLIS;
    }

    private Build build(Path source, Path archive, Path replayDirectory) {
        try {
            WorldBaseArchive.Inventory inventory = WorldBaseArchive.inventory(source);
            long required = inventory.totalBytes() * 2L + DISK_MARGIN_BYTES;
            FileStore store = Files.getFileStore(replayDirectory);
            if (store.getUsableSpace() < required) {
                return Build.failed("not enough free disk for the world base");
            }
            Path copyRoot = archive.getParent().resolve("raw");
            long copyStart = System.nanoTime();
            WorldBaseArchive.copyFiltered(source, copyRoot);
            long copyMillis = (System.nanoTime() - copyStart) / 1_000_000L;
            long packStart = System.nanoTime();
            WorldBaseArchive.writeFromDirectory(copyRoot, archive);
            long packMillis = (System.nanoTime() - packStart) / 1_000_000L;
            deleteRecursively(copyRoot);
            byte[] bytes = Files.readAllBytes(archive);
            return new Build(bytes, inventory.totalBytes(), copyMillis, packMillis, "");
        } catch (IOException | ReplayFormatException | RuntimeException exception) {
            return Build.failed("base capture failed: " + message(exception));
        }
    }

    private void truncate(String reason) {
        samplingStopped = true;
        coverageStatus = "truncated";
        if (logBuilder != null) {
            System.out.println("[pz-replay] world log truncated reason=" + reason
                + " changes=" + logBuilder.changes() + " bytes=" + logBuilder.bytes());
        }
    }

    private void fail(String message) {
        failure = message;
        summary = "world=failed reason=" + message;
        System.out.println("[pz-replay] world capture unavailable: " + message);
        future = null;
        deleteStaging();
    }

    private void deleteStaging() {
        if (staging != null) {
            deleteRecursively(staging);
            staging = null;
        }
    }

    private static int worldVersion() {
        try {
            return IsoWorld.getWorldVersion();
        } catch (RuntimeException exception) {
            return 1;
        }
    }

    private static String currentSaveDirectory() {
        try {
            String directory = ZomboidFileSystem.instance.getCurrentSaveDir();
            return directory == null ? "" : directory;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String safeName(String captureName) {
        if (captureName == null || captureName.isBlank()) {
            return "capture";
        }
        return captureName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank()
            ? exception.getClass().getSimpleName() : value;
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}
