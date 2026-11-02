package com.gavrh.pzreplay;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public record ReplayGaps(int version, String data) {
    public static final int NONE_VERSION = 0;
    public static final int CURRENT_VERSION = ReplayGapCodec.CURRENT_VERSION;

    private static final String CORRUPT_WARNING =
        "timeline gap data is corrupt or incomplete";

    public ReplayGaps {
        data = data == null ? "" : data;
    }

    public static ReplayGaps none() {
        return new ReplayGaps(NONE_VERSION, "");
    }

    public static ReplayGaps encode(List<ReplayGap> gaps) throws ReplayFormatException {
        if (gaps == null || gaps.isEmpty()) {
            return none();
        }
        byte[] bytes = ReplayGapCodec.encode(gaps);
        return new ReplayGaps(CURRENT_VERSION, Base64.getEncoder().encodeToString(bytes));
    }

    public boolean isPresent() {
        return version != NONE_VERSION || !data.isEmpty();
    }

    public boolean isCurrent() {
        return version == CURRENT_VERSION;
    }

    public ReplayGapCodec.Result decode() throws ReplayFormatException {
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
        return ReplayGapCodec.decode(bytes);
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
            ReplayGapCodec.Result result = decode();
            if (result.gaps().isEmpty() && result.warnings().isEmpty()) {
                return List.of(CORRUPT_WARNING);
            }
            return result.warnings();
        } catch (ReplayFormatException exception) {
            return List.of(CORRUPT_WARNING);
        }
    }

    static ReplayGaps fromValues(Map<String, String> values) {
        String versionValue = values.get("gaps_version");
        String data = values.getOrDefault("gaps_data", "");
        if (versionValue == null) {
            return data.isEmpty() ? none() : new ReplayGaps(-1, data);
        }
        try {
            return new ReplayGaps(Integer.parseInt(versionValue), data);
        } catch (NumberFormatException exception) {
            return new ReplayGaps(-1, data);
        }
    }
}
