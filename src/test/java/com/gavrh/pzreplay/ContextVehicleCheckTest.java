package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextVehicleCheckTest {
    private static final List<String> RECORDED = List.of("sql=4 script=Base.CarSmall02SmashedLeft");

    @Test
    void acceptsACompleteVehicleLoadWithThePlayerStoreClosed() {
        assertEquals("", ContextVehicleCheck.verify(true, RECORDED, RECORDED,
            false, true, true));
    }

    @Test
    void blocksPlaybackWhenTheVehicleStoreDidNotOpen() {
        String reason = ContextVehicleCheck.verify(false, RECORDED, List.of(),
            false, true, true);

        assertTrue(reason.contains("vehicle store did not open"), reason);
    }

    @Test
    void blocksPlaybackWhenRecordedVehiclesAreMissing() {
        String reason = ContextVehicleCheck.verify(true, RECORDED, List.of(),
            false, true, true);

        assertTrue(reason.contains("recorded vehicles are missing"), reason);
        assertTrue(reason.contains("Base.CarSmall02SmashedLeft"), reason);
    }

    @Test
    void blocksPlaybackWhenThePlayerStoreOpened() {
        String reason = ContextVehicleCheck.verify(true, RECORDED, RECORDED,
            true, true, true);

        assertTrue(reason.contains("player store opened"), reason);
    }

    @Test
    void blocksPlaybackWhenNoSaveOrTheRealSaveChanged() {
        String noSave = ContextVehicleCheck.verify(true, RECORDED, RECORDED,
            false, false, true);
        String realSave = ContextVehicleCheck.verify(true, RECORDED, RECORDED,
            false, true, false);

        assertTrue(noSave.contains("no-save was not restored"), noSave);
        assertTrue(realSave.contains("real save changed"), realSave);
    }
}
