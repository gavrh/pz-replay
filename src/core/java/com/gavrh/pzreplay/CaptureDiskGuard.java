package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

public final class CaptureDiskGuard {
    public static final long MIN_START_BYTES = 512L * 1024L * 1024L;
    public static final long MIN_RUNNING_BYTES = 128L * 1024L * 1024L;

    private final LongSupplier usableSpace;
    private final long minimumBytes;

    public CaptureDiskGuard(Path directory, long minimumBytes) {
        this(() -> usableSpace(directory), minimumBytes);
    }

    CaptureDiskGuard(LongSupplier usableSpace, long minimumBytes) {
        if (usableSpace == null) {
            throw new IllegalArgumentException("usable space supplier is required");
        }
        if (minimumBytes < 0L) {
            throw new IllegalArgumentException("minimum free space must not be negative");
        }
        this.usableSpace = usableSpace;
        this.minimumBytes = minimumBytes;
    }

    public boolean hasSpace() {
        try {
            return usableSpace.getAsLong() >= minimumBytes;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public long minimumBytes() {
        return minimumBytes;
    }

    private static long usableSpace(Path directory) {
        try {
            FileStore store = Files.getFileStore(directory);
            return store.getUsableSpace();
        } catch (IOException exception) {
            return 0L;
        }
    }
}
