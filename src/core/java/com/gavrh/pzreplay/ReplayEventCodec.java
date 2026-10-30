package com.gavrh.pzreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplayEventCodec {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_EVENTS = 2048;
    private static final int MAX_WARNINGS = 8;
    private static final int RECORD_HEADER_BYTES = 4 + 4 + 1 + 1 + 4 + 4 + 2;
    private static final int MAX_RECORD_BYTES = RECORD_HEADER_BYTES + ReplayEvent.MAX_DETAIL_LENGTH;

    public record Result(List<ReplayEvent> events, List<String> warnings) {
        public Result {
            events = List.copyOf(events);
            warnings = List.copyOf(warnings);
        }
    }

    private ReplayEventCodec() {
    }

    public static byte[] encode(List<ReplayEvent> events) throws ReplayFormatException {
        if (events == null) {
            throw new ReplayFormatException("action events are missing");
        }
        if (events.size() > MAX_EVENTS) {
            throw new ReplayFormatException("too many action events: " + events.size());
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(CURRENT_VERSION);
            out.writeInt(events.size());
            for (int index = 0; index < events.size(); index++) {
                ReplayEvent event = events.get(index);
                if (event == null) {
                    throw new ReplayFormatException("action event " + index + " is missing");
                }
                byte[] detail = event.detail().getBytes(StandardCharsets.UTF_8);
                if (detail.length > ReplayEvent.MAX_DETAIL_LENGTH) {
                    throw new ReplayFormatException("action event " + index
                        + " has an oversized payload");
                }
                out.writeInt((int) event.sequence());
                out.writeFloat(event.timeSeconds());
                out.writeByte(event.kind().code());
                out.writeByte(event.end() ? 1 : 0);
                out.writeFloat(event.direction());
                out.writeFloat(event.durationSeconds());
                out.writeShort(detail.length);
                out.write(detail);
            }
            out.flush();
        } catch (IOException exception) {
            throw new ReplayFormatException("action events could not be encoded", exception);
        }
        byte[] encoded = payload.toByteArray();
        if (encoded.length > MAX_EVENTS * MAX_RECORD_BYTES + 6) {
            throw new ReplayFormatException("action events are too large");
        }
        return encoded;
    }

    public static Result decode(byte[] bytes) throws ReplayFormatException {
        if (bytes == null || bytes.length < 6) {
            throw new ReplayFormatException("action event data is corrupt or incomplete");
        }
        Set<String> warnings = new LinkedHashSet<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedShort();
            if (version != CURRENT_VERSION) {
                throw new ReplayFormatException(
                    "action event data version is unsupported: " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_EVENTS) {
                throw new ReplayFormatException("action event count is invalid: " + count);
            }
            List<ReplayEvent> events = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                if (in.available() < RECORD_HEADER_BYTES) {
                    throw new ReplayFormatException(
                        "action event data is corrupt or incomplete");
                }
                long sequence = in.readInt() & 0xFFFFFFFFL;
                float time = in.readFloat();
                int kindCode = in.readUnsignedByte();
                boolean end = (in.readUnsignedByte() & 1) != 0;
                float direction = in.readFloat();
                float duration = in.readFloat();
                int detailLength = in.readUnsignedShort();
                if (detailLength > ReplayEvent.MAX_DETAIL_LENGTH
                    || in.available() < detailLength) {
                    throw new ReplayFormatException(
                        "action event data is corrupt or incomplete");
                }
                byte[] detailBytes = new byte[detailLength];
                in.readFully(detailBytes);
                ReplayEvent.Kind kind = ReplayEvent.Kind.fromCode(kindCode);
                if (kind == null) {
                    addWarning(warnings, "unknown action event type " + kindCode
                        + " was ignored");
                    continue;
                }
                if (!Float.isFinite(time) || time < 0.0f
                    || !Float.isFinite(direction)
                    || !Float.isFinite(duration)
                    || duration < 0.0f) {
                    addWarning(warnings, "invalid action event " + index + " was ignored");
                    continue;
                }
                events.add(new ReplayEvent(sequence, time, kind, end,
                    new String(detailBytes, StandardCharsets.UTF_8), direction,
                    duration));
            }
            if (in.available() > 0) {
                throw new ReplayFormatException("action event data is corrupt or incomplete");
            }
            return new Result(events, List.copyOf(warnings));
        } catch (EOFException exception) {
            throw new ReplayFormatException(
                "action event data is corrupt or incomplete", exception);
        } catch (IOException exception) {
            throw new ReplayFormatException(
                "action event data is corrupt or incomplete", exception);
        }
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
