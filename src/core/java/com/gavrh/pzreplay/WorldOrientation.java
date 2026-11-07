package com.gavrh.pzreplay;

public final class WorldOrientation {
    private static final int[] INDEX = {0, 7, 3, 6, 2, 5, 1, 4};
    private static final int[] ENGINE = {0, 6, 4, 2, 7, 5, 3, 1};

    private WorldOrientation() {
    }

    public static int index(int engineIndex) {
        return INDEX[engineIndex & 7];
    }

    public static int engineIndex(int index) {
        return ENGINE[index & 7];
    }
}
