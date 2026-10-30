package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReplayEventTimeline {
    private static final double TIME_EPSILON = 0.0001;
    private static final double MAX_EVENT_TAIL_SECONDS = 0.25;
    private static final int MAX_WARNINGS = 8;

    private final List<ReplayEvent> events;
    private final List<String> warnings;

    public ReplayEventTimeline(List<ReplayEvent> events, double duration)
        throws ReplayFormatException {
        if (!Double.isFinite(duration) || duration < 0.0) {
            throw new ReplayFormatException("replay duration is invalid");
        }
        List<ReplayEvent> valid = new ArrayList<>();
        Set<String> collectedWarnings = new LinkedHashSet<>();
        if (events != null) {
            if (events.size() > ReplayEventCodec.MAX_EVENTS) {
                throw new ReplayFormatException(
                    "too many action events: " + events.size());
            }
            for (int index = 0; index < events.size(); index++) {
                ReplayEvent event = events.get(index);
                if (event == null) {
                    addWarning(collectedWarnings,
                        "action event " + index + " is missing and was ignored");
                    continue;
                }
                if (event.timeSeconds() > duration + MAX_EVENT_TAIL_SECONDS) {
                    addWarning(collectedWarnings,
                        "action event outside the replay duration was ignored");
                    continue;
                }
                if (event.timeSeconds() > duration) {
                    valid.add(new ReplayEvent(event.sequence(), (float) duration,
                        event.kind(), event.end(), event.detail(), event.direction(),
                        event.durationSeconds()));
                    continue;
                }
                valid.add(event);
            }
        }
        valid.sort(Comparator.comparingDouble(ReplayEvent::timeSeconds));
        this.events = List.copyOf(valid);
        this.warnings = List.copyOf(collectedWarnings);
    }

    public List<ReplayEvent> events() {
        return events;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int getEventCount() {
        return events.size();
    }

    public List<ReplayEvent> eventsAt(double time) {
        if (events.isEmpty() || !Double.isFinite(time)) {
            return List.of();
        }
        double target = clamp(time);
        List<ReplayEvent> result = new ArrayList<>();
        int index = firstAfter(target - TIME_EPSILON);
        while (index < events.size()
            && events.get(index).timeSeconds() <= target + TIME_EPSILON) {
            result.add(events.get(index));
            index++;
        }
        return List.copyOf(result);
    }

    public List<ReplayEvent> eventsBetween(double fromExclusive, double toInclusive) {
        if (events.isEmpty() || !Double.isFinite(fromExclusive)
            || !Double.isFinite(toInclusive) || toInclusive <= fromExclusive) {
            return List.of();
        }
        double from = clamp(fromExclusive);
        double to = clamp(toInclusive);
        if (to <= from) {
            return List.of();
        }
        List<ReplayEvent> result = new ArrayList<>();
        int index = firstAfter(from);
        while (index < events.size()
            && events.get(index).timeSeconds() <= to + TIME_EPSILON) {
            result.add(events.get(index));
            index++;
        }
        return List.copyOf(result);
    }

    public List<ReplayEvent> persistentAt(double time) {
        if (events.isEmpty() || !Double.isFinite(time)) {
            return List.of();
        }
        double target = clamp(time);
        Map<ReplayEvent.Kind, ReplayEvent> active =
            new EnumMap<>(ReplayEvent.Kind.class);
        for (ReplayEvent event : events) {
            if (event.timeSeconds() > target + TIME_EPSILON) {
                break;
            }
            if (!event.kind().persistent()) {
                continue;
            }
            if (event.end()) {
                active.remove(event.kind());
            } else {
                active.put(event.kind(), event);
            }
        }
        return List.copyOf(active.values());
    }

    public double nextEventTime(double afterExclusive) {
        if (events.isEmpty() || !Double.isFinite(afterExclusive)) {
            return Double.POSITIVE_INFINITY;
        }
        int index = firstAfter(clamp(afterExclusive));
        if (index >= events.size()) {
            return Double.POSITIVE_INFINITY;
        }
        return events.get(index).timeSeconds();
    }

    private int firstAfter(double time) {
        int low = 0;
        int high = events.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (events.get(middle).timeSeconds() <= time) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static double clamp(double time) {
        return Math.max(0.0, time);
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
