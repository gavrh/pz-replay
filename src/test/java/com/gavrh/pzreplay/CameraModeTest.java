package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CameraModeTest {
    @Test
    void modesExposeStableIdsAndLabels() {
        assertEquals("player", CameraMode.PLAYER.id());
        assertEquals("Player", CameraMode.PLAYER.label());
        assertEquals("follow_ghost", CameraMode.FOLLOW_GHOST.id());
        assertEquals("Follow ghost", CameraMode.FOLLOW_GHOST.label());
        assertEquals("free", CameraMode.FREE.id());
        assertEquals("Free camera", CameraMode.FREE.label());
    }

    @Test
    void fromIdAcceptsKnownIdsCaseInsensitively() {
        assertEquals(CameraMode.PLAYER, CameraMode.fromId("player"));
        assertEquals(CameraMode.FOLLOW_GHOST, CameraMode.fromId(" FOLLOW_GHOST "));
        assertEquals(CameraMode.FREE, CameraMode.fromId("Free"));
    }

    @Test
    void fromIdRejectsUnknownValues() {
        assertNull(CameraMode.fromId(null));
        assertNull(CameraMode.fromId(""));
        assertNull(CameraMode.fromId("orbit"));
    }
}
