package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class ContextVehicleCheck {
    private ContextVehicleCheck() {
    }

    public static String verify(boolean vehicleStoreOpen, List<String> expectedVehicles,
        List<String> loadedVehicles, boolean playerStoreOpen, boolean noSave,
        boolean realSaveUnchanged) {
        List<String> problems = new ArrayList<>();
        if (!vehicleStoreOpen) {
            problems.add("the vehicle store did not open");
        }
        List<String> missing = new ArrayList<>();
        for (String expected : expectedVehicles == null ? List.<String>of() : expectedVehicles) {
            if (loadedVehicles == null || !loadedVehicles.contains(expected)) {
                missing.add(expected);
            }
        }
        if (!missing.isEmpty()) {
            problems.add("recorded vehicles are missing: " + String.join(", ", missing));
        }
        if (playerStoreOpen) {
            problems.add("the player store opened during the vehicle window");
        }
        if (!noSave) {
            problems.add("no-save was not restored");
        }
        if (!realSaveUnchanged) {
            problems.add("the real save changed during the vehicle window");
        }
        return String.join("; ", problems);
    }
}
