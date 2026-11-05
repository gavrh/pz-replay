package com.gavrh.pzreplay;

public sealed interface WorldStateRecord permits WorldDoorState, WorldWindowState,
    WorldBarricadeState, WorldVehicleState {
    int kind();

    int classId();

    WorldTarget target();
}
