package com.gavrh.pzreplay;

import java.util.List;
import java.util.Locale;

public final class CameraBounds {
    private final boolean bounded;
    private final float minX;
    private final float minY;
    private final float maxX;
    private final float maxY;

    private CameraBounds(boolean bounded, float minX, float minY, float maxX, float maxY) {
        this.bounded = bounded;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public static CameraBounds empty() {
        return new CameraBounds(false, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public static CameraBounds fromRecords(List<WorldLogRecord> records, int paddingTiles) {
        if (records == null || records.isEmpty()) {
            return empty();
        }
        int padding = Math.max(0, paddingTiles);
        long minSx = Long.MAX_VALUE;
        long minSy = Long.MAX_VALUE;
        long maxSx = Long.MIN_VALUE;
        long maxSy = Long.MIN_VALUE;
        for (WorldLogRecord record : records) {
            if (record == null || record.record() == null || record.record().target() == null) {
                continue;
            }
            WorldTarget target = record.record().target();
            minSx = Math.min(minSx, target.sx());
            minSy = Math.min(minSy, target.sy());
            maxSx = Math.max(maxSx, target.sx());
            maxSy = Math.max(maxSy, target.sy());
        }
        if (minSx == Long.MAX_VALUE) {
            return empty();
        }
        float lowerX = minSx - padding;
        float lowerY = minSy - padding;
        float upperX = maxSx + 1.0f + padding;
        float upperY = maxSy + 1.0f + padding;
        if (!(lowerX < upperX) || !(lowerY < upperY)) {
            return empty();
        }
        return new CameraBounds(true, lowerX, lowerY, upperX, upperY);
    }

    public boolean isBounded() {
        return bounded;
    }

    public float minX() {
        return minX;
    }

    public float minY() {
        return minY;
    }

    public float maxX() {
        return maxX;
    }

    public float maxY() {
        return maxY;
    }

    public float clampX(float x) {
        if (!bounded || !Float.isFinite(x)) {
            return x;
        }
        return Math.max(minX, Math.min(maxX, x));
    }

    public float clampY(float y) {
        if (!bounded || !Float.isFinite(y)) {
            return y;
        }
        return Math.max(minY, Math.min(maxY, y));
    }

    public float clampOffsetX(float anchorX, float offsetX) {
        if (!bounded || !Float.isFinite(anchorX) || !Float.isFinite(offsetX)) {
            return offsetX;
        }
        return clampX(anchorX + offsetX) - anchorX;
    }

    public float clampOffsetY(float anchorY, float offsetY) {
        if (!bounded || !Float.isFinite(anchorY) || !Float.isFinite(offsetY)) {
            return offsetY;
        }
        return clampY(anchorY + offsetY) - anchorY;
    }

    public String text() {
        if (!bounded) {
            return "none";
        }
        return String.format(Locale.ROOT, "%.2f,%.2f..%.2f,%.2f", minX, minY, maxX, maxY);
    }
}
