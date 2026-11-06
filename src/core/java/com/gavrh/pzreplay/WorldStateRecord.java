package com.gavrh.pzreplay;

public sealed interface WorldStateRecord permits WorldDoorState, WorldWindowState,
    WorldBarricadeState, WorldVehicleState, WorldGenericState, WorldObjectDestroyed,
    WorldObjectAdded {
    int kind();

    int classId();

    WorldTarget target();
}
