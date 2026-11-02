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
        ReplaySample.Stance stance,
        boolean moving,
        boolean aiming,
        boolean inVehicle,
        ReplayEquipment equipment
    ) {
        public Sample {
            equipment = equipment == null ? ReplayEquipment.none() : equipment;
        }
    }

    public record Movement(float x, float y) {
        public Movement {
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                x = 0.0f;
                y = 0.0f;
            }
        }

        public boolean isMoving() {
            return x != 0.0f || y != 0.0f;
        }
    }

    private static final double MOVEMENT_WINDOW_SECONDS = 0.1;
    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 4.0;

    private final List<ReplaySample> frames;
    private final double[] relativeTimes;
    private final double duration;
    private final ReplayEquipment equipment;
    private final ReplayEventTimeline eventTimeline;
    private final List<String> eventWarnings;
    private final ReplayAppearanceTimeline appearanceTimeline;
    private final ReplayVehicleTimeline vehicleTimeline;
    private final ReplayGapTimeline gapTimeline;
    private final List<GapHold> gapHolds;

    private record GapHold(float holdStart, float holdEnd, int frameIndex, ReplayGap gap) {
    }

    private State state = State.STOPPED;
    private double elapsed;
    private double speed = 1.0;

    public ReplayTimeline(ReplayData data) throws ReplayFormatException {
        this(data == null ? null : data.frames(),
            data == null || data.metadata() == null
                ? ReplayAppearance.none() : data.metadata().appearance(),
            data == null || data.metadata() == null
                ? ReplayAppearanceChanges.none() : data.metadata().appearanceChanges(),
            data == null || data.metadata() == null
                ? ReplayEquipment.none() : data.metadata().equipment(),
            data == null || data.metadata() == null
                ? ReplayEvents.none() : data.metadata().events(),
            data == null || data.metadata() == null
                ? ReplayVehicles.none() : data.metadata().vehicles(),
            data == null || data.metadata() == null
                ? ReplayGaps.none() : data.metadata().gaps());
    }

    public ReplayTimeline(List<ReplaySample> frames) throws ReplayFormatException {
        this(frames, ReplayAppearance.none(), ReplayAppearanceChanges.none(),
            ReplayEquipment.none(), ReplayEvents.none(), ReplayVehicles.none(),
            ReplayGaps.none());
    }

    private ReplayTimeline(List<ReplaySample> frames, ReplayAppearance appearance,
        ReplayAppearanceChanges appearanceChanges, ReplayEquipment equipment,
        ReplayEvents events, ReplayVehicles vehicles, ReplayGaps gaps)
        throws ReplayFormatException {
        if (frames == null) {
            throw new ReplayFormatException("replay data is missing");
        }
        this.equipment = equipment == null ? ReplayEquipment.none() : equipment;
        List<ReplaySample> validated = new ArrayList<>(frames);
        if (validated.isEmpty()) {
            this.frames = List.of();
            this.relativeTimes = new double[0];
            duration = 0.0;
            EventDecode decoded = decodeEvents(events, duration);
            this.eventTimeline = decoded.timeline();
            this.eventWarnings = decoded.warnings();
            this.appearanceTimeline = decodeAppearances(appearance, appearanceChanges,
                duration);
            this.vehicleTimeline = decodeVehicles(vehicles, duration);
            this.gapTimeline = decodeGaps(gaps, duration, 0.0);
            this.gapHolds = List.of();
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
        EventDecode decoded = decodeEvents(events, duration);
        this.eventTimeline = decoded.timeline();
        this.eventWarnings = decoded.warnings();
        this.appearanceTimeline = decodeAppearances(appearance, appearanceChanges, duration);
        this.vehicleTimeline = decodeVehicles(vehicles, duration);
        this.gapTimeline = decodeGaps(gaps, duration, firstSeconds);
        this.gapHolds = buildGapHolds(gapTimeline);
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

    public boolean resume() {
        if (isEmpty() || state == State.PLAYING) {
            return false;
        }
        if (elapsed >= duration) {
            elapsed = 0.0;
        }
        state = State.PLAYING;
        return true;
    }

    public boolean seek(double seconds) {
        if (isEmpty() || !Double.isFinite(seconds)) {
            return false;
        }
        elapsed = Math.max(0.0, Math.min(seconds, duration));
        return true;
    }

    public boolean restart() {
        if (isEmpty()) {
            return false;
        }
        elapsed = 0.0;
        if (state != State.PAUSED) {
            state = State.PLAYING;
        }
        return true;
    }

    public boolean setSpeed(double multiplier) {
        if (!isValidSpeed(multiplier)) {
            return false;
        }
        speed = multiplier;
        return true;
    }

    public static boolean isValidSpeed(double multiplier) {
        return Double.isFinite(multiplier)
            && multiplier >= MIN_SPEED
            && multiplier <= MAX_SPEED;
    }

    public void stop() {
        state = State.STOPPED;
        elapsed = 0.0;
        speed = 1.0;
    }

    public void reset() {
        state = State.STOPPED;
        elapsed = 0.0;
        speed = 1.0;
    }

    public boolean advance(double deltaSeconds) {
        if (state != State.PLAYING || !Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return state == State.PLAYING;
        }
        elapsed += deltaSeconds * speed;
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
        GapHold hold = gapHoldAt(target);
        if (hold != null) {
            return sampleOf(hold.frameIndex());
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
            start.stance(),
            start.moving(),
            start.aiming(),
            start.inVehicle(),
            equipment
        );
    }

    public Movement movementAt(double seconds) {
        if (isEmpty() || !Double.isFinite(seconds) || duration <= 0.0) {
            return new Movement(0.0f, 0.0f);
        }
        double target = Math.max(0.0, Math.min(seconds, duration));
        double window = Math.min(MOVEMENT_WINDOW_SECONDS, duration);
        boolean crossesGap = false;
        for (GapHold hold : gapHolds) {
            if (target - window < hold.holdEnd() && target + window > hold.holdStart()) {
                crossesGap = true;
                break;
            }
        }
        if (isGapHold(target) || crossesGap) {
            return new Movement(0.0f, 0.0f);
        }
            || isGapHold(target + window)) {
            return new Movement(0.0f, 0.0f);
        }
        Sample before = sampleAt(Math.max(0.0, target - window));
        Sample after = sampleAt(Math.min(duration, target + window));
        float dx = after.x() - before.x();
        float dy = after.y() - before.y();
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0e-5) {
            return new Movement(0.0f, 0.0f);
        }
        return new Movement((float) (dx / length), (float) (dy / length));
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

    public double getSpeed() {
        return speed;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public ReplayEquipment getEquipment() {
        return equipment;
    }

    public ReplayAppearanceTimeline getAppearanceTimeline() {
        return appearanceTimeline;
    }

    public ReplayAppearance getInitialAppearance() {
        return appearanceTimeline.initialAppearance();
    }

    public ReplayAppearance appearanceAt(double seconds) {
        return appearanceTimeline.appearanceAt(effectiveGapTime(seconds));
    }

    public int getAppearanceChangeCount() {
        return appearanceTimeline.getChangeCount();
    }

    public List<String> getAppearanceWarnings() {
        return appearanceTimeline.warnings();
    }

    public ReplayEventTimeline getEventTimeline() {
        return eventTimeline;
    }

    public List<ReplayEvent> getEvents() {
        return eventTimeline.events();
    }

    public int getEventCount() {
        return eventTimeline.getEventCount();
    }

    public List<String> getEventWarnings() {
        return eventWarnings;
    }

    public ReplayVehicleTimeline getVehicleTimeline() {
        return vehicleTimeline;
    }

    public ReplayVehicleTimeline.State vehicleAt(double seconds) {
        if (isGapHold(seconds)) {
            return null;
        }
        return vehicleTimeline == null ? null : vehicleTimeline.vehicleAt(seconds);
    }

    public int getVehicleCount() {
        return vehicleTimeline == null ? 0 : vehicleTimeline.getSegmentCount();
    }

    public List<String> getVehicleWarnings() {
        return vehicleTimeline == null ? List.of() : vehicleTimeline.warnings();
    }

    public ReplayGapTimeline getGapTimeline() {
        return gapTimeline;
    }

    public List<ReplayGap> getGaps() {
        return gapTimeline == null ? List.of() : gapTimeline.gaps();
    }

    public int getGapCount() {
        return gapTimeline == null ? 0 : gapTimeline.getGapCount();
    }

    public List<String> getGapWarnings() {
        return gapTimeline == null ? List.of() : gapTimeline.warnings();
    }

    public boolean isGapHold(double seconds) {
        return gapHoldAt(seconds) != null;
    }

    public ReplayGap.Reason gapReasonAt(double seconds) {
        GapHold hold = gapHoldAt(seconds);
        return hold == null ? null : hold.gap().reason();
    }

    private static ReplayGapTimeline decodeGaps(ReplayGaps gaps, double duration,
        double offsetSeconds) {
        List<ReplayGap> decoded = List.of();
        List<String> warnings = new ArrayList<>();
        if (gaps != null && gaps.isPresent()) {
            try {
                ReplayGapCodec.Result result = gaps.decode();
                List<ReplayGap> shifted = new ArrayList<>(result.gaps().size());
                for (ReplayGap gap : result.gaps()) {
                    shifted.add(new ReplayGap(
                        (float) (gap.startTimeSeconds() - offsetSeconds),
                        (float) (gap.endTimeSeconds() - offsetSeconds),
                        gap.reason(), gap.lastX(), gap.lastY(), gap.lastZ(), gap.nextX(),
                        gap.nextY(), gap.nextZ()));
                }
                decoded = shifted;
                warnings.addAll(result.warnings());
            } catch (ReplayFormatException exception) {
                List<String> gapWarnings = gaps.warnings();
                if (gapWarnings.isEmpty()) {
                    warnings.add("timeline gap data is corrupt or incomplete");
                } else {
                    warnings.addAll(gapWarnings);
                }
            }
        }
        try {
            return new ReplayGapTimeline(decoded, duration, warnings);
        } catch (ReplayFormatException exception) {
            return null;
        }
    }

    private List<GapHold> buildGapHolds(ReplayGapTimeline timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return List.of();
        }
        List<GapHold> holds = new ArrayList<>();
        for (ReplayGap gap : timeline.gaps()) {
            double start = clampRelative(gap.startTimeSeconds());
            double end = clampRelative(gap.endTimeSeconds());
            int index = lastFrameAtOrBefore(start);
            if (index < 0) {
                index = 0;
            }
            int destination = firstFrameAtOrAfter(end);
            double holdEnd = destination < relativeTimes.length
                ? relativeTimes[destination] : duration;
            if (holdEnd <= relativeTimes[index]) {
                continue;
            }
            holds.add(new GapHold((float) relativeTimes[index], (float) holdEnd, index, gap));
        }
        return List.copyOf(holds);
    }

    private double clampRelative(double seconds) {
        return Math.max(0.0, Math.min(seconds, duration));
    }

    private GapHold gapHoldAt(double seconds) {
        if (gapHolds.isEmpty() || !Double.isFinite(seconds)) {
            return null;
        }
        double target = Math.max(0.0, Math.min(seconds, duration));
        for (GapHold hold : gapHolds) {
            if (target > hold.holdStart() && target < hold.holdEnd()) {
                return hold;
            }
        }
        return null;
    }

    private double effectiveGapTime(double seconds) {
        GapHold hold = gapHoldAt(seconds);
        return hold == null ? seconds : hold.holdStart();
    }

    private record EventDecode(ReplayEventTimeline timeline, List<String> warnings) {
    }

    private static ReplayVehicleTimeline decodeVehicles(ReplayVehicles vehicles,
        double duration) {
        List<ReplayVehicleSegment> decoded = List.of();
        List<String> warnings = new ArrayList<>();
        if (vehicles != null && vehicles.isPresent()) {
            try {
                ReplayVehicleCodec.Result result = vehicles.decode();
                decoded = result.segments();
                warnings.addAll(result.warnings());
            } catch (ReplayFormatException exception) {
                List<String> vehicleWarnings = vehicles.warnings();
                if (vehicleWarnings.isEmpty()) {
                    warnings.add("vehicle data is corrupt or incomplete");
                } else {
                    warnings.addAll(vehicleWarnings);
                }
            }
        }
        try {
            return new ReplayVehicleTimeline(decoded, duration, warnings);
        } catch (ReplayFormatException exception) {
            return null;
        }
    }

    private static ReplayAppearanceTimeline decodeAppearances(ReplayAppearance appearance,
        ReplayAppearanceChanges changes, double duration) {
        List<ReplayAppearanceChange> decoded = List.of();
        List<String> warnings = new ArrayList<>();
        if (changes != null && changes.isPresent()) {
            try {
                ReplayAppearanceChangeCodec.Result result = changes.decode();
                decoded = result.changes();
                warnings.addAll(result.warnings());
            } catch (ReplayFormatException exception) {
                List<String> changeWarnings = changes.warnings();
                if (changeWarnings.isEmpty()) {
                    warnings.add("appearance change data is corrupt or incomplete");
                } else {
                    warnings.addAll(changeWarnings);
                }
            }
        }
        try {
            return new ReplayAppearanceTimeline(appearance, decoded, duration, warnings);
        } catch (ReplayFormatException exception) {
            warnings.add("appearance change data is corrupt or incomplete");
            try {
                return new ReplayAppearanceTimeline(appearance, List.of(), duration, warnings);
            } catch (ReplayFormatException ignored) {
                return null;
            }
        }
    }

    private static EventDecode decodeEvents(ReplayEvents events, double duration) {
        List<ReplayEvent> decoded = List.of();
        List<String> warnings = new ArrayList<>();
        if (events != null && events.isPresent()) {
            try {
                ReplayEventCodec.Result result = events.decode();
                decoded = result.events();
                warnings.addAll(result.warnings());
            } catch (ReplayFormatException exception) {
                warnings.add(exception.getMessage());
            }
        }
        try {
            ReplayEventTimeline timeline = new ReplayEventTimeline(decoded, duration);
            warnings.addAll(timeline.warnings());
            return new EventDecode(timeline, List.copyOf(warnings));
        } catch (ReplayFormatException exception) {
            warnings.add("action event data is corrupt or incomplete");
            try {
                return new EventDecode(new ReplayEventTimeline(List.of(), duration),
                    List.copyOf(warnings));
            } catch (ReplayFormatException ignored) {
                return new EventDecode(null, List.copyOf(warnings));
            }
        }
    }

    private int firstFrameAtOrAfter(double target) {
        int low = 0;
        int high = relativeTimes.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (relativeTimes[middle] < target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
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
            frame.stance(),
            frame.moving(),
            frame.aiming(),
            frame.inVehicle(),
            equipment
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
