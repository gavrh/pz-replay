package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record ReplayVehicles(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = ReplayVehicleCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING = "vehicle data is corrupt or incomplete";

    public ReplayVehicles {
        data = data == null ? "" : data;
    }

    public static ReplayVehicles none() {
        return new ReplayVehicles(NONE_VERSION, "");
    }

    public static ReplayVehicles encode(List<ReplayVehicleSegment> segments)
        throws ReplayFormatException {
        if (segments == null || segments.isEmpty()) {
            return none();
        }
        byte[] bytes = ReplayVehicleCodec.encode(segments);
        return new ReplayVehicles(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public ReplayVehicleCodec.Result decode() throws ReplayFormatException {
        if (version != CURRENT_VERSION) {
            throw new ReplayFormatException(
                "vehicle data version is unsupported: " + version);
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
        return ReplayVehicleCodec.decode(bytes);
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of();
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (version != CURRENT_VERSION) {
            return List.of("vehicle data version is unsupported: " + version);
        }
        try {
            ReplayVehicleCodec.Result result = decode();
            if (result.segments().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static ReplayVehicles fromValues(Map<String, String> values) {
        String versionValue = values.get("vehicles_version");
        String data = values.getOrDefault("vehicles_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new ReplayVehicles(-1, data);
        }
        try {
            return new ReplayVehicles(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new ReplayVehicles(-1, data);
        }
    }
}
