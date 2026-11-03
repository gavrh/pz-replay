package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record ActionEvents(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = ActionEventCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING = "action event data is corrupt or incomplete";

    public ActionEvents {
        data = data == null ? "" : data;
    }

    public static ActionEvents none() {
        return new ActionEvents(NONE_VERSION, "");
    }

    public static ActionEvents encode(List<ActionEvent> events) throws ReplayFormatException {
        if (events == null || events.isEmpty()) {
            return none();
        }
        byte[] bytes = ActionEventCodec.encode(events);
        return new ActionEvents(CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version >= ActionEventCodec.MIN_SUPPORTED_VERSION
            && version <= CURRENT_VERSION;
    }

    public ActionEventCodec.Result decode() throws ReplayFormatException {
        if (!isCurrent()) {
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
        return ActionEventCodec.decode(bytes);
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of();
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (!isCurrent()) {
            return List.of("action event data version is unsupported: " + version);
        }
        try {
            ActionEventCodec.Result result = decode();
            if (result.events().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static ActionEvents fromValues(Map<String, String> values) {
        String versionValue = values.get("events_version");
        String data = values.getOrDefault("events_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new ActionEvents(-1, data);
        }
        try {
            return new ActionEvents(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new ActionEvents(-1, data);
        }
    }
}
