package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record AppearanceChanges(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = AppearanceChangeCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING =
        "appearance change data is corrupt or incomplete";

    public AppearanceChanges {
        data = data == null ? "" : data;
    }

    public static AppearanceChanges none() {
        return new AppearanceChanges(NONE_VERSION, "");
    }

    public static AppearanceChanges encode(List<AppearanceChange> changes)
        throws ReplayFormatException {
        if (changes == null || changes.isEmpty()) {
            return none();
        }
        byte[] bytes = AppearanceChangeCodec.encode(changes);
        return new AppearanceChanges(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public AppearanceChangeCodec.Result decode() throws ReplayFormatException {
        if (version != CURRENT_VERSION) {
            throw new ReplayFormatException(
                "appearance change data version is unsupported: " + version);
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
        return AppearanceChangeCodec.decode(bytes);
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of();
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (version != CURRENT_VERSION) {
            return List.of("appearance change data version is unsupported: " + version);
        }
        try {
            AppearanceChangeCodec.Result result = decode();
            if (result.changes().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static AppearanceChanges fromValues(Map<String, String> values) {
        String versionValue = values.get("appearance_changes_version");
        String data = values.getOrDefault("appearance_changes_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new AppearanceChanges(-1, data);
        }
        try {
            return new AppearanceChanges(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new AppearanceChanges(-1, data);
        }
    }
}
