package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySurfaceModelTest {
    @Test
    void oneInteractiveSurfaceOwnsFocusAtATime() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();

        assertTrue(model.open(ReplaySurfaceModel.PANEL));
        assertTrue(model.isOpen(ReplaySurfaceModel.PANEL));
        assertFalse(model.open(ReplaySurfaceModel.LIBRARY));
        assertTrue(model.isOpen(ReplaySurfaceModel.PANEL));
        assertEquals(ReplaySurfaceModel.PANEL, model.active());

        assertTrue(model.close(ReplaySurfaceModel.PANEL));
        assertFalse(model.isOpen(ReplaySurfaceModel.PANEL));
        assertTrue(model.open(ReplaySurfaceModel.LIBRARY));
        assertEquals(ReplaySurfaceModel.LIBRARY, model.active());
    }

    @Test
    void reopeningTheOwnerIsIdempotent() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();

        assertTrue(model.open(ReplaySurfaceModel.PANEL));
        assertTrue(model.open(ReplaySurfaceModel.PANEL));
        assertEquals(ReplaySurfaceModel.PANEL, model.active());
    }

    @Test
    void closingAnInactiveSurfaceLeavesTheOwnerUntouched() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();
        model.open(ReplaySurfaceModel.PANEL);

        assertFalse(model.close(ReplaySurfaceModel.LIBRARY));
        assertFalse(model.close(ReplaySurfaceModel.HUD));
        assertFalse(model.close(null));
        assertEquals(ReplaySurfaceModel.PANEL, model.active());
    }

    @Test
    void hudAndUnknownSurfacesNeverOwnInput() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();

        assertFalse(model.open(ReplaySurfaceModel.HUD));
        assertFalse(model.open(ReplaySurfaceModel.DIALOG));
        assertFalse(model.open("missing"));
        assertFalse(model.open(null));
        assertEquals("", model.active());
        assertFalse(model.isOpen(ReplaySurfaceModel.HUD));
    }

    @Test
    void dialogRequiresAnOwnerAndClosesWithIt() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();

        assertFalse(model.openDialog(ReplaySurfaceModel.PANEL));
        model.open(ReplaySurfaceModel.PANEL);
        assertFalse(model.openDialog(ReplaySurfaceModel.LIBRARY));
        assertTrue(model.openDialog(ReplaySurfaceModel.PANEL));
        assertTrue(model.isDialogOpen());
        assertFalse(model.openDialog(ReplaySurfaceModel.PANEL));

        assertTrue(model.close(ReplaySurfaceModel.PANEL));
        assertFalse(model.isDialogOpen());
        assertEquals("", model.active());
    }

    @Test
    void dialogClosesBeforeClearingItsOwner() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();
        model.open(ReplaySurfaceModel.LIBRARY);
        model.openDialog(ReplaySurfaceModel.LIBRARY);

        assertTrue(model.closeDialog());
        assertFalse(model.closeDialog());
        assertFalse(model.isDialogOpen());
        assertTrue(model.isOpen(ReplaySurfaceModel.LIBRARY));
    }

    @Test
    void escapeOrderIsDialogThenFreeCameraThenSurface() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();

        assertEquals(ReplaySurfaceModel.EscapeAction.NONE, model.escapeAction(false));
        assertEquals(ReplaySurfaceModel.EscapeAction.FREE_CAMERA, model.escapeAction(true));

        model.open(ReplaySurfaceModel.PANEL);
        assertEquals(ReplaySurfaceModel.EscapeAction.PANEL, model.escapeAction(false));
        assertEquals(ReplaySurfaceModel.EscapeAction.FREE_CAMERA, model.escapeAction(true));

        model.openDialog(ReplaySurfaceModel.PANEL);
        assertEquals(ReplaySurfaceModel.EscapeAction.DIALOG, model.escapeAction(false));
        assertEquals(ReplaySurfaceModel.EscapeAction.DIALOG, model.escapeAction(true));

        model.closeDialog();
        model.close(ReplaySurfaceModel.PANEL);
        model.open(ReplaySurfaceModel.LIBRARY);
        assertEquals(ReplaySurfaceModel.EscapeAction.LIBRARY, model.escapeAction(false));
        assertEquals(ReplaySurfaceModel.EscapeAction.FREE_CAMERA, model.escapeAction(true));
    }

    @Test
    void globalShortcutsRequireAnIdleFocusOwner() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();

        assertTrue(model.allowsGlobalShortcut(false));
        assertFalse(model.allowsGlobalShortcut(true));

        model.open(ReplaySurfaceModel.PANEL);
        assertFalse(model.allowsGlobalShortcut(false));

        model.openDialog(ReplaySurfaceModel.PANEL);
        assertFalse(model.allowsGlobalShortcut(false));

        model.closeDialog();
        model.close(ReplaySurfaceModel.PANEL);
        assertTrue(model.allowsGlobalShortcut(false));
    }

    @Test
    void resetClearsOwnership() {
        ReplaySurfaceModel model = new ReplaySurfaceModel();
        model.open(ReplaySurfaceModel.PANEL);
        model.openDialog(ReplaySurfaceModel.PANEL);

        model.reset();

        assertEquals("", model.active());
        assertFalse(model.isDialogOpen());
        assertTrue(model.allowsGlobalShortcut(false));
        assertEquals(ReplaySurfaceModel.EscapeAction.NONE, model.escapeAction(false));
    }
}
