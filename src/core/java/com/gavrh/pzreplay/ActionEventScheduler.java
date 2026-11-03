package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ActionEventScheduler {
    private final ActionEventTimeline timeline;
    private final Set<Long> fired = new HashSet<>();
    private double time;

    public ActionEventScheduler(ActionEventTimeline timeline) {
        if (timeline == null) {
            throw new IllegalArgumentException("event timeline is required");
        }
        this.timeline = timeline;
    }

    public List<ActionEvent> start() {
        time = 0.0;
        fired.clear();
        return markFired(timeline.eventsAt(0.0));
    }

    public List<ActionEvent> advance(double toSeconds) {
        if (!Double.isFinite(toSeconds)) {
            return List.of();
        }
        double target = Math.max(0.0, toSeconds);
        List<ActionEvent> crossed = markFired(timeline.eventsBetween(time, target));
        time = target;
        return crossed;
    }

    public List<ActionEvent> seek(double seconds) {
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

    private List<ActionEvent> markFired(List<ActionEvent> events) {
        List<ActionEvent> result = new ArrayList<>(events.size());
        for (ActionEvent event : events) {
            if (fired.add(event.sequence())) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }
}
