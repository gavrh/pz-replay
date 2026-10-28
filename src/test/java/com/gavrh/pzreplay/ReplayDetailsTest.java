package com.gavrh.pzreplay;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayDetailsTest {
    private static final long MODIFIED = 1_700_000_000_000L;

    @Test
    void mapsEveryRequiredFieldForAValidReplay() {
        ReplayCatalogue.Entry entry = new ReplayCatalogue.Entry(
            "Morning Run.pzrp.gz", "/replays/Morning Run.pzrp.gz", MODIFIED, 2_097_152L,
            true, "", 90.0, 30.0, "Muldraugh, KY", "pz-replay-test", "Sandbox",
            "Alice", true);

        Map<String, String> rows = rows(ReplayDetails.rows(entry, true));

        assertEquals("Morning Run.pzrp.gz", rows.get("Name"));
        assertTrue(rows.get("Date / duration").endsWith("/ 1:30"));
        assertEquals("Muldraugh, KY", rows.get("Map"));
        assertEquals("Alice", rows.get("Character"));
        assertEquals("pz-replay-test / Sandbox", rows.get("Save / mode"));
        assertEquals("2.0 MB / 30 Hz", rows.get("Size / rate"));
        assertEquals("valid", rows.get("Status"));
        assertEquals("yes / yes", rows.get("Favorite / last"));
    }

    @Test
    void favouriteAndLastReplayStatesAreIndependent() {
        ReplayCatalogue.Entry entry = entry(true);
        assertEquals("yes / yes", rows(ReplayDetails.rows(entry, true)).get("Favorite / last"));
        assertEquals("no / yes", rows(ReplayDetails.rows(entry, false)).get("Favorite / last"));

        ReplayCatalogue.Entry notLast = entry(false);
        assertEquals("yes / no", rows(ReplayDetails.rows(notLast, true)).get("Favorite / last"));
    }

    @Test
    void unreadableReplayShowsItsErrorAndUnknownMetadata() {
        ReplayCatalogue.Entry entry = new ReplayCatalogue.Entry(
            "broken.pzrp.gz", "/replays/broken.pzrp.gz", 0L, 12L, false, "checksum mismatch",
            0.0, 0.0, "", "", "", "", false);

        Map<String, String> rows = rows(ReplayDetails.rows(entry, false));

        assertEquals("unknown date / 0:00", rows.get("Date / duration"));
        assertEquals("unknown map", rows.get("Map"));
        assertEquals("unknown character", rows.get("Character"));
        assertEquals("unknown save / unknown mode", rows.get("Save / mode"));
        assertEquals("12 B / unknown", rows.get("Size / rate"));
        assertEquals("unreadable: checksum mismatch", rows.get("Status"));
    }

    @Test
    void nullEntryHasNoRows() {
        assertEquals(List.of(), ReplayDetails.rows(null, false));
    }

    @Test
    void formatsDurationSizeAndRateStably() {
        assertEquals("0:00", ReplayDetails.formatDuration(0.0));
        assertEquals("0:00", ReplayDetails.formatDuration(Double.NaN));
        assertEquals("1:30", ReplayDetails.formatDuration(90.4));
        assertEquals("512 B", ReplayDetails.formatSize(512L));
        assertEquals("2.0 KB", ReplayDetails.formatSize(2048L));
        assertEquals("2.0 MB", ReplayDetails.formatSize(2_097_152L));
        assertEquals("unknown size", ReplayDetails.formatSize(-1L));
        assertEquals("30 Hz", ReplayDetails.formatRate(30.0));
        assertEquals("25.5 Hz", ReplayDetails.formatRate(25.5));
        assertEquals("unknown", ReplayDetails.formatRate(0.0));
        assertEquals("unknown date", ReplayDetails.formatDate(0L));
    }

    private static ReplayCatalogue.Entry entry(boolean lastReplay) {
        return new ReplayCatalogue.Entry("run.pzrp.gz", "/replays/run.pzrp.gz", MODIFIED,
            1024L, true, "", 10.0, 30.0, "Riverside, KY", "save", "Survival", "Bob",
            lastReplay);
    }

    private static Map<String, String> rows(List<ReplayDetails.Row> rows) {
        return rows.stream().collect(Collectors.toMap(ReplayDetails.Row::label,
            ReplayDetails.Row::value));
    }
}
