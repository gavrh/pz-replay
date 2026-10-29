package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeCameraTest {
    @Test
    void panMovesRelativeToTheAnchor() {
        FreeCamera camera = new FreeCamera();
        assertFalse(camera.isPositioned());
        assertEquals(0.0f, camera.offsetX(10.0f));

        assertTrue(camera.moveTo(10.0f, 20.0f));
        assertTrue(camera.isPositioned());
        assertEquals(0.0f, camera.offsetX(10.0f));

        camera.pan(2.5f, -1.5f, 10.0f, 20.0f);
        assertEquals(12.5f, camera.getX());
        assertEquals(18.5f, camera.getY());
        assertEquals(2.5f, camera.offsetX(10.0f));
        assertEquals(-1.5f, camera.offsetY(20.0f));
    }

    @Test
    void panClampsToTheMaximumDistance() {
        FreeCamera camera = new FreeCamera();
        camera.moveTo(0.0f, 0.0f);

        camera.pan(100.0f, 0.0f, 0.0f, 0.0f);

        assertEquals(FreeCamera.MAX_DISTANCE_TILES, camera.getX(), 0.001f);
        assertEquals(0.0f, camera.getY(), 0.001f);
        assertEquals(FreeCamera.MAX_DISTANCE_TILES, camera.offsetX(0.0f), 0.001f);
    }

    @Test
    void clampHandlesDiagonalDistance() {
        FreeCamera camera = new FreeCamera();
        camera.moveTo(0.0f, 0.0f);

        camera.pan(100.0f, 100.0f, 0.0f, 0.0f);

        float offsetX = camera.offsetX(0.0f);
        float offsetY = camera.offsetY(0.0f);
        assertEquals(FreeCamera.MAX_DISTANCE_TILES,
            (float) Math.hypot(offsetX, offsetY), 0.001f);
        assertEquals(offsetX, offsetY, 0.001f);
    }

    @Test
    void invalidInputIsIgnored() {
        FreeCamera camera = new FreeCamera();
        camera.moveTo(5.0f, 5.0f);

        camera.pan(Float.NaN, 1.0f, 0.0f, 0.0f);
        camera.pan(1.0f, Float.POSITIVE_INFINITY, 0.0f, 0.0f);
        camera.clampTo(Float.NaN, 0.0f);

        assertEquals(5.0f, camera.getX());
        assertEquals(5.0f, camera.getY());
        assertFalse(camera.moveTo(Float.NaN, 0.0f));
    }

    @Test
    void anchorMovementReclampsThePosition() {
        FreeCamera camera = new FreeCamera();
        camera.moveTo(20.0f, 0.0f);
        assertEquals(20.0f, camera.offsetX(0.0f));

        camera.clampTo(60.0f, 0.0f);

        assertEquals(FreeCamera.MAX_DISTANCE_TILES,
            Math.abs(camera.offsetX(60.0f)), 0.001f);
    }

    @Test
    void resetClearsThePosition() {
        FreeCamera camera = new FreeCamera();
        camera.moveTo(3.0f, 4.0f);
        camera.reset();

        assertFalse(camera.isPositioned());
        assertEquals(0.0f, camera.offsetX(0.0f));
        assertEquals(0.0f, camera.offsetY(0.0f));
    }
}
