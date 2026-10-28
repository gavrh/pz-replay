package com.gavrh.pzreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsAreSafeAndDistinct() {
        ReplaySettings settings = new ReplaySettings();

        assertEquals(CaptureSession.DEFAULT_SAMPLE_RATE_HZ, settings.getSampleRate());
        assertEquals(RecordingCountdown.DEFAULT_SECONDS, settings.getCountdownSeconds());
        assertFalse(settings.isCameraFollowDefault());
        assertFalse(settings.isDebugControls());
        assertFalse(settings.isKillSwitch());
        assertEquals(ReplaySettings.DEFAULT_KEY_RECORD,
            settings.key(ReplaySettings.ACTION_RECORD));
        assertEquals(ReplaySettings.DEFAULT_KEY_PLAY, settings.key(ReplaySettings.ACTION_PLAY));
        assertEquals(ReplaySettings.DEFAULT_KEY_CAMERA,
            settings.key(ReplaySettings.ACTION_CAMERA));
        assertEquals(ReplaySettings.DEFAULT_KEY_PANEL, settings.key(ReplaySettings.ACTION_PANEL));

        Set<Integer> keys = new HashSet<>();
        for (String action : ReplaySettings.ACTIONS) {
            assertTrue(settings.key(action) > 0);
            assertTrue(keys.add(settings.key(action)), "duplicate default for " + action);
        }
    }

    @Test
    void sampleRateAcceptsTwentyAndThirtyAndRejectsOutside() {
        ReplaySettings settings = new ReplaySettings();

        assertTrue(ReplaySettings.isValidSampleRate(20.0));
        assertTrue(ReplaySettings.isValidSampleRate(30.0));
        assertTrue(settings.setSampleRate(20.0));
        assertEquals(20.0, settings.getSampleRate());
        assertTrue(settings.setSampleRate(25.5));
        assertEquals(25.5, settings.getSampleRate());

        assertFalse(ReplaySettings.isValidSampleRate(19.99));
        assertFalse(ReplaySettings.isValidSampleRate(30.01));
        assertFalse(ReplaySettings.isValidSampleRate(Double.NaN));
        assertFalse(ReplaySettings.isValidSampleRate(Double.POSITIVE_INFINITY));
        assertFalse(settings.setSampleRate(19.99));
        assertFalse(settings.setSampleRate(30.01));
        assertEquals(25.5, settings.getSampleRate());
    }

    @Test
    void countdownAcceptsZeroThroughTenAndRejectsOutside() {
        ReplaySettings settings = new ReplaySettings();

        assertTrue(settings.setCountdownSeconds(0));
        assertEquals(0, settings.getCountdownSeconds());
        assertTrue(settings.setCountdownSeconds(10));
        assertEquals(10, settings.getCountdownSeconds());
        assertTrue(settings.setCountdownSeconds(4));
        assertEquals(4, settings.getCountdownSeconds());

        assertFalse(settings.setCountdownSeconds(-1));
        assertFalse(settings.setCountdownSeconds(11));
        assertEquals(4, settings.getCountdownSeconds());
    }

    @Test
    void malformedCountdownFallsBackToDefault() throws Exception {
        Path path = tempDir.resolve("countdown.properties");
        Files.writeString(path, "countdown_seconds=abc\n");
        assertEquals(RecordingCountdown.DEFAULT_SECONDS,
            ReplaySettings.load(path).getCountdownSeconds());

        Files.writeString(path, "countdown_seconds=99\n");
        assertEquals(RecordingCountdown.DEFAULT_SECONDS,
            ReplaySettings.load(path).getCountdownSeconds());

        Files.writeString(path, "countdown_seconds=-1\n");
        assertEquals(RecordingCountdown.DEFAULT_SECONDS,
            ReplaySettings.load(path).getCountdownSeconds());

        Files.writeString(path, "countdown_seconds=0\n");
        assertEquals(0, ReplaySettings.load(path).getCountdownSeconds());
    }

    @Test
    void persistenceRoundTripsAllSettings() throws Exception {
        Path path = tempDir.resolve("pz-replay/settings.properties");
        ReplaySettings settings = new ReplaySettings();
        settings.setSampleRate(20.0);
        settings.setCountdownSeconds(7);
        settings.setCameraFollowDefault(true);
        settings.setDebugControls(true);
        settings.setKillSwitch(true);
        assertTrue(settings.bind(ReplaySettings.ACTION_RECORD, 210));
        settings.save(path);

        ReplaySettings loaded = ReplaySettings.load(path);

        assertEquals(20.0, loaded.getSampleRate());
        assertEquals(7, loaded.getCountdownSeconds());
        assertTrue(loaded.isCameraFollowDefault());
        assertTrue(loaded.isDebugControls());
        assertTrue(loaded.isKillSwitch());
        assertEquals(210, loaded.key(ReplaySettings.ACTION_RECORD));
        assertEquals(ReplaySettings.DEFAULT_KEY_PLAY,
            loaded.key(ReplaySettings.ACTION_PLAY));
    }

    @Test
    void missingAndCorruptFilesFallBackToDefaults() throws Exception {
        Path missing = tempDir.resolve("missing.properties");
        ReplaySettings missingSettings = ReplaySettings.load(missing);
        assertEquals(CaptureSession.DEFAULT_SAMPLE_RATE_HZ, missingSettings.getSampleRate());

        Path corrupt = tempDir.resolve("corrupt.properties");
        Files.writeString(corrupt, "sample_rate_hz=99\ncamera_follow_default=maybe\n"
            + "key.record=0\nkey.play=209\nnot a setting\n");
        ReplaySettings corruptSettings = ReplaySettings.load(corrupt);

        assertEquals(CaptureSession.DEFAULT_SAMPLE_RATE_HZ, corruptSettings.getSampleRate());
        assertFalse(corruptSettings.isCameraFollowDefault());
        assertFalse(corruptSettings.isDebugControls());
        assertFalse(corruptSettings.isKillSwitch());
        Set<Integer> keys = new HashSet<>();
        for (String action : ReplaySettings.ACTIONS) {
            assertTrue(corruptSettings.key(action) > 0);
            assertTrue(keys.add(corruptSettings.key(action)));
        }
    }

    @Test
    void duplicateKeysInFileAreResolvedToDistinctBindings() throws Exception {
        Path path = tempDir.resolve("duplicates.properties");
        Files.writeString(path, "key.record=209\nkey.play=209\nkey.camera=209\nkey.panel=209\n");

        ReplaySettings loaded = ReplaySettings.load(path);
        Set<Integer> keys = new HashSet<>();
        for (String action : ReplaySettings.ACTIONS) {
            assertTrue(loaded.key(action) > 0);
            assertTrue(keys.add(loaded.key(action)), "duplicate key for " + action);
        }
    }

    @Test
    void bindingRejectsDuplicatesAndUnknownActions() {
        ReplaySettings settings = new ReplaySettings();

        assertFalse(settings.bind(ReplaySettings.ACTION_RECORD, 0));
        assertFalse(settings.bind(ReplaySettings.ACTION_RECORD,
            settings.key(ReplaySettings.ACTION_PLAY)));
        assertFalse(settings.bind("unknown", 200));
        assertTrue(settings.bind(ReplaySettings.ACTION_RECORD, 210));
        assertEquals(210, settings.key(ReplaySettings.ACTION_RECORD));
    }

    @Test
    void conflictsReportKnownVanillaAndOtherBindings() {
        ReplaySettings settings = new ReplaySettings();

        List<ReplaySettings.Conflict> conflicts = settings.conflicts(
            Map.of(ReplaySettings.DEFAULT_KEY_RECORD, "PageUp"));

        assertEquals(1, conflicts.size());
        assertEquals(ReplaySettings.ACTION_RECORD, conflicts.get(0).action());
        assertEquals("PageUp", conflicts.get(0).other());
    }
}
