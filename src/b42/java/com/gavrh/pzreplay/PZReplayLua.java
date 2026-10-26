package com.gavrh.pzreplay;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;

@Exposer.LuaClass(name = "PZReplay")
public final class PZReplayLua {
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
        return ReplayRecorder.INSTANCE.start(name);
    }

    public static boolean stopRecording() {
        return ReplayRecorder.INSTANCE.stop();
    }

    public static boolean stopRecordingOnDeath() {
        return ReplayRecorder.INSTANCE.stop(CaptureSession.StopReason.PLAYER_DEAD);
    }

    public static void recordTick() {
        ReplayRecorder.INSTANCE.tick();
    }

    public static boolean isRecording() {
        return ReplayRecorder.INSTANCE.isRecording();
    }

    public static String getStatus() {
        return ReplayRecorder.INSTANCE.getStatus();
    }
}
