package com.gavrh.pzreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldCoverageWindowTest {
    private static final int PADDED_33 = 33 * 33;
    private static final int INTERIOR_33 = 31 * 31;
    private static final int BOUNDARY_33 = PADDED_33 - INTERIOR_33;

    private static WorldCoverageWindow loadedSurface() {
        WorldCoverageWindow window = new WorldCoverageWindow();
        loadedLayer(window);
        return window;
    }

    private static void loadedLayer(WorldCoverageWindow window) {
        for (int square = 0; square < PADDED_33; square++) {
            window.loaded();
        }
    }

    private static void emptyLayer(WorldCoverageWindow window) {
        for (int square = 0; square < PADDED_33; square++) {
            window.empty();
        }
    }

    @Test
    void playerAtSurfaceWithLoadedSurfaceIsComplete() {
        WorldCoverageWindow window = new WorldCoverageWindow();
        emptyLayer(window);
        loadedLayer(window);
        emptyLayer(window);

        assertEquals(PADDED_33, window.expectedSquares());
        assertEquals("complete", window.status());
    }

    @Test
    void playerBelowWithLoadedSurfaceAndEmptyBasementIsComplete() {
        WorldCoverageWindow window = new WorldCoverageWindow();
        emptyLayer(window);
        emptyLayer(window);
        loadedLayer(window);

        assertEquals(0, window.missingInterior());
        assertEquals(0, window.missingBoundary());
        assertEquals("complete", window.status());
    }

    @Test
    void playerBelowWithRealMissingSurfaceSquareIsPartial() {
        WorldCoverageWindow window = loadedSurface();
        window.missing(false);
        emptyLayer(window);

        assertEquals(1, window.missingInterior());
        assertEquals("partial", window.status());
    }

    @Test
    void unloadedChunkAtScannedLayerIsPartial() {
        WorldCoverageWindow window = loadedSurface();
        for (int layer = 0; layer < 3; layer++) {
            for (int square = 0; square < 49; square++) {
                window.missing(false);
            }
            for (int square = 0; square < 15; square++) {
                window.missing(true);
            }
        }

        assertEquals(3 * 64, window.missingInterior() + window.missingBoundary());
        assertEquals("partial", window.status());
    }

    @Test
    void truncatedLogRemainsTruncated() {
        assertEquals(
                "truncated",
                WorldCoverageStatus.combine("truncated", loadedSurface().status()));
    }

    @Test
    void boundaryOnlyMissingBelowAllowanceIsComplete() {
        WorldCoverageWindow window = loadedSurface();
        for (int square = 0; square < 10; square++) {
            window.missing(true);
        }

        assertEquals("complete", window.status());
    }

    @Test
    void interiorMissingIsPartial() {
        WorldCoverageWindow window = loadedSurface();
        window.missing(false);

        assertEquals("partial", window.status());
    }

    @Test
    void emptyWorldWithUnloadedChunkIsPartial() {
        WorldCoverageWindow window = new WorldCoverageWindow();
        for (int layer = 0; layer < 3; layer++) {
            for (int square = 0; square < INTERIOR_33; square++) {
                window.missing(false);
            }
            for (int square = 0; square < BOUNDARY_33; square++) {
                window.missing(true);
            }
        }

        assertEquals(3 * PADDED_33, window.expectedSquares());
        assertEquals(3 * INTERIOR_33, window.missingInterior());
        assertEquals(3 * BOUNDARY_33, window.missingBoundary());
        assertEquals("partial", window.status());
    }

    @Test
    void loadedSquaresExcludeEmptySquaresFromExpected() {
        WorldCoverageWindow window = loadedSurface();
        emptyLayer(window);
        window.missing(false);

        assertEquals(1089, window.expectedSquares() - window.missingInterior() - window.missingBoundary());
    }
}
