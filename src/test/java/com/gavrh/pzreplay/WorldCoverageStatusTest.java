package com.gavrh.pzreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldCoverageStatusTest {
    private static final int PADDED_33 = 33 * 33;
    private static final int INTERIOR_33 = 31 * 31;
    private static final int BOUNDARY_33 = PADDED_33 - INTERIOR_33;

    @Test
    void zeroMissingIsComplete() {
        assertEquals("complete", WorldCoverageStatus.classify(PADDED_33, 0, 0));
    }

    @Test
    void oneBoundaryMissingIsComplete() {
        assertEquals("complete", WorldCoverageStatus.classify(PADDED_33, 0, 1));
    }

    @Test
    void boundaryAllowanceIsExactAtItsLimit() {
        assertEquals("complete", WorldCoverageStatus.classify(PADDED_33, 0, 10));
        assertEquals("partial", WorldCoverageStatus.classify(PADDED_33, 0, 11));
    }

    @Test
    void manyBoundaryMissesArePartial() {
        assertEquals("partial", WorldCoverageStatus.classify(PADDED_33, 0, BOUNDARY_33));
    }

    @Test
    void oneInteriorMissingIsPartial() {
        assertEquals("partial", WorldCoverageStatus.classify(PADDED_33, 1, 0));
    }

    @Test
    void allMissingIsPartial() {
        assertEquals("partial", WorldCoverageStatus.classify(PADDED_33, INTERIOR_33, BOUNDARY_33));
    }

    @Test
    void boundaryAllowanceScalesWithExpectedArea() {
        assertEquals("complete", WorldCoverageStatus.classify(200, 0, 2));
        assertEquals("partial", WorldCoverageStatus.classify(200, 0, 3));
        assertEquals("partial", WorldCoverageStatus.classify(99, 0, 1));
    }

    @Test
    void smallPaddedAreasDoNotAllowBoundaryMisses() {
        assertEquals("partial", WorldCoverageStatus.classify(9, 0, 1));
        assertEquals("partial", WorldCoverageStatus.classify(1, 0, 1));
    }

    @Test
    void truncatedStatusIsNeverFoldedIntoPartial() {
        assertEquals("truncated", WorldCoverageStatus.combine("truncated", "complete"));
        assertEquals("truncated", WorldCoverageStatus.combine("truncated", "partial"));
    }

    @Test
    void combineKeepsTheStricterScanStatus() {
        assertEquals("partial", WorldCoverageStatus.combine("complete", "partial"));
        assertEquals("partial", WorldCoverageStatus.combine("partial", "complete"));
        assertEquals("complete", WorldCoverageStatus.combine("complete", "complete"));
    }
}
