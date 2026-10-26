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
    private State state = State.STOPPED;
    private double elapsed;

    public GhostMotionController(ScriptedGhostPath path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        this.path = path;
    }

    public void start(ReplayGhost ghost) {
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
        if (state != State.PLAYING) {
            return false;
        }
        state = State.PAUSED;
        System.out.println("[pz-replay] replay ghost motion paused at="
            + String.format("%.2f", elapsed));
        return true;
    }

    public void stop(ReplayGhost ghost) {
        boolean wasActive = state != State.STOPPED;
        state = State.STOPPED;
        elapsed = 0.0;
        restoreCamera();
        applyLocomotion(ghost, ReplaySample.Locomotion.IDLE);
        if (wasActive) {
            System.out.println("[pz-replay] replay ghost motion stopped");
        }
    }

    public void reset(ReplayGhost ghost) {
        state = State.STOPPED;
        elapsed = 0.0;
        restoreCamera();
        apply(ghost, path.sample(elapsed));
        System.out.println("[pz-replay] replay ghost motion reset");
    }

    public void update(ReplayGhost ghost) {
        if (state != State.PLAYING) {
            return;
        }

        float delta = GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
        if (!Float.isFinite(delta) || delta <= 0.0f) {
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
    }

    private void apply(ReplayGhost ghost, ScriptedGhostPath.Sample sample) {
        ghost.setForceX(sample.x());
        ghost.setForceY(sample.y());
        ghost.setZ(sample.z());
        ghost.setCurrentSquareFromPosition();
        ghost.setMovingSquare(ghost.getCurrentSquare());
        ghost.setDirectionAngle(sample.facing());
        applyLocomotion(ghost, sample.locomotion());
    }

    private static void applyLocomotion(ReplayGhost ghost, ReplaySample.Locomotion locomotion) {
        switch (locomotion) {
            case WALK -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(false);
                ghost.setSprinting(false);
                ghost.setSneaking(false);
                ghost.setVariable("WalkSpeed", WALK_SPEED);
            }
            case RUN -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(true);
                ghost.setSprinting(false);
                ghost.setSneaking(false);
                ghost.setVariable("WalkSpeed", RUN_SPEED);
            }
            case SPRINT -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(true);
                ghost.setSprinting(true);
                ghost.setSneaking(false);
                ghost.setVariable("WalkSpeed", 1.0f);
            }
            case SNEAK -> {
                ghost.setScriptedMovement(true);
                ghost.setRunning(false);
                ghost.setSprinting(false);
                ghost.setSneaking(true);
                ghost.setVariable("WalkSpeed", SNEAK_SPEED);
            }
            case IDLE -> {
                ghost.setScriptedMovement(false);
                ghost.setRunning(false);
                ghost.setSprinting(false);
                ghost.setSneaking(false);
            }
        }
    }

    private void restoreCamera() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            IsoCamera.clearCameraCharacter();
            return;
        }
        IsoCamera.setCameraCharacter(player);
        int index = player.getIndex();
        if (cameraAt(index) != null) {
            cameraAt(index).center();
        }
    }

    private static PlayerCamera cameraAt(int index) {
        if (IsoCamera.cameras == null || index < 0 || index >= IsoCamera.cameras.length) {
            return null;
        }
        return IsoCamera.cameras[index];
    }
}
