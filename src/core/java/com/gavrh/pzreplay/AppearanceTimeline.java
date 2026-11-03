package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AppearanceTimeline {
    private static final double TIME_EPSILON = 0.0001;
    private static final double MAX_CHANGE_TAIL_SECONDS = 0.25;
    private static final int MAX_WARNINGS = 8;

    private final Appearance initial;
    private final List<AppearanceChange> changes;
    private final List<String> warnings;

    public AppearanceTimeline(Appearance initial,
        List<AppearanceChange> changes, double duration) throws ReplayFormatException {
        this(initial, changes, duration, List.of());
    }

    public AppearanceTimeline(Appearance initial,
        List<AppearanceChange> changes, double duration, List<String> extraWarnings)
        throws ReplayFormatException {
        if (!Double.isFinite(duration) || duration < 0.0) {
            throw new ReplayFormatException("replay duration is invalid");
        }
        this.initial = initial == null ? Appearance.none() : initial;
        List<AppearanceChange> valid = new ArrayList<>();
        Set<String> collectedWarnings = new LinkedHashSet<>();
        if (extraWarnings != null) {
            for (String warning : extraWarnings) {
                if (warning != null && !warning.isBlank()) {
                    addWarning(collectedWarnings, warning);
                }
            }
        }
        if (changes != null) {
            if (changes.size() > AppearanceChangeCodec.MAX_CHANGES) {
                throw new ReplayFormatException(
                    "too many appearance changes: " + changes.size());
            }
            for (int index = 0; index < changes.size(); index++) {
                AppearanceChange change = changes.get(index);
                if (change == null) {
                    addWarning(collectedWarnings,
                        "appearance change " + index + " is missing and was ignored");
                    continue;
                }
                if (!change.appearance().isCurrent()) {
                    addWarning(collectedWarnings,
                        "appearance change " + index + " is unsupported and was ignored");
                    continue;
                }
                if (change.timeSeconds() > duration + MAX_CHANGE_TAIL_SECONDS) {
                    addWarning(collectedWarnings,
                        "appearance change outside the replay duration was ignored");
                    continue;
                }
                if (change.timeSeconds() > duration) {
                    valid.add(new AppearanceChange(change.sequence(), (float) duration,
                        change.appearance()));
                    continue;
                }
                valid.add(change);
            }
        }
        valid.sort(Comparator.comparingDouble(AppearanceChange::timeSeconds)
            .thenComparingLong(AppearanceChange::sequence));
        this.changes = List.copyOf(valid);
        this.warnings = List.copyOf(collectedWarnings);
    }

    public Appearance initialAppearance() {
        return initial;
    }

    public List<AppearanceChange> changes() {
        return changes;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int getChangeCount() {
        return changes.size();
    }

    public Appearance appearanceAt(double time) {
        if (changes.isEmpty() || !Double.isFinite(time)) {
            return initial;
        }
        double target = Math.max(0.0, time);
        Appearance result = initial;
        for (AppearanceChange change : changes) {
            if (change.timeSeconds() > target + TIME_EPSILON) {
                break;
            }
            result = change.appearance();
        }
        return result;
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
