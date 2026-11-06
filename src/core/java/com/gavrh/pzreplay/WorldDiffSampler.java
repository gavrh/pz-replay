package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorldDiffSampler {
    private final Map<String, WorldStateRecord> lastRecorded = new HashMap<>();
    private final Set<String> duplicateKeys = new HashSet<>();

    public void seedT0(List<WorldStateRecord> records) {
        lastRecorded.clear();
        duplicateKeys.clear();
        for (WorldStateRecord record : records) {
            String identity = WorldIdentity.of(record);
            if (lastRecorded.putIfAbsent(identity, record) != null) {
                duplicateKeys.add(identity);
            }
        }
    }

    public List<WorldLogEntry> diff(WorldDiffSample sample) {
        Map<String, WorldStateRecord> current = currentByIdentity(sample.current());
        Set<String> duplicates = new HashSet<>(sample.duplicateIdentities());
        duplicates.addAll(duplicatesIn(sample.current()));

        List<WorldLogEntry> entries = new ArrayList<>();
        collectRemovals(sample, current, entries);
        collectStatesAndAdditions(sample, current, duplicates, entries);
        advanceLastRecorded(sample, current);
        return entries;
    }

    public long getDuplicateIdentities() {
        return duplicateKeys.size();
    }

    public static long squareKey(int sx, int sy, int sz) {
        return ((long) sx << 40) | ((long) sy << 20) | (sz & 0x3FL);
    }

    private Map<String, WorldStateRecord> currentByIdentity(List<WorldStateRecord> records) {
        Map<String, WorldStateRecord> current = new LinkedHashMap<>();
        for (WorldStateRecord record : records) {
            current.putIfAbsent(WorldIdentity.of(record), record);
        }
        return current;
    }

    private Set<String> duplicatesIn(List<WorldStateRecord> records) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (WorldStateRecord record : records) {
            String identity = WorldIdentity.of(record);
            if (!seen.add(identity)) {
                duplicates.add(identity);
            }
        }
        return duplicates;
    }

    private void collectRemovals(WorldDiffSample sample, Map<String, WorldStateRecord> current,
        List<WorldLogEntry> entries) {
        for (Map.Entry<String, WorldStateRecord> entry : lastRecorded.entrySet()) {
            String identity = entry.getKey();
            if (current.containsKey(identity)) {
                continue;
            }
            WorldStateRecord previous = entry.getValue();
            if (!observable(previous, sample.observableSquares())) {
                continue;
            }
            if (previous.classId() == WorldLogCodec.CLASS_VEHICLE
                && sample.presentVehicleIdentities().contains(identity)) {
                continue;
            }
            entries.add(removalEntry(previous));
        }
    }

    private void collectStatesAndAdditions(WorldDiffSample sample,
        Map<String, WorldStateRecord> current, Set<String> duplicates,
        List<WorldLogEntry> entries) {
        for (Map.Entry<String, WorldStateRecord> entry : current.entrySet()) {
            String identity = entry.getKey();
            WorldStateRecord record = entry.getValue();
            WorldStateRecord previous = lastRecorded.get(identity);
            int flags = duplicates.contains(identity) ? WorldLogCodec.FLAG_PARTIAL : 0;
            boolean newlyCovered = newlyCovered(record, sample.newlyCoveredSquares());
            if (previous == null) {
                if (addNewRecord(record, newlyCovered, flags, entries)
                    && (flags & WorldLogCodec.FLAG_PARTIAL) != 0) {
                    duplicateKeys.add(identity);
                }
            } else if (record.classId() != WorldLogCodec.CLASS_GENERIC
                && !record.equals(previous)) {
                if ((flags & WorldLogCodec.FLAG_PARTIAL) != 0) {
                    duplicateKeys.add(identity);
                }
                entries.add(new WorldLogEntry(record, flags));
            }
        }
    }

    private boolean addNewRecord(WorldStateRecord record, boolean newlyCovered, int flags,
        List<WorldLogEntry> entries) {
        if (record instanceof WorldGenericState generic) {
            if (newlyCovered) {
                return false;
            }
            int entryFlags = flags
                | (generic.complex() ? WorldLogCodec.FLAG_RECORDED_ONLY : 0);
            entries.add(new WorldLogEntry(new WorldObjectAdded(generic.target(),
                generic.classId(), generic.sprite(), generic.target().type(),
                generic.objectIndex(), generic.complex() ? 2 : 1, null), entryFlags));
            return true;
        }
        if (record.classId() == WorldLogCodec.CLASS_VEHICLE) {
            int entryFlags = flags
                | (newlyCovered ? 0 : WorldLogCodec.FLAG_RELOAD_REQUIRED);
            entries.add(new WorldLogEntry(record, entryFlags));
            return true;
        }
        if (newlyCovered) {
            entries.add(new WorldLogEntry(record, flags));
            return true;
        }
        entries.add(new WorldLogEntry(new WorldObjectAdded(record.target(),
            record.classId(), sprite(record), "", objectIndex(record), 1, record),
            flags));
        return true;
    }

    private WorldLogEntry removalEntry(WorldStateRecord previous) {
        if (previous instanceof WorldVehicleState vehicle) {
            WorldVehicleState removed = new WorldVehicleState(vehicle.target(), false,
                vehicle.headlightsOn(), vehicle.poseX(), vehicle.poseY(), vehicle.poseZ(),
                vehicle.posePhysicsZ(), vehicle.quaternionX(), vehicle.quaternionY(),
                vehicle.quaternionZ(), vehicle.quaternionW());
            return new WorldLogEntry(removed, WorldLogCodec.FLAG_RELOAD_REQUIRED);
        }
        int flags = 0;
        String type = "";
        WorldStateRecord restoreState = previous;
        if (previous instanceof WorldGenericState generic) {
            type = generic.target().type();
            restoreState = null;
            if (generic.complex()) {
                flags |= WorldLogCodec.FLAG_RECORDED_ONLY;
            }
        }
        return new WorldLogEntry(new WorldObjectDestroyed(previous.target(),
            previous.classId(), sprite(previous), type, objectIndex(previous), restoreState),
            flags);
    }

    private void advanceLastRecorded(WorldDiffSample sample,
        Map<String, WorldStateRecord> current) {
        Map<String, WorldStateRecord> next = new HashMap<>(lastRecorded);
        for (Map.Entry<String, WorldStateRecord> entry : lastRecorded.entrySet()) {
            String identity = entry.getKey();
            if (current.containsKey(identity)) {
                continue;
            }
            WorldStateRecord previous = entry.getValue();
            if (!observable(previous, sample.observableSquares())) {
                continue;
            }
            if (previous.classId() == WorldLogCodec.CLASS_VEHICLE
                && sample.presentVehicleIdentities().contains(identity)) {
                continue;
            }
            next.remove(identity);
        }
        next.putAll(current);
        lastRecorded.clear();
        lastRecorded.putAll(next);
    }

    private static boolean observable(WorldStateRecord record, Set<Long> observableSquares) {
        WorldTarget target = record.target();
        return observableSquares.contains(squareKey(target.sx(), target.sy(), target.sz()));
    }

    private static boolean newlyCovered(WorldStateRecord record, Set<Long> newlyCoveredSquares) {
        WorldTarget target = record.target();
        return newlyCoveredSquares.contains(squareKey(target.sx(), target.sy(), target.sz()));
    }

    private static String sprite(WorldStateRecord record) {
        if (record instanceof WorldDoorState door) {
            return door.sprite();
        }
        if (record instanceof WorldWindowState window) {
            return window.sprite();
        }
        if (record instanceof WorldBarricadeState barricade) {
            return barricade.sprite();
        }
        if (record instanceof WorldGenericState generic) {
            return generic.sprite();
        }
        return "";
    }

    private static int objectIndex(WorldStateRecord record) {
        if (record instanceof WorldDoorState door) {
            return door.objectIndex();
        }
        if (record instanceof WorldWindowState window) {
            return window.objectIndex();
        }
        if (record instanceof WorldBarricadeState barricade) {
            return barricade.objectIndex();
        }
        if (record instanceof WorldGenericState generic) {
            return generic.objectIndex();
        }
        return -1;
    }
}
