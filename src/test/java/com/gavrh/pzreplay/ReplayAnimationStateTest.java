package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayAnimationStateTest {
    @Test
    void walkingWhileAimingKeepsTheWalkAnimationAndAimBlend() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, true, true,
            0.0f, 1.0f, false);

        assertTrue(state.moving());
        assertTrue(state.aiming());
        assertTrue(state.strafing());
        assertFalse(state.running());
        assertFalse(state.sprinting());
        assertEquals(1.0f, state.deltaY(), 0.0001f);
        assertEquals(ReplayAnimationState.WALK_SPEED, state.walkSpeed(), 0.0001f);
    }

    @Test
    void runningAndSprintingKeepTheirLocomotionClipsWhileAiming() {
        ReplayAnimationState run = ReplayAnimationState.compose(
            ReplaySample.Locomotion.RUN, ReplaySample.Stance.STAND, true, true,
            0.0f, 1.0f, false);
        ReplayAnimationState sprint = ReplayAnimationState.compose(
            ReplaySample.Locomotion.SPRINT, ReplaySample.Stance.STAND, true, true,
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
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.SNEAK, ReplaySample.Stance.CROUCH, true, true,
            0.0f, 1.0f, false);

        assertTrue(state.moving());
        assertTrue(state.sneaking());
        assertFalse(state.aiming());
        assertFalse(state.strafing());
        assertEquals(ReplayAnimationState.SNEAK_SPEED, state.walkSpeed(), 0.0001f);
    }

    @Test
    void crouchedIdleDoesNotUseTheSneakWalkAnimation() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.SNEAK, ReplaySample.Stance.CROUCH, false, true,
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
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.SNEAK, ReplaySample.Stance.CROUCH, false, false,
            0.0f, 1.0f, false);

        assertFalse(state.moving());
        assertTrue(state.sneaking());
        assertFalse(state.aiming());
        assertEquals(0.0f, state.walkSpeed(), 0.0001f);
    }

    @Test
    void legacyWalkingFramesStillMoveWithoutTheMovingFlag() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, false, false,
            0.0f, 0.0f, false);

        assertTrue(state.moving());
        assertEquals(ReplayAnimationState.WALK_SPEED, state.walkSpeed(), 0.0001f);
    }

    @Test
    void idleAimingKeepsTheAimPoseWithoutStrafing() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.STAND, false, true,
            0.0f, 1.0f, false);

        assertFalse(state.moving());
        assertTrue(state.aiming());
        assertFalse(state.strafing());
        assertEquals(0.0f, state.deltaX(), 0.0001f);
        assertEquals(0.0f, state.deltaY(), 0.0001f);
    }

    @Test
    void activeActionsPreserveTheRecordedAimForAttackVariants() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.RUN, ReplaySample.Stance.STAND, true, true,
            0.0f, 1.0f, true);

        assertTrue(state.aiming());
        assertTrue(state.running());
        assertFalse(state.strafing());
    }

    @Test
    void sidewaysMovementBlendsIntoTheStrafeAnimation() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, true, true,
            1.0f, 0.0f, false);

        assertTrue(state.strafing());
        assertEquals(1.0f, state.deltaX(), 0.0001f);
        assertEquals(0.0f, state.deltaY(), 0.0001f);
    }

    @Test
    void missingMovementFallsBackToForwardWalkWhileAiming() {
        ReplayAnimationState state = ReplayAnimationState.compose(
            ReplaySample.Locomotion.WALK, ReplaySample.Stance.STAND, true, true,
            0.0f, 0.0f, false);

        assertEquals(0.0f, state.deltaX(), 0.0001f);
        assertEquals(1.0f, state.deltaY(), 0.0001f);
    }

    @Test
    void capturedCrouchedIdleIsIdleInsteadOfSneakWalk() {
        assertEquals(ReplaySample.Locomotion.IDLE,
            ReplaySample.Locomotion.fromState(false, false, true, false));
        assertEquals(ReplaySample.Locomotion.SNEAK,
            ReplaySample.Locomotion.fromState(false, false, true, true));
        assertEquals(ReplaySample.Locomotion.IDLE,
            ReplaySample.Locomotion.fromState(false, false, false, false));
        assertEquals(ReplaySample.Locomotion.WALK,
            ReplaySample.Locomotion.fromState(false, false, false, true));
        assertEquals(ReplaySample.Locomotion.RUN,
            ReplaySample.Locomotion.fromState(false, true, false, true));
        assertEquals(ReplaySample.Locomotion.SPRINT,
            ReplaySample.Locomotion.fromState(true, true, false, true));
    }

    @Test
    void stanceAndNullInputsAreNormalised() {
        ReplayAnimationState crouch = ReplayAnimationState.compose(
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.CROUCH, false, false,
            0.0f, 0.0f, false);
        ReplayAnimationState prone = ReplayAnimationState.compose(
            ReplaySample.Locomotion.IDLE, ReplaySample.Stance.PRONE, false, false,
            0.0f, 0.0f, false);
        ReplayAnimationState empty = ReplayAnimationState.compose(null, null, false, false,
            0.0f, 0.0f, false);

        assertTrue(crouch.sneaking());
        assertTrue(prone.onFloor());
        assertFalse(empty.moving());
        assertEquals(0.0f, empty.walkSpeed(), 0.0001f);
    }
}
