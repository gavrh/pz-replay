package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayControlStateTest {
    @Test
    void recordingProgressAndStopUpdatesStructuredState() {
        ReplayControlState state = new ReplayControlState();
        state.recordingStarted(20.0);
        state.recordingProgress(12.5f, 250, 20.0, 19.8);

        assertTrue(state.isRecording());
        assertEquals(12.5f, state.getRecordingSeconds());
        assertEquals(250, state.getRecordedFrames());
        assertEquals(20.0, state.getConfiguredSampleRateHz());
        assertEquals(19.8, state.getActualSampleRateHz());

        state.recordingStopped("/tmp/replay.pzrp.gz", "frames=250", "");
        assertFalse(state.isRecording());
        assertEquals("/tmp/replay.pzrp.gz", state.getLastReplayPath());
        assertEquals("frames=250", state.getLastReplaySummary());
        assertEquals("", state.getCaptureError());
    }

    @Test
    void failedCaptureReportsErrorAndKeepsLastReplay() {
        ReplayControlState state = new ReplayControlState();
        state.recordingStarted(30.0);
        state.recordingStopped("/tmp/ok.pzrp.gz", "frames=3", "");
        state.recordingStarted(30.0);
        state.captureFailed("no frames");

        assertFalse(state.isRecording());
        assertEquals("no frames", state.getCaptureError());
        assertEquals("/tmp/ok.pzrp.gz", state.getLastReplayPath());

        state.recordingStarted(30.0);
        assertEquals("", state.getCaptureError());
    }

    @Test
    void playbackCannotStartWhileRecordingOrPlaying() {
        ReplayControlState state = new ReplayControlState();
        state.recordingStarted(30.0);
        assertFalse(state.playbackStarted(10.0));

        state.recordingStopped("", "", "");
        assertTrue(state.playbackStarted(10.0));
        assertFalse(state.playbackStarted(10.0));
    }

    @Test
    void playbackProgressStopAndFailureAreDeterministic() {
        ReplayControlState state = new ReplayControlState();
        assertTrue(state.playbackStarted(20.0));
        state.playbackProgress(5.5, 20.0, true);

        assertTrue(state.isPlaying());
        assertEquals(5.5, state.getPlaybackTime());
        assertEquals(20.0, state.getPlaybackDuration());
        assertTrue(state.isCameraFollow());

        state.playbackFailed("missing replay");
        assertFalse(state.isPlaying());
        assertEquals("missing replay", state.getPlaybackError());
        assertFalse(state.isCameraFollow());

        assertTrue(state.playbackStarted(30.0));
        assertEquals("", state.getPlaybackError());
        state.playbackStopped();
        assertFalse(state.isPlaying());
    }

    @Test
    void compatibilityWarningsAreClearedOnNewPlayback() {
        ReplayControlState state = new ReplayControlState();
        state.playbackStarted(10.0);
        state.setCompatibilityWarnings(List.of("map mismatch", "save mismatch"));
        assertEquals(2, state.getCompatibilityWarnings().size());

        state.playbackFailed("bad");
        assertTrue(state.playbackStarted(10.0));
        assertEquals(0, state.getCompatibilityWarnings().size());
    }

    @Test
    void clearErrorsLeavesProgressUntouched() {
        ReplayControlState state = new ReplayControlState();
        state.captureFailed("capture broke");
        state.playbackFailed("playback broke");
        state.clearErrors();

        assertEquals("", state.getCaptureError());
        assertEquals("", state.getPlaybackError());
    }
}
