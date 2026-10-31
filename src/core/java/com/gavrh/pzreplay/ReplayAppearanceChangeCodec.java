package com.gavrh.pzreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplayAppearanceChangeCodec {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_CHANGES = 512;
    public static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_SNAPSHOT_BYTES = 384 * 1024;

    private static final int MAX_WARNINGS = 8;
    private static final int RECORD_HEADER_BYTES = 4 + 4 + 2 + 4;

    public record Result(List<ReplayAppearanceChange> changes, List<String> warnings) {
        public Result {
            changes = List.copyOf(changes);
            warnings = List.copyOf(warnings);
        }
    }

    private ReplayAppearanceChangeCodec() {
    }

    public static byte[] encode(List<ReplayAppearanceChange> changes)
        throws ReplayFormatException {
        if (changes == null) {
            throw new ReplayFormatException("appearance changes are missing");
        }
        if (changes.size() > MAX_CHANGES) {
            throw new ReplayFormatException("too many appearance changes: " + changes.size());
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int totalSnapshotBytes = 0;
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(CURRENT_VERSION);
            out.writeInt(changes.size());
            for (int index = 0; index < changes.size(); index++) {
                ReplayAppearanceChange change = changes.get(index);
                if (change == null) {
                    throw new ReplayFormatException("appearance change " + index + " is missing");
                }
                if (!change.appearance().isCurrent()) {
                    throw new ReplayFormatException("appearance change " + index
                        + " has an unsupported snapshot version");
                }
                byte[] snapshot = change.appearance().decode();
                if (snapshot.length == 0 || snapshot.length > MAX_SNAPSHOT_BYTES) {
                    throw new ReplayFormatException("appearance change " + index
                        + " has an invalid snapshot size");
                }
                totalSnapshotBytes += snapshot.length;
                if (totalSnapshotBytes > MAX_TOTAL_SNAPSHOT_BYTES) {
                    throw new ReplayFormatException("appearance change data is too large");
                }
                out.writeInt((int) change.sequence());
                out.writeFloat(change.timeSeconds());
                out.writeShort(change.appearance().version());
                out.writeInt(snapshot.length);
                out.write(snapshot);
            }
            out.flush();
        } catch (IOException exception) {
            throw new ReplayFormatException("appearance changes could not be encoded", exception);
        }
        return payload.toByteArray();
    }

    public static Result decode(byte[] bytes) throws ReplayFormatException {
        if (bytes == null || bytes.length < 10) {
            throw new ReplayFormatException(
                "appearance change data is corrupt or incomplete");
        }
        Set<String> warnings = new LinkedHashSet<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedShort();
            if (version != CURRENT_VERSION) {
                throw new ReplayFormatException(
                    "appearance change data version is unsupported: " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_CHANGES) {
                throw new ReplayFormatException(
                    "appearance change count is invalid: " + count);
            }
            List<ReplayAppearanceChange> changes = new ArrayList<>(count);
            int totalSnapshotBytes = 0;
            for (int index = 0; index < count; index++) {
                if (in.available() < RECORD_HEADER_BYTES) {
                    throw new ReplayFormatException(
                        "appearance change data is corrupt or incomplete");
                }
                long sequence = in.readInt() & 0xFFFFFFFFL;
                float time = in.readFloat();
                int snapshotVersion = in.readUnsignedShort();
                int length = in.readInt();
                if (length < 0 || length > MAX_SNAPSHOT_BYTES
                    || in.available() < length) {
                    throw new ReplayFormatException(
                        "appearance change data is corrupt or incomplete");
                }
                byte[] snapshot = new byte[length];
                in.readFully(snapshot);
                totalSnapshotBytes += length;
                if (totalSnapshotBytes > MAX_TOTAL_SNAPSHOT_BYTES) {
                    throw new ReplayFormatException("appearance change data is too large");
                }
                if (!Float.isFinite(time) || time < 0.0f) {
                    addWarning(warnings, "invalid appearance change " + index
                        + " was ignored");
                    continue;
                }
                if (snapshotVersion != ReplayAppearance.CURRENT_VERSION) {
                    addWarning(warnings, "appearance change " + index
                        + " has an unsupported snapshot version and was ignored");
                    continue;
                }
                if (length == 0) {
                    addWarning(warnings, "appearance change " + index
                        + " is incomplete and was ignored");
                    continue;
                }
                ReplayAppearance appearance = new ReplayAppearance(snapshotVersion,
                    Base64.getEncoder().encodeToString(snapshot));
                changes.add(new ReplayAppearanceChange(sequence, time, appearance));
            }
            if (in.available() > 0) {
                throw new ReplayFormatException(
                    "appearance change data is corrupt or incomplete");
            }
            return new Result(changes, List.copyOf(warnings));
        } catch (EOFException exception) {
            throw new ReplayFormatException(
                "appearance change data is corrupt or incomplete", exception);
        } catch (IOException exception) {
            throw new ReplayFormatException(
                "appearance change data is corrupt or incomplete", exception);
        }
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
