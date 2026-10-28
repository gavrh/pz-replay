package com.gavrh.pzreplay;

import java.util.List;

public final class ReplayControlState {
    private boolean recording;
    private boolean countingDown;
    private double countdownRemainingSeconds;
    private int countdownDurationSeconds;
    private String recordingName = "";
    private String recordingResult = "";
    private long recordingResultVersion;
    private float recordingSeconds;
    private int recordedFrames;
    private double configuredSampleRateHz = CaptureSession.DEFAULT_SAMPLE_RATE_HZ;
    private double actualSampleRateHz;
    private String lastReplayPath = "";
    private String lastReplaySummary = "";
    private String captureError = "";

    private boolean playing;
    private boolean playbackPaused;
    private double playbackSpeed = 1.0;
    private double playbackTime;
    private double playbackDuration;
    private boolean cameraFollow;
    private String playbackError = "";
    private List<String> compatibilityWarnings = List.of();

    public synchronized void countdownStarted(int durationSeconds, String name) {
        countingDown = true;
        countdownDurationSeconds = durationSeconds;
        countdownRemainingSeconds = durationSeconds;
        recordingName = safe(name);
        recordingResult = "";
        recordingResultVersion++;
    }

    public synchronized void countdownProgress(double remainingSeconds) {
        countdownRemainingSeconds = Math.max(0.0, remainingSeconds);
    }

    public synchronized void countdownFinished() {
        countingDown = false;
        countdownRemainingSeconds = 0.0;
    }

    public synchronized void countdownCancelled() {
        countingDown = false;
        countdownRemainingSeconds = 0.0;
        recordingName = "";
        recordingResult = "Countdown cancelled.";
        recordingResultVersion++;
    }

    public synchronized void recordingStarted(double sampleRateHz) {
        recordingStarted(sampleRateHz, "");
    }

    public synchronized void recordingStarted(double sampleRateHz, String name) {
        countingDown = false;
        countdownRemainingSeconds = 0.0;
        recording = true;
        recordingSeconds = 0f;
        recordedFrames = 0;
        configuredSampleRateHz = sampleRateHz;
        actualSampleRateHz = 0.0;
        recordingName = safe(name);
        recordingResult = "";
        recordingResultVersion++;
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
        if (path != null && !path.isBlank()) {
            recordingResult = "Saved " + fileName(path);
        } else if (error != null && !error.isBlank()) {
            recordingResult = "Recording failed: " + captureError;
        } else {
            recordingResult = "Recording stopped.";
        }
        recordingResultVersion++;
    }

    public synchronized void captureFailed(String error) {
        recording = false;
        countingDown = false;
        countdownRemainingSeconds = 0.0;
        captureError = safe(error);
        recordingResult = "Recording failed: " + captureError;
        recordingResultVersion++;
    }

    public synchronized boolean playbackStarted(double duration) {
        if (recording || playing) {
            return false;
        }
        playing = true;
        playbackPaused = false;
        playbackSpeed = 1.0;
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

    public synchronized void setPlaybackPaused(boolean paused) {
        playbackPaused = paused;
    }

    public synchronized void setPlaybackSpeed(double speed) {
        if (Double.isFinite(speed) && speed > 0.0) {
            playbackSpeed = speed;
        }
    }

    public synchronized void playbackStopped() {
        playing = false;
        playbackPaused = false;
        playbackSpeed = 1.0;
        playbackTime = 0.0;
        playbackDuration = 0.0;
        cameraFollow = false;
    }

    public synchronized void playbackFailed(String error) {
        playing = false;
        playbackPaused = false;
        playbackSpeed = 1.0;
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

    public synchronized boolean isCountingDown() {
        return countingDown;
    }

    public synchronized double getCountdownRemainingSeconds() {
        return countdownRemainingSeconds;
    }

    public synchronized int getCountdownDurationSeconds() {
        return countdownDurationSeconds;
    }

    public synchronized String getRecordingName() {
        return recordingName;
    }

    public synchronized String getRecordingResult() {
        return recordingResult;
    }

    public synchronized long getRecordingResultVersion() {
        return recordingResultVersion;
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

    public synchronized boolean isPlaybackPaused() {
        return playbackPaused;
    }

    public synchronized double getPlaybackSpeed() {
        return playbackSpeed;
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

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
