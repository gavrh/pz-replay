package com.gavrh.pzreplay;

public final class CameraSmoothing {
    public static final float RESPONSE_SECONDS = 0.08f;
    public static final float MAX_SPEED_TILES_PER_SECOND = 90.0f;
    public static final float SNAP_DISTANCE_TILES = 0.01f;
    public static final float MAX_TARGET_DISTANCE_TILES = 16.0f;
    private static final float MAX_STEP_SECONDS = 0.1f;

    private float x;
    private float y;
    private float targetX;
    private float targetY;
    private boolean active;

    public boolean resetTo(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        this.x = x;
        this.y = y;
        targetX = x;
        targetY = y;
        active = true;
        return true;
    }

    public boolean setTarget(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return false;
        }
        targetX = x;
        targetY = y;
        if (!active) {
            this.x = x;
            this.y = y;
            active = true;
            return true;
        }
        if (distance(this.x, this.y, x, y) > MAX_TARGET_DISTANCE_TILES) {
            this.x = x;
            this.y = y;
        }
        return true;
    }

    public void update(float deltaSeconds) {
        if (!active || !Float.isFinite(deltaSeconds) || deltaSeconds <= 0.0f) {
            return;
        }
        float delta = Math.min(deltaSeconds, MAX_STEP_SECONDS);
        float alpha = 1.0f - (float) Math.exp(-delta / RESPONSE_SECONDS);
        float stepX = (targetX - x) * alpha;
        float stepY = (targetY - y) * alpha;
        float step = (float) Math.hypot(stepX, stepY);
        float maxStep = MAX_SPEED_TILES_PER_SECOND * delta;
        if (step > maxStep) {
            float scale = maxStep / step;
            stepX *= scale;
            stepY *= scale;
        }
        x += stepX;
        y += stepY;
        if (distance(x, y, targetX, targetY) <= SNAP_DISTANCE_TILES) {
            x = targetX;
            y = targetY;
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isAtTarget() {
        return !active || distance(x, y, targetX, targetY) <= SNAP_DISTANCE_TILES;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getTargetX() {
        return targetX;
    }

    public float getTargetY() {
        return targetY;
    }

    public void clear() {
        x = 0.0f;
        y = 0.0f;
        targetX = 0.0f;
        targetY = 0.0f;
        active = false;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }
}
