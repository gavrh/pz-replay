package com.gavrh.pzreplay;

public record WorldWindowOpen(Strategy strategy) {
    public enum Strategy {
        NO_CHANGE,
        REFLECTION,
        BLOCK
    }

    public static WorldWindowOpen of(boolean currentOpen, boolean recordedOpen,
        boolean reflectionAvailable) {
        if (currentOpen == recordedOpen) {
            return new WorldWindowOpen(Strategy.NO_CHANGE);
        }
        return new WorldWindowOpen(reflectionAvailable ? Strategy.REFLECTION : Strategy.BLOCK);
    }
}
