package com.gavrh.pzreplay;

public final class WorldCoverageStatus {
    private static final int BOUNDARY_ALLOWANCE_PERCENT = 1;

    private WorldCoverageStatus() {}

    public static String classify(int expectedSquares, int missingInterior, int missingBoundary) {
        if (missingInterior > 0) {
            return "partial";
        }
        int allowedBoundary = expectedSquares * BOUNDARY_ALLOWANCE_PERCENT / 100;
        return missingBoundary > allowedBoundary ? "partial" : "complete";
    }

    public static String combine(String currentStatus, String scanStatus) {
        if ("truncated".equals(currentStatus)) {
            return "truncated";
        }
        return "partial".equals(scanStatus) ? "partial" : currentStatus;
    }
}
