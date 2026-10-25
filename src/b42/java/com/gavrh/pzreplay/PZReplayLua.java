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

    public static void recordTick(double numberTicks) {
        ReplayRecorder.INSTANCE.tick((long) numberTicks);
    }

    public static String getStatus() {
        return ReplayRecorder.INSTANCE.getStatus();
    }

    public static String getLastReplayPath() {
        return ReplayRecorder.INSTANCE.getLastReplayPath();
    }
}
