package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSurfaceModelTest {
    @Test
    void oneInteractiveSurfaceOwnsFocusAtATime() {
        UiSurfaceModel model = new UiSurfaceModel();

        assertTrue(model.open(UiSurfaceModel.PANEL));
        assertTrue(model.isOpen(UiSurfaceModel.PANEL));
        assertFalse(model.open(UiSurfaceModel.LIBRARY));
        assertTrue(model.isOpen(UiSurfaceModel.PANEL));
        assertEquals(UiSurfaceModel.PANEL, model.active());

        assertTrue(model.close(UiSurfaceModel.PANEL));
        assertFalse(model.isOpen(UiSurfaceModel.PANEL));
        assertTrue(model.open(UiSurfaceModel.LIBRARY));
        assertEquals(UiSurfaceModel.LIBRARY, model.active());
    }

    @Test
    void reopeningTheOwnerIsIdempotent() {
        UiSurfaceModel model = new UiSurfaceModel();

        assertTrue(model.open(UiSurfaceModel.PANEL));
        assertTrue(model.open(UiSurfaceModel.PANEL));
        assertEquals(UiSurfaceModel.PANEL, model.active());
    }

    @Test
    void closingAnInactiveSurfaceLeavesTheOwnerUntouched() {
        UiSurfaceModel model = new UiSurfaceModel();
        model.open(UiSurfaceModel.PANEL);

        assertFalse(model.close(UiSurfaceModel.LIBRARY));
        assertFalse(model.close(UiSurfaceModel.HUD));
        assertFalse(model.close(null));
        assertEquals(UiSurfaceModel.PANEL, model.active());
    }

    @Test
    void hudAndUnknownSurfacesNeverOwnInput() {
        UiSurfaceModel model = new UiSurfaceModel();

        assertFalse(model.open(UiSurfaceModel.HUD));
        assertFalse(model.open(UiSurfaceModel.DIALOG));
        assertFalse(model.open("missing"));
        assertFalse(model.open(null));
        assertEquals("", model.active());
        assertFalse(model.isOpen(UiSurfaceModel.HUD));
    }

    @Test
    void dialogRequiresAnOwnerAndClosesWithIt() {
        UiSurfaceModel model = new UiSurfaceModel();

        assertFalse(model.openDialog(UiSurfaceModel.PANEL));
        model.open(UiSurfaceModel.PANEL);
        assertFalse(model.openDialog(UiSurfaceModel.LIBRARY));
        assertTrue(model.openDialog(UiSurfaceModel.PANEL));
        assertTrue(model.isDialogOpen());
        assertFalse(model.openDialog(UiSurfaceModel.PANEL));

        assertTrue(model.close(UiSurfaceModel.PANEL));
        assertFalse(model.isDialogOpen());
        assertEquals("", model.active());
    }

    @Test
    void dialogClosesBeforeClearingItsOwner() {
        UiSurfaceModel model = new UiSurfaceModel();
        model.open(UiSurfaceModel.LIBRARY);
        model.openDialog(UiSurfaceModel.LIBRARY);

        assertTrue(model.closeDialog());
        assertFalse(model.closeDialog());
        assertFalse(model.isDialogOpen());
        assertTrue(model.isOpen(UiSurfaceModel.LIBRARY));
    }

    @Test
    void escapeOrderIsDialogThenFreeCameraThenSurface() {
        UiSurfaceModel model = new UiSurfaceModel();

        assertEquals(UiSurfaceModel.EscapeAction.NONE, model.escapeAction(false));
        assertEquals(UiSurfaceModel.EscapeAction.FREE_CAMERA, model.escapeAction(true));

        model.open(UiSurfaceModel.PANEL);
        assertEquals(UiSurfaceModel.EscapeAction.PANEL, model.escapeAction(false));
        assertEquals(UiSurfaceModel.EscapeAction.FREE_CAMERA, model.escapeAction(true));

        model.openDialog(UiSurfaceModel.PANEL);
        assertEquals(UiSurfaceModel.EscapeAction.DIALOG, model.escapeAction(false));
        assertEquals(UiSurfaceModel.EscapeAction.DIALOG, model.escapeAction(true));

        model.closeDialog();
        model.close(UiSurfaceModel.PANEL);
        model.open(UiSurfaceModel.LIBRARY);
        assertEquals(UiSurfaceModel.EscapeAction.LIBRARY, model.escapeAction(false));
        assertEquals(UiSurfaceModel.EscapeAction.FREE_CAMERA, model.escapeAction(true));
    }

    @Test
    void globalShortcutsRequireAnIdleFocusOwner() {
        UiSurfaceModel model = new UiSurfaceModel();

        assertTrue(model.allowsGlobalShortcut(false));
        assertFalse(model.allowsGlobalShortcut(true));

        model.open(UiSurfaceModel.PANEL);
        assertFalse(model.allowsGlobalShortcut(false));

        model.openDialog(UiSurfaceModel.PANEL);
        assertFalse(model.allowsGlobalShortcut(false));

        model.closeDialog();
        model.close(UiSurfaceModel.PANEL);
        assertTrue(model.allowsGlobalShortcut(false));
    }

    @Test
    void resetClearsOwnership() {
        UiSurfaceModel model = new UiSurfaceModel();
        model.open(UiSurfaceModel.PANEL);
        model.openDialog(UiSurfaceModel.PANEL);

        model.reset();

        assertEquals("", model.active());
        assertFalse(model.isDialogOpen());
        assertTrue(model.allowsGlobalShortcut(false));
        assertEquals(UiSurfaceModel.EscapeAction.NONE, model.escapeAction(false));
    }
}
