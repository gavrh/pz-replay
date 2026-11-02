package com.gavrh.pzreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplayGapCodec {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_GAPS = 128;
    public static final int MAX_GAP_BYTES = 33;
    private static final int MAX_WARNINGS = 8;
    private static final float TIME_EPSILON = 0.0001f;

    public record Result(List<ReplayGap> gaps, List<String> warnings) {
        public Result {
            gaps = List.copyOf(gaps);
            warnings = List.copyOf(warnings);
        }
    }

    private ReplayGapCodec() {
    }

    public static byte[] encode(List<ReplayGap> gaps) throws ReplayFormatException {
        if (gaps == null) {
            throw new ReplayFormatException("timeline gap data is missing");
        }
        if (gaps.size() > MAX_GAPS) {
            throw new ReplayFormatException("too many timeline gaps: " + gaps.size());
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(CURRENT_VERSION);
            out.writeInt(gaps.size());
            float previousEnd = 0.0f;
            for (int index = 0; index < gaps.size(); index++) {
                ReplayGap gap = gaps.get(index);
                if (gap == null) {
                    throw new ReplayFormatException("timeline gap " + index + " is missing");
                }
                if (gap.startTimeSeconds() < previousEnd - TIME_EPSILON) {
                    throw new ReplayFormatException("timeline gaps overlap at gap " + index);
                }
                previousEnd = gap.endTimeSeconds();
                out.writeFloat(gap.startTimeSeconds());
                out.writeFloat(gap.endTimeSeconds());
                out.writeByte(gap.reason().id());
                out.writeFloat(gap.lastX());
                out.writeFloat(gap.lastY());
                out.writeFloat(gap.lastZ());
                out.writeFloat(gap.nextX());
                out.writeFloat(gap.nextY());
                out.writeFloat(gap.nextZ());
            }
            out.flush();
        } catch (IOException exception) {
            throw new ReplayFormatException("timeline gap data could not be encoded", exception);
        }
        return payload.toByteArray();
    }

    public static Result decode(byte[] bytes) throws ReplayFormatException {
        if (bytes == null || bytes.length < 6) {
            throw new ReplayFormatException("timeline gap data is corrupt or incomplete");
        }
        Set<String> warnings = new LinkedHashSet<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedShort();
            if (version != CURRENT_VERSION) {
                throw new ReplayFormatException(
                    "timeline gap data version is unsupported: " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_GAPS) {
                throw new ReplayFormatException("timeline gap count is invalid: " + count);
            }
            List<ReplayGap> gaps = new ArrayList<>(count);
            float previousEnd = 0.0f;
            for (int index = 0; index < count; index++) {
                if (in.available() < MAX_GAP_BYTES) {
                    throw new ReplayFormatException("timeline gap data is corrupt or incomplete");
                }
                float start = in.readFloat();
                float end = in.readFloat();
                int reasonId = in.readUnsignedByte();
                float lastX = in.readFloat();
                float lastY = in.readFloat();
                float lastZ = in.readFloat();
                float nextX = in.readFloat();
                float nextY = in.readFloat();
                float nextZ = in.readFloat();
                ReplayGap.Reason reason = ReplayGap.Reason.fromId(reasonId);
                if (reason == null) {
                    addWarning(warnings, "unknown timeline gap reason " + reasonId
                        + " was ignored");
                    continue;
                }
                if (!Float.isFinite(start) || start < 0.0f || !Float.isFinite(end)
                    || end < start || !Float.isFinite(lastX) || !Float.isFinite(lastY)
                    || !Float.isFinite(lastZ) || !Float.isFinite(nextX)
                    || !Float.isFinite(nextY) || !Float.isFinite(nextZ)) {
                    addWarning(warnings, "invalid timeline gap " + index + " was ignored");
                    continue;
                }
                if (start < previousEnd - TIME_EPSILON) {
                    addWarning(warnings, "overlapping timeline gap " + index
                        + " was ignored");
                    continue;
                }
                previousEnd = end;
                gaps.add(new ReplayGap(start, end, reason, lastX, lastY, lastZ,
                    nextX, nextY, nextZ));
            }
            if (in.available() > 0) {
                throw new ReplayFormatException("timeline gap data is corrupt or incomplete");
            }
            return new Result(gaps, List.copyOf(warnings));
        } catch (EOFException exception) {
            throw new ReplayFormatException("timeline gap data is corrupt or incomplete",
                exception);
        } catch (IOException exception) {
            throw new ReplayFormatException("timeline gap data is corrupt or incomplete",
                exception);
        }
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
