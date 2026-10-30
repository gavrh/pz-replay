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

    public enum StopReason {
        NONE,
        REQUESTED,
        NO_FRAMES,
        MAX_FRAMES,
        MAX_DURATION,
        PLAYER_DEAD,
        PLAYER_UNAVAILABLE
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

    private boolean recording;
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
        this.lastSummary = summary(false);
    }

    public boolean start(long wallMillis) {
        if (recording) {
            return false;
        }
        frames.clear();
        events.clear();
        recording = true;
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

        if (paused) {
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
        if (activeSeconds < nextSampleSeconds) {
            return;
        }

        while (nextSampleSeconds <= activeSeconds) {
            nextSampleSeconds += sampleIntervalSeconds;
        }

        float speed = speed(player);
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

    public float currentTimeSeconds() {
        return (float) activeSeconds;
    }

    public boolean recordEvent(ReplayEvent.Kind kind, boolean end, String detail,
        float direction, float durationSeconds) {
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
        if (!events.isEmpty()) {
            ReplayEvent previous = events.get(events.size() - 1);
            if (previous.kind() == kind
                && previous.end() == end
                && previous.detail().equals(safeDetail)
                && activeSeconds - previous.timeSeconds() <= EVENT_DEDUPE_SECONDS) {
                return false;
            }
        }
        events.add(new ReplayEvent(events.size() + 1L, (float) activeSeconds, kind, end,
            safeDetail, direction, durationSeconds));
        return true;
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
