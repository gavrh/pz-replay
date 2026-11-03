package com.gavrh.pzreplay;

import java.io.IOException;
import java.util.function.Consumer;
import zombie.GameTime;
import zombie.inventory.types.WeaponType;

public final class GhostMotionController {
    private static final float MAX_STEP_SECONDS = 0.1f;
    private static final float STRAFE_SPEED = 0.8f;

    public enum State {
        STOPPED,
        PLAYING,
        PAUSED
    }

    private final ScriptedGhostPath path;
    private final PlaybackTimeline replay;
    private final Consumer<String> warning;
    private final ReplayCameraController camera = new ReplayCameraController();
    private final GhostActionController actions;
    private State state = State.STOPPED;
    private double elapsed;
    private long lastReplayUpdateNanos;
    private Equipment appliedEquipment;
    private Appearance appliedAppearance;
    private VehicleVisual vehicleVisual;
    private String vehicleFailureKey = "";
    private boolean holdingGap;

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

    public GhostMotionController(PlaybackTimeline replay) {
        this(replay, null);
    }

    public GhostMotionController(PlaybackTimeline replay, Consumer<String> warning) {
        if (replay == null) {
            throw new IllegalArgumentException("replay is required");
        }
        this.path = null;
        this.replay = replay;
        this.warning = warning;
        this.actions = new GhostActionController(replay.getEventTimeline());
    }

    public void start(GhostActor ghost) {
        if (replay != null) {
            if (!replay.start()) {
                return;
            }
            holdingGap = false;
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

    public boolean pause(GhostActor ghost) {
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

    public boolean resume(GhostActor ghost) {
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

    public boolean seek(GhostActor ghost, double seconds) {
        if (replay == null || ghost == null || !replay.seek(seconds)) {
            return false;
        }
        lastReplayUpdateNanos = System.nanoTime();
        apply(ghost, replay.sample());
        if (!replay.isPlaying()) {
            applyPausedPose(ghost);
        }
        holdingGap = replay.isGapHold(replay.getCurrentTime());
        camera.resetSmoothing(ghost);
        if (holdingGap) {
            actions.reset(ghost);
        } else {
            actions.seek(ghost, replay.getCurrentTime());
        }
        camera.update(ghost, 0.0);
        System.out.println("[pz-replay] replay ghost motion seeked to="
            + String.format("%.2f", replay.getCurrentTime()) + " "
            + camera.status(ghost));
        return true;
    }

    public boolean setSpeed(GhostActor ghost, double multiplier) {
        if (replay == null || !replay.setSpeed(multiplier)) {
            return false;
        }
        lastReplayUpdateNanos = System.nanoTime();
        camera.update(ghost, 0.0);
        System.out.println("[pz-replay] replay ghost motion speed="
            + String.format("%.2f", replay.getSpeed()));
        return true;
    }

    public boolean restart(GhostActor ghost) {
        if (replay == null || ghost == null || !replay.restart()) {
            return false;
        }
        lastReplayUpdateNanos = System.nanoTime();
        holdingGap = false;
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

    public void stop(GhostActor ghost) {
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
        despawnVehicle();
        setVehiclePose(ghost, false);
        vehicleFailureKey = "";
        resetLocomotion(ghost);
        clearReplayState(ghost);
        appliedEquipment = null;
        appliedAppearance = null;
        holdingGap = false;
        if (wasActive) {
            System.out.println("[pz-replay] replay ghost motion stopped");
        }
    }

    public void reset(GhostActor ghost) {
        camera.restore();
        despawnVehicle();
        setVehiclePose(ghost, false);
        vehicleFailureKey = "";
        appliedEquipment = null;
        appliedAppearance = null;
        holdingGap = false;
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

    public void update(GhostActor ghost) {
        if (replay != null) {
            long nowNanos = System.nanoTime();
            double delta = (nowNanos - lastReplayUpdateNanos) / 1_000_000_000.0;
            lastReplayUpdateNanos = nowNanos;
            if (GameTime.isGamePaused() || !Double.isFinite(delta) || delta <= 0.0) {
                return;
            }
            if (replay.isPlaying()) {
                boolean advancing = replay.advance(Math.min(delta, MAX_STEP_SECONDS));
                boolean gapHold = replay.isGapHold(replay.getCurrentTime());
                if (gapHold != holdingGap) {
                    holdingGap = gapHold;
                    if (gapHold) {
                        actions.reset(ghost);
                    } else {
                        actions.seek(ghost, replay.getCurrentTime());
                    }
                    camera.resetSmoothing(ghost);
                }
                apply(ghost, replay.sample());
                if (!holdingGap) {
                    actions.advance(ghost, replay.getCurrentTime());
                }
                if (!advancing) {
                    actions.complete(ghost);
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

    public boolean setCameraMode(ReplayCameraMode mode, GhostActor ghost) {
        return camera.setMode(mode, ghost);
    }

    public boolean setCameraFollow(boolean enabled, GhostActor ghost) {
        return camera.setFollow(enabled, ghost);
    }

    public boolean exitFreeCamera(GhostActor ghost) {
        return camera.exitFree(ghost);
    }

    public ReplayCameraMode getCameraMode() {
        return camera.getMode();
    }

    public boolean isCameraFollow() {
        return camera.isFollowing();
    }

    void markSpawnAppearanceApplied() {
        appliedAppearance = replay == null
            ? Appearance.none() : replay.getInitialAppearance();
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

    private void apply(GhostActor ghost, ScriptedGhostPath.Sample sample) {
        apply(ghost, sample.x(), sample.y(), sample.z(), sample.facing(), sample.locomotion(),
            CaptureSample.Stance.STAND, sample.locomotion() != CaptureSample.Locomotion.IDLE,
            false, Equipment.none(), Appearance.none(),
            new PlaybackTimeline.Movement(0.0f, 0.0f));
    }

    private void apply(GhostActor ghost, PlaybackTimeline.Sample sample) {
        double time = replay.getCurrentTime();
        PlaybackTimeline.Movement movement = replay.movementAt(time);
        apply(ghost, sample.x(), sample.y(), sample.z(), sample.facing(), sample.locomotion(),
            sample.stance(), sample.moving(), sample.aiming(), sample.equipment(),
            replay.appearanceAt(time), movement);
        VehicleTimeline.State vehicleState = replay.vehicleAt(time);
        boolean gapHold = replay.isGapHold(time);
        applyVehicle(ghost, vehicleState, !gapHold && (sample.inVehicle() || vehicleState != null));
    }

    private void applyVehicle(GhostActor ghost, VehicleTimeline.State state,
        boolean inVehicle) {
        if (ghost == null) {
            return;
        }
        if (state == null) {
            despawnVehicle();
            setVehiclePose(ghost, inVehicle);
            return;
        }
        if (vehicleVisual == null || !vehicleVisual.matches(state.segment())) {
            despawnVehicle();
            String key = state.segment().key();
            if (!key.equals(vehicleFailureKey)) {
                vehicleVisual = VehicleVisual.spawn(state.segment(), this::warn);
                if (vehicleVisual == null) {
                    vehicleFailureKey = key;
                } else {
                    vehicleFailureKey = "";
                    System.out.println("[pz-replay] replay vehicle spawned script="
                        + state.segment().scriptName() + " seat=" + state.segment().seat()
                        + " skin=" + state.segment().skinIndex());
                }
            }
        }
        if (vehicleVisual != null) {
            vehicleVisual.apply(state);
        }
        setVehiclePose(ghost, true);
    }

    private void despawnVehicle() {
        if (vehicleVisual == null) {
            return;
        }
        String script = vehicleVisual.getScriptName();
        vehicleVisual.despawn();
        vehicleVisual = null;
        System.out.println("[pz-replay] replay vehicle despawned script=" + script);
    }

    private void setVehiclePose(GhostActor ghost, boolean inVehicle) {
        if (ghost != null) {
            ghost.setVehiclePose(inVehicle);
        }
    }

    private void apply(GhostActor ghost, float x, float y, float z, float facing,
        CaptureSample.Locomotion locomotion, CaptureSample.Stance stance,
        boolean recordedMoving, boolean aiming, Equipment equipment,
        Appearance appearance, PlaybackTimeline.Movement movement) {
        ghost.setForceX(x);
        ghost.setForceY(y);
        ghost.setZ(z);
        ghost.setCurrentSquareFromPosition();
        ghost.setMovingSquare(ghost.getCurrentSquare());
        ghost.setDirectionAngle(facing);
        Boolean aimOverride = actions == null ? null : actions.aimOverride();
        boolean actionActive = aimOverride != null;
        boolean animationAiming = actionActive ? aimOverride.booleanValue() : aiming;
        float forwardX = ghost.getForwardDirectionX();
        float forwardY = ghost.getForwardDirectionY();
        float deltaY = movement.x() * forwardX + movement.y() * forwardY;
        float deltaX = movement.x() * -forwardY + movement.y() * forwardX;
        applyLocomotion(ghost, GhostAnimationState.compose(locomotion, stance,
            recordedMoving, animationAiming, deltaX, deltaY, actionActive));
        applyEquipment(ghost, equipment);
        applyAppearance(ghost, appearance);
        if (actions != null) {
            actions.applyActiveAttack(ghost);
        }
    }

    private static void applyLocomotion(GhostActor ghost, GhostAnimationState animation) {
        ghost.setScriptedMovement(animation.moving());
        ghost.setRunning(animation.running());
        ghost.setSprinting(animation.sprinting());
        ghost.setSneaking(animation.sneaking());
        ghost.setOnFloor(animation.onFloor());
        ghost.setVariable("WalkSpeed", animation.walkSpeed());
        ghost.setVariable("StrafeSpeed", STRAFE_SPEED);
        ghost.setVariable("DeltaX", animation.deltaX());
        ghost.setVariable("DeltaY", animation.deltaY());
        ghost.setScriptedStrafing(animation.strafing());
        ghost.setIsAiming(animation.aiming());
    }

    private void applyEquipment(GhostActor ghost, Equipment equipment) {
        Equipment next = equipment == null ? Equipment.none() : equipment;
        if (next.equals(appliedEquipment)) {
            return;
        }
        appliedEquipment = next;
        clearHeldItems(ghost);
        if (next.isPresent() && next.isCurrent()) {
            try {
                EquipmentSnapshot.decode(next).applyTo(ghost, this::warn);
            } catch (ReplayFormatException | RuntimeException exception) {
                warn("held-item data is corrupt or incomplete");
                System.out.println("[pz-replay] held-item data could not be applied: "
                    + exception);
            }
        }
        if (actions == null || !actions.hasActiveAttack()) {
            applyWeaponStyle(ghost);
        }
    }

    private static void applyWeaponStyle(GhostActor ghost) {
        try {
            WeaponType type = WeaponType.getWeaponType(ghost);
            String style = type == null || type == WeaponType.UNARMED
                ? "" : type.getType();
            ghost.setVariable("Weapon", style == null ? "" : style);
        } catch (RuntimeException exception) {
            ghost.setVariable("Weapon", "");
        }
    }

    private void applyAppearance(GhostActor ghost, Appearance appearance) {
        Appearance next = appearance == null ? Appearance.none() : appearance;
        if (next.equals(appliedAppearance)) {
            return;
        }
        appliedAppearance = next;
        if (!next.isCurrent()) {
            return;
        }
        try {
            AppearanceSnapshot.decode(next).applyTo(ghost);
            if (replay != null) {
                System.out.println("[pz-replay] replay appearance applied time="
                    + String.format("%.3f", replay.getCurrentTime())
                    + " bytes=" + next.data().length());
            }
        } catch (ReplayFormatException | IOException | RuntimeException exception) {
            warn("appearance data is corrupt or incomplete");
            System.out.println("[pz-replay] appearance data could not be applied: "
                + exception);
        }
    }

    private void warn(String message) {
        if (warning != null) {
            warning.accept(message);
        }
    }

    private static void clearHeldItems(GhostActor ghost) {
        if (ghost == null) {
            return;
        }
        ghost.setPrimaryHandItem(null);
        ghost.setSecondaryHandItem(null);
    }

    private static void clearReplayState(GhostActor ghost) {
        if (ghost == null) {
            return;
        }
        ghost.setIsAiming(false);
        ghost.setScriptedStrafing(false);
        ghost.setVariable("DeltaX", 0.0f);
        ghost.setVariable("DeltaY", 0.0f);
        ghost.setVariable("Weapon", "");
        ghost.clearVariable("rangedWeapon");
        clearHeldItems(ghost);
    }

    private static void applyPausedPose(GhostActor ghost) {
        if (ghost == null) {
            return;
        }
        ghost.setVariable("WalkSpeed", 0.0f);
        ghost.setVariable("StrafeSpeed", 0.0f);
    }

    private static void resetLocomotion(GhostActor ghost) {
        ghost.setScriptedMovement(false);
        ghost.setRunning(false);
        ghost.setSprinting(false);
        ghost.setSneaking(false);
        ghost.setOnFloor(false);
        ghost.setScriptedStrafing(false);
        ghost.setVariable("DeltaX", 0.0f);
        ghost.setVariable("DeltaY", 0.0f);
        ghost.setVariable("WalkSpeed", 0.0f);
        ghost.setVariable("StrafeSpeed", 0.0f);
    }

    void refreshCamera(GhostActor ghost) {
        camera.refresh(ghost);
    }
}
