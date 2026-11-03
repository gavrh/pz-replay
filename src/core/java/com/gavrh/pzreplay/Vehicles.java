package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record Vehicles(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = VehicleCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING = "vehicle data is corrupt or incomplete";

    public Vehicles {
        data = data == null ? "" : data;
    }

    public static Vehicles none() {
        return new Vehicles(NONE_VERSION, "");
    }

    public static Vehicles encode(List<VehicleSegment> segments)
        throws ReplayFormatException {
        if (segments == null || segments.isEmpty()) {
            return none();
        }
        byte[] bytes = VehicleCodec.encode(segments);
        return new Vehicles(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public VehicleCodec.Result decode() throws ReplayFormatException {
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
        return VehicleCodec.decode(bytes);
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
            VehicleCodec.Result result = decode();
            if (result.segments().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static Vehicles fromValues(Map<String, String> values) {
        String versionValue = values.get("vehicles_version");
        String data = values.getOrDefault("vehicles_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new Vehicles(-1, data);
        }
        try {
            return new Vehicles(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new Vehicles(-1, data);
        }
    }
}
