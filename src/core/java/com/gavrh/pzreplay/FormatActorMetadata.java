package com.gavrh.pzreplay;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class FormatActorMetadata {
    static final int MAX_BYTES = 262_144;

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_.]{0,95}");
    private static final List<String> KEY_ORDER = List.of(
        "appearance_version", "appearance_data",
        "appearance_changes_version", "appearance_changes_data",
        "equipment_version", "equipment_data",
        "equipment_changes_version", "equipment_changes_data",
        "events_version", "events_data",
        "vehicles_version", "vehicles_data",
        "gaps_version", "gaps_data"
    );
    private static final Set<String> KNOWN_KEYS = Set.copyOf(KEY_ORDER);

    record Result(Map<String, String> values, List<String> warnings) {
        Result {
            values = Map.copyOf(values);
            warnings = List.copyOf(warnings);
        }
    }

    private FormatActorMetadata() {
    }

    static byte[] encode(ReplayMetadata metadata) {
        Map<String, String> values = metadata.toValues();
        StringBuilder builder = new StringBuilder();
        for (String key : KEY_ORDER) {
            String value = values.get(key);
            if (value != null) {
                builder.append(key).append('=')
                    .append(FormatManifest.normalizeValue(value)).append('\n');
            }
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Result parse(byte[] bytes) throws FormatException {
        if (bytes.length > MAX_BYTES) {
            throw new FormatException(FormatReason.SECTION_TABLE,
                "actor metadata exceeds the cap");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                "actor metadata is not valid UTF-8", exception);
        }
        if (text.startsWith("\uFEFF") || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
            throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                "actor metadata contains a forbidden character");
        }

        Map<String, String> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            boolean terminated = lineEnd >= 0;
            if (!terminated) {
                lineEnd = text.length();
            }
            if (lineEnd == lineStart) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "actor metadata contains a blank line");
            }
            String line = text.substring(lineStart, lineEnd);
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "actor metadata line is not key=value");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!KEY_PATTERN.matcher(key).matches() || !printable(value)) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "actor metadata line is not valid: " + key);
            }
            if (values.containsKey(key)) {
                throw new FormatException(FormatReason.MANIFEST_DUPLICATE_KEY,
                    "actor metadata key is duplicated: " + key);
            }
            values.put(key, value);
            if (!KNOWN_KEYS.contains(key)) {
                warnings.add("unknown actor metadata key: " + key);
            }
            lineStart = terminated ? lineEnd + 1 : lineEnd;
        }
        return new Result(values, warnings);
    }

    private static boolean printable(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character >= 0x7F && character <= 0x9F) {
                return false;
            }
        }
        return true;
    }
}
