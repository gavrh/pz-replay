package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
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

    private Future<Build> future;
    private Path staging;
    private int paddingTiles;
    private boolean requested;
    private String failure = "";
    private WorldPackage packageData;
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

    synchronized void tick() {
        if (!requested || packageData != null || !failure.isEmpty()) {
            return;
        }
        poll();
    }

    synchronized WorldPackage finish() {
        if (!requested) {
            return null;
        }
        long deadline = System.currentTimeMillis() + FINISH_WAIT_MILLIS;
        while (packageData == null && failure.isEmpty()
            && System.currentTimeMillis() < deadline) {
            poll();
            if (packageData == null && failure.isEmpty()) {
                try {
                    wait(50L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
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
        failure = "";
        packageData = null;
        summary = "world=off";
        paddingTiles = 0;
    }

    private void poll() {
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
        byte[] log;
        try {
            log = WorldLogCodec.encodeT0(scan.records());
        } catch (ReplayFormatException exception) {
            fail("T0 sample could not be encoded: " + exception.getMessage());
            return;
        }
        long count = scan.records().size();
        packageData = new WorldPackage(build.base(), log, paddingTiles,
            WorldPackage.DEFAULT_SAMPLE_RATE_HZ, scan.coverageStatus(),
            scan.coverageSquares(), count, count, worldVersion());
        summary = String.format(Locale.ROOT,
            "world=padded base_bytes=%d base_copy_ms=%d base_pack_ms=%d t0_ms=%d t0_objects=%d coverage_squares=%d",
            build.base().length, build.copyMillis(), build.packMillis(),
            scan.durationMillis(), count, scan.coverageSquares());
        System.out.println("[pz-replay] world capture ready " + summary);
        deleteStaging();
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
