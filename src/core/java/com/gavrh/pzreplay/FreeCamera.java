package com.gavrh.pzreplay;

public final class FreeCamera {
    public static final float MAX_DISTANCE_TILES = 30.0f;

    private float x;
    private float y;
    private boolean positioned;

    public boolean moveTo(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        this.x = x;
        this.y = y;
        positioned = true;
        return true;
    }

    public void pan(float dx, float dy, float anchorX, float anchorY) {
        if (!positioned || !Float.isFinite(dx) || !Float.isFinite(dy)
            || !Float.isFinite(anchorX) || !Float.isFinite(anchorY)) {
            return;
        }
        x += dx;
        y += dy;
        clampTo(anchorX, anchorY);
    }

    public void clampTo(float anchorX, float anchorY) {
        if (!positioned || !Float.isFinite(anchorX) || !Float.isFinite(anchorY)) {
            return;
        }
        float offsetX = x - anchorX;
        float offsetY = y - anchorY;
        float distance = (float) Math.hypot(offsetX, offsetY);
        if (distance <= MAX_DISTANCE_TILES || distance <= 0.0f) {
            return;
        }
        float scale = MAX_DISTANCE_TILES / distance;
        x = anchorX + offsetX * scale;
        y = anchorY + offsetY * scale;
    }

    public float offsetX(float anchorX) {
        return positioned ? x - anchorX : 0.0f;
    }

    public float offsetY(float anchorY) {
        return positioned ? y - anchorY : 0.0f;
    }

    public boolean isPositioned() {
        return positioned;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public void reset() {
        x = 0.0f;
        y = 0.0f;
        positioned = false;
    }
}
