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

public final class ReplayVehicleCodec {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_SEGMENTS = 64;
    public static final int MAX_SAMPLES = 54_000;
    public static final int MAX_SAMPLE_BYTES = 32;
    private static final int MAX_WARNINGS = 8;
    private static final float TIME_EPSILON = 0.0001f;

    public record Result(List<ReplayVehicleSegment> segments, List<String> warnings) {
        public Result {
            segments = List.copyOf(segments);
            warnings = List.copyOf(warnings);
        }
    }

    private ReplayVehicleCodec() {
    }

    public static byte[] encode(List<ReplayVehicleSegment> segments)
        throws ReplayFormatException {
        if (segments == null) {
            throw new ReplayFormatException("vehicle data is missing");
        }
        if (segments.size() > MAX_SEGMENTS) {
            throw new ReplayFormatException("too many vehicle segments: " + segments.size());
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int totalSamples = 0;
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeShort(CURRENT_VERSION);
            out.writeInt(segments.size());
            for (int index = 0; index < segments.size(); index++) {
                ReplayVehicleSegment segment = segments.get(index);
                if (segment == null) {
                    throw new ReplayFormatException("vehicle segment " + index + " is missing");
                }
                if (!segment.isValid()) {
                    throw new ReplayFormatException("vehicle segment " + index
                        + " has no identity or samples");
                }
                if (segment.samples().size() > MAX_SAMPLES) {
                    throw new ReplayFormatException("vehicle segment " + index
                        + " has too many samples");
                }
                totalSamples += segment.samples().size();
                if (totalSamples > MAX_SAMPLES) {
                    throw new ReplayFormatException("vehicle data has too many samples");
                }
                out.writeFloat(segment.startTimeSeconds());
                out.writeFloat(segment.endTimeSeconds());
                out.writeByte(segment.seat());
                out.writeInt(segment.skinIndex());
                writeString(out, segment.scriptName(), ReplayVehicleSegment.MAX_SCRIPT_LENGTH);
                writeString(out, segment.modelFile(), ReplayVehicleSegment.MAX_MODEL_LENGTH);
                out.writeInt(segment.samples().size());
                for (int sampleIndex = 0; sampleIndex < segment.samples().size(); sampleIndex++) {
                    ReplayVehicleSample sample = segment.samples().get(sampleIndex);
                    if (sample == null) {
                        throw new ReplayFormatException("vehicle sample " + sampleIndex
                            + " is missing");
                    }
                    out.writeFloat(sample.timeSeconds());
                    out.writeFloat(sample.x());
                    out.writeFloat(sample.y());
                    out.writeFloat(sample.z());
                    out.writeFloat(sample.rotationX());
                    out.writeFloat(sample.rotationY());
                    out.writeFloat(sample.rotationZ());
                    out.writeFloat(sample.rotationW());
                }
            }
            out.flush();
        } catch (IOException exception) {
            throw new ReplayFormatException("vehicle data could not be encoded", exception);
        }
        int maximumBytes = 6 + MAX_SEGMENTS
            * (8 + 1 + 4 + 2 + ReplayVehicleSegment.MAX_SCRIPT_LENGTH
                + 2 + ReplayVehicleSegment.MAX_MODEL_LENGTH + 4)
            + MAX_SAMPLES * MAX_SAMPLE_BYTES;
        byte[] encoded = payload.toByteArray();
        if (encoded.length > maximumBytes) {
            throw new ReplayFormatException("vehicle data is too large");
        }
        return encoded;
    }

    public static Result decode(byte[] bytes) throws ReplayFormatException {
        if (bytes == null || bytes.length < 6) {
            throw new ReplayFormatException("vehicle data is corrupt or incomplete");
        }
        Set<String> warnings = new LinkedHashSet<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedShort();
            if (version != CURRENT_VERSION) {
                throw new ReplayFormatException(
                    "vehicle data version is unsupported: " + version);
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_SEGMENTS) {
                throw new ReplayFormatException("vehicle segment count is invalid: " + count);
            }
            List<ReplayVehicleSegment> segments = new ArrayList<>(count);
            int totalSamples = 0;
            for (int index = 0; index < count; index++) {
                if (in.available() < 8 + 1 + 4 + 2 + 2 + 4) {
                    throw new ReplayFormatException("vehicle data is corrupt or incomplete");
                }
                float start = in.readFloat();
                float end = in.readFloat();
                int seat = in.readUnsignedByte();
                int skinIndex = in.readInt();
                String scriptName = readString(in, ReplayVehicleSegment.MAX_SCRIPT_LENGTH);
                String modelFile = readString(in, ReplayVehicleSegment.MAX_MODEL_LENGTH);
                int sampleCount = in.readInt();
                if (sampleCount < 0 || sampleCount > MAX_SAMPLES
                    || in.available() < sampleCount * MAX_SAMPLE_BYTES) {
                    throw new ReplayFormatException("vehicle data is corrupt or incomplete");
                }
                List<ReplayVehicleSample> samples = new ArrayList<>(sampleCount);
                for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                    float time = in.readFloat();
                    float x = in.readFloat();
                    float y = in.readFloat();
                    float z = in.readFloat();
                    float rotationX = in.readFloat();
                    float rotationY = in.readFloat();
                    float rotationZ = in.readFloat();
                    float rotationW = in.readFloat();
                    if (!Float.isFinite(time) || !Float.isFinite(x) || !Float.isFinite(y)
                        || !Float.isFinite(z) || !Float.isFinite(rotationX)
                        || !Float.isFinite(rotationY) || !Float.isFinite(rotationZ)
                        || !Float.isFinite(rotationW)) {
                        addWarning(warnings, "invalid vehicle sample " + sampleIndex
                            + " was ignored");
                        continue;
                    }
                    samples.add(new ReplayVehicleSample(time, x, y, z, rotationX,
                        rotationY, rotationZ, rotationW));
                }
                totalSamples += sampleCount;
                if (totalSamples > MAX_SAMPLES) {
                    throw new ReplayFormatException("vehicle data has too many samples");
                }
                if (!Float.isFinite(start) || start < 0.0f || !Float.isFinite(end)
                    || end < start || scriptName.isBlank() || samples.isEmpty()) {
                    addWarning(warnings, "invalid vehicle segment " + index + " was ignored");
                    continue;
                }
                if (!samplesAreOrdered(samples, start, end)) {
                    addWarning(warnings, "vehicle segment " + index
                        + " has invalid sample times and was ignored");
                    continue;
                }
                segments.add(new ReplayVehicleSegment(start, end, scriptName, modelFile,
                    skinIndex, seat, samples));
            }
            if (in.available() > 0) {
                throw new ReplayFormatException("vehicle data is corrupt or incomplete");
            }
            return new Result(segments, List.copyOf(warnings));
        } catch (EOFException exception) {
            throw new ReplayFormatException("vehicle data is corrupt or incomplete", exception);
        } catch (IOException exception) {
            throw new ReplayFormatException("vehicle data is corrupt or incomplete", exception);
        }
    }

    private static boolean samplesAreOrdered(List<ReplayVehicleSample> samples,
        float start, float end) {
        float previous = Float.NEGATIVE_INFINITY;
        for (ReplayVehicleSample sample : samples) {
            if (sample.timeSeconds() < start - TIME_EPSILON
                || sample.timeSeconds() > end + TIME_EPSILON
                || sample.timeSeconds() <= previous) {
                return false;
            }
            previous = sample.timeSeconds();
        }
        return true;
    }

    private static void writeString(DataOutputStream out, String value, int maxLength)
        throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, maxLength);
        out.writeShort(length);
        out.write(bytes, 0, length);
    }

    private static String readString(DataInputStream in, int maxLength)
        throws IOException, ReplayFormatException {
        int length = in.readUnsignedShort();
        if (length > maxLength || in.available() < length) {
            throw new ReplayFormatException("vehicle data is corrupt or incomplete");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
