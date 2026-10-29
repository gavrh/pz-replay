package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayCameraModeTest {
    @Test
    void modesExposeStableIdsAndLabels() {
        assertEquals("player", ReplayCameraMode.PLAYER.id());
        assertEquals("Player", ReplayCameraMode.PLAYER.label());
        assertEquals("follow_ghost", ReplayCameraMode.FOLLOW_GHOST.id());
        assertEquals("Follow ghost", ReplayCameraMode.FOLLOW_GHOST.label());
        assertEquals("free", ReplayCameraMode.FREE.id());
        assertEquals("Free camera", ReplayCameraMode.FREE.label());
    }

    @Test
    void fromIdAcceptsKnownIdsCaseInsensitively() {
        assertEquals(ReplayCameraMode.PLAYER, ReplayCameraMode.fromId("player"));
        assertEquals(ReplayCameraMode.FOLLOW_GHOST, ReplayCameraMode.fromId(" FOLLOW_GHOST "));
        assertEquals(ReplayCameraMode.FREE, ReplayCameraMode.fromId("Free"));
    }

    @Test
    void fromIdRejectsUnknownValues() {
        assertNull(ReplayCameraMode.fromId(null));
        assertNull(ReplayCameraMode.fromId(""));
        assertNull(ReplayCameraMode.fromId("orbit"));
    }
}
