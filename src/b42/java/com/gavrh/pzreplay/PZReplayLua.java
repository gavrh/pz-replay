package com.gavrh.pzreplay;

import java.util.List;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.input.GameKeyboard;

@Exposer.LuaClass(name = "PZReplay")
public final class PZReplayLua {
    private static final ReplayControlState STATE = new ReplayControlState();

    public PZReplayLua() {
    }

    @LuaMethod(name = "getPZReplayVersion", global = true)
    public static String getVersion() {
        return BuildInfo.VERSION;
    }

    public static String version() {
        return BuildInfo.VERSION;
    }

    public static boolean startRecording(String name) {
        ReplaySettings settings = ReplaySettingsStore.get();
        if (settings.isKillSwitch()) {
            failCapture("kill switch is enabled");
            return false;
        }
        if (ReplayPlaybackApi.isPlaying()) {
            failCapture("stop playback before recording");
            return false;
        }
        if (ReplayRecorder.INSTANCE.isRecording()) {
            return false;
        }

        boolean started = ReplayRecorder.INSTANCE.start(name, settings.getSampleRate());
        if (started) {
            STATE.recordingStarted(settings.getSampleRate());
        } else {
            String error = ReplayRecorder.INSTANCE.getLastError();
            failCapture(error.isBlank() ? "capture could not start" : error);
        }
        return started;
    }

    public static boolean stopRecording() {
        boolean stopped = ReplayRecorder.INSTANCE.stop();
        if (stopped) {
            finishCapture();
        }
        return stopped;
    }

    public static boolean stopRecordingOnDeath() {
        boolean stopped = ReplayRecorder.INSTANCE.stop(CaptureSession.StopReason.PLAYER_DEAD);
        if (stopped) {
            finishCapture();
        }
        return stopped;
    }

    public static void tick() {
        ReplayRecorder.INSTANCE.tick();
        syncRecordingState();
        ReplayPlaybackApi.tick();
    }

    public static void recordTick() {
        tick();
    }

    public static boolean isRecording() {
        return ReplayRecorder.INSTANCE.isRecording();
    }

    public static boolean isDebugEnabled() {
        return Boolean.getBoolean("pzreplay.debug")
            || ReplaySettingsStore.get().isDebugControls();
    }

    public static boolean isLuaKeyboardEnabled() {
        return GameKeyboard.doLuaKeyPressed;
    }

    public static String getStatus() {
        return ReplayRecorder.INSTANCE.getStatus();
    }

    public static String getLastReplayPath() {
        return ReplayRecorder.INSTANCE.getLastReplayPath();
    }

    public static String getLastReplaySummary() {
        return ReplayRecorder.INSTANCE.getLastReplaySummary();
    }

    public static float getRecordingSeconds() {
        return STATE.getRecordingSeconds();
    }

    public static int getRecordedFrames() {
        return STATE.getRecordedFrames();
    }

    public static double getConfiguredSampleRate() {
        return STATE.getConfiguredSampleRateHz();
    }

    public static double getActualSampleRate() {
        return STATE.getActualSampleRateHz();
    }

    public static String getCaptureError() {
        return STATE.getCaptureError();
    }

    public static double getSampleRate() {
        return ReplaySettingsStore.get().getSampleRate();
    }

    public static boolean setSampleRate(double sampleRateHz) {
        if (!ReplaySettingsStore.get().setSampleRate(sampleRateHz)) {
            return false;
        }
        return ReplaySettingsStore.save();
    }

    public static boolean isCameraFollowDefault() {
        return ReplaySettingsStore.get().isCameraFollowDefault();
    }

    public static boolean setCameraFollowDefault(boolean enabled) {
        ReplaySettingsStore.get().setCameraFollowDefault(enabled);
        return ReplaySettingsStore.save();
    }

    public static boolean isDebugControlsEnabled() {
        return ReplaySettingsStore.get().isDebugControls();
    }

    public static boolean setDebugControlsEnabled(boolean enabled) {
        ReplaySettingsStore.get().setDebugControls(enabled);
        return ReplaySettingsStore.save();
    }

    public static boolean isKillSwitchEnabled() {
        return ReplaySettingsStore.get().isKillSwitch();
    }

    public static boolean setKillSwitchEnabled(boolean enabled) {
        ReplaySettings settings = ReplaySettingsStore.get();
        if (settings.isKillSwitch() == enabled) {
            return true;
        }
        if (enabled) {
            if (ReplayRecorder.INSTANCE.isRecording()) {
                stopRecording();
            }
            ReplayPlaybackApi.stop();
            ReplayGhostApi.despawn();
            STATE.clearErrors();
        }
        settings.setKillSwitch(enabled);
        STATE.clearErrors();
        return ReplaySettingsStore.save();
    }

    public static int getKeyBinding(String action) {
        return ReplaySettingsStore.get().key(action);
    }

    public static String getKeyBindingName(String action) {
        return ReplayKeybinds.keyName(ReplaySettingsStore.get().key(action));
    }

    public static String checkKeyBinding(String action, int key) {
        return ReplayKeybinds.check(action, key);
    }

    public static boolean setKeyBinding(String action, int key) {
        if (!ReplaySettingsStore.get().bind(action, key)) {
            return false;
        }
        return ReplaySettingsStore.save();
    }

    public static boolean resetKeyBindings() {
        ReplaySettingsStore.get().resetKeys();
        return ReplaySettingsStore.save();
    }

    public static int getKeyBindingWarningCount() {
        return ReplayKeybinds.warnings().size();
    }

    public static String getKeyBindingWarning(int index) {
        List<String> warnings = ReplayKeybinds.warnings();
        if (index < 0 || index >= warnings.size()) {
            return "";
        }
        return warnings.get(index);
    }

    public static String getSettingsPath() {
        return ReplayStorage.settingsPath().toString();
    }

    static ReplayControlState state() {
        return STATE;
    }

    private static void syncRecordingState() {
        if (ReplayRecorder.INSTANCE.isRecording()) {
            CaptureSession.Summary summary = ReplayRecorder.INSTANCE.getSummary();
            STATE.recordingProgress(summary.durationSeconds(), summary.frameCount(),
                summary.configuredRateHz(), summary.actualRateHz());
        } else if (STATE.isRecording()) {
            finishCapture();
        }
    }

    private static void finishCapture() {
        CaptureSession.Summary summary = ReplayRecorder.INSTANCE.getSummary();
        String error = ReplayRecorder.INSTANCE.getLastError();
        if (error.isBlank() && summary.stopReason() == CaptureSession.StopReason.NO_FRAMES) {
            error = "no frames";
        }
        STATE.recordingStopped(ReplayRecorder.INSTANCE.getLastReplayPath(),
            ReplayRecorder.INSTANCE.getLastReplaySummary(), error);
    }

    private static void failCapture(String message) {
        STATE.captureFailed(message);
        System.out.println("[pz-replay] capture unavailable: " + message);
    }
}
