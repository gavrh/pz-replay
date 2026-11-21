package com.gavrh.pzreplay;

public final class WorldCoverageWindow {
    private int loadedSquares;
    private int missingInterior;
    private int missingBoundary;

    public void loaded() {
        loadedSquares++;
    }

    public void empty() {}

    public void missing(boolean boundary) {
        if (boundary) {
            missingBoundary++;
        } else {
            missingInterior++;
        }
    }

    public int expectedSquares() {
        return loadedSquares + missingInterior + missingBoundary;
    }

    public int missingInterior() {
        return missingInterior;
    }

    public int missingBoundary() {
        return missingBoundary;
    }

    public String status() {
        return WorldCoverageStatus.classify(expectedSquares(), missingInterior, missingBoundary);
    }
}
