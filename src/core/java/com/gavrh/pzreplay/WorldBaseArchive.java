package com.gavrh.pzreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class WorldBaseArchive {
    public static final int MAX_ENTRIES = 131_072;
    public static final long MAX_TOTAL_BYTES = 768L * 1024L * 1024L;
    public static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;

    public record Entry(String name, long size) {
        public Entry {
            name = name == null ? "" : name;
            if (size < 0L) {
                throw new IllegalArgumentException("entry size must not be negative");
            }
        }
    }

    public record Inventory(List<Entry> entries, long totalBytes) {
        public Inventory {
            entries = List.copyOf(entries);
        }
    }

    private WorldBaseArchive() {
    }

    public static Inventory inventory(Path root) throws IOException, ReplayFormatException {
        List<Entry> entries = new ArrayList<>();
        long[] total = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = relativeName(root, file);
                if (!WorldBaseFilter.keep(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                entries.add(new Entry(relative, attributes.size()));
                total[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(Entry::name));
        validateInventory(entries);
        return new Inventory(entries, total[0]);
    }

    public static Inventory copyFiltered(Path source, Path target)
        throws IOException, ReplayFormatException {
        Inventory inventory = inventory(source);
        Files.createDirectories(target);
        for (Entry entry : inventory.entries()) {
            Path output = target.resolve(entry.name());
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source.resolve(entry.name()), output,
                StandardCopyOption.REPLACE_EXISTING);
        }
        return inventory;
    }

    public static void writeFromDirectory(Path root, Path target)
        throws IOException, ReplayFormatException {
        Inventory inventory = inventory(root);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream file = Files.newOutputStream(target,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
            ZipOutputStream zip = new ZipOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (Entry entry : inventory.entries()) {
                Path source = root.resolve(entry.name());
                long crc = crc(source);
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(entry.size());
                zipEntry.setCompressedSize(entry.size());
                zipEntry.setCrc(crc);
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                copy(source, zip, buffer);
                zip.closeEntry();
            }
        }
    }

    public static void write(Path target, List<Entry> entries, Path root)
        throws IOException, ReplayFormatException {
        validateInventory(entries);
        try (OutputStream file = Files.newOutputStream(target,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
            ZipOutputStream zip = new ZipOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (Entry entry : entries) {
                Path source = root.resolve(entry.name());
                long actual = Files.size(source);
                if (actual != entry.size()) {
                    throw new ReplayFormatException("base entry changed while packing: "
                        + entry.name());
                }
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(actual);
                zipEntry.setCompressedSize(actual);
                zipEntry.setCrc(crc(source));
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                copy(source, zip, buffer);
                zip.closeEntry();
            }
        }
    }

    public static void validate(byte[] archive) throws ReplayFormatException {
        if (!hasCentralDirectory(archive)) {
            throw new ReplayFormatException("base archive has no central directory");
        }
        Set<String> names = new HashSet<>();
        long total = 0L;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getMethod() != ZipEntry.STORED) {
                    throw new ReplayFormatException("base entry is not stored: "
                        + entry.getName());
                }
                validateName(entry.getName());
                if (!names.add(entry.getName())) {
                    throw new ReplayFormatException("base entry is duplicated: "
                        + entry.getName());
                }
                long size = entry.getSize();
                if (size < 0L || size > MAX_ENTRY_BYTES) {
                    throw new ReplayFormatException("base entry size is invalid: "
                        + entry.getName());
                }
                total += size;
                if (total > MAX_TOTAL_BYTES) {
                    throw new ReplayFormatException("base exceeds the total size cap");
                }
                count++;
                if (count > MAX_ENTRIES) {
                    throw new ReplayFormatException("base exceeds the entry cap");
                }
            }
            if (count == 0) {
                throw new ReplayFormatException("base archive is empty");
            }
        } catch (IOException exception) {
            throw new ReplayFormatException("base archive is unreadable", exception);
        }
    }

    public static void extract(byte[] archive, Path root) throws IOException, ReplayFormatException {
        validate(archive);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = normalizedRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedRoot) || target.equals(normalizedRoot)) {
                    throw new ReplayFormatException("base entry escapes the context root: "
                        + entry.getName());
                }
                Path parent = target.getParent();
                if (parent != null) {
                    createDirectoriesSafely(normalizedRoot, parent);
                }
                if (Files.isSymbolicLink(target)) {
                    throw new ReplayFormatException("base entry targets a symlink: "
                        + entry.getName());
                }
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                zip.closeEntry();
            }
        }
    }

    public static void extract(Path archive, Path root) throws IOException, ReplayFormatException {
        extract(Files.readAllBytes(archive), root);
    }

    public static Inventory inventory(byte[] archive) throws ReplayFormatException {
        List<Entry> entries = new ArrayList<>();
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                validateName(entry.getName());
                long size = entry.getSize();
                if (size < 0L) {
                    throw new ReplayFormatException("base entry size is unknown: "
                        + entry.getName());
                }
                entries.add(new Entry(entry.getName(), size));
                total += size;
            }
        } catch (IOException exception) {
            throw new ReplayFormatException("base archive is unreadable", exception);
        }
        return new Inventory(entries, total);
    }

    static boolean isValidName(String name) {
        try {
            validateName(name);
            return true;
        } catch (ReplayFormatException exception) {
            return false;
        }
    }

    static void validateName(String name) throws ReplayFormatException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
            || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) {
            throw new ReplayFormatException("base entry name is invalid: " + name);
        }
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ReplayFormatException("base entry name is invalid: " + name);
            }
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 4_096) {
            throw new ReplayFormatException("base entry name exceeds the cap");
        }
    }

    private static boolean hasCentralDirectory(byte[] archive) {
        int minimum = 22;
        if (archive.length < minimum) {
            return false;
        }
        int start = Math.max(0, archive.length - minimum - 0xFFFF);
        for (int offset = archive.length - minimum; offset >= start; offset--) {
            if ((archive[offset] & 0xFF) == 0x50
                && (archive[offset + 1] & 0xFF) == 0x4B
                && (archive[offset + 2] & 0xFF) == 0x05
                && (archive[offset + 3] & 0xFF) == 0x06) {
                return true;
            }
        }
        return false;
    }

    private static void validateInventory(List<Entry> entries) throws ReplayFormatException {
        if (entries == null) {
            throw new ReplayFormatException("base inventory is missing");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new ReplayFormatException("base exceeds the entry cap");
        }
        Set<String> names = new HashSet<>();
        long total = 0L;
        for (Entry entry : entries) {
            validateName(entry.name());
            if (!names.add(entry.name())) {
                throw new ReplayFormatException("base entry is duplicated: " + entry.name());
            }
            if (entry.size() > MAX_ENTRY_BYTES) {
                throw new ReplayFormatException("base entry exceeds the cap: " + entry.name());
            }
            total += entry.size();
            if (total > MAX_TOTAL_BYTES) {
                throw new ReplayFormatException("base exceeds the total size cap");
            }
        }
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static long crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                crc.update(buffer, 0, count);
            }
        }
        return crc.getValue();
    }

    private static void copy(Path source, OutputStream output, byte[] buffer) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void createDirectoriesSafely(Path root, Path directory) throws IOException,
        ReplayFormatException {
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current)) {
                    throw new ReplayFormatException("base entry path is not a directory: "
                        + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }
}
