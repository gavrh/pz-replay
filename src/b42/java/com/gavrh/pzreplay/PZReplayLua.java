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
}
