package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record TimelineGaps(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = TimelineGapCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING =
        "timeline gap data is corrupt or incomplete";

    public TimelineGaps {
        data = data == null ? "" : data;
    }

    public static TimelineGaps none() {
        return new TimelineGaps(NONE_VERSION, "");
    }

    public static TimelineGaps encode(List<TimelineGap> gaps) throws ReplayFormatException {
        if (gaps == null || gaps.isEmpty()) {
            return none();
        }
        byte[] bytes = TimelineGapCodec.encode(gaps);
        return new TimelineGaps(CURRENT_VERSION, Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public TimelineGapCodec.Result decode() throws ReplayFormatException {
        if (version != CURRENT_VERSION) {
            throw new ReplayFormatException(
                "timeline gap data version is unsupported: " + version);
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
        return TimelineGapCodec.decode(bytes);
    }

    public List<String> warnings() {
        if (!isPresent()) {
            return List.of();
        }
        if (version <= NONE_VERSION) {
            return List.of(CORRUPT_WARNING);
        }
        if (version != CURRENT_VERSION) {
            return List.of("timeline gap data version is unsupported: " + version);
        }
        try {
            TimelineGapCodec.Result result = decode();
            if (result.gaps().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static TimelineGaps fromValues(Map<String, String> values) {
        String versionValue = values.get("gaps_version");
        String data = values.getOrDefault("gaps_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new TimelineGaps(-1, data);
        }
        try {
            return new TimelineGaps(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new TimelineGaps(-1, data);
        }
    }
}
