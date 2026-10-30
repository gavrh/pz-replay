package com.gavrh.pzreplay;

import java.util.LinkedHashMap;
import java.util.Map;

public record ReplayMetadata(
    int formatVersion,
    String modVersion,
    String gameVersion,
    String saveName,
    String gameMode,
    String mapName,
    String playerName,
    float startX,
    float startY,
    float startZ,
    double sampleRateHz,
    long startTimeMillis,
    long endTimeMillis,
    ReplayAppearance appearance,
    ReplayEquipment equipment,
    ReplayEvents events
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public ReplayMetadata {
        modVersion = safe(modVersion);
        gameVersion = safe(gameVersion);
        saveName = safe(saveName);
        gameMode = safe(gameMode);
        mapName = safe(mapName);
        playerName = safe(playerName);
        appearance = appearance == null ? ReplayAppearance.none() : appearance;
        equipment = equipment == null ? ReplayEquipment.none() : equipment;
        events = events == null ? ReplayEvents.none() : events;
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, ReplayAppearance appearance, ReplayEquipment equipment) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            equipment, ReplayEvents.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, ReplayAppearance appearance) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            ReplayEquipment.none(), ReplayEvents.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis,
            ReplayAppearance.none(), ReplayEquipment.none(), ReplayEvents.none());
    }

    public ReplayMetadata withEnd(long endTimeMillis) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, equipment, events);
    }

    public ReplayMetadata withEvents(ReplayEvents events) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, equipment, events);
    }

    public Map<String, String> toValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("format", Integer.toString(formatVersion));
        values.put("mod_version", modVersion);
        values.put("game_version", gameVersion);
        values.put("save_name", saveName);
        values.put("game_mode", gameMode);
        values.put("map", mapName);
        values.put("player_name", playerName);
        values.put("start_x", Float.toString(startX));
        values.put("start_y", Float.toString(startY));
        values.put("start_z", Float.toString(startZ));
        values.put("sample_rate_hz", Double.toString(sampleRateHz));
        values.put("start_time_ms", Long.toString(startTimeMillis));
        values.put("end_time_ms", Long.toString(endTimeMillis));
        if (appearance.isPresent()) {
            values.put("appearance_version", Integer.toString(appearance.version()));
            values.put("appearance_data", appearance.data());
        }
        if (equipment.isPresent()) {
            values.put("equipment_version", Integer.toString(equipment.version()));
            values.put("equipment_data", equipment.data());
        }
        if (events.isPresent()) {
            values.put("events_version", Integer.toString(events.version()));
            values.put("events_data", events.data());
        }
        return values;
    }

    public String toText() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : toValues().entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    public static ReplayMetadata fromValues(Map<String, String> values) throws ReplayFormatException {
        int version = parseInt(values, "format");
        if (version != CURRENT_FORMAT_VERSION) {
            throw new ReplayFormatException(
                "unsupported replay format version " + version
                    + " (supported: " + CURRENT_FORMAT_VERSION + ")");
        }
        return new ReplayMetadata(
            version,
            required(values, "mod_version"),
            required(values, "game_version"),
            required(values, "save_name"),
            values.getOrDefault("game_mode", ""),
            required(values, "map"),
            required(values, "player_name"),
            parseFloat(values, "start_x"),
            parseFloat(values, "start_y"),
            parseFloat(values, "start_z"),
            parseDouble(values, "sample_rate_hz"),
            parseLong(values, "start_time_ms"),
            parseLong(values, "end_time_ms"),
            ReplayAppearance.fromValues(values),
            ReplayEquipment.fromValues(values),
            ReplayEvents.fromValues(values)
        );
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String required(Map<String, String> values, String key)
        throws ReplayFormatException {
        String value = values.get(key);
        if (value == null) {
            throw new ReplayFormatException("replay header is missing '" + key + "'");
        }
        return value;
    }

    private static int parseInt(Map<String, String> values, String key)
        throws ReplayFormatException {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException exception) {
            throw new ReplayFormatException("invalid integer '" + key + "'");
        }
    }

    private static long parseLong(Map<String, String> values, String key)
        throws ReplayFormatException {
        try {
            return Long.parseLong(required(values, key));
        } catch (NumberFormatException exception) {
            throw new ReplayFormatException("invalid integer '" + key + "'");
        }
    }

    private static float parseFloat(Map<String, String> values, String key)
        throws ReplayFormatException {
        try {
            return Float.parseFloat(required(values, key));
        } catch (NumberFormatException exception) {
            throw new ReplayFormatException("invalid number '" + key + "'");
        }
    }

    private static double parseDouble(Map<String, String> values, String key)
        throws ReplayFormatException {
        try {
            return Double.parseDouble(required(values, key));
        } catch (NumberFormatException exception) {
            throw new ReplayFormatException("invalid number '" + key + "'");
        }
    }
}
