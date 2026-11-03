package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TimelineGapPlayback {
    private static final double TIME_EPSILON = 0.0001;
    private static final double MAX_GAP_TAIL_SECONDS = 0.25;
    private static final int MAX_WARNINGS = 8;

    private final List<TimelineGap> gaps;
    private final List<String> warnings;

    public TimelineGapPlayback(List<TimelineGap> gaps, double duration)
        throws ReplayFormatException {
        this(gaps, duration, List.of());
    }

    public TimelineGapPlayback(List<TimelineGap> gaps, double duration,
        List<String> extraWarnings) throws ReplayFormatException {
        if (!Double.isFinite(duration) || duration < 0.0) {
            throw new ReplayFormatException("replay duration is invalid");
        }
        Set<String> collectedWarnings = new LinkedHashSet<>();
        if (extraWarnings != null) {
            for (String warning : extraWarnings) {
                if (warning != null && !warning.isBlank()) {
                    addWarning(collectedWarnings, warning);
                }
            }
        }
        List<TimelineGap> valid = new ArrayList<>();
        if (gaps != null) {
            if (gaps.size() > TimelineGapCodec.MAX_GAPS) {
                throw new ReplayFormatException("too many timeline gaps: " + gaps.size());
            }
            for (int index = 0; index < gaps.size(); index++) {
                TimelineGap gap = gaps.get(index);
                if (gap == null) {
                    addWarning(collectedWarnings,
                        "timeline gap " + index + " is missing and was ignored");
                    continue;
                }
                if (gap.startTimeSeconds() > duration + MAX_GAP_TAIL_SECONDS) {
                    addWarning(collectedWarnings,
                        "timeline gap outside the replay duration was ignored");
                    continue;
                }
                double start = Math.max(0.0, Math.min(duration, gap.startTimeSeconds()));
                double end = Math.max(0.0, Math.min(duration, gap.endTimeSeconds()));
                if (end < start - TIME_EPSILON) {
                    addWarning(collectedWarnings,
                        "timeline gap " + index + " has invalid timing and was ignored");
                    continue;
                }
                if (end < start) {
                    end = start;
                }
                valid.add(new TimelineGap((float) start, (float) end, gap.reason(),
                    gap.lastX(), gap.lastY(), gap.lastZ(), gap.nextX(), gap.nextY(),
                    gap.nextZ()));
            }
        }
        valid.sort(Comparator.comparingDouble(TimelineGap::startTimeSeconds));
        List<TimelineGap> nonOverlapping = new ArrayList<>();
        double previousEnd = 0.0;
        for (TimelineGap gap : valid) {
            if (!nonOverlapping.isEmpty()
                && gap.startTimeSeconds() < previousEnd - TIME_EPSILON) {
                addWarning(collectedWarnings,
                    "overlapping timeline gaps were truncated");
                if (gap.endTimeSeconds() <= previousEnd + TIME_EPSILON) {
                    continue;
                }
                gap = new TimelineGap((float) previousEnd, gap.endTimeSeconds(),
                    gap.reason(), gap.lastX(), gap.lastY(), gap.lastZ(), gap.nextX(),
                    gap.nextY(), gap.nextZ());
            }
            nonOverlapping.add(gap);
            previousEnd = gap.endTimeSeconds();
        }
        this.gaps = List.copyOf(nonOverlapping);
        this.warnings = List.copyOf(collectedWarnings);
    }

    public List<TimelineGap> gaps() {
        return gaps;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int getGapCount() {
        return gaps.size();
    }

    public boolean isEmpty() {
        return gaps.isEmpty();
    }

    private static void addWarning(Set<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }
}
