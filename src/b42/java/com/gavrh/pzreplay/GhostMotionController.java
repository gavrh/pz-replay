package com.gavrh.pzreplay;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.PlayerCamera;

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
    private State state = State.STOPPED;
    private double elapsed;
    private boolean cameraFollow;
    private long lastReplayUpdateNanos;

    public GhostMotionController(ScriptedGhostPath path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        this.path = path;
        this.replay = null;
    }

    public GhostMotionController(ReplayTimeline replay) {
        if (replay == null) {
            throw new IllegalArgumentException("replay is required");
        }
        this.path = null;
        this.replay = replay;
    }

    public void start(ReplayGhost ghost) {
        if (replay != null) {
            if (!replay.start()) {
                return;
            }
            lastReplayUpdateNanos = System.nanoTime();
            apply(ghost, replay.sample());
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

    public boolean pause() {
        if (replay != null) {
            if (!replay.pause()) {
                return false;
            }
            System.out.println("[pz-replay] replay ghost motion paused at="
                + String.format("%.2f", replay.getCurrentTime()));
            return true;
        }

        if (state != State.PLAYING) {
            return false;
        }
        state = State.PAUSED;
        System.out.println("[pz-replay] replay ghost motion paused at="
            + String.format("%.2f", elapsed));
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
        cameraFollow = false;
        restoreCamera();
        resetLocomotion(ghost);
        if (wasActive) {
            System.out.println("[pz-replay] replay ghost motion stopped");
        }
    }

    public void reset(ReplayGhost ghost) {
        cameraFollow = false;
        restoreCamera();
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
            if (!replay.isPlaying()) {
                return;
            }
            long nowNanos = System.nanoTime();
            double delta = (nowNanos - lastReplayUpdateNanos) / 1_000_000_000.0;
            lastReplayUpdateNanos = nowNanos;
            if (GameTime.isGamePaused() || !Double.isFinite(delta) || delta <= 0.0) {
                return;
            }
            replay.advance(Math.min(delta, MAX_STEP_SECONDS));
            apply(ghost, replay.sample());
            applyCamera(ghost);
            return;
        }

        if (state != State.PLAYING) {
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
        applyCamera(ghost);
    }

    public boolean setCameraFollow(boolean enabled, ReplayGhost ghost) {
        if (!enabled) {
            cameraFollow = false;
            restoreCamera();
            return true;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        PlayerCamera camera = player == null ? null : cameraAt(player.getIndex());
        if (!isActive() || ghost == null || camera == null) {
            return false;
        }
        cameraFollow = true;
        if (!applyCamera(ghost)) {
            cameraFollow = false;
            return false;
        }
        IsoCamera.setCameraCharacter(player);
        camera.center();
        return true;
    }

    public boolean isCameraFollow() {
        return cameraFollow;
    }

    public boolean isActive() {
        return replay != null ? replay.isActive() : state != State.STOPPED;
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
            ReplaySample.Stance.STAND);
    }

    private void apply(ReplayGhost ghost, ReplayTimeline.Sample sample) {
        apply(ghost, sample.x(), sample.y(), sample.z(), sample.facing(), sample.locomotion(),
            sample.stance());
    }

    private void apply(ReplayGhost ghost, float x, float y, float z, float facing,
        ReplaySample.Locomotion locomotion, ReplaySample.Stance stance) {
        ghost.setForceX(x);
        ghost.setForceY(y);
        ghost.setZ(z);
        ghost.setCurrentSquareFromPosition();
        ghost.setMovingSquare(ghost.getCurrentSquare());
        ghost.setDirectionAngle(facing);
        applyLocomotion(ghost, locomotion, stance);
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

    private static void resetLocomotion(ReplayGhost ghost) {
        ghost.setScriptedMovement(false);
        ghost.setRunning(false);
        ghost.setSprinting(false);
        ghost.setSneaking(false);
        ghost.setOnFloor(false);
    }

    void refreshCamera(ReplayGhost ghost) {
        applyCamera(ghost);
    }

    private boolean applyCamera(ReplayGhost ghost) {
        if (!cameraFollow) {
            return false;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        PlayerCamera camera = player == null ? null : cameraAt(player.getIndex());
        if (camera == null) {
            return false;
        }
        camera.deferedX = ghost.getX() - player.getX();
        camera.deferedY = ghost.getY() - player.getY();
        return true;
    }

    private void restoreCamera() {
        clearCameraOffsets();
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            IsoCamera.clearCameraCharacter();
            return;
        }
        IsoCamera.setCameraCharacter(player);
        PlayerCamera camera = cameraAt(player.getIndex());
        if (camera != null) {
            camera.center();
        }
    }

    private static void clearCameraOffsets() {
        if (IsoCamera.cameras == null) {
            return;
        }
        for (PlayerCamera camera : IsoCamera.cameras) {
            if (camera != null) {
                camera.deferedX = 0f;
                camera.deferedY = 0f;
            }
        }
    }

    private static PlayerCamera cameraAt(int index) {
        if (IsoCamera.cameras == null || index < 0 || index >= IsoCamera.cameras.length) {
            return null;
        }
        return IsoCamera.cameras[index];
    }
}
