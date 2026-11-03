package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class VehicleTimeline {
    public record State(
        VehicleSegment segment,
        double time,
        float x,
        float y,
        float z,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW
    ) {
    }

    private static final double TIME_EPSILON = 0.0001;
    private static final double MAX_SEGMENT_TAIL_SECONDS = 0.25;
    private static final int MAX_WARNINGS = 8;

    private final List<VehicleSegment> segments;
    private final List<String> warnings;
    private final double duration;

    public VehicleTimeline(List<VehicleSegment> segments, double duration)
        throws ReplayFormatException {
        this(segments, duration, List.of());
    }

    public VehicleTimeline(List<VehicleSegment> segments, double duration,
        List<String> extraWarnings) throws ReplayFormatException {
        if (!Double.isFinite(duration) || duration < 0.0) {
            throw new ReplayFormatException("replay duration is invalid");
        }
        this.duration = duration;
        Set<String> collectedWarnings = new LinkedHashSet<>();
        if (extraWarnings != null) {
            for (String warning : extraWarnings) {
                if (warning != null && !warning.isBlank()) {
                    addWarning(collectedWarnings, warning);
                }
            }
        }
        List<VehicleSegment> valid = new ArrayList<>();
        if (segments != null) {
            if (segments.size() > VehicleCodec.MAX_SEGMENTS) {
                throw new ReplayFormatException(
                    "too many vehicle segments: " + segments.size());
            }
            for (int index = 0; index < segments.size(); index++) {
                VehicleSegment segment = segments.get(index);
                if (segment == null) {
                    addWarning(collectedWarnings,
                        "vehicle segment " + index + " is missing and was ignored");
                    continue;
                }
                if (!segment.isValid()) {
                    addWarning(collectedWarnings,
                        "vehicle segment " + index + " is incomplete and was ignored");
                    continue;
                }
                if (!samplesAreOrdered(segment.samples(), segment.startTimeSeconds(),
                    segment.endTimeSeconds())) {
                    addWarning(collectedWarnings,
                        "vehicle segment " + index
                            + " has invalid sample times and was ignored");
                    continue;
                }
                if (segment.startTimeSeconds() > duration + MAX_SEGMENT_TAIL_SECONDS) {
                    addWarning(collectedWarnings,
                        "vehicle segment outside the replay duration was ignored");
                    continue;
                }
                double start = Math.max(0.0, segment.startTimeSeconds());
                double end = Math.min(duration, segment.endTimeSeconds());
                if (end < start) {
                    addWarning(collectedWarnings,
                        "vehicle segment " + index + " has invalid timing and was ignored");
                    continue;
                }
                VehicleSegment clamped = new VehicleSegment((float) start,
                    (float) end, segment.scriptName(), segment.modelFile(),
                    segment.skinIndex(), segment.seat(), segment.samples());
                valid.add(clamped);
            }
        }
        valid.sort(Comparator.comparingDouble(VehicleSegment::startTimeSeconds));
        this.segments = List.copyOf(valid);
        this.warnings = List.copyOf(collectedWarnings);
    }

    public List<VehicleSegment> segments() {
        return segments;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public int getSampleCount() {
        int total = 0;
        for (VehicleSegment segment : segments) {
            total += segment.samples().size();
        }
        return total;
    }

    public State vehicleAt(double time) {
        if (segments.isEmpty() || !Double.isFinite(time)) {
            return null;
        }
        double target = Math.max(0.0, Math.min(time, duration));
        int index = lastSegmentStartingAtOrBefore(target);
        if (index < 0) {
            return null;
        }
        VehicleSegment segment = segments.get(index);
        if (target > segment.endTimeSeconds() + TIME_EPSILON) {
            return null;
        }
        return interpolate(segment, Math.min(target, segment.endTimeSeconds()));
    }

    public VehicleSegment segmentAt(double time) {
        State state = vehicleAt(time);
        return state == null ? null : state.segment();
    }

    private State interpolate(VehicleSegment segment, double target) {
        List<VehicleSample> samples = segment.samples();
        if (samples.isEmpty()) {
            return null;
        }
        int index = lastSampleAtOrBefore(samples, target);
        if (index < 0) {
            index = 0;
        }
        VehicleSample start = samples.get(index);
        if (index >= samples.size() - 1 || target <= start.timeSeconds()) {
            return stateOf(segment, target, start);
        }
        VehicleSample end = samples.get(index + 1);
        double span = end.timeSeconds() - start.timeSeconds();
        if (span <= 0.0) {
            return stateOf(segment, target, start);
        }
        float alpha = (float) ((target - start.timeSeconds()) / span);
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        float[] rotation = interpolateRotation(start, end, alpha);
        return new State(segment, target,
            lerp(start.x(), end.x(), alpha),
            lerp(start.y(), end.y(), alpha),
            lerp(start.z(), end.z(), alpha),
            rotation[0], rotation[1], rotation[2], rotation[3]);
    }

    private static State stateOf(VehicleSegment segment, double target,
        VehicleSample sample) {
        return new State(segment, target, sample.x(), sample.y(), sample.z(),
            sample.rotationX(), sample.rotationY(), sample.rotationZ(),
            sample.rotationW());
    }

    private static float[] interpolateRotation(VehicleSample start,
        VehicleSample end, float alpha) {
        float ax = start.rotationX();
        float ay = start.rotationY();
        float az = start.rotationZ();
        float aw = start.rotationW();
        float bx = end.rotationX();
        float by = end.rotationY();
        float bz = end.rotationZ();
        float bw = end.rotationW();
        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0.0f) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        if (dot > 0.9995f) {
            return normalize(lerp(ax, bx, alpha), lerp(ay, by, alpha),
                lerp(az, bz, alpha), lerp(aw, bw, alpha));
        }
        double theta = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double sinTheta = Math.sin(theta);
        if (sinTheta < 1.0e-6) {
            return normalize(ax, ay, az, aw);
        }
        double weightStart = Math.sin((1.0 - alpha) * theta) / sinTheta;
        double weightEnd = Math.sin(alpha * theta) / sinTheta;
        return normalize((float) (ax * weightStart + bx * weightEnd),
            (float) (ay * weightStart + by * weightEnd),
            (float) (az * weightStart + bz * weightEnd),
            (float) (aw * weightStart + bw * weightEnd));
    }

    private static float[] normalize(float x, float y, float z, float w) {
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length < 1.0e-6) {
            return new float[] { 0.0f, 0.0f, 0.0f, 1.0f };
        }
        return new float[] { (float) (x / length), (float) (y / length),
            (float) (z / length), (float) (w / length) };
    }

    private static float lerp(float start, float end, float alpha) {
        return start + (end - start) * alpha;
    }

    private int lastSegmentStartingAtOrBefore(double target) {
        int low = 0;
        int high = segments.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).startTimeSeconds() <= target + TIME_EPSILON) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low - 1;
    }

    private static int lastSampleAtOrBefore(List<VehicleSample> samples, double target) {
        int low = 0;
        int high = samples.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (samples.get(middle).timeSeconds() <= target + TIME_EPSILON) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low - 1;
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }

    private static boolean samplesAreOrdered(List<VehicleSample> samples,
        float start, float end) {
        float previous = Float.NEGATIVE_INFINITY;
        for (VehicleSample sample : samples) {
            if (sample == null
                || !Float.isFinite(sample.timeSeconds())
                || sample.timeSeconds() < start - TIME_EPSILON
                || sample.timeSeconds() > end + TIME_EPSILON
                || sample.timeSeconds() <= previous) {
                return false;
            }
            previous = sample.timeSeconds();
        }
        return true;
    }
}
