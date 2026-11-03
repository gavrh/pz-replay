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
    Appearance appearance,
    AppearanceChanges appearanceChanges,
    Equipment equipment,
    EquipmentChanges equipmentChanges,
    ActionEvents events,
    Vehicles vehicles,
    TimelineGaps gaps
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public ReplayMetadata {
        modVersion = safe(modVersion);
        gameVersion = safe(gameVersion);
        saveName = safe(saveName);
        gameMode = safe(gameMode);
        mapName = safe(mapName);
        playerName = safe(playerName);
        appearance = appearance == null ? Appearance.none() : appearance;
        appearanceChanges =
            appearanceChanges == null ? AppearanceChanges.none() : appearanceChanges;
        equipment = equipment == null ? Equipment.none() : equipment;
        equipmentChanges =
            equipmentChanges == null ? EquipmentChanges.none() : equipmentChanges;
        events = events == null ? ActionEvents.none() : events;
        vehicles = vehicles == null ? Vehicles.none() : vehicles;
        gaps = gaps == null ? TimelineGaps.none() : gaps;
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, Appearance appearance, AppearanceChanges appearanceChanges,
        Equipment equipment, ActionEvents events, Vehicles vehicles, TimelineGaps gaps) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            appearanceChanges, equipment, EquipmentChanges.none(), events, vehicles, gaps);
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, Appearance appearance, AppearanceChanges appearanceChanges,
        Equipment equipment, ActionEvents events, Vehicles vehicles) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            appearanceChanges, equipment, EquipmentChanges.none(), events, vehicles,
            TimelineGaps.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, Appearance appearance, AppearanceChanges appearanceChanges,
        Equipment equipment, ActionEvents events) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            appearanceChanges, equipment, EquipmentChanges.none(), events,
            Vehicles.none(), TimelineGaps.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, Appearance appearance, Equipment equipment,
        ActionEvents events) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            AppearanceChanges.none(), equipment, EquipmentChanges.none(), events,
            Vehicles.none(), TimelineGaps.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, Appearance appearance, Equipment equipment) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            AppearanceChanges.none(), equipment, EquipmentChanges.none(),
            ActionEvents.none(), Vehicles.none(), TimelineGaps.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis, Appearance appearance) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis, appearance,
            AppearanceChanges.none(), Equipment.none(),
            EquipmentChanges.none(), ActionEvents.none(), Vehicles.none(),
            TimelineGaps.none());
    }

    public ReplayMetadata(int formatVersion, String modVersion, String gameVersion,
        String saveName, String gameMode, String mapName, String playerName, float startX,
        float startY, float startZ, double sampleRateHz, long startTimeMillis,
        long endTimeMillis) {
        this(formatVersion, modVersion, gameVersion, saveName, gameMode, mapName, playerName,
            startX, startY, startZ, sampleRateHz, startTimeMillis, endTimeMillis,
            Appearance.none(), AppearanceChanges.none(), Equipment.none(),
            EquipmentChanges.none(), ActionEvents.none(), Vehicles.none(),
            TimelineGaps.none());
    }

    public ReplayMetadata withEnd(long endTimeMillis) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, appearanceChanges, equipment,
            equipmentChanges, events, vehicles, gaps);
    }

    public ReplayMetadata withEvents(ActionEvents events) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, appearanceChanges, equipment,
            equipmentChanges, events, vehicles, gaps);
    }

    public ReplayMetadata withAppearanceChanges(AppearanceChanges appearanceChanges) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, appearanceChanges, equipment,
            equipmentChanges, events, vehicles, gaps);
    }

    public ReplayMetadata withEquipmentChanges(EquipmentChanges equipmentChanges) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, appearanceChanges, equipment,
            equipmentChanges, events, vehicles, gaps);
    }

    public ReplayMetadata withVehicles(Vehicles vehicles) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, appearanceChanges, equipment,
            equipmentChanges, events, vehicles, gaps);
    }

    public ReplayMetadata withGaps(TimelineGaps gaps) {
        return new ReplayMetadata(formatVersion, modVersion, gameVersion, saveName,
            gameMode, mapName, playerName, startX, startY, startZ, sampleRateHz,
            startTimeMillis, endTimeMillis, appearance, appearanceChanges, equipment,
            equipmentChanges, events, vehicles, gaps);
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
        if (appearanceChanges.isPresent()) {
            values.put("appearance_changes_version",
                Integer.toString(appearanceChanges.version()));
            values.put("appearance_changes_data", appearanceChanges.data());
        }
        if (equipment.isPresent()) {
            values.put("equipment_version", Integer.toString(equipment.version()));
            values.put("equipment_data", equipment.data());
        }
        if (equipmentChanges.isPresent()) {
            values.put("equipment_changes_version",
                Integer.toString(equipmentChanges.version()));
            values.put("equipment_changes_data", equipmentChanges.data());
        }
        if (events.isPresent()) {
            values.put("events_version", Integer.toString(events.version()));
            values.put("events_data", events.data());
        }
        if (vehicles.isPresent()) {
            values.put("vehicles_version", Integer.toString(vehicles.version()));
            values.put("vehicles_data", vehicles.data());
        }
        if (gaps.isPresent()) {
            values.put("gaps_version", Integer.toString(gaps.version()));
            values.put("gaps_data", gaps.data());
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
            Appearance.fromValues(values),
            AppearanceChanges.fromValues(values),
            Equipment.fromValues(values),
            EquipmentChanges.fromValues(values),
            ActionEvents.fromValues(values),
            Vehicles.fromValues(values),
            TimelineGaps.fromValues(values)
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
