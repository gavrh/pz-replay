package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record ReplayAppearance(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = 1;

    private static final String MISSING_WARNING = "replay has no appearance data";
    private static final String CORRUPT_WARNING = "appearance data is corrupt or incomplete";

    public ReplayAppearance {
        data = data == null ? "" : data;
    }

    public static ReplayAppearance none() {
        return new ReplayAppearance(NONE_VERSION, "");
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public byte[] decode() throws ReplayFormatException {
        if (version != CURRENT_VERSION) {
            throw new ReplayFormatException("appearance data version is unsupported: " + version);
        }
        if (data.isEmpty()) {
            throw new ReplayFormatException(CORRUPT_WARNING);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length == 0) {
                throw new ReplayFormatException(CORRUPT_WARNING);
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw new ReplayFormatException(CORRUPT_WARNING, exception);
        }
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of(MISSING_WARNING);
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (version != CURRENT_VERSION) {
            return List.of("appearance data version is unsupported: " + version);
        }
        try {
            decode();
            return List.of();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static ReplayAppearance fromValues(Map<String, String> values) {
        String versionValue = values.get("appearance_version");
        String data = values.getOrDefault("appearance_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new ReplayAppearance(-1, data);
        }
        try {
            return new ReplayAppearance(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new ReplayAppearance(-1, data);
        }
    }
}
