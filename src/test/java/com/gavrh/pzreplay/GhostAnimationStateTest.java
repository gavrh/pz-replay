package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostAnimationStateTest {
    @Test
    void walkingWhileAimingKeepsTheWalkAnimationAndAimBlend() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, true, true,
            0.0f, 1.0f, false);

        assertTrue(state.moving());
        assertTrue(state.aiming());
        assertTrue(state.strafing());
        assertFalse(state.running());
        assertFalse(state.sprinting());
        assertEquals(1.0f, state.deltaY(), 0.0001f);
        assertEquals(GhostAnimationState.WALK_SPEED, state.walkSpeed(), 0.0001f);
    }

    @Test
    void runningAndSprintingKeepTheirLocomotionClipsWhileAiming() {
        GhostAnimationState run = GhostAnimationState.compose(
            CaptureSample.Locomotion.RUN, CaptureSample.Stance.STAND, true, true,
            0.0f, 1.0f, false);
        GhostAnimationState sprint = GhostAnimationState.compose(
            CaptureSample.Locomotion.SPRINT, CaptureSample.Stance.STAND, true, true,
            0.0f, 1.0f, false);

        assertTrue(run.running());
        assertFalse(run.aiming());
        assertFalse(run.strafing());
        assertTrue(sprint.sprinting());
        assertFalse(sprint.aiming());
        assertFalse(sprint.strafing());
    }

    @Test
    void sneakingWhileMovingKeepsTheSneakWalkState() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.SNEAK, CaptureSample.Stance.CROUCH, true, true,
            0.0f, 1.0f, false);

        assertTrue(state.moving());
        assertTrue(state.sneaking());
        assertFalse(state.aiming());
        assertFalse(state.strafing());
        assertEquals(GhostAnimationState.SNEAK_SPEED, state.walkSpeed(), 0.0001f);
    }

    @Test
    void crouchedIdleDoesNotUseTheSneakWalkAnimation() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.SNEAK, CaptureSample.Stance.CROUCH, false, true,
            0.0f, 1.0f, false);

        assertFalse(state.moving());
        assertTrue(state.sneaking());
        assertTrue(state.aiming());
        assertFalse(state.strafing());
        assertEquals(0.0f, state.deltaX(), 0.0001f);
        assertEquals(0.0f, state.deltaY(), 0.0001f);
        assertEquals(0.0f, state.walkSpeed(), 0.0001f);
    }

    @Test
    void crouchedIdleWithoutAimAlsoStaysStill() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.SNEAK, CaptureSample.Stance.CROUCH, false, false,
            0.0f, 1.0f, false);

        assertFalse(state.moving());
        assertTrue(state.sneaking());
        assertFalse(state.aiming());
        assertEquals(0.0f, state.walkSpeed(), 0.0001f);
    }

    @Test
    void legacyWalkingFramesStillMoveWithoutTheMovingFlag() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, false, false,
            0.0f, 0.0f, false);

        assertTrue(state.moving());
        assertEquals(GhostAnimationState.WALK_SPEED, state.walkSpeed(), 0.0001f);
    }

    @Test
    void idleAimingKeepsTheAimPoseWithoutStrafing() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.STAND, false, true,
            0.0f, 1.0f, false);

        assertFalse(state.moving());
        assertTrue(state.aiming());
        assertFalse(state.strafing());
        assertEquals(0.0f, state.deltaX(), 0.0001f);
        assertEquals(0.0f, state.deltaY(), 0.0001f);
    }

    @Test
    void activeActionsPreserveTheRecordedAimForAttackVariants() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.RUN, CaptureSample.Stance.STAND, true, true,
            0.0f, 1.0f, true);

        assertTrue(state.aiming());
        assertTrue(state.running());
        assertFalse(state.strafing());
    }

    @Test
    void sidewaysMovementBlendsIntoTheStrafeAnimation() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, true, true,
            1.0f, 0.0f, false);

        assertTrue(state.strafing());
        assertEquals(1.0f, state.deltaX(), 0.0001f);
        assertEquals(0.0f, state.deltaY(), 0.0001f);
    }

    @Test
    void missingMovementFallsBackToForwardWalkWhileAiming() {
        GhostAnimationState state = GhostAnimationState.compose(
            CaptureSample.Locomotion.WALK, CaptureSample.Stance.STAND, true, true,
            0.0f, 0.0f, false);

        assertEquals(0.0f, state.deltaX(), 0.0001f);
        assertEquals(1.0f, state.deltaY(), 0.0001f);
    }

    @Test
    void capturedCrouchedIdleIsIdleInsteadOfSneakWalk() {
        assertEquals(CaptureSample.Locomotion.IDLE,
            CaptureSample.Locomotion.fromState(false, false, true, false));
        assertEquals(CaptureSample.Locomotion.SNEAK,
            CaptureSample.Locomotion.fromState(false, false, true, true));
        assertEquals(CaptureSample.Locomotion.IDLE,
            CaptureSample.Locomotion.fromState(false, false, false, false));
        assertEquals(CaptureSample.Locomotion.WALK,
            CaptureSample.Locomotion.fromState(false, false, false, true));
        assertEquals(CaptureSample.Locomotion.RUN,
            CaptureSample.Locomotion.fromState(false, true, false, true));
        assertEquals(CaptureSample.Locomotion.SPRINT,
            CaptureSample.Locomotion.fromState(true, true, false, true));
    }

    @Test
    void stanceAndNullInputsAreNormalised() {
        GhostAnimationState crouch = GhostAnimationState.compose(
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.CROUCH, false, false,
            0.0f, 0.0f, false);
        GhostAnimationState prone = GhostAnimationState.compose(
            CaptureSample.Locomotion.IDLE, CaptureSample.Stance.PRONE, false, false,
            0.0f, 0.0f, false);
        GhostAnimationState empty = GhostAnimationState.compose(null, null, false, false,
            0.0f, 0.0f, false);

        assertTrue(crouch.sneaking());
        assertTrue(prone.onFloor());
        assertFalse(empty.moving());
        assertEquals(0.0f, empty.walkSpeed(), 0.0001f);
    }
}
