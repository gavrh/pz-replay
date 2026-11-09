package com.gavrh.pzreplay;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class FormatManifest {
    static final int MAX_BYTES = 65_536;
    static final int MAX_KEYS = 512;
    static final int MAX_LINE_BYTES = 1_024;

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_.]{0,95}");
    private static final Pattern ACTOR_ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CREATED_BY_PATTERN = Pattern.compile("[A-Za-z0-9._+-]{1,32}");

    private static final Set<String> KNOWN_KEYS = Set.of(
        "manifest_version", "min_reader_format", "replay_id", "created_by", "game_version",
        "source_save_name", "source_save_mode", "source_player_name", "source_map_name",
        "source_map_version", "source_world_version", "recorded_at_ms", "start_time_ms",
        "end_time_ms", "actor_sample_rate_hz", "actor_count", "world_mode",
        "world_padding_tiles", "world_sample_rate_hz", "world_coverage_status",
        "world_coverage_squares", "world_coverage_objects", "world_log_changes",
        "world_log_bytes", "world_base_bytes", "world_base_format", "world_log_format",
        "world_first_sample", "world_index_format", "mods_count", "mods_version",
        "mods_section");

    private static final Set<String> ACTOR_FIELDS = Set.of(
        "id", "role", "display_name", "track_section", "known_coverage", "frame_count",
        "meta_section");

    record Actor(
        String id,
        String role,
        String displayName,
        String trackSection,
        String knownCoverage,
        long frameCount,
        String metaSection
    ) {
    }

    int manifestVersion;
    int minReaderFormat;
    String replayId;
    String createdBy;
    String gameVersion;
    String sourceSaveName;
    String sourceSaveMode;
    String sourcePlayerName;
    String sourceMapName;
    String sourceMapVersion;
    String sourceWorldVersion;
    long recordedAtMillis;
    long startTimeMillis;
    long endTimeMillis;
    int actorSampleRateHz;
    List<Actor> actors;
    String worldMode;
    int worldPaddingTiles;
    int worldSampleRateHz;
    String worldCoverageStatus;
    long worldCoverageSquares;
    long worldCoverageObjects;
    long worldLogChanges;
    long worldLogBytes;
    long worldBaseBytes;
    String worldBaseFormat;
    String worldLogFormat;
    String worldFirstSample;
    String worldIndexFormat;
    int modsCount;
    String modsVersion;
    String modsSection;
    String worldIssue;
    List<String> warnings;

    private FormatManifest() {
    }

    static FormatManifest fromMetadata(ReplayMetadata metadata, int frameCount,
        boolean actorMetaPresent, int modsCount, String modsVersion, boolean modsPresent) {
        return fromMetadata(metadata, frameCount, actorMetaPresent, modsCount, modsVersion,
            modsPresent, (WorldPackage) null);
    }

    static FormatManifest fromMetadata(ReplayMetadata metadata, int frameCount,
        boolean actorMetaPresent, int modsCount, String modsVersion, boolean modsPresent,
        WorldPackage world) {
        FormatManifest manifest = base(metadata, frameCount, actorMetaPresent, modsCount,
            modsVersion, modsPresent);
        if (world != null && world.baseBytes() > 0L) {
            applyWorld(manifest, world.baseBytes(), world.logBytes(), world.paddingTiles(),
                world.sampleRateHz(), world.coverageStatus(), world.coverageSquares(),
                world.coverageObjects(), world.logChanges(), world.worldVersion());
        }
        return manifest;
    }

    static FormatManifest fromMetadata(ReplayMetadata metadata, int frameCount,
        boolean actorMetaPresent, int modsCount, String modsVersion, boolean modsPresent,
        WorldPackageStaged world) {
        FormatManifest manifest = base(metadata, frameCount, actorMetaPresent, modsCount,
            modsVersion, modsPresent);
        if (world != null && world.baseBytes() > 0L) {
            applyWorld(manifest, world.baseBytes(), world.logBytes(), world.paddingTiles(),
                world.sampleRateHz(), world.coverageStatus(), world.coverageSquares(),
                world.coverageObjects(), world.logChanges(), world.worldVersion());
        }
        return manifest;
    }

    private static FormatManifest base(ReplayMetadata metadata, int frameCount,
        boolean actorMetaPresent, int modsCount, String modsVersion, boolean modsPresent) {
        FormatManifest manifest = new FormatManifest();
        manifest.manifestVersion = 1;
        manifest.minReaderFormat = 1;
        manifest.replayId = replayId(metadata);
        manifest.createdBy = valueOr(metadata.modVersion(), "unknown");
        manifest.gameVersion = valueOr(metadata.gameVersion(), "unknown");
        manifest.sourceSaveName = metadata.saveName();
        manifest.sourceSaveMode = valueOr(metadata.gameMode(), "unknown");
        manifest.sourcePlayerName = metadata.playerName();
        manifest.sourceMapName = valueOr(metadata.mapName(), "unknown");
        manifest.sourceMapVersion = "";
        manifest.sourceWorldVersion = "1";
        manifest.recordedAtMillis = Math.max(1L,
            Math.max(metadata.startTimeMillis(), metadata.endTimeMillis()));
        manifest.startTimeMillis = Math.max(0L, metadata.startTimeMillis());
        manifest.endTimeMillis = Math.max(manifest.startTimeMillis, metadata.endTimeMillis());
        manifest.actorSampleRateHz = (int) Math.round(metadata.sampleRateHz());
        manifest.actors = List.of(new Actor(
            "local",
            "local",
            metadata.playerName(),
            "actor/local",
            "complete",
            frameCount,
            actorMetaPresent ? "actor_meta/local" : ""
        ));
        manifest.worldMode = "off";
        manifest.modsCount = modsCount;
        manifest.modsVersion = modsVersion == null ? "" : modsVersion;
        manifest.modsSection = modsPresent ? "mods" : "";
        manifest.worldIssue = "";
        manifest.warnings = List.of();
        return manifest;
    }

    private static void applyWorld(FormatManifest manifest, long baseBytes, long logBytes,
        int paddingTiles, int sampleRateHz, String coverageStatus, long coverageSquares,
        long coverageObjects, long logChanges, int worldVersion) {
        manifest.worldMode = "padded";
        manifest.worldPaddingTiles = paddingTiles;
        manifest.worldSampleRateHz = sampleRateHz;
        manifest.worldCoverageStatus = coverageStatus;
        manifest.worldCoverageSquares = coverageSquares;
        manifest.worldCoverageObjects = coverageObjects;
        manifest.worldLogChanges = logChanges;
        manifest.worldLogBytes = logBytes;
        manifest.worldBaseBytes = baseBytes;
        manifest.worldBaseFormat = "zip-store-1";
        manifest.worldLogFormat = "pzwlog-1";
        manifest.worldFirstSample = "full";
        manifest.worldIndexFormat = "";
        manifest.sourceWorldVersion = Integer.toString(worldVersion);
    }

    static FormatManifest parse(byte[] bytes) throws FormatException {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) {
            throw new FormatException(FormatReason.MANIFEST_LENGTH,
                "manifest length is outside the cap");
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
                "manifest is not valid UTF-8", exception);
        }
        if (text.startsWith("\uFEFF") || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
            throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                "manifest contains a forbidden character");
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
            int lineBytes = text.substring(lineStart, lineEnd)
                .getBytes(StandardCharsets.UTF_8).length;
            if (lineBytes > MAX_LINE_BYTES) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "manifest line exceeds the cap");
            }
            if (lineEnd == lineStart) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "manifest contains a blank line");
            }
            String line = text.substring(lineStart, lineEnd);
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "manifest line is not key=value");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!KEY_PATTERN.matcher(key).matches() || !printable(value)) {
                throw new FormatException(FormatReason.MANIFEST_GRAMMAR,
                    "manifest line is not valid: " + key);
            }
            if (values.containsKey(key)) {
                throw new FormatException(FormatReason.MANIFEST_DUPLICATE_KEY,
                    "manifest key is duplicated: " + key);
            }
            values.put(key, value);
            if (values.size() > MAX_KEYS) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "manifest key count exceeds the cap");
            }
            lineStart = terminated ? lineEnd + 1 : lineEnd;
        }
        if (values.isEmpty()) {
            throw new FormatException(FormatReason.MANIFEST_GRAMMAR, "manifest is empty");
        }

        for (String key : values.keySet()) {
            if (!isKnownKey(key)) {
                warnings.add("unknown manifest key: " + key);
            }
        }

        FormatManifest manifest = new FormatManifest();
        manifest.warnings = warnings;
        long manifestVersion = uint(values, "manifest_version", 1, Long.MAX_VALUE);
        if (manifestVersion != 1) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "unsupported manifest version " + manifestVersion);
        }
        manifest.manifestVersion = 1;
        long minReaderFormat = uint(values, "min_reader_format", 1, Long.MAX_VALUE);
        if (minReaderFormat > 1) {
            throw new FormatException(FormatReason.UNSUPPORTED_VERSION,
                "package requires reader format " + minReaderFormat);
        }
        manifest.minReaderFormat = 1;
        manifest.replayId = parseReplayId(values);
        manifest.createdBy = createdBy(values);
        manifest.gameVersion = string(values, "game_version", 1, 64);
        manifest.sourceSaveName = string(values, "source_save_name", 0, 128);
        if (manifest.sourceSaveName.startsWith(".")) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                "source_save_name starts with a dot");
        }
        manifest.sourceSaveMode = string(values, "source_save_mode", 1, 32);
        manifest.sourcePlayerName = string(values, "source_player_name", 0, 128);
        manifest.sourceMapName = string(values, "source_map_name", 1, 256);
        manifest.sourceMapVersion = string(values, "source_map_version", 0, 64);
        manifest.sourceWorldVersion = string(values, "source_world_version", 1, 64);
        manifest.recordedAtMillis = uint(values, "recorded_at_ms", 1, Long.MAX_VALUE);
        manifest.startTimeMillis = uint(values, "start_time_ms", 0, Long.MAX_VALUE);
        manifest.endTimeMillis = uint(values, "end_time_ms", 0, Long.MAX_VALUE);
        if (manifest.startTimeMillis > manifest.endTimeMillis) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                "start_time_ms is after end_time_ms");
        }
        if (manifest.endTimeMillis > manifest.recordedAtMillis + 86_400_000L) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                "end_time_ms is more than one day after recorded_at_ms");
        }
        manifest.actorSampleRateHz = (int) uint(values, "actor_sample_rate_hz", 1, 120);
        manifest.actors = parseActors(values);
        manifest.worldMode = enumValue(values.get("world_mode"), "off");
        manifest.worldIssue = "";
        parseWorld(manifest, values);
        manifest.modsCount = (int) uint(values, "mods_count", 0, 1_024);
        manifest.modsVersion = string(values, "mods_version", 0, 64);
        manifest.modsSection = "";
        if (values.containsKey("mods_section")) {
            manifest.modsSection = sectionReference(values, "mods_section");
            if (!"mods".equals(manifest.modsSection)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "mods_section must reference mods");
            }
        }
        return manifest;
    }

    String toText() {
        StringBuilder builder = new StringBuilder();
        line(builder, "manifest_version", Integer.toString(manifestVersion));
        line(builder, "min_reader_format", Integer.toString(minReaderFormat));
        line(builder, "replay_id", replayId);
        line(builder, "created_by", createdBy);
        line(builder, "game_version", gameVersion);
        line(builder, "source_save_name", sourceSaveName);
        line(builder, "source_save_mode", sourceSaveMode);
        line(builder, "source_player_name", sourcePlayerName);
        line(builder, "source_map_name", sourceMapName);
        line(builder, "source_map_version", sourceMapVersion);
        line(builder, "source_world_version", sourceWorldVersion);
        line(builder, "recorded_at_ms", Long.toString(recordedAtMillis));
        line(builder, "start_time_ms", Long.toString(startTimeMillis));
        line(builder, "end_time_ms", Long.toString(endTimeMillis));
        line(builder, "actor_sample_rate_hz", Integer.toString(actorSampleRateHz));
        line(builder, "actor_count", Integer.toString(actors.size()));
        for (int index = 0; index < actors.size(); index++) {
            Actor actor = actors.get(index);
            String prefix = "actor." + index + ".";
            line(builder, prefix + "id", actor.id());
            line(builder, prefix + "role", actor.role());
            line(builder, prefix + "display_name", actor.displayName());
            line(builder, prefix + "track_section", actor.trackSection());
            line(builder, prefix + "known_coverage", actor.knownCoverage());
            line(builder, prefix + "frame_count", Long.toString(actor.frameCount()));
            if (!actor.metaSection().isEmpty()) {
                line(builder, prefix + "meta_section", actor.metaSection());
            }
        }
        line(builder, "world_mode", worldMode);
        if ("padded".equals(worldMode)) {
            line(builder, "world_padding_tiles", Integer.toString(worldPaddingTiles));
            line(builder, "world_sample_rate_hz", Integer.toString(worldSampleRateHz));
            line(builder, "world_coverage_status", worldCoverageStatus);
            line(builder, "world_coverage_squares", Long.toString(worldCoverageSquares));
            line(builder, "world_coverage_objects", Long.toString(worldCoverageObjects));
            line(builder, "world_log_changes", Long.toString(worldLogChanges));
            line(builder, "world_log_bytes", Long.toString(worldLogBytes));
            line(builder, "world_base_bytes", Long.toString(worldBaseBytes));
            line(builder, "world_base_format", worldBaseFormat);
            line(builder, "world_log_format", worldLogFormat);
            line(builder, "world_first_sample", worldFirstSample);
            if (!worldIndexFormat.isEmpty()) {
                line(builder, "world_index_format", worldIndexFormat);
            }
        }
        line(builder, "mods_count", Integer.toString(modsCount));
        line(builder, "mods_version", modsVersion);
        if (!modsSection.isEmpty()) {
            line(builder, "mods_section", modsSection);
        }
        return builder.toString();
    }

    String worldStatus() {
        if ("off".equals(worldMode)) {
            return "none";
        }
        if ("padded".equals(worldMode) && worldIssue.isEmpty()) {
            return "padded";
        }
        return "unsupported";
    }

    private static void parseWorld(FormatManifest manifest, Map<String, String> values)
        throws FormatException {
        if ("off".equals(manifest.worldMode)) {
            for (String key : values.keySet()) {
                if (key.startsWith("world_") && !"world_mode".equals(key)) {
                    manifest.warnings.add("world field ignored while world_mode=off: " + key);
                }
            }
            return;
        }
        if (!"padded".equals(manifest.worldMode) && !"full".equals(manifest.worldMode)) {
            manifest.worldIssue = FormatReason.WORLD_UNSUPPORTED;
            manifest.warnings.add("unknown world_mode: " + manifest.worldMode);
            return;
        }
        if ("full".equals(manifest.worldMode)) {
            manifest.worldIssue = FormatReason.WORLD_UNSUPPORTED;
            return;
        }
        try {
            manifest.worldPaddingTiles = (int) uint(values, "world_padding_tiles", 0, 128);
            manifest.worldSampleRateHz = (int) uint(values, "world_sample_rate_hz", 1, 10);
            manifest.worldCoverageStatus = enumValue(
                required(values, "world_coverage_status"), "complete");
            if (!"complete".equals(manifest.worldCoverageStatus)
                && !"partial".equals(manifest.worldCoverageStatus)
                && !"truncated".equals(manifest.worldCoverageStatus)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "world_coverage_status is not a known token");
            }
            manifest.worldCoverageSquares = uint(values, "world_coverage_squares",
                0, 500_000);
            manifest.worldCoverageObjects = uint(values, "world_coverage_objects",
                0, 1_000_000);
            manifest.worldLogChanges = uint(values, "world_log_changes", 0, 4_000_000);
            manifest.worldLogBytes = uint(values, "world_log_bytes", 0, 536_870_912);
            manifest.worldBaseBytes = uint(values, "world_base_bytes", 0, 805_306_368);
            manifest.worldBaseFormat = enumValue(
                required(values, "world_base_format"), "zip-store-1");
            if (!"zip-store-1".equals(manifest.worldBaseFormat)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "world_base_format is not zip-store-1");
            }
            manifest.worldLogFormat = enumValue(
                required(values, "world_log_format"), "pzwlog-1");
            if (!"pzwlog-1".equals(manifest.worldLogFormat)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "world_log_format is not pzwlog-1");
            }
            manifest.worldFirstSample = enumValue(
                required(values, "world_first_sample"), "full");
            if (!"full".equals(manifest.worldFirstSample)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "world_first_sample is not full");
            }
            manifest.worldIndexFormat = "";
            if (values.containsKey("world_index_format")) {
                manifest.worldIndexFormat = enumValue(
                    values.get("world_index_format"), "pzwidx-1");
                if (!"pzwidx-1".equals(manifest.worldIndexFormat)) {
                    throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                        "world_index_format is not pzwidx-1");
                }
            }
        } catch (FormatException exception) {
            manifest.worldIssue = exception.reason();
            manifest.warnings.add("world metadata is invalid: " + exception.getMessage());
        }
    }

    private static List<Actor> parseActors(Map<String, String> values) throws FormatException {
        int count = (int) uint(values, "actor_count", 1, 16);
        Map<Integer, Map<String, String>> rows = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("actor.")) {
                continue;
            }
            String[] parts = key.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("actor")) {
                continue;
            }
            String digits = parts[1];
            if (!digits.matches("0|[1-9][0-9]*")) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(digits);
            } catch (NumberFormatException exception) {
                continue;
            }
            if (index < 0 || index > 15) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor index is outside 0..15: " + index);
            }
            if (!ACTOR_FIELDS.contains(parts[2])) {
                continue;
            }
            rows.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                .put(parts[2], entry.getValue());
        }
        if (rows.size() != count) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                "actor_count does not match the actor rows");
        }
        List<Actor> actors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Map<String, String> row = rows.get(index);
            if (row == null) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor index is missing: " + index);
            }
            String id = required(row, "id");
            if (!ACTOR_ID_PATTERN.matcher(id).matches()) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor id is invalid: " + id);
            }
            String role = enumValue(required(row, "role"), "local");
            if (!"local".equals(role) && !"remote".equals(role)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor role is invalid: " + role);
            }
            String displayName = row.getOrDefault("display_name", "");
            if (displayName.getBytes(StandardCharsets.UTF_8).length > 128) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor display_name exceeds the cap");
            }
            String trackSection = sectionReference(row, "track_section");
            if (!("actor/" + id).equals(trackSection)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor track_section does not match its id");
            }
            String knownCoverage = enumValue(required(row, "known_coverage"), "complete");
            if (!"complete".equals(knownCoverage)
                && !"client_known".equals(knownCoverage)) {
                throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                    "actor known_coverage is invalid: " + knownCoverage);
            }
            long frameCount = uint(row, "frame_count", 1, FormatWriter.MAX_FRAMES);
            String metaSection = "";
            if (row.containsKey("meta_section")) {
                metaSection = sectionReference(row, "meta_section");
                if (!("actor_meta/" + id).equals(metaSection)) {
                    throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                        "actor meta_section does not match its id");
                }
            }
            actors.add(new Actor(id, role, displayName, trackSection, knownCoverage,
                frameCount, metaSection));
        }
        return actors;
    }

    private static String parseReplayId(Map<String, String> values) throws FormatException {
        String id = required(values, "replay_id");
        if (!UUID_PATTERN.matcher(id).matches()) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                "replay_id is not a canonical lowercase UUID");
        }
        return id;
    }

    private static String createdBy(Map<String, String> values) throws FormatException {
        String value = required(values, "created_by");
        if (!CREATED_BY_PATTERN.matcher(value).matches()) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                "created_by is invalid");
        }
        return value;
    }

    private static String string(Map<String, String> values, String key, int minimum, int maximum)
        throws FormatException {
        String value = required(values, key);
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length < minimum || length > maximum) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                key + " is outside its length cap");
        }
        return value;
    }

    private static String sectionReference(Map<String, String> values, String key)
        throws FormatException {
        String value = required(values, key);
        if (!isValidSectionId(value)) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                key + " is not a valid section id");
        }
        return value;
    }

    private static long uint(Map<String, String> values, String key, long minimum, long maximum)
        throws FormatException {
        String value = required(values, key);
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                key + " is not an unsigned decimal integer");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                key + " is out of range", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new FormatException(FormatReason.MANIFEST_FIELD_INVALID,
                key + " is out of range");
        }
        return parsed;
    }

    private static String required(Map<String, String> values, String key)
        throws FormatException {
        String value = values.get(key);
        if (value == null) {
            throw new FormatException(FormatReason.MANIFEST_MISSING_FIELD,
                "manifest is missing " + key);
        }
        return value;
    }

    private static String enumValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    static boolean isValidSectionId(String id) {
        if (id == null || id.isEmpty()
            || id.getBytes(StandardCharsets.US_ASCII).length != id.length()
            || id.length() > 64) {
            return false;
        }
        String[] segments = id.split("/", -1);
        if (segments.length < 1 || segments.length > 2) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return false;
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                boolean valid = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '.' || character == '-';
                if (!valid) {
                    return false;
                }
            }
        }
        return true;
    }

    static String normalizeValue(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC);
    }

    private static boolean isKnownKey(String key) {
        return KNOWN_KEYS.contains(key) || key.startsWith("actor.");
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

    private static void line(StringBuilder builder, String key, String value) {
        builder.append(key).append('=').append(normalizeValue(value)).append('\n');
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String replayId(ReplayMetadata metadata) {
        String seed = metadata.startTimeMillis() + "|" + metadata.endTimeMillis() + "|"
            + metadata.saveName() + "|" + metadata.playerName() + "|" + metadata.modVersion()
            + "|" + metadata.mapName();
        return java.util.UUID.nameUUIDFromBytes(
            seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
