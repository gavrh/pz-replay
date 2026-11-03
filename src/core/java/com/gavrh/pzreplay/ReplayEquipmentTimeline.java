package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplayEquipmentTimeline {
    private static final double TIME_EPSILON = 0.0001;
    private static final double MAX_CHANGE_TAIL_SECONDS = 0.25;
    private static final int MAX_WARNINGS = 8;

    private final Equipment initial;
    private final List<EquipmentChange> changes;
    private final List<String> warnings;

    public ReplayEquipmentTimeline(Equipment initial,
        List<EquipmentChange> changes, double duration) throws ReplayFormatException {
        this(initial, changes, duration, 0.0, List.of());
    }

    public ReplayEquipmentTimeline(Equipment initial,
        List<EquipmentChange> changes, double duration, double offsetSeconds,
        List<String> extraWarnings) throws ReplayFormatException {
        if (!Double.isFinite(duration) || duration < 0.0) {
            throw new ReplayFormatException("replay duration is invalid");
        }
        if (!Double.isFinite(offsetSeconds)) {
            throw new ReplayFormatException("replay time offset is invalid");
        }
        this.initial = initial == null ? Equipment.none() : initial;
        List<EquipmentChange> valid = new ArrayList<>();
        Set<String> collectedWarnings = new LinkedHashSet<>();
        if (extraWarnings != null) {
            for (String warning : extraWarnings) {
                if (warning != null && !warning.isBlank()) {
                    addWarning(collectedWarnings, warning);
                }
            }
        }
        if (changes != null) {
            if (changes.size() > EquipmentChangeCodec.MAX_CHANGES) {
                throw new ReplayFormatException(
                    "too many equipment changes: " + changes.size());
            }
            for (int index = 0; index < changes.size(); index++) {
                EquipmentChange change = changes.get(index);
                if (change == null) {
                    addWarning(collectedWarnings,
                        "equipment change " + index + " is missing and was ignored");
                    continue;
                }
                if (!change.equipment().isCurrent()) {
                    addWarning(collectedWarnings,
                        "equipment change " + index + " is unsupported and was ignored");
                    continue;
                }
                double relativeTime = change.timeSeconds() - offsetSeconds;
                if (relativeTime < 0.0) {
                    relativeTime = 0.0;
                }
                if (relativeTime > duration + MAX_CHANGE_TAIL_SECONDS) {
                    addWarning(collectedWarnings,
                        "equipment change outside the replay duration was ignored");
                    continue;
                }
                if (relativeTime > duration) {
                    relativeTime = duration;
                }
                valid.add(new EquipmentChange(change.sequence(),
                    (float) relativeTime, change.equipment()));
            }
        }
        valid.sort(Comparator.comparingDouble(EquipmentChange::timeSeconds)
            .thenComparingLong(EquipmentChange::sequence));
        this.changes = List.copyOf(valid);
        this.warnings = List.copyOf(collectedWarnings);
    }

    public Equipment initialEquipment() {
        return initial;
    }

    public List<EquipmentChange> changes() {
        return changes;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int getChangeCount() {
        return changes.size();
    }

    public Equipment equipmentAt(double time) {
        if (!Double.isFinite(time)) {
            return initial;
        }
        double target = Math.max(0.0, time);
        Equipment result = initial;
        for (EquipmentChange change : changes) {
            if (change.timeSeconds() > target + TIME_EPSILON) {
                break;
            }
            result = change.equipment();
        }
        return result;
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
