package com.gavrh.pzreplay;

import java.util.List;

public final class ReplayControlState {
    private boolean recording;
    private float recordingSeconds;
    private int recordedFrames;
    private double configuredSampleRateHz = CaptureSession.DEFAULT_SAMPLE_RATE_HZ;
    private double actualSampleRateHz;
    private String lastReplayPath = "";
    private String lastReplaySummary = "";
    private String captureError = "";

    private boolean playing;
    private double playbackTime;
    private double playbackDuration;
    private boolean cameraFollow;
    private String playbackError = "";
    private List<String> compatibilityWarnings = List.of();

    public synchronized void recordingStarted(double sampleRateHz) {
        recording = true;
        recordingSeconds = 0f;
        recordedFrames = 0;
        configuredSampleRateHz = sampleRateHz;
        actualSampleRateHz = 0.0;
        captureError = "";
    }

    public synchronized void recordingProgress(float seconds, int frames,
        double configuredRateHz, double actualRateHz) {
        recordingSeconds = seconds;
        recordedFrames = frames;
        configuredSampleRateHz = configuredRateHz;
        actualSampleRateHz = actualRateHz;
    }

    public synchronized void recordingStopped(String path, String summary, String error) {
        recording = false;
        if (path != null && !path.isBlank()) {
            lastReplayPath = path;
            lastReplaySummary = safe(summary);
        }
        if (error != null && !error.isBlank()) {
            captureError = safe(error);
        }
    }

    public synchronized void captureFailed(String error) {
        recording = false;
        captureError = safe(error);
    }

    public synchronized boolean playbackStarted(double duration) {
        if (recording || playing) {
            return false;
        }
        playing = true;
        playbackTime = 0.0;
        playbackDuration = Math.max(0.0, duration);
        cameraFollow = false;
        playbackError = "";
        compatibilityWarnings = List.of();
        return true;
    }

    public synchronized void playbackProgress(double time, double duration,
        boolean followCamera) {
        playbackTime = Math.max(0.0, time);
        playbackDuration = Math.max(0.0, duration);
        cameraFollow = followCamera;
    }

    public synchronized void playbackStopped() {
        playing = false;
        playbackTime = 0.0;
        playbackDuration = 0.0;
        cameraFollow = false;
    }

    public synchronized void playbackFailed(String error) {
        playing = false;
        playbackTime = 0.0;
        playbackDuration = 0.0;
        cameraFollow = false;
        playbackError = safe(error);
        compatibilityWarnings = List.of();
    }

    public synchronized void setCompatibilityWarnings(List<String> warnings) {
        compatibilityWarnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public synchronized void setCameraFollow(boolean enabled) {
        cameraFollow = enabled;
    }

    public synchronized void clearErrors() {
        captureError = "";
        playbackError = "";
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized float getRecordingSeconds() {
        return recordingSeconds;
    }

    public synchronized int getRecordedFrames() {
        return recordedFrames;
    }

    public synchronized double getConfiguredSampleRateHz() {
        return configuredSampleRateHz;
    }

    public synchronized double getActualSampleRateHz() {
        return actualSampleRateHz;
    }

    public synchronized String getLastReplayPath() {
        return lastReplayPath;
    }

    public synchronized String getLastReplaySummary() {
        return lastReplaySummary;
    }

    public synchronized String getCaptureError() {
        return captureError;
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized double getPlaybackTime() {
        return playbackTime;
    }

    public synchronized double getPlaybackDuration() {
        return playbackDuration;
    }

    public synchronized boolean isCameraFollow() {
        return cameraFollow;
    }

    public synchronized String getPlaybackError() {
        return playbackError;
    }

    public synchronized List<String> getCompatibilityWarnings() {
        return compatibilityWarnings;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
