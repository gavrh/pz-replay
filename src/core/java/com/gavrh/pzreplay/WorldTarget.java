package com.gavrh.pzreplay;

public record WorldTarget(
    int sx,
    int sy,
    int sz,
    int orientation,
    int parentClass,
    int opposite,
    long sqlId,
    String script,
    String type
) {
    public WorldTarget {
        script = script == null ? "" : script;
        type = type == null ? "" : type;
    }

    public static WorldTarget door(int sx, int sy, int sz, int orientation) {
        return new WorldTarget(sx, sy, sz, orientation, 0, 0, 0L, "", "");
    }

    public static WorldTarget window(int sx, int sy, int sz, int orientation) {
        return new WorldTarget(sx, sy, sz, orientation, 0, 0, 0L, "", "");
    }

    public static WorldTarget barricade(int sx, int sy, int sz, int orientation,
        int parentClass, int opposite) {
        return new WorldTarget(sx, sy, sz, orientation, parentClass, opposite, 0L, "", "");
    }

    public static WorldTarget vehicle(int sx, int sy, int sz, long sqlId, String script) {
        return new WorldTarget(sx, sy, sz, 255, 0, 0, sqlId, script, "");
    }

    public static WorldTarget generic(int sx, int sy, int sz, int orientation, String type) {
        return new WorldTarget(sx, sy, sz, orientation, 0, 0, 0L, "", type);
    }
}
