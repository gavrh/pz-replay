package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CaptureSession {
    public static final double DEFAULT_SAMPLE_RATE_HZ = 30.0;
    public static final double MIN_SAMPLE_RATE_HZ = 20.0;
    public static final double MAX_SAMPLE_RATE_HZ = 30.0;
    public static final int MAX_FRAMES = 54_000;
    public static final double MAX_DURATION_SECONDS = 30.0 * 60.0;
    public static final int ESTIMATED_BYTES_PER_FRAME = BinaryReplayCodec.RECORD_BYTES;
    public static final int MAX_EVENTS = ReplayEventCodec.MAX_EVENTS;
    static final long MAX_ACTIVE_WALL_DELTA_MILLIS = 250L;
    static final double EVENT_DEDUPE_SECONDS = 0.08;
    static final float TELEPORT_MIN_DISTANCE = 10.0f;
    static final float TELEPORT_MIN_SPEED = 40.0f;
    static final float VEHICLE_TELEPORT_MIN_DISTANCE = 64.0f;
    static final int MAX_GAP_WARNINGS = 8;

    public enum StopReason {
        NONE,
        REQUESTED,
        NO_FRAMES,
        MAX_FRAMES,
        MAX_DURATION,
        PLAYER_DEAD,
        PLAYER_UNAVAILABLE,
        LOADING
    }

    public record PlayerSnapshot(
        float x,
        float y,
        float z,
        float angle,
        float gameTimeSeconds,
        ReplaySample.Locomotion locomotion,
        ReplaySample.Stance stance,
        int flags
    ) {
        public PlayerSnapshot {
            locomotion = locomotion == null ? ReplaySample.Locomotion.IDLE : locomotion;
            stance = stance == null ? ReplaySample.Stance.STAND : stance;
        }
    }

    public record Summary(
        boolean recording,
        int frameCount,
        float durationSeconds,
        double configuredRateHz,
        double actualRateHz,
        long estimatedBytes,
        float pausedSeconds,
        float gapSeconds,
        StopReason stopReason
    ) {
        public String format() {
            String text = "frames=" + frameCount
                + " duration=" + String.format(Locale.ROOT, "%.2f", durationSeconds) + "s"
                + " rate=" + String.format(Locale.ROOT, "%.1f", configuredRateHz) + "Hz"
                + " actual=" + String.format(Locale.ROOT, "%.2f", actualRateHz) + "Hz"
                + " estimated_bytes=" + estimatedBytes
                + " paused=" + String.format(Locale.ROOT, "%.2f", pausedSeconds) + "s"
                + " gaps=" + String.format(Locale.ROOT, "%.2f", gapSeconds) + "s";
            if (stopReason == StopReason.NONE) {
                return text;
            }
            return text + " stop=" + stopReason.name().toLowerCase();
        }
    }

    private final double sampleRateHz;
    private final double sampleIntervalSeconds;
    private final int maxFrames;
    private final double maxDurationSeconds;
    private final List<ReplaySample> frames;
    private final List<ReplayEvent> events;
    private final List<GapDraft> gaps;
    private final List<String> warnings;

    private boolean recording;
    private boolean pausedLastTick;
    private GapDraft openGap;
    private long lastTickWallMillis;
    private double activeSeconds;
    private double pausedSeconds;
    private double gapSeconds;
    private double nextSampleSeconds;
    private double lastSampleSeconds;
    private float lastX;
    private float lastY;
    private float lastZ;
    private boolean hasLastPosition;
    private StopReason stopReason = StopReason.NONE;
    private Summary lastSummary;

    public CaptureSession() {
        this(DEFAULT_SAMPLE_RATE_HZ);
    }

    public CaptureSession(double sampleRateHz) {
        this(sampleRateHz, MAX_FRAMES, MAX_DURATION_SECONDS);
    }

    CaptureSession(double sampleRateHz, int maxFrames, double maxDurationSeconds) {
        if (!Double.isFinite(sampleRateHz)
            || sampleRateHz < MIN_SAMPLE_RATE_HZ
            || sampleRateHz > MAX_SAMPLE_RATE_HZ) {
            throw new IllegalArgumentException("sample rate must be between "
                + MIN_SAMPLE_RATE_HZ + " and " + MAX_SAMPLE_RATE_HZ + " Hz");
        }
        if (maxFrames < 1) {
            throw new IllegalArgumentException("max frames must be positive");
        }
        if (!Double.isFinite(maxDurationSeconds) || maxDurationSeconds <= 0.0) {
            throw new IllegalArgumentException("max duration must be positive");
        }
        this.sampleRateHz = sampleRateHz;
        this.sampleIntervalSeconds = 1.0 / sampleRateHz;
        this.maxFrames = maxFrames;
        this.maxDurationSeconds = maxDurationSeconds;
        this.frames = new ArrayList<>(Math.min(maxFrames, 4096));
        this.events = new ArrayList<>();
        this.gaps = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.lastSummary = summary(false);
    }

    public boolean start(long wallMillis) {
        if (recording) {
            return false;
        }
        frames.clear();
        events.clear();
        gaps.clear();
        warnings.clear();
        recording = true;
        pausedLastTick = false;
        openGap = null;
        lastTickWallMillis = wallMillis;
        activeSeconds = 0.0;
        pausedSeconds = 0.0;
        gapSeconds = 0.0;
        nextSampleSeconds = 0.0;
        lastSampleSeconds = 0.0;
        hasLastPosition = false;
        stopReason = StopReason.NONE;
        lastSummary = null;
        return true;
    }

    public void tick(long tick, long wallMillis, PlayerSnapshot player, boolean paused) {
        if (!recording) {
            return;
        }

        long wallDeltaMillis = Math.max(0L, wallMillis - lastTickWallMillis);
        lastTickWallMillis = wallMillis;
        long activeDeltaMillis = Math.min(wallDeltaMillis, MAX_ACTIVE_WALL_DELTA_MILLIS);
        if (wallDeltaMillis > activeDeltaMillis) {
            gapSeconds += (wallDeltaMillis - activeDeltaMillis) / 1000.0;
        }

        boolean wasPaused = pausedLastTick;
        pausedLastTick = paused;
        if (paused) {
            if (!wasPaused) {
                openGap(ReplayGap.Reason.RECORDING_PAUSE);
            }
            pausedSeconds += activeDeltaMillis / 1000.0;
            return;
        }

        activeSeconds += activeDeltaMillis / 1000.0;

        if (player == null) {
            stop(StopReason.PLAYER_UNAVAILABLE);
            return;
        }
        if (activeSeconds >= maxDurationSeconds) {
            stop(StopReason.MAX_DURATION);
            return;
        }
        if (frames.size() >= maxFrames) {
            stop(StopReason.MAX_FRAMES);
            return;
        }

        boolean discontinuity = false;
        if (hasLastPosition && openGap == null) {
            if (wallDeltaMillis > activeDeltaMillis) {
                openGap(ReplayGap.Reason.LONG_TICK);
                discontinuity = true;
            } else {
                discontinuity = openTeleportGap(player);
            }
        }
        if (discontinuity) {
            nextSampleSeconds = activeSeconds;
        }
        if (activeSeconds < nextSampleSeconds) {
            return;
        }

        while (nextSampleSeconds <= activeSeconds) {
            nextSampleSeconds += sampleIntervalSeconds;
        }

        float speed = speed(player);
        closeOpenGap((float) activeSeconds, player.x(), player.y(), player.z());
        frames.add(new ReplaySample(
            tick,
            (float) activeSeconds,
            player.gameTimeSeconds(),
            player.x(),
            player.y(),
            player.z(),
            player.angle(),
            speed,
            player.locomotion(),
            player.stance(),
            player.flags()
        ));

        lastSampleSeconds = activeSeconds;
        lastX = player.x();
        lastY = player.y();
        lastZ = player.z();
        hasLastPosition = true;

        if (frames.size() >= maxFrames) {
            stop(StopReason.MAX_FRAMES);
        }
    }

    public Summary stop(StopReason reason) {
        if (!recording) {
            return lastSummary == null ? summary(false) : lastSummary;
        }
        if (reason == StopReason.PLAYER_DEAD
            || reason == StopReason.PLAYER_UNAVAILABLE
            || reason == StopReason.LOADING) {
            openGap(gapReason(reason));
        }
        closeOpenGap((float) activeSeconds, lastX, lastY, lastZ);
        recording = false;
        if (reason == StopReason.REQUESTED && frames.isEmpty()) {
            reason = StopReason.NO_FRAMES;
        }
        stopReason = reason;
        lastSummary = summary(false);
        return lastSummary;
    }

    public Summary status() {
        return summary(recording);
    }

    public boolean isRecording() {
        return recording;
    }

    public Summary getLastSummary() {
        return lastSummary == null ? summary(false) : lastSummary;
    }

    public List<ReplaySample> frames() {
        return List.copyOf(frames);
    }

    public List<ReplayEvent> events() {
        return List.copyOf(events);
    }

    public List<ReplayGap> gaps() {
        List<ReplayGap> result = new ArrayList<>(gaps.size() + 1);
        for (GapDraft draft : gaps) {
            result.add(draft.toGap());
        }
        if (openGap != null) {
            result.add(openGap.toGap());
        }
        return List.copyOf(result);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public float currentTimeSeconds() {
        return (float) activeSeconds;
    }

    public boolean recordEvent(ReplayEvent.Kind kind, boolean end, String detail,
        float direction, float durationSeconds) {
        return recordEvent(kind, end, detail, direction, durationSeconds,
            ReplayActionContext.none());
    }

    public boolean recordEvent(ReplayEvent.Kind kind, boolean end, String detail,
        float direction, float durationSeconds, ReplayActionContext context) {
        if (!recording || kind == null) {
            return false;
        }
        if (events.size() >= MAX_EVENTS) {
            return false;
        }
        String safeDetail = detail == null ? "" : detail;
        if (safeDetail.length() > ReplayEvent.MAX_DETAIL_LENGTH) {
            safeDetail = safeDetail.substring(0, ReplayEvent.MAX_DETAIL_LENGTH);
        }
        ReplayActionContext safeContext =
            context == null ? ReplayActionContext.none() : context;
        if (!events.isEmpty()) {
            ReplayEvent previous = events.get(events.size() - 1);
            if (previous.kind() == kind
                && previous.end() == end
                && previous.detail().equals(safeDetail)
                && previous.context().equals(safeContext)
                && activeSeconds - previous.timeSeconds() <= EVENT_DEDUPE_SECONDS) {
                return false;
            }
        }
        events.add(new ReplayEvent(events.size() + 1L, (float) activeSeconds, kind, end,
            safeDetail, direction, durationSeconds, safeContext));
        return true;
    }

    private boolean openTeleportGap(PlayerSnapshot player) {
        double deltaSeconds = activeSeconds - lastSampleSeconds;
        if (deltaSeconds <= 0.0) {
            return false;
        }
        double distance = Math.sqrt(
            square(player.x() - lastX) + square(player.y() - lastY)
                + square(player.z() - lastZ));
        if (distance < TELEPORT_MIN_DISTANCE) {
            return false;
        }
        boolean inVehicle = (player.flags() & ReplaySample.FLAG_IN_VEHICLE) != 0;
        double speed = distance / deltaSeconds;
        if (inVehicle) {
            if (distance >= VEHICLE_TELEPORT_MIN_DISTANCE) {
                openGap(ReplayGap.Reason.TELEPORT);
                return true;
            }
        } else if (speed >= TELEPORT_MIN_SPEED) {
            openGap(ReplayGap.Reason.TELEPORT);
            return true;
        }
        recordWarning("unclassified discontinuity distance="
            + formatNumber(distance) + " speed=" + formatNumber(speed)
            + " vehicle=" + inVehicle);
        return false;
    }

    private void openGap(ReplayGap.Reason reason) {
        if (openGap != null || !hasLastPosition || reason == null) {
            return;
        }
        openGap = new GapDraft((float) lastSampleSeconds, reason, lastX, lastY, lastZ);
    }

    private void closeOpenGap(float endSeconds, float nextX, float nextY, float nextZ) {
        if (openGap == null) {
            return;
        }
        openGap.close(endSeconds, nextX, nextY, nextZ);
        gaps.add(openGap);
        openGap = null;
    }

    private void recordWarning(String warning) {
        if (warnings.size() < MAX_GAP_WARNINGS) {
            warnings.add(warning);
        }
    }

    private static ReplayGap.Reason gapReason(StopReason reason) {
        if (reason == StopReason.PLAYER_DEAD) {
            return ReplayGap.Reason.DEATH;
        }
        if (reason == StopReason.LOADING) {
            return ReplayGap.Reason.LOADING;
        }
        return ReplayGap.Reason.PLAYER_UNAVAILABLE;
    }

    private static double square(float value) {
        return value * value;
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static final class GapDraft {
        private final float startTimeSeconds;
        private final ReplayGap.Reason reason;
        private final float lastX;
        private final float lastY;
        private final float lastZ;
        private float endTimeSeconds;
        private float nextX;
        private float nextY;
        private float nextZ;

        private GapDraft(float startTimeSeconds, ReplayGap.Reason reason,
            float lastX, float lastY, float lastZ) {
            this.startTimeSeconds = startTimeSeconds;
            this.endTimeSeconds = startTimeSeconds;
            this.reason = reason;
            this.lastX = lastX;
            this.lastY = lastY;
            this.lastZ = lastZ;
            this.nextX = lastX;
            this.nextY = lastY;
            this.nextZ = lastZ;
        }

        private void close(float endSeconds, float nextX, float nextY, float nextZ) {
            this.endTimeSeconds = Math.max(startTimeSeconds, endSeconds);
            this.nextX = nextX;
            this.nextY = nextY;
            this.nextZ = nextZ;
        }

        private ReplayGap toGap() {
            return new ReplayGap(startTimeSeconds, endTimeSeconds, reason,
                lastX, lastY, lastZ, nextX, nextY, nextZ);
        }
    }

    private float speed(PlayerSnapshot player) {
        if (!hasLastPosition) {
            return 0f;
        }
        double deltaSeconds = activeSeconds - lastSampleSeconds;
        if (deltaSeconds <= 0.0) {
            return 0f;
        }
        float dx = player.x() - lastX;
        float dy = player.y() - lastY;
        float dz = player.z() - lastZ;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) (distance / deltaSeconds);
    }

    private Summary summary(boolean isRecording) {
        double actualRate = activeSeconds > 0.0 ? frames.size() / activeSeconds : 0.0;
        return new Summary(
            isRecording,
            frames.size(),
            (float) activeSeconds,
            sampleRateHz,
            actualRate,
            (long) frames.size() * ESTIMATED_BYTES_PER_FRAME,
            (float) pausedSeconds,
            (float) gapSeconds,
            isRecording ? StopReason.NONE : stopReason
        );
    }
}
