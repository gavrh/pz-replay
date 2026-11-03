package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record ReplayEquipmentChanges(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = ReplayEquipmentChangeCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING =
        "equipment change data is corrupt or incomplete";

    public ReplayEquipmentChanges {
        data = data == null ? "" : data;
    }

    public static ReplayEquipmentChanges none() {
        return new ReplayEquipmentChanges(NONE_VERSION, "");
    }

    public static ReplayEquipmentChanges encode(List<ReplayEquipmentChange> changes)
        throws ReplayFormatException {
        if (changes == null || changes.isEmpty()) {
            return none();
        }
        byte[] bytes = ReplayEquipmentChangeCodec.encode(changes);
        return new ReplayEquipmentChanges(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public ReplayEquipmentChangeCodec.Result decode() throws ReplayFormatException {
        if (version != CURRENT_VERSION) {
            throw new ReplayFormatException(
                "equipment change data version is unsupported: " + version);
        }
        if (data.isEmpty()) {
            throw new ReplayFormatException(CORRUPT_WARNING);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException exception) {
            throw new ReplayFormatException(CORRUPT_WARNING, exception);
        }
        return ReplayEquipmentChangeCodec.decode(bytes);
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of();
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (version != CURRENT_VERSION) {
            return List.of("equipment change data version is unsupported: " + version);
        }
        try {
            ReplayEquipmentChangeCodec.Result result = decode();
            if (result.changes().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static ReplayEquipmentChanges fromValues(Map<String, String> values) {
        String versionValue = values.get("equipment_changes_version");
        String data = values.getOrDefault("equipment_changes_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new ReplayEquipmentChanges(-1, data);
        }
        try {
            return new ReplayEquipmentChanges(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new ReplayEquipmentChanges(-1, data);
        }
    }
}
