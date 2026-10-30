package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReplayEventScheduler {
    private final ReplayEventTimeline timeline;
    private final Set<Long> fired = new HashSet<>();
    private double time;

    public ReplayEventScheduler(ReplayEventTimeline timeline) {
        if (timeline == null) {
            throw new IllegalArgumentException("event timeline is required");
        }
        this.timeline = timeline;
    }

    public List<ReplayEvent> start() {
        time = 0.0;
        fired.clear();
        return markFired(timeline.eventsAt(0.0));
    }

    public List<ReplayEvent> advance(double toSeconds) {
        if (!Double.isFinite(toSeconds)) {
            return List.of();
        }
        double target = Math.max(0.0, toSeconds);
        List<ReplayEvent> crossed = markFired(timeline.eventsBetween(time, target));
        time = target;
        return crossed;
    }

    public List<ReplayEvent> seek(double seconds) {
        if (!Double.isFinite(seconds)) {
            return List.of();
        }
        time = Math.max(0.0, seconds);
        fired.clear();
        return timeline.persistentAt(time);
    }

    public void reset() {
        time = 0.0;
        fired.clear();
    }

    public double getTime() {
        return time;
    }

    private List<ReplayEvent> markFired(List<ReplayEvent> events) {
        List<ReplayEvent> result = new ArrayList<>(events.size());
        for (ReplayEvent event : events) {
            if (fired.add(event.sequence())) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }
}
