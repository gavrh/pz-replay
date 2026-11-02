package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReplayGapTimeline {
    private static final double TIME_EPSILON = 0.0001;
    private static final double MAX_GAP_TAIL_SECONDS = 0.25;
    private static final int MAX_WARNINGS = 8;

    private final List<ReplayGap> gaps;
    private final List<String> warnings;

    public ReplayGapTimeline(List<ReplayGap> gaps, double duration)
        throws ReplayFormatException {
        this(gaps, duration, List.of());
    }

    public ReplayGapTimeline(List<ReplayGap> gaps, double duration,
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
        List<ReplayGap> valid = new ArrayList<>();
        if (gaps != null) {
            if (gaps.size() > ReplayGapCodec.MAX_GAPS) {
                throw new ReplayFormatException("too many timeline gaps: " + gaps.size());
            }
            for (int index = 0; index < gaps.size(); index++) {
                ReplayGap gap = gaps.get(index);
                if (gap == null) {
                    addWarning(collectedWarnings,
                        "timeline gap " + index + " is missing and was ignored");
                    continue;
                }
                if (gap.reason() == null) {
                    addWarning(collectedWarnings,
                        "timeline gap " + index + " has no reason and was ignored");
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
                valid.add(new ReplayGap((float) start, (float) end, gap.reason(),
                    gap.lastX(), gap.lastY(), gap.lastZ(), gap.nextX(), gap.nextY(),
                    gap.nextZ()));
            }
        }
        valid.sort(Comparator.comparingDouble(ReplayGap::startTimeSeconds));
        List<ReplayGap> nonOverlapping = new ArrayList<>();
        double previousEnd = 0.0;
        for (ReplayGap gap : valid) {
            if (!nonOverlapping.isEmpty()
                && gap.startTimeSeconds() < previousEnd - TIME_EPSILON) {
                addWarning(collectedWarnings,
                    "overlapping timeline gaps were truncated");
                if (gap.endTimeSeconds() <= previousEnd + TIME_EPSILON) {
                    continue;
                }
                gap = new ReplayGap((float) previousEnd, gap.endTimeSeconds(),
                    gap.reason(), gap.lastX(), gap.lastY(), gap.lastZ(), gap.nextX(),
                    gap.nextY(), gap.nextZ());
            }
            nonOverlapping.add(gap);
            previousEnd = gap.endTimeSeconds();
        }
        this.gaps = List.copyOf(nonOverlapping);
        this.warnings = List.copyOf(collectedWarnings);
    }

    public List<ReplayGap> gaps() {
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
