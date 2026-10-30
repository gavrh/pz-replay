package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record ReplayEvents(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = ReplayEventCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING = "action event data is corrupt or incomplete";

    public ReplayEvents {
        data = data == null ? "" : data;
    }

    public static ReplayEvents none() {
        return new ReplayEvents(NONE_VERSION, "");
    }

    public static ReplayEvents encode(List<ReplayEvent> events) throws ReplayFormatException {
        if (events == null || events.isEmpty()) {
            return none();
        }
        byte[] bytes = ReplayEventCodec.encode(events);
        return new ReplayEvents(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public ReplayEventCodec.Result decode() throws ReplayFormatException {
        if (version != CURRENT_VERSION) {
            throw new ReplayFormatException(
                "action event data version is unsupported: " + version);
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
        return ReplayEventCodec.decode(bytes);
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of();
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (version != CURRENT_VERSION) {
            return List.of("action event data version is unsupported: " + version);
        }
        try {
            ReplayEventCodec.Result result = decode();
            if (result.events().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static ReplayEvents fromValues(Map<String, String> values) {
        String versionValue = values.get("events_version");
        String data = values.getOrDefault("events_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new ReplayEvents(-1, data);
        }
        try {
            return new ReplayEvents(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new ReplayEvents(-1, data);
        }
    }
}
