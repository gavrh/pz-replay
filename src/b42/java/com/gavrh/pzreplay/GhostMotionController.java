package com.gavrh.pzreplay;

import java.util.function.Consumer;
import zombie.GameTime;

public final class GhostMotionController {
    private static final float MAX_STEP_SECONDS = 0.1f;
    private static final float WALK_SPEED = 0.8f;
    private static final float RUN_SPEED = 0.65f;
    private static final float SNEAK_SPEED = 0.8f;

    public enum State {
        STOPPED,
        PLAYING,
        PAUSED
    }

    private final ScriptedGhostPath path;
    private final ReplayTimeline replay;
    private final Consumer<String> warning;
    private final ReplayCameraController camera = new ReplayCameraController();
    private final GhostActionController actions;
    private State state = State.STOPPED;
    private double elapsed;
    private long lastReplayUpdateNanos;
    private ReplayEquipment appliedEquipment;

    public GhostMotionController(ScriptedGhostPath path) {
        this(path, null);
    }

    public GhostMotionController(ScriptedGhostPath path, Consumer<String> warning) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        this.path = path;
        this.replay = null;
        this.warning = warning;
        this.actions = null;
    }

    public GhostMotionController(ReplayTimeline replay) {
        this(replay, null);
    }

    public GhostMotionController(ReplayTimeline replay, Consumer<String> warning) {
        if (replay == null) {
            throw new IllegalArgumentException("replay is required");
        }
        this.path = null;
        this.replay = replay;
        this.warning = warning;
        this.actions = new GhostActionController(replay.getEventTimeline());
    }

    public void start(ReplayGhost ghost) {
        if (replay != null) {
            if (!replay.start()) {
                return;
            }
            lastReplayUpdateNanos = System.nanoTime();
            apply(ghost, replay.sample());
            actions.start(ghost);
            System.out.println("[pz-replay] replay ghost motion started duration="
                + String.format("%.2f", replay.getDuration()));
            return;
        }

        if (state == State.PAUSED) {
            state = State.PLAYING;
            System.out.println("[pz-replay] replay ghost motion resumed");
            return;
        }
        elapsed = 0.0;
        state = State.PLAYING;
        apply(ghost, path.sample(elapsed));
        System.out.println("[pz-replay] replay ghost motion started duration="
            + String.format("%.2f", path.getDuration()));
    }

    public boolean pause(ReplayGhost ghost) {
        if (replay != null) {
            if (!replay.pause()) {
                return false;
            }
            applyPausedPose(ghost);
            System.out.println("[pz-replay] replay ghost motion paused at="
                + String.format("%.2f", replay.getCurrentTime()));
            return true;
        }

        if (state != State.PLAYING) {
            return false;
        }
        state = State.PAUSED;
        applyPausedPose(ghost);
        System.out.println("[pz-replay] replay ghost motion paused at="
            + String.format("%.2f", elapsed));
        return true;
    }

    public boolean resume(ReplayGhost ghost) {
        if (replay != null) {
            if (!replay.resume()) {
                return false;
            }
            lastReplayUpdateNanos = System.nanoTime();
            apply(ghost, replay.sample());
            camera.update(ghost, 0.0);
            System.out.println("[pz-replay] replay ghost motion resumed at="
                + String.format("%.2f", replay.getCurrentTime()));
            return true;
        }

        if (state != State.PAUSED) {
            return false;
        }
        state = State.PLAYING;
        apply(ghost, path.sample(elapsed));
        camera.update(ghost, 0.0);
        System.out.println("[pz-replay] replay ghost motion resumed at="
            + String.format("%.2f", elapsed));
        return true;
    }

    public boolean seek(ReplayGhost ghost, double seconds) {
        if (replay == null || ghost == null || !replay.seek(seconds)) {
            return false;
        }
        lastReplayUpdateNanos = System.nanoTime();
        apply(ghost, replay.sample());
        if (!replay.isPlaying()) {
            applyPausedPose(ghost);
        }
        camera.resetSmoothing(ghost);
        actions.seek(ghost, replay.getCurrentTime());
        camera.update(ghost, 0.0);
        System.out.println("[pz-replay] replay ghost motion seeked to="
            + String.format("%.2f", replay.getCurrentTime()) + " "
            + camera.status(ghost));
        return true;
    }

    public boolean setSpeed(ReplayGhost ghost, double multiplier) {
        if (replay == null || !replay.setSpeed(multiplier)) {
            return false;
        }
        lastReplayUpdateNanos = System.nanoTime();
        camera.update(ghost, 0.0);
        System.out.println("[pz-replay] replay ghost motion speed="
            + String.format("%.2f", replay.getSpeed()));
        return true;
    }

    public boolean restart(ReplayGhost ghost) {
        if (replay == null || ghost == null || !replay.restart()) {
            return false;
        }
        lastReplayUpdateNanos = System.nanoTime();
        apply(ghost, replay.sample());
        if (replay.isPaused()) {
            applyPausedPose(ghost);
        }
        camera.resetSmoothing(ghost);
        actions.restart(ghost);
        camera.update(ghost, 0.0);
        System.out.println("[pz-replay] replay ghost motion restarted "
            + camera.status(ghost));
        return true;
    }

    public void stop(ReplayGhost ghost) {
        boolean wasActive = isActive();
        if (replay != null) {
            replay.stop();
        } else {
            state = State.STOPPED;
            elapsed = 0.0;
        }
        camera.restore();
        if (actions != null) {
            actions.stop(ghost);
        }
        resetLocomotion(ghost);
        clearReplayState(ghost);
        appliedEquipment = null;
        if (wasActive) {
            System.out.println("[pz-replay] replay ghost motion stopped");
        }
    }

    public void reset(ReplayGhost ghost) {
        camera.restore();
        appliedEquipment = null;
        if (actions != null) {
            actions.reset(ghost);
        }
        if (replay != null) {
            replay.reset();
            if (!replay.isEmpty()) {
                apply(ghost, replay.sampleAt(0.0));
            }
            System.out.println("[pz-replay] replay ghost motion reset");
            return;
        }
        state = State.STOPPED;
        elapsed = 0.0;
        apply(ghost, path.sample(elapsed));
        System.out.println("[pz-replay] replay ghost motion reset");
    }

    public void update(ReplayGhost ghost) {
        if (replay != null) {
            long nowNanos = System.nanoTime();
            double delta = (nowNanos - lastReplayUpdateNanos) / 1_000_000_000.0;
            lastReplayUpdateNanos = nowNanos;
            if (GameTime.isGamePaused() || !Double.isFinite(delta) || delta <= 0.0) {
                return;
            }
            if (replay.isPlaying()) {
                boolean advancing = replay.advance(Math.min(delta, MAX_STEP_SECONDS));
                apply(ghost, replay.sample());
                actions.advance(ghost, replay.getCurrentTime());
                if (!advancing) {
                    applyPausedPose(ghost);
                }
            }
            camera.update(ghost, delta);
            return;
        }

        if (state != State.PLAYING) {
            camera.update(ghost, realSecondsSinceLastUpdate());
            return;
        }

        float delta = realSecondsSinceLastUpdate();
        if (delta <= 0.0f) {
            return;
        }

        elapsed += Math.min(delta, MAX_STEP_SECONDS);
        if (path.isLoop()) {
            elapsed %= path.getDuration();
        } else if (elapsed >= path.getDuration()) {
            elapsed = path.getDuration();
            state = State.STOPPED;
        }
        apply(ghost, path.sample(elapsed));
        camera.update(ghost, delta);
    }

    public boolean setCameraMode(ReplayCameraMode mode, ReplayGhost ghost) {
        return camera.setMode(mode, ghost);
    }

    public boolean setCameraFollow(boolean enabled, ReplayGhost ghost) {
        return camera.setFollow(enabled, ghost);
    }

    public boolean exitFreeCamera(ReplayGhost ghost) {
        return camera.exitFree(ghost);
    }

    public ReplayCameraMode getCameraMode() {
        return camera.getMode();
    }

    public boolean isCameraFollow() {
        return camera.isFollowing();
    }

    public boolean isCameraFree() {
        return camera.isFree();
    }

    public boolean isActive() {
        return replay != null ? replay.isActive() : state != State.STOPPED;
    }

    public boolean isPlaying() {
        return replay != null ? replay.isPlaying() : state == State.PLAYING;
    }

    public boolean isPaused() {
        return replay != null ? replay.isPaused() : state == State.PAUSED;
    }

    public boolean isFinished() {
        return replay != null
            && !replay.isPlaying()
            && !replay.isPaused()
            && replay.getCurrentTime() >= replay.getDuration();
    }

    public double getSpeed() {
        return replay != null ? replay.getSpeed() : 1.0;
    }

    public double getCurrentTime() {
        return replay != null ? replay.getCurrentTime() : elapsed;
    }

    public double getDuration() {
        return replay != null ? replay.getDuration() : path.getDuration();
    }

    private static float realSecondsSinceLastUpdate() {
        float delta = GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
        if (!Float.isFinite(delta) || delta <= 0.0f) {
            return 0.0f;
        }
        return delta;
    }

    private void apply(ReplayGhost ghost, ScriptedGhostPath.Sample sample) {
        apply(ghost, sample.x(), sample.y(), sample.z(), sample.facing(), sample.locomotion(),
            ReplaySample.Stance.STAND, false, ReplayEquipment.none());
    }

    private void apply(ReplayGhost ghost, ReplayTimeline.Sample sample) {
        apply(ghost, sample.x(), sample.y(), sample.z(), sample.facing(), sample.locomotion(),
            sample.stance(), sample.aiming(), sample.equipment());
    }

    private void apply(ReplayGhost ghost, float x, float y, float z, float facing,
        ReplaySample.Locomotion locomotion, ReplaySample.Stance stance, boolean aiming,
        ReplayEquipment equipment) {
        ghost.setForceX(x);
        ghost.setForceY(y);
        ghost.setZ(z);
        ghost.setCurrentSquareFromPosition();
        ghost.setMovingSquare(ghost.getCurrentSquare());
        ghost.setDirectionAngle(facing);
        applyLocomotion(ghost, locomotion, stance);
        ghost.setIsAiming(aiming);
        applyEquipment(ghost, equipment);
    }

    private static void applyLocomotion(ReplayGhost ghost, ReplaySample.Locomotion locomotion,
        ReplaySample.Stance stance) {
        switch (locomotion) {
            case WALK -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(false);
                ghost.setSprinting(false);
                ghost.setVariable("WalkSpeed", WALK_SPEED);
            }
            case RUN -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(true);
                ghost.setSprinting(false);
                ghost.setVariable("WalkSpeed", RUN_SPEED);
            }
            case SPRINT -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(true);
                ghost.setSprinting(true);
                ghost.setVariable("WalkSpeed", 1.0f);
            }
            case SNEAK -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(false);
                ghost.setSprinting(false);
                ghost.setVariable("WalkSpeed", SNEAK_SPEED);
            }
            case IDLE -> {
                ghost.setScriptedMovement(false);
                ghost.setRunning(false);
                ghost.setSprinting(false);
            }
        }
        switch (stance) {
            case CROUCH -> {
                ghost.setSneaking(true);
                ghost.setOnFloor(false);
            }
            case PRONE -> {
                ghost.setSneaking(false);
                ghost.setOnFloor(true);
            }
            case STAND -> {
                ghost.setSneaking(locomotion == ReplaySample.Locomotion.SNEAK);
                ghost.setOnFloor(false);
            }
        }
    }

    private void applyEquipment(ReplayGhost ghost, ReplayEquipment equipment) {
        ReplayEquipment next = equipment == null ? ReplayEquipment.none() : equipment;
        if (next.equals(appliedEquipment)) {
            return;
        }
        appliedEquipment = next;
        clearHeldItems(ghost);
        if (!next.isPresent() || !next.isCurrent()) {
            return;
        }
        try {
            EquipmentSnapshot.decode(next).applyTo(ghost, this::warn);
        } catch (ReplayFormatException | RuntimeException exception) {
            warn("held-item data is corrupt or incomplete");
            System.out.println("[pz-replay] held-item data could not be applied: "
                + exception);
        }
    }

    private void warn(String message) {
        if (warning != null) {
            warning.accept(message);
        }
    }

    private static void clearHeldItems(ReplayGhost ghost) {
        if (ghost == null) {
            return;
        }
        ghost.setPrimaryHandItem(null);
        ghost.setSecondaryHandItem(null);
    }

    private static void clearReplayState(ReplayGhost ghost) {
        if (ghost == null) {
            return;
        }
        ghost.setIsAiming(false);
        clearHeldItems(ghost);
    }

    private static void applyPausedPose(ReplayGhost ghost) {
        if (ghost == null) {
            return;
        }
        ghost.setScriptedMovement(false);
        ghost.setRunning(false);
        ghost.setSprinting(false);
    }

    private static void resetLocomotion(ReplayGhost ghost) {
        ghost.setScriptedMovement(false);
        ghost.setRunning(false);
        ghost.setSprinting(false);
        ghost.setSneaking(false);
        ghost.setOnFloor(false);
    }

    void refreshCamera(ReplayGhost ghost) {
        camera.refresh(ghost);
    }
}
