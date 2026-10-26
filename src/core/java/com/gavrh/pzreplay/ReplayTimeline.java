package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class ReplayTimeline {
    public enum State {
        STOPPED,
        PLAYING,
        PAUSED
    }

    public record Sample(
        double time,
        float x,
        float y,
        float z,
        float facing,
        ReplaySample.Locomotion locomotion,
        ReplaySample.Stance stance
    ) {
    }

    private final List<ReplaySample> frames;
    private final double[] relativeTimes;
    private final double duration;

    private State state = State.STOPPED;
    private double elapsed;

    public ReplayTimeline(ReplayData data) throws ReplayFormatException {
        this(data == null ? null : data.frames());
    }

    public ReplayTimeline(List<ReplaySample> frames) throws ReplayFormatException {
        if (frames == null) {
            throw new ReplayFormatException("replay data is missing");
        }
        List<ReplaySample> validated = new ArrayList<>(frames);
        if (validated.isEmpty()) {
            this.frames = List.of();
            this.relativeTimes = new double[0];
            duration = 0.0;
            return;
        }

        for (int index = 0; index < validated.size(); index++) {
            ReplaySample frame = validated.get(index);
            if (frame == null) {
                throw new ReplayFormatException("replay frame " + index + " is missing");
            }
            validateFrame(frame, index);
        }

        double firstSeconds = validated.get(0).timeSeconds();
        double[] times = new double[validated.size()];
        for (int index = 0; index < validated.size(); index++) {
            ReplaySample frame = validated.get(index);
            double relative = (double) frame.timeSeconds() - firstSeconds;
            if (relative < 0.0 || (index > 0 && relative <= times[index - 1])) {
                throw new ReplayFormatException("replay frame times are not increasing at frame "
                    + index);
            }
            times[index] = relative;
        }
        this.frames = List.copyOf(validated);
        this.relativeTimes = times;
        duration = times[times.length - 1];
    }

    public boolean start() {
        if (isEmpty() || state == State.PLAYING) {
            return false;
        }
        if (state == State.PAUSED) {
            state = State.PLAYING;
            return true;
        }
        elapsed = 0.0;
        state = State.PLAYING;
        return true;
    }

    public boolean pause() {
        if (state != State.PLAYING) {
            return false;
        }
        state = State.PAUSED;
        return true;
    }

    public void stop() {
        state = State.STOPPED;
        elapsed = 0.0;
    }

    public void reset() {
        state = State.STOPPED;
        elapsed = 0.0;
    }

    public boolean advance(double deltaSeconds) {
        if (state != State.PLAYING || !Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return state == State.PLAYING;
        }
        elapsed += deltaSeconds;
        if (elapsed >= duration) {
            elapsed = duration;
            state = State.STOPPED;
            return false;
        }
        return true;
    }

    public Sample sample() {
        if (isEmpty()) {
            throw new IllegalStateException("replay timeline is empty");
        }
        return sampleAt(elapsed);
    }

    public Sample sampleAt(double seconds) {
        if (isEmpty()) {
            throw new IllegalStateException("replay timeline is empty");
        }
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("sample time must be finite");
        }
        double target = Math.max(0.0, Math.min(seconds, duration));
        int index = lastFrameAtOrBefore(target);
        if (index < 0) {
            return sampleOf(0);
        }
        if (index >= frames.size() - 1 || target == relativeTimes[index]) {
            return sampleOf(index);
        }

        ReplaySample start = frames.get(index);
        ReplaySample end = frames.get(index + 1);
        double span = relativeTimes[index + 1] - relativeTimes[index];
        if (span <= 0.0) {
            return sampleOf(index);
        }
        double alpha = (target - relativeTimes[index]) / span;
        return new Sample(
            target,
            MotionMath.lerp(start.x(), end.x(), alpha),
            MotionMath.lerp(start.y(), end.y(), alpha),
            MotionMath.lerp(start.z(), end.z(), alpha),
            MotionMath.interpolateFacing(start.angle(), end.angle(), alpha),
            start.locomotion(),
            start.stance()
        );
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public boolean isPlaying() {
        return state == State.PLAYING;
    }

    public boolean isPaused() {
        return state == State.PAUSED;
    }

    public boolean isActive() {
        return state != State.STOPPED;
    }

    public State getState() {
        return state;
    }

    public double getCurrentTime() {
        return elapsed;
    }

    public double getDuration() {
        return duration;
    }

    public int getFrameCount() {
        return frames.size();
    }

    private int lastFrameAtOrBefore(double target) {
        int low = 0;
        int high = relativeTimes.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (relativeTimes[middle] <= target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low - 1;
    }

    private Sample sampleOf(int index) {
        ReplaySample frame = frames.get(index);
        return new Sample(
            relativeTimes[index],
            frame.x(),
            frame.y(),
            frame.z(),
            MotionMath.normalizeFacing(frame.angle()),
            frame.locomotion(),
            frame.stance()
        );
    }

    private static void validateFrame(ReplaySample frame, int index) throws ReplayFormatException {
        if (!Float.isFinite(frame.timeSeconds())
            || !Float.isFinite(frame.x())
            || !Float.isFinite(frame.y())
            || !Float.isFinite(frame.z())
            || !Float.isFinite(frame.angle())) {
            throw new ReplayFormatException("replay frame " + index + " has invalid values");
        }
        if (frame.locomotion() == null || frame.stance() == null) {
            throw new ReplayFormatException("replay frame " + index + " has invalid state");
        }
    }
}
