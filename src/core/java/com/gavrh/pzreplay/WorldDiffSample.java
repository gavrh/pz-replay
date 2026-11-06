package com.gavrh.pzreplay;

import java.util.List;
import java.util.Set;

public record WorldDiffSample(
    List<WorldStateRecord> current,
    Set<Long> observableSquares,
    Set<Long> newlyCoveredSquares,
    Set<String> presentVehicleIdentities,
    Set<String> duplicateIdentities
) {
    public WorldDiffSample {
        current = List.copyOf(current == null ? List.of() : current);
        observableSquares = Set.copyOf(observableSquares == null ? Set.of()
            : observableSquares);
        newlyCoveredSquares = Set.copyOf(newlyCoveredSquares == null ? Set.of()
            : newlyCoveredSquares);
        presentVehicleIdentities = Set.copyOf(presentVehicleIdentities == null ? Set.of()
            : presentVehicleIdentities);
        duplicateIdentities = Set.copyOf(duplicateIdentities == null ? Set.of()
            : duplicateIdentities);
    }

    public static WorldDiffSample of(List<WorldStateRecord> current,
        Set<Long> observableSquares, Set<Long> newlyCoveredSquares,
        Set<String> presentVehicleIdentities) {
        return new WorldDiffSample(current, observableSquares, newlyCoveredSquares,
            presentVehicleIdentities, Set.of());
    }
}
