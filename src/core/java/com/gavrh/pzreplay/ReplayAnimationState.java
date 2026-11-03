package com.gavrh.pzreplay;

public record ReplayAnimationState(
    boolean moving,
    boolean running,
    boolean sprinting,
    boolean sneaking,
    boolean onFloor,
    boolean aiming,
    boolean strafing,
    float deltaX,
    float deltaY,
    float walkSpeed
) {
    public static final float WALK_SPEED = 0.8f;
    public static final float RUN_SPEED = 0.65f;
    public static final float SPRINT_SPEED = 1.0f;
    public static final float SNEAK_SPEED = 0.8f;
    public static final float MIN_MOVEMENT = 0.01f;

    public static ReplayAnimationState compose(CaptureSample.Locomotion locomotion,
        CaptureSample.Stance stance, boolean recordedMoving, boolean aiming, float deltaX,
        float deltaY, boolean actionActive) {
        CaptureSample.Locomotion movement = locomotion == null
            ? CaptureSample.Locomotion.IDLE : locomotion;
        CaptureSample.Stance position = stance == null
            ? CaptureSample.Stance.STAND : stance;
        boolean moving = movement != CaptureSample.Locomotion.IDLE
            && (movement != CaptureSample.Locomotion.SNEAK || recordedMoving);
        CaptureSample.Locomotion effectiveMovement = moving
            ? movement : CaptureSample.Locomotion.IDLE;
        boolean running = moving && (effectiveMovement == CaptureSample.Locomotion.RUN
            || effectiveMovement == CaptureSample.Locomotion.SPRINT);
        boolean sprinting = moving && effectiveMovement == CaptureSample.Locomotion.SPRINT;
        boolean sneakMovement = moving && effectiveMovement == CaptureSample.Locomotion.SNEAK;
        boolean crouch = position == CaptureSample.Stance.CROUCH;
        boolean prone = position == CaptureSample.Stance.PRONE;
        boolean locomotionAiming = aiming
            && (actionActive || !moving || keepsAim(effectiveMovement));
        boolean strafing = locomotionAiming
            && effectiveMovement == CaptureSample.Locomotion.WALK;
        float localX = 0.0f;
        float localY = 0.0f;
        if (strafing) {
            localX = clamp(deltaX);
            localY = clamp(deltaY);
            if (Math.abs(localX) < MIN_MOVEMENT && Math.abs(localY) < MIN_MOVEMENT) {
                localY = 1.0f;
            }
        }
        return new ReplayAnimationState(
            moving,
            running,
            sprinting,
            sneakMovement || crouch,
            prone,
            locomotionAiming,
            strafing,
            localX,
            localY,
            speed(effectiveMovement)
        );
    }

    private static boolean keepsAim(CaptureSample.Locomotion locomotion) {
        return locomotion == CaptureSample.Locomotion.IDLE
            || locomotion == CaptureSample.Locomotion.WALK;
    }

    private static float speed(CaptureSample.Locomotion locomotion) {
        return switch (locomotion) {
            case RUN -> RUN_SPEED;
            case SPRINT -> SPRINT_SPEED;
            case SNEAK -> SNEAK_SPEED;
            case WALK -> WALK_SPEED;
            case IDLE -> 0.0f;
        };
    }

    private static float clamp(float value) {
        if (!Float.isFinite(value)) {
            return 0.0f;
        }
        return Math.max(-1.0f, Math.min(1.0f, value));
    }
}
