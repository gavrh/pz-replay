package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraSmoothingTest {
    @Test
    void stepMovesTowardTheTargetWithoutOvershoot() {
        CameraSmoothing smoothing = new CameraSmoothing();
        assertTrue(smoothing.resetTo(0.0f, 0.0f));
        assertTrue(smoothing.setTarget(10.0f, 0.0f));

        smoothing.update(1.0f / 60.0f);

        assertTrue(smoothing.getX() > 0.0f);
        assertTrue(smoothing.getX() < 10.0f);
        assertTrue(smoothing.getY() == 0.0f);
    }

    @Test
    void stepIsBoundedByMaximumSpeed() {
        CameraSmoothing smoothing = new CameraSmoothing();
        smoothing.resetTo(0.0f, 0.0f);
        smoothing.setTarget(15.0f, 0.0f);

        smoothing.update(0.001f);

        float maximum = CameraSmoothing.MAX_SPEED_TILES_PER_SECOND * 0.001f;
        assertTrue(smoothing.getX() <= maximum + 0.0001f);
    }

    @Test
    void smoothingConvergesAndSnapsToTheTarget() {
        CameraSmoothing smoothing = new CameraSmoothing();
        smoothing.resetTo(0.0f, 0.0f);
        smoothing.setTarget(0.8f, -0.4f);

        for (int index = 0; index < 300; index++) {
            smoothing.update(1.0f / 60.0f);
        }

        assertEquals(0.8f, smoothing.getX());
        assertEquals(-0.4f, smoothing.getY());
        assertTrue(smoothing.isAtTarget());
    }

    @Test
    void frameRateDoesNotChangeTheSettledResult() {
        CameraSmoothing fast = new CameraSmoothing();
        fast.resetTo(0.0f, 0.0f);
        fast.setTarget(2.0f, 1.0f);
        CameraSmoothing slow = new CameraSmoothing();
        slow.resetTo(0.0f, 0.0f);
        slow.setTarget(2.0f, 1.0f);

        for (int index = 0; index < 30; index++) {
            fast.update(1.0f / 60.0f);
        }
        for (int index = 0; index < 5; index++) {
            slow.update(0.1f);
        }

        assertEquals(2.0f, fast.getX());
        assertEquals(1.0f, fast.getY());
        assertEquals(fast.getX(), slow.getX(), 0.001f);
        assertEquals(fast.getY(), slow.getY(), 0.001f);
    }

    @Test
    void largeTargetChangeSnapsInsteadOfSweeping() {
        CameraSmoothing smoothing = new CameraSmoothing();
        smoothing.resetTo(0.0f, 0.0f);

        smoothing.setTarget(CameraSmoothing.MAX_TARGET_DISTANCE_TILES + 1.0f, 0.0f);

        assertEquals(CameraSmoothing.MAX_TARGET_DISTANCE_TILES + 1.0f,
            smoothing.getX());
        assertTrue(smoothing.isAtTarget());
    }

    @Test
    void invalidTargetsAndDeltasAreIgnored() {
        CameraSmoothing smoothing = new CameraSmoothing();
        assertFalse(smoothing.resetTo(Float.NaN, 0.0f));
        assertFalse(smoothing.setTarget(0.0f, Float.POSITIVE_INFINITY));
        assertFalse(smoothing.isActive());

        assertTrue(smoothing.resetTo(1.0f, 2.0f));
        smoothing.setTarget(4.0f, 6.0f);
        smoothing.update(Float.NaN);
        smoothing.update(Float.POSITIVE_INFINITY);
        smoothing.update(0.0f);
        smoothing.update(-1.0f);

        assertEquals(1.0f, smoothing.getX());
        assertEquals(2.0f, smoothing.getY());
        assertTrue(smoothing.isActive());
    }

    @Test
    void targetIsIgnoredWhenItIsNotFinite() {
        CameraSmoothing smoothing = new CameraSmoothing();
        smoothing.resetTo(1.0f, 2.0f);
        smoothing.setTarget(5.0f, 6.0f);

        assertFalse(smoothing.setTarget(Float.NaN, 7.0f));

        assertEquals(5.0f, smoothing.getTargetX());
        assertEquals(6.0f, smoothing.getTargetY());
    }

    @Test
    void resetAndClearReplaceState() {
        CameraSmoothing smoothing = new CameraSmoothing();
        smoothing.resetTo(3.0f, 4.0f);
        smoothing.setTarget(8.0f, 9.0f);

        assertTrue(smoothing.resetTo(1.0f, 1.0f));
        assertEquals(1.0f, smoothing.getX());
        assertEquals(1.0f, smoothing.getTargetX());

        smoothing.clear();
        assertFalse(smoothing.isActive());
        assertEquals(0.0f, smoothing.getX());
        assertEquals(0.0f, smoothing.getTargetX());
        assertTrue(smoothing.isAtTarget());
    }

    @Test
    void firstTargetSnapsWhenNoStateExists() {
        CameraSmoothing smoothing = new CameraSmoothing();

        assertTrue(smoothing.setTarget(2.0f, 3.0f));

        assertTrue(smoothing.isActive());
        assertEquals(2.0f, smoothing.getX());
        assertEquals(3.0f, smoothing.getY());
    }
}
