package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldSeekDecisionTest {
    @Test
    void forwardSeekDoesNotTriggerReload() {
        assertEquals(WorldSeekDecision.Action.APPLY_FORWARD,
            WorldSeekDecision.seek(true, 1.0, 2.0));
        assertEquals(WorldSeekDecision.Action.APPLY_FORWARD,
            WorldSeekDecision.seek(true, 1.0, 1.0));
    }

    @Test
    void backwardSeekTriggersReload() {
        assertEquals(WorldSeekDecision.Action.RELOAD_REQUIRED,
            WorldSeekDecision.seek(true, 2.0, 1.0));
        assertEquals(WorldSeekDecision.Action.RELOAD_REQUIRED,
            WorldSeekDecision.seek(true, 2.0, 0.0));
    }

    @Test
    void restartTriggersReloadForWorldPlayback() {
        assertEquals(WorldSeekDecision.Action.RELOAD_REQUIRED,
            WorldSeekDecision.restart(true));
        assertEquals(WorldSeekDecision.Action.NORMAL,
            WorldSeekDecision.restart(false));
    }

    @Test
    void actorOnlyPlaybackKeepsTheNormalPath() {
        assertEquals(WorldSeekDecision.Action.NORMAL,
            WorldSeekDecision.seek(false, 2.0, 1.0));
    }
}
