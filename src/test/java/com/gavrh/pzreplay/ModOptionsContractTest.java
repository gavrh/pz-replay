package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModOptionsContractTest {
    private static final Path OPTIONS_LUA = Path.of("mod", "Contents", "mods", "pz-replay",
        "42", "media", "lua", "client", "PZReplayOptions.lua");
    private static final Pattern OPTION = Pattern.compile(
        "add(Slider|TickBox|KeyBind)\\(\"([^\"]+)\"");

    @Test
    void registersOnlyRepresentableSettingsWithUniqueIds() throws IOException {
        Map<String, String> options = options();

        assertEquals("slider", options.get("sample_rate_hz"));
        assertEquals("slider", options.get("countdown_seconds"));
        assertEquals("tickbox", options.get("camera_follow_default"));
        assertEquals("tickbox", options.get("debug_controls"));
        assertEquals("tickbox", options.get("kill_switch"));
        assertEquals(5, options.size());
    }

    @Test
    void keybindingsStayOutOfModOptions() throws IOException {
        Map<String, String> options = options();
        String lua = Files.readString(OPTIONS_LUA);

        assertFalse(options.values().contains("keybind"));
        assertFalse(lua.contains("addKeyBind"));
        for (String action : ReplaySettings.ACTIONS) {
            assertFalse(options.containsKey("key." + action));
        }
    }

    @Test
    void modOptionsIdMatchesTheModId() throws IOException {
        String lua = Files.readString(OPTIONS_LUA);

        assertTrue(lua.contains("M.MOD_OPTIONS_ID = \"pz-replay\""));
    }

    @Test
    void everyMappedSettingUsesTheJavaSettingsBridge() throws IOException {
        String lua = Files.readString(OPTIONS_LUA);

        assertTrue(lua.contains("getSampleRate"));
        assertTrue(lua.contains("setSampleRate"));
        assertTrue(lua.contains("getCountdownSeconds"));
        assertTrue(lua.contains("setCountdownSeconds"));
        assertTrue(lua.contains("isCameraFollowDefault"));
        assertTrue(lua.contains("setCameraFollowDefault"));
        assertTrue(lua.contains("isDebugControlsEnabled"));
        assertTrue(lua.contains("setDebugControlsEnabled"));
        assertTrue(lua.contains("isKillSwitchEnabled"));
        assertTrue(lua.contains("setKillSwitchEnabled"));
    }

    private static Map<String, String> options() throws IOException {
        String lua = Files.readString(OPTIONS_LUA);
        Map<String, String> options = new LinkedHashMap<>();
        Matcher matcher = OPTION.matcher(lua);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase();
            String id = matcher.group(2);
            assertFalse(options.containsKey(id), "duplicate ModOptions id " + id);
            options.put(id, type);
        }
        return options;
    }
}
