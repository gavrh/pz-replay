package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import zombie.core.Core;
import zombie.iso.IsoWorld;

final class WorldContextManager {
    private static final String MARKER_EXTENSION = ".properties";
    private static final String LATEST_SAVE = "latestSave.ini";
    private static final Object LOCK = new Object();

    private static boolean shutdownHookInstalled;
    private static FileChannel lockChannel;
    private static FileLock fileLock;
    private static String contextId = "";
    private static String folderName = "";
    private static String gameMode = "";
    private static Path contextPath;
    private static Path saveRoot;
    private static Path realSavePath;
    private static String realSaveHash = "";
    private static Path markerPath;
    private static Path backupPath;
    private static String packageId = "";
    private static boolean prepared;

    private WorldContextManager() {
    }

    static void installShutdownHook() {
        synchronized (LOCK) {
            if (shutdownHookInstalled) {
                return;
            }
            shutdownHookInstalled = true;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> cleanup("shutdown"), "pz-replay-context-cleanup"));
    }

    static String prepare(String replayPath, ReplayMetadata metadata,
        String expectedWorldVersion) throws IOException, ReplayFormatException {
        synchronized (LOCK) {
            if (prepared) {
                throw new IOException("a playback context is already prepared");
            }
            Path contexts = contextsDirectory();
            Files.createDirectories(contexts);
            acquireLock(contexts);
            try {
                byte[] base = FormatReader.readWorldBase(Paths.get(replayPath));
                String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                contextId = id;
                folderName = WorldContext.folderName(id);
                gameMode = safeGameMode(metadata.gameMode());
                packageId = Long.toHexString(metadata.startTimeMillis());
                saveRoot = Paths.get(Core.getMyDocumentFolder(), "Saves", gameMode);
                realSavePath = realSavePath(saveRoot, metadata.saveName());
                realSaveHash = hashTree(realSavePath);
                contextPath = saveRoot.resolve(folderName).normalize();
                markerPath = contexts.resolve(id + MARKER_EXTENSION);
                backupPath = contexts.resolve(id + ".latestSave.bak");
                if (Files.exists(contextPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("context folder already exists");
                }
                Files.createDirectories(contextPath);
                WorldBaseArchive.extract(base, contextPath);
                writeSentinel(contextPath);
                backupLatestSave();
                writeMarker("prepared");
                prepared = true;
                System.out.println("[pz-replay] context prepared id=" + id
                    + " mode=" + gameMode + " folder=" + folderName
                    + " base_bytes=" + base.length);
                return folderName;
            } catch (IOException | ReplayFormatException | RuntimeException exception) {
                releaseLock();
                deleteContextQuietly();
                throw exception;
            }
        }
    }

    static void markLoaded() {
        synchronized (LOCK) {
            if (!prepared) {
                return;
            }
            try {
                writeMarker("loaded");
            } catch (IOException | RuntimeException exception) {
                System.out.println("[pz-replay] context marker update failed: "
                    + exception.getMessage());
            }
        }
    }

    static List<String> verify(ReplayMetadata metadata, String expectedWorldVersion) {
        List<String> warnings = new ArrayList<>();
        synchronized (LOCK) {
            if (!prepared) {
                warnings.add("context is not prepared");
                return List.copyOf(warnings);
            }
            IsoWorld world = IsoWorld.instance;
            if (world == null) {
                warnings.add("world is not ready");
                return List.copyOf(warnings);
            }
            if (!folderName.equals(world.getWorld())) {
                warnings.add("world identity mismatch expected=" + folderName
                    + " loaded=" + world.getWorld());
            }
            if (!mapMatches(metadata.mapName(), world.getMap())) {
                warnings.add("map mismatch expected=" + metadata.mapName()
                    + " loaded=" + world.getMap());
            }
            String gameVersion = gameVersion();
            if (!metadata.gameVersion().isBlank() && !"unknown".equals(metadata.gameVersion())
                && !metadata.gameVersion().equals(gameVersion)) {
                warnings.add("game version mismatch expected=" + metadata.gameVersion()
                    + " loaded=" + gameVersion);
            }
            if (expectedWorldVersion != null && !expectedWorldVersion.isBlank()) {
                String loadedVersion = Integer.toString(IsoWorld.getWorldVersion());
                if (!expectedWorldVersion.equals(loadedVersion)) {
                    warnings.add("world version mismatch expected=" + expectedWorldVersion
                        + " loaded=" + loadedVersion);
                }
            }
        }
        return List.copyOf(warnings);
    }

    static void cleanup(String reason) {
        synchronized (LOCK) {
            if (!prepared) {
                return;
            }
            System.out.println("[pz-replay] context cleanup id=" + contextId
                + " reason=" + reason);
            restoreLatestSave();
            deleteContextQuietly();
            releaseLock();
            prepared = false;
            contextId = "";
            folderName = "";
            gameMode = "";
            contextPath = null;
            saveRoot = null;
            realSavePath = null;
            realSaveHash = "";
            markerPath = null;
            backupPath = null;
            packageId = "";
        }
    }

    static void cleanupStale() {
        synchronized (LOCK) {
            if (prepared) {
                return;
            }
            Path contexts = contextsDirectory();
            if (!Files.isDirectory(contexts)) {
                return;
            }
            try {
                Files.createDirectories(contexts);
                acquireLock(contexts);
            } catch (IOException | RuntimeException exception) {
                System.out.println("[pz-replay] context cleanup skipped: "
                    + exception.getMessage());
                return;
            }
            try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(contexts, "*" + MARKER_EXTENSION)) {
                for (Path markerFile : stream) {
                    cleanupMarker(contexts, markerFile);
                }
            } catch (IOException exception) {
                System.out.println("[pz-replay] context cleanup failed: "
                    + exception.getMessage());
            } finally {
                releaseLock();
            }
        }
    }

    static boolean isPrepared() {
        synchronized (LOCK) {
            return prepared;
        }
    }

    static String getFolderName() {
        synchronized (LOCK) {
            return folderName;
        }
    }

    static String getGameMode() {
        synchronized (LOCK) {
            return gameMode;
        }
    }

    private static void cleanupMarker(Path contexts, Path markerFile) {
        String text;
        try {
            text = Files.readString(markerFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] context marker unreadable: "
                + markerFile.getFileName());
            return;
        }
        WorldContext.Marker marker = WorldContext.parseMarker(text);
        if (marker == null) {
            System.out.println("[pz-replay] context marker is corrupt: "
                + markerFile.getFileName());
            return;
        }
        if (!isSafeGameMode(marker.gameMode())) {
            System.out.println("[pz-replay] context marker game mode is invalid: "
                + markerFile.getFileName());
            return;
        }
        Path modeRoot = Paths.get(Core.getMyDocumentFolder(), "Saves", marker.gameMode());
        Path folder = modeRoot.resolve(marker.folderName());
        String sentinelId = readSentinel(folder);
        WorldContext.Cleanup decision = WorldContext.cleanup(marker.folderName(), marker,
            sentinelId, System.currentTimeMillis(), WorldContext.pidAlive(marker.pid()),
            modeRoot, folder);
        if (decision.action() == WorldContext.Action.REPORT) {
            System.out.println("[pz-replay] context orphan reported id=" + marker.contextId()
                + " reason=" + decision.reason());
            return;
        }
        if (decision.action() == WorldContext.Action.KEEP) {
            return;
        }
        try {
            restoreLatestSave(marker.folderName(),
                contexts.resolve(marker.contextId() + ".latestSave.bak"));
            deleteDirectory(folder);
            Files.deleteIfExists(markerFile);
            Files.deleteIfExists(contexts.resolve(marker.contextId() + ".latestSave.bak"));
            System.out.println("[pz-replay] context orphan deleted id="
                + marker.contextId());
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] context orphan delete failed id="
                + marker.contextId() + " error=" + exception.getMessage());
        }
    }

    private static void acquireLock(Path contexts) throws IOException {
        if (fileLock != null && fileLock.isValid()) {
            return;
        }
        Path lockPath = contexts.resolve("lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
        try {
            fileLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException exception) {
            fileLock = null;
        }
        if (fileLock == null) {
            lockChannel.close();
            lockChannel = null;
            throw new IOException("another playback context holds the lock");
        }
    }

    private static void releaseLock() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try {
            if (lockChannel != null && lockChannel.isOpen()) {
                lockChannel.close();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        fileLock = null;
        lockChannel = null;
    }

    private static void writeMarker(String state) throws IOException {
        WorldContext.Marker marker = new WorldContext.Marker(contextId,
            ProcessHandle.current().pid(), System.currentTimeMillis(), state, gameMode,
            folderName, packageId, "");
        ReplayFiles.writeAtomically(markerPath,
            WorldContext.markerText(marker).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSentinel(Path context) throws IOException {
        ReplayFiles.writeAtomically(context.resolve(WorldContext.SENTINEL_NAME),
            WorldContext.sentinelText(contextId).getBytes(StandardCharsets.UTF_8));
    }

    private static void backupLatestSave() throws IOException {
        Path latest = latestSavePath();
        if (Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)) {
            ReplayFiles.writeAtomically(backupPath, Files.readAllBytes(latest));
        }
    }

    private static void restoreLatestSave() {
        if (folderName.isBlank()) {
            return;
        }
        Path latest = latestSavePath();
        try {
            String current = Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(latest, StandardCharsets.UTF_8) : "";
            String backup = Files.isRegularFile(backupPath, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(backupPath, StandardCharsets.UTF_8) : "";
            String restored = WorldContext.restoreLatestSave(current, backup, folderName);
            if (!restored.equals(current)) {
                ReplayFiles.writeAtomically(latest,
                    restored.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] continue restore failed: "
                + exception.getMessage());
        }
    }

    private static void restoreLatestSave(String contextFolder, Path backup) {
        Path latest = latestSavePath();
        try {
            String current = Files.isRegularFile(latest, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(latest, StandardCharsets.UTF_8) : "";
            String previous = Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(backup, StandardCharsets.UTF_8) : "";
            String restored = WorldContext.restoreLatestSave(current, previous, contextFolder);
            if (!restored.equals(current)) {
                ReplayFiles.writeAtomically(latest,
                    restored.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] continue restore failed: "
                + exception.getMessage());
        }
    }

    private static String readSentinel(Path folder) {
        try {
            Path sentinel = folder.resolve(WorldContext.SENTINEL_NAME);
            if (!Files.isRegularFile(sentinel, LinkOption.NOFOLLOW_LINKS)) {
                return "";
            }
            return WorldContext.sentinelId(Files.readString(sentinel, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private static void deleteContextQuietly() {
        if (contextPath != null) {
            try {
                deleteDirectory(contextPath);
            } catch (IOException | RuntimeException exception) {
                System.out.println("[pz-replay] context delete failed: "
                    + exception.getMessage());
            }
        }
        try {
            if (markerPath != null) {
                Files.deleteIfExists(markerPath);
            }
            if (backupPath != null) {
                Files.deleteIfExists(backupPath);
            }
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] context marker delete failed: "
                + exception.getMessage());
        }
    }

    static boolean realSaveUnchanged() {
        synchronized (LOCK) {
            if (realSaveHash.isBlank() || realSavePath == null) {
                return true;
            }
            return realSaveHash.equals(hashTree(realSavePath));
        }
    }

    private static Path realSavePath(Path modeRoot, String saveName) {
        if (modeRoot == null || saveName == null || saveName.isBlank()) {
            return null;
        }
        try {
            Path root = modeRoot.toAbsolutePath().normalize();
            Path candidate = root.resolve(Paths.get(saveName)).normalize();
            if (!candidate.startsWith(root) || candidate.equals(root)) {
                return null;
            }
            return candidate;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String hashTree(Path root) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            try (Stream<Path> stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
            }
            for (Path file : files) {
                digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (java.io.InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            return "";
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        WorldContext.makeWritable(directory);
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static String safeGameMode(String value) throws IOException {
        String mode = value == null ? "" : value;
        if (mode.isBlank()) {
            return "Sandbox";
        }
        if (!isSafeGameMode(mode)) {
            throw new IOException("replay game mode is not a safe save directory name");
        }
        return mode;
    }

    private static boolean isSafeGameMode(String mode) {
        if (mode == null || mode.isEmpty() || mode.length() > 32
            || mode.equals(".") || mode.equals("..")
            || !mode.equals(mode.trim())) {
            return false;
        }
        for (int index = 0; index < mode.length(); index++) {
            char character = mode.charAt(index);
            if (character == '/' || character == '\\' || character == ':'
                || character < 0x20) {
                return false;
            }
        }
        return true;
    }

    private static Path contextsDirectory() {
        return ReplayStorage.directory().resolve("contexts");
    }

    private static Path latestSavePath() {
        return Paths.get(Core.getMyDocumentFolder(), LATEST_SAVE);
    }

    private static String gameVersion() {
        try {
            return Core.getInstance().getGameAndBuildVersion();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static boolean mapMatches(String expected, String loaded) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        for (String part : expected.split(";")) {
            if (part.trim().equals(loaded)) {
                return true;
            }
        }
        return false;
    }
}
