package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record EquipmentChanges(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = EquipmentChangeCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING =
        "equipment change data is corrupt or incomplete";

    public EquipmentChanges {
        data = data == null ? "" : data;
    }

    public static EquipmentChanges none() {
        return new EquipmentChanges(NONE_VERSION, "");
    }

    public static EquipmentChanges encode(List<EquipmentChange> changes)
        throws ReplayFormatException {
        if (changes == null || changes.isEmpty()) {
            return none();
        }
        byte[] bytes = EquipmentChangeCodec.encode(changes);
        return new EquipmentChanges(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public EquipmentChangeCodec.Result decode() throws ReplayFormatException {
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
        return EquipmentChangeCodec.decode(bytes);
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
            EquipmentChangeCodec.Result result = decode();
            if (result.changes().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static EquipmentChanges fromValues(Map<String, String> values) {
        String versionValue = values.get("equipment_changes_version");
        String data = values.getOrDefault("equipment_changes_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new EquipmentChanges(-1, data);
        }
        try {
            return new EquipmentChanges(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new EquipmentChanges(-1, data);
        }
    }
}
