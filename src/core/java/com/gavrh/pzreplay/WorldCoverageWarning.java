package com.gavrh.pzreplay;

import java.util.Locale;

public final class WorldCoverageWarning {
    private WorldCoverageWarning() {
    }

    public static String message(String coverageStatus, long lastRecordMillis) {
        if ("truncated".equals(coverageStatus)) {
            return "world log is truncated; the world freezes at "
                + String.format(Locale.ROOT, "%.2f", lastRecordMillis / 1000.0) + "s";
        }
        if ("partial".equals(coverageStatus)) {
            return "world coverage is partial";
        }
        return "";
    }
}
