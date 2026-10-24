package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModDescriptorTest {
    private static final Path MOD_ROOT =
        Path.of("mod", "Contents", "mods", "pz-replay");

    @Test
    void rootDescriptorDeclaresLoadableJavaMod() throws IOException {
        assertDescriptor(MOD_ROOT.resolve("mod.info"));
    }

    @Test
    void versionDescriptorDeclaresLoadableJavaMod() throws IOException {
        assertDescriptor(MOD_ROOT.resolve("42/mod.info"));
    }

    private static void assertDescriptor(Path descriptor) throws IOException {
        assertTrue(Files.isRegularFile(descriptor), "mod.info must exist: " + descriptor);

        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(descriptor)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                values.put(trimmed.substring(0, separator).trim(),
                    trimmed.substring(separator + 1).trim());
            }
        }

        assertEquals("pz-replay", values.get("id"));
        assertEquals("\\ZombieBuddy", values.get("require"));
        assertEquals("media/java/PZReplay.jar", values.get("javaJarFile"));
        assertEquals("com.gavrh.pzreplay", values.get("javaPkgName"));
        assertEquals("Replay mod for Project Zomboid", values.get("description"));
        assertTrue(values.containsKey("name"), "mod.info must declare a name");

        assertTrue(Files.isRegularFile(MOD_ROOT.resolve("42/media/lua/client/PZReplay.lua")),
            "Lua entry point must exist");
        assertTrue(Files.isDirectory(MOD_ROOT.resolve("42/media/java")),
            "staged jar directory must exist");
    }
}
