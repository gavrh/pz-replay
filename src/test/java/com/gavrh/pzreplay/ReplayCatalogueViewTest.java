package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayCatalogueViewTest {
    private static final List<ReplayCatalogue.Entry> ENTRIES = List.of(
        entry("Alpha Run.pzrp.gz", 1_000L, 90.0, "Muldraugh, KY", "Alice", true, true),
        entry("bravo.pzrp.gz", 3_000L, 10.0, "Riverside, KY", "Bob", false, false),
        entry("Charlie.pzrp.gz", 2_000L, 30.0, "West Point, KY", "Carol", false, true)
    );

    @Test
    void emptyQueryReturnsAllEntries() {
        assertEquals(3, ReplayCatalogueView.apply(ENTRIES, "", "date", false).size());
        assertEquals(3, ReplayCatalogueView.apply(ENTRIES, "   ", "date", false).size());
    }

    @Test
    void searchMatchesNameMapAndCharacterCaseInsensitively() {
        assertEquals(List.of("Alpha Run.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "alpha", "date", false)));
        assertEquals(List.of("Alpha Run.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "MULDRAUGH", "date", false)));
        assertEquals(List.of("bravo.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "bob", "date", false)));
    }

    @Test
    void searchKeepsUnreadableRowsVisibleWhenTheyMatch() {
        List<ReplayCatalogue.Entry> result =
            ReplayCatalogueView.apply(ENTRIES, "bravo", "date", false);
        assertEquals(1, result.size());
        assertEquals(false, result.get(0).valid());
    }

    @Test
    void searchWithNoMatchesReturnsEmpty() {
        assertEquals(List.of(),
            names(ReplayCatalogueView.apply(ENTRIES, "nothing", "date", false)));
    }

    @Test
    void sortsByDateInBothDirections() {
        assertEquals(List.of("bravo.pzrp.gz", "Charlie.pzrp.gz", "Alpha Run.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "", "date", false)));
        assertEquals(List.of("Alpha Run.pzrp.gz", "Charlie.pzrp.gz", "bravo.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "", "date", true)));
    }

    @Test
    void sortsByNameCaseInsensitivelyInBothDirections() {
        assertEquals(List.of("Alpha Run.pzrp.gz", "bravo.pzrp.gz", "Charlie.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "", "name", true)));
        assertEquals(List.of("Charlie.pzrp.gz", "bravo.pzrp.gz", "Alpha Run.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "", "name", false)));
    }

    @Test
    void sortsByDurationInBothDirections() {
        assertEquals(List.of("bravo.pzrp.gz", "Charlie.pzrp.gz", "Alpha Run.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "", "duration", true)));
        assertEquals(List.of("Alpha Run.pzrp.gz", "Charlie.pzrp.gz", "bravo.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, "", "duration", false)));
    }

    @Test
    void filterAndSortCombine() {
        assertEquals(List.of("Alpha Run.pzrp.gz", "Charlie.pzrp.gz", "bravo.pzrp.gz"),
            names(ReplayCatalogueView.apply(ENTRIES, ".pzrp.gz", "duration", false)));
    }

    @Test
    void selectionKeepsExistingPathWhenVisible() {
        List<ReplayCatalogue.Entry> sorted =
            ReplayCatalogueView.apply(ENTRIES, "", "name", true);
        assertEquals(1, ReplayCatalogueView.selectionIndex(sorted,
            sorted.get(1).path(), -1));
    }

    @Test
    void selectionFallsBackToSameIndexWhenPreferredPathIsGone() {
        List<ReplayCatalogue.Entry> sorted =
            ReplayCatalogueView.apply(ENTRIES, "", "name", true);
        assertEquals(2, ReplayCatalogueView.selectionIndex(sorted,
            "/gone/missing.pzrp.gz", 2));
    }

    @Test
    void selectionClampsFallbackAndUsesLastReplayForInitialOpen() {
        List<ReplayCatalogue.Entry> sorted =
            ReplayCatalogueView.apply(ENTRIES, "", "name", true);
        assertEquals(2, ReplayCatalogueView.selectionIndex(sorted, "", 99));
        assertEquals(0, ReplayCatalogueView.selectionIndex(sorted, "", -1));
        assertEquals(-1, ReplayCatalogueView.selectionIndex(List.of(), "", -1));
    }

    private static ReplayCatalogue.Entry entry(String fileName, long modified,
        double duration, String map, String player, boolean lastReplay, boolean valid) {
        return new ReplayCatalogue.Entry(fileName, "/replays/" + fileName, modified,
            100L, valid, valid ? "" : "unreadable", duration, 30.0, map, "save",
            "Sandbox", player, lastReplay);
    }

    private static List<String> names(List<ReplayCatalogue.Entry> entries) {
        return entries.stream().map(ReplayCatalogue.Entry::fileName).toList();
    }
}
