package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ReplayCatalogueView {
    public static final String SORT_DATE = "date";
    public static final String SORT_NAME = "name";
    public static final String SORT_DURATION = "duration";

    private ReplayCatalogueView() {
    }

    public static String normalizeSort(String sort) {
        if (SORT_NAME.equals(sort)) {
            return SORT_NAME;
        }
        if (SORT_DURATION.equals(sort)) {
            return SORT_DURATION;
        }
        return SORT_DATE;
    }

    public static List<ReplayCatalogue.Entry> apply(List<ReplayCatalogue.Entry> entries,
        String query, String sort, boolean ascending) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ReplayCatalogue.Entry> filtered = new ArrayList<>();
        for (ReplayCatalogue.Entry entry : entries) {
            if (matches(entry, needle)) {
                filtered.add(entry);
            }
        }

        Comparator<ReplayCatalogue.Entry> primary = switch (normalizeSort(sort)) {
            case SORT_NAME -> Comparator.comparing(ReplayCatalogueView::nameKey);
            case SORT_DURATION -> Comparator.comparingDouble(
                ReplayCatalogue.Entry::durationSeconds);
            default -> Comparator.comparingLong(ReplayCatalogue.Entry::modifiedMillis);
        };
        if (!ascending) {
            primary = primary.reversed();
        }
        filtered.sort(primary.thenComparing(ReplayCatalogueView::nameKey));
        return List.copyOf(filtered);
    }

    public static int selectionIndex(List<ReplayCatalogue.Entry> entries,
        String preferredPath, int fallbackIndex) {
        if (entries.isEmpty()) {
            return -1;
        }
        if (preferredPath != null && !preferredPath.isBlank()) {
            for (int index = 0; index < entries.size(); index++) {
                if (preferredPath.equals(entries.get(index).path())) {
                    return index;
                }
            }
        }
        if (fallbackIndex >= 0) {
            return Math.min(fallbackIndex, entries.size() - 1);
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).lastReplay()) {
                return index;
            }
        }
        return 0;
    }

    private static boolean matches(ReplayCatalogue.Entry entry, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(entry.fileName(), needle)
            || contains(entry.mapName(), needle)
            || contains(entry.playerName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String nameKey(ReplayCatalogue.Entry entry) {
        return entry.fileName().toLowerCase(Locale.ROOT);
    }
}
