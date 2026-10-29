package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizationCoverageTest {
    private static final Path CLIENT_LUA =
        Path.of("mod", "Contents", "mods", "pz-replay", "42", "media", "lua", "client");
    private static final Path PRIMARY_TRANSLATION =
        Path.of("mod", "Contents", "mods", "pz-replay", "42", "media", "lua", "shared",
            "Translate", "EN", "UI.json");
    private static final Pattern KEY =
        Pattern.compile("UI_pzreplay_[a-z0-9_]+");
    private static final Pattern JSON_ENTRY = Pattern.compile(
        "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void primaryTranslationCoversEveryKeyUsedByTheMod() throws IOException {
        Map<String, String> translations = readTranslations();
        Set<String> used = usedKeys();

        List<String> missing = new ArrayList<>();
        for (String key : used) {
            if (!translations.containsKey(key)) {
                missing.add(key);
            }
        }

        assertTrue(missing.isEmpty(), "missing translation keys: " + missing);
    }

    @Test
    void primaryTranslationHasNoBlankOrUnusedEntries() throws IOException {
        Map<String, String> translations = readTranslations();
        Set<String> used = usedKeys();

        List<String> blank = new ArrayList<>();
        List<String> unused = new ArrayList<>();
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            if (entry.getValue().isBlank() || entry.getValue().equals(entry.getKey())) {
                blank.add(entry.getKey());
            }
            if (!used.contains(entry.getKey())) {
                unused.add(entry.getKey());
            }
        }

        assertTrue(blank.isEmpty(), "blank translation values: " + blank);
        assertTrue(unused.isEmpty(), "unused translation keys: " + unused);
    }

    @Test
    void translationValuesUseStringPlaceholdersOnly() throws IOException {
        Map<String, String> translations = readTranslations();
        Pattern placeholder = Pattern.compile("%.");

        assertEquals("%s", onlyPlaceholder(translations.get("UI_pzreplay_common_error")));
        assertEquals("%s", onlyPlaceholder(
            translations.get("UI_pzreplay_load_started_warning_many")));
        assertEquals("(%s favorite)", translations.get(
            "UI_pzreplay_browser_favorite_suffix_one"));
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            Matcher matcher = placeholder.matcher(entry.getValue());
            while (matcher.find()) {
                assertEquals("%s", matcher.group(),
                    entry.getKey() + " uses a non-string placeholder");
            }
        }
    }

    @Test
    void translationFallbackModuleHandlesMissingKeys() throws Exception {
        Assumptions.assumeTrue(commandAvailable("lua"), "lua interpreter is unavailable");
        Path module = CLIENT_LUA.resolve("PZReplayText.lua").toAbsolutePath();
        Path script = Files.createTempFile("pzreplay-text-test", ".lua");
        try {
            Files.writeString(script, String.join("\n",
                "getText = function(key)",
                "  if key == 'UI_pzreplay_panel_title' then return 'TITRE' end",
                "  if key == 'UI_pzreplay_empty' then return '' end",
                "  if key == 'UI_pzreplay_keyed' then return key end",
                "  return nil",
                "end",
                "dofile([[" + module + "]])",
                "local T = PZReplayText",
                "local function check(name, actual, expected)",
                "  if actual ~= expected then",
                "    io.stderr:write(name .. ' expected [' .. expected .. '] got [' "
                    + ".. actual .. ']\\n')",
                "    os.exit(1)",
                "  end",
                "end",
                "check('localized', T.get('UI_pzreplay_panel_title', 'Fallback'), 'TITRE')",
                "check('missing', T.get('UI_pzreplay_missing', 'Fallback'), 'Fallback')",
                "check('empty', T.get('UI_pzreplay_empty', 'Fallback'), 'Fallback')",
                "check('key', T.get('UI_pzreplay_keyed', 'Fallback'), 'Fallback')",
                "check('format', T.format('UI_pzreplay_missing', 'Hello %s', 'x'), "
                    + "'Hello x')",
                "check('saved', T.message('Saved run.pzrp.gz'), 'Saved run.pzrp.gz')",
                "check('camera', T.message('Player'), 'Player')",
                "check('detail', T.detailLabel('Name'), 'Name')",
                "print('ok')",
                ""), StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("lua", script.toString())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "lua test timed out");
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("ok"), output);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private static boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "-v")
                .redirectErrorStream(true)
                .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }

    private static String onlyPlaceholder(String value) {
        assertTrue(value != null, "translation value must exist");
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = Pattern.compile("%[a-z]").matcher(value);
        while (matcher.find()) {
            placeholders.add(matcher.group());
        }
        assertEquals(1, placeholders.size(), "unexpected placeholders in " + value);
        return placeholders.get(0);
    }

    private static Set<String> usedKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(CLIENT_LUA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".lua")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    keys.add(matcher.group());
                }
            }
        }
        return keys;
    }

    private static Map<String, String> readTranslations() throws IOException {
        String json = Files.readString(PRIMARY_TRANSLATION);
        Map<String, String> translations = new LinkedHashMap<>();
        Matcher matcher = JSON_ENTRY.matcher(json);
        while (matcher.find()) {
            String key = unescape(matcher.group(1));
            String value = unescape(matcher.group(2));
            assertFalse(translations.containsKey(key), "duplicate translation key " + key);
            translations.put(key, value);
        }
        return translations;
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                default -> result.append(next);
            }
        }
        return result.toString();
    }
}
