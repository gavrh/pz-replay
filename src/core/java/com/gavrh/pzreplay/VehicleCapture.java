package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class VehicleCapture {
    public static final double SAMPLE_INTERVAL_SECONDS = 0.1;
    public static final double HEARTBEAT_SECONDS = 1.0;
    public static final float POSITION_EPSILON = 0.005f;
    public static final float ROTATION_EPSILON = 0.00005f;

    public record VehicleSnapshot(
        float x,
        float y,
        float z,
        float rotationX,
        float rotationY,
        float rotationZ,
        float rotationW,
        String scriptName,
        String modelFile,
        int skinIndex,
        int seat
    ) {
        public VehicleSnapshot {
            scriptName = scriptName == null ? "" : scriptName;
            modelFile = modelFile == null ? "" : modelFile;
        }

        public String key() {
            return scriptName + "|" + modelFile + "|" + skinIndex + "|" + seat;
        }
    }

    private final List<VehicleSegment> segments = new ArrayList<>();
    private final List<VehicleSample> currentSamples = new ArrayList<>();
    private VehicleSnapshot currentSnapshot;
    private VehicleSnapshot lastSample;
    private float currentStart;
    private float lastSampleTime;
    private int totalSamples;
    private boolean dropped;
    private boolean skipped;
    private String warning = "";

    public void reset() {
        segments.clear();
        currentSamples.clear();
        currentSnapshot = null;
        lastSample = null;
        currentStart = 0.0f;
        lastSampleTime = 0.0f;
        totalSamples = 0;
        dropped = false;
        skipped = false;
        warning = "";
    }

    public boolean record(float timeSeconds, VehicleSnapshot snapshot) {
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            return false;
        }
        if (dropped) {
            return false;
        }
        if (snapshot == null) {
            return exit(timeSeconds);
        }
        if (snapshot.scriptName().isBlank()) {
            skipped = true;
            if (warning.isEmpty()) {
                warning = "vehicle has no script name";
            }
            return exit(timeSeconds);
        }
        if (totalSamples >= VehicleCodec.MAX_SAMPLES) {
            dropped = true;
            warning = "vehicle data sample limit reached";
            return exit(timeSeconds);
        }
        boolean changed = false;
        if (currentSnapshot == null || !currentSnapshot.key().equals(snapshot.key())) {
            changed = startSegment(timeSeconds, snapshot);
        } else {
            currentSnapshot = snapshot;
        }
        float minimumTime = lastSampleTime + (float) SAMPLE_INTERVAL_SECONDS;
        VehicleSample sample = new VehicleSample(timeSeconds, snapshot.x(),
            snapshot.y(), snapshot.z(), snapshot.rotationX(), snapshot.rotationY(),
            snapshot.rotationZ(), snapshot.rotationW());
        if (lastSample == null || timeSeconds >= minimumTime) {
            if (lastSample == null || moved(lastSample, snapshot)
                || timeSeconds - lastSampleTime >= (float) HEARTBEAT_SECONDS) {
                currentSamples.add(sample);
                lastSample = snapshot;
                lastSampleTime = timeSeconds;
                totalSamples++;
                return true;
            }
        }
        return changed;
    }

    public boolean exit(float timeSeconds) {
        if (currentSnapshot == null) {
            return false;
        }
        float safeTime = Math.max(timeSeconds, currentStart);
        segments.add(new VehicleSegment(currentStart, safeTime,
            currentSnapshot.scriptName(), currentSnapshot.modelFile(),
            currentSnapshot.skinIndex(), currentSnapshot.seat(), currentSamples));
        currentSamples.clear();
        currentSnapshot = null;
        lastSample = null;
        lastSampleTime = 0.0f;
        return true;
    }

    public void finish(float timeSeconds) {
        exit(timeSeconds);
    }

    public boolean isRecording() {
        return currentSnapshot != null;
    }

    public boolean isDropped() {
        return dropped;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public String getWarning() {
        return warning;
    }

    public int getSegmentCount() {
        return segments.size() + (currentSnapshot == null ? 0 : 1);
    }

    public int getSampleCount() {
        return totalSamples;
    }

    public Vehicles encode() {
        int activeSamples = currentSnapshot == null ? 0 : currentSamples.size();
        if (segments.isEmpty() && activeSamples == 0) {
            return Vehicles.none();
        }
        List<VehicleSegment> encoded = new ArrayList<>(segments.size() + 1);
        encoded.addAll(segments);
        if (currentSnapshot != null) {
            encoded.add(new VehicleSegment(currentStart, lastSampleTime,
                currentSnapshot.scriptName(), currentSnapshot.modelFile(),
                currentSnapshot.skinIndex(), currentSnapshot.seat(), currentSamples));
        }
        try {
            return Vehicles.encode(encoded);
        } catch (ReplayFormatException exception) {
            warning = "vehicle data could not be written: " + exception.getMessage();
            return Vehicles.none();
        }
    }

    private boolean startSegment(float timeSeconds, VehicleSnapshot snapshot) {
        exit(timeSeconds);
        currentSnapshot = snapshot;
        currentStart = timeSeconds;
        lastSample = null;
        lastSampleTime = timeSeconds;
        return true;
    }

    private static boolean moved(VehicleSnapshot previous, VehicleSnapshot next) {
        if (Math.abs(previous.x() - next.x()) > POSITION_EPSILON
            || Math.abs(previous.y() - next.y()) > POSITION_EPSILON
            || Math.abs(previous.z() - next.z()) > POSITION_EPSILON) {
            return true;
        }
        float dot = previous.rotationX() * next.rotationX()
            + previous.rotationY() * next.rotationY()
            + previous.rotationZ() * next.rotationZ()
            + previous.rotationW() * next.rotationW();
        return Math.abs(1.0f - Math.abs(dot)) > ROTATION_EPSILON;
    }
}
