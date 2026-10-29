package com.gavrh.pzreplay;

import java.util.List;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.input.GameKeyboard;

@Exposer.LuaClass(name = "PZReplay")
public final class PZReplayLua {
    private static final ReplayControlState STATE = new ReplayControlState();
    private static final RecordingCountdown COUNTDOWN = new RecordingCountdown();
    private static String countdownName = "";
    private static boolean countdownPaused;

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
        if (COUNTDOWN.isRunning() || ReplayRecorder.INSTANCE.isRecording()) {
            return false;
        }
        String blocker = startBlocker(settings);
        if (blocker != null) {
            failCapture(blocker);
            return false;
        }

        String resolvedName = resolveRecordingName(name);
        if (resolvedName.isEmpty()) {
            failCapture("recording name is unavailable");
            return false;
        }
        int countdownSeconds = settings.getCountdownSeconds();
        if (countdownSeconds == 0) {
            return startCapture(resolvedName, settings);
        }
        if (!COUNTDOWN.start(countdownSeconds, System.currentTimeMillis())) {
            return false;
        }
        countdownName = resolvedName;
        countdownPaused = false;
        STATE.countdownStarted(countdownSeconds, resolvedName);
        System.out.println("[pz-replay] countdown started name=" + resolvedName
            + " seconds=" + countdownSeconds);
        return true;
    }

    public static boolean stopRecording() {
        if (COUNTDOWN.isRunning()) {
            return cancelCountdownInternal("requested");
        }
        boolean stopped = ReplayRecorder.INSTANCE.stop();
        if (stopped) {
            finishCapture();
        }
        return stopped;
    }

    public static boolean stopRecordingOnDeath() {
        if (COUNTDOWN.isRunning()) {
            return cancelCountdownInternal("player died");
        }
        boolean stopped = ReplayRecorder.INSTANCE.stop(CaptureSession.StopReason.PLAYER_DEAD);
        if (stopped) {
            finishCapture();
        }
        return stopped;
    }

    public static boolean cancelCountdown() {
        return cancelCountdownInternal("requested");
    }

    public static boolean isCountingDown() {
        return COUNTDOWN.isRunning();
    }

    public static double getCountdownRemainingSeconds() {
        return STATE.getCountdownRemainingSeconds();
    }

    public static int getCountdownDurationSeconds() {
        return STATE.getCountdownDurationSeconds();
    }

    public static String getRecordingName() {
        return COUNTDOWN.isRunning() ? countdownName : ReplayRecorder.INSTANCE.getCaptureName();
    }

    public static String getLastRecordingResult() {
        return STATE.getRecordingResult();
    }

    public static long getRecordingResultVersion() {
        return STATE.getRecordingResultVersion();
    }

    public static void tick() {
        tickCountdown();
        ReplayRecorder.INSTANCE.tick();
        syncRecordingState();
        ReplayPlaybackApi.tick();
    }

    public static void countdownTick() {
        tickCountdown();
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

    public static int getCountdownSeconds() {
        return ReplaySettingsStore.get().getCountdownSeconds();
    }

    public static boolean setCountdownSeconds(int seconds) {
        if (!ReplaySettingsStore.get().setCountdownSeconds(seconds)) {
            return false;
        }
        return ReplaySettingsStore.save();
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
            cancelCountdownInternal("kill switch is enabled");
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

    public static boolean hasKeyBindingConflict(String action) {
        return ReplayKeybinds.hasConflict(action);
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

    private static void tickCountdown() {
        if (!COUNTDOWN.isRunning()) {
            return;
        }
        ReplaySettings settings = ReplaySettingsStore.get();
        if (settings.isKillSwitch()) {
            cancelCountdownInternal("kill switch is enabled");
            return;
        }
        boolean paused = GameTime.isGamePaused();
        if (paused != countdownPaused) {
            countdownPaused = paused;
            System.out.println("[pz-replay] countdown " + (paused ? "paused" : "resumed"));
        }
        boolean finished = COUNTDOWN.tick(System.currentTimeMillis(), paused);
        if (!finished) {
            STATE.countdownProgress(COUNTDOWN.getRemainingSeconds());
            return;
        }

        String name = countdownName;
        countdownName = "";
        countdownPaused = false;
        STATE.countdownFinished();
        if (ReplayRecorder.INSTANCE.isRecording()) {
            failCapture("recording is already active");
            return;
        }
        String blocker = startBlocker(settings);
        if (blocker != null) {
            failCapture(blocker);
            return;
        }
        System.out.println("[pz-replay] countdown finished name=" + name);
        startCapture(name, settings);
    }

    private static String startBlocker(ReplaySettings settings) {
        if (settings.isKillSwitch()) {
            return "kill switch is enabled";
        }
        if (ReplayPlaybackApi.isPlaying()) {
            return "stop playback before recording";
        }
        return playerBlocker();
    }

    private static String playerBlocker() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return "no local player is loaded";
        }
        if (player instanceof ReplayGhost) {
            return "ghost is the global player";
        }
        if (!player.isLocalPlayer()) {
            return "no local player is loaded";
        }
        if (player.isDead()) {
            return "player is dead";
        }
        return null;
    }

    private static String resolveRecordingName(String name) {
        String base = RecordingNames.resolve(name, CaptureMetadataFactory.newCaptureId());
        return RecordingNames.unique(ReplayStorage.directory(), base);
    }

    private static boolean startCapture(String name, ReplaySettings settings) {
        boolean started = ReplayRecorder.INSTANCE.start(name, settings.getSampleRate());
        if (started) {
            STATE.recordingStarted(settings.getSampleRate(),
                ReplayRecorder.INSTANCE.getCaptureName());
        } else {
            String error = ReplayRecorder.INSTANCE.getLastError();
            failCapture(error.isBlank() ? "capture could not start" : error);
        }
        return started;
    }

    private static boolean cancelCountdownInternal(String reason) {
        if (!COUNTDOWN.isRunning()) {
            return false;
        }
        String name = countdownName;
        COUNTDOWN.cancel();
        countdownName = "";
        countdownPaused = false;
        STATE.countdownCancelled();
        System.out.println("[pz-replay] countdown cancelled name=" + name
            + " reason=" + reason + " path=none frames=0");
        return true;
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
        STATE.recordingStopped(ReplayRecorder.INSTANCE.getCaptureResultPath(),
            ReplayRecorder.INSTANCE.getCaptureResultSummary(), error);
    }

    private static void failCapture(String message) {
        STATE.captureFailed(message);
        System.out.println("[pz-replay] capture unavailable: " + message);
    }
}
