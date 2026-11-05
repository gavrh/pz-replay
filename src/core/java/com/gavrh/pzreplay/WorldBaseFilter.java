package com.gavrh.pzreplay;

import java.util.List;
import java.util.Set;

public final class WorldBaseFilter {
    private static final Set<String> STRIPPED_FILES = Set.of(
        "WorldDictionaryLog.lua",
        "WorldDictionaryReadable.lua",
        "thumb.png",
        "map_meta.bin",
        "map_visited.bin",
        "recorded_media.bin",
        "z_outfits.bin",
        "iTrack.bin",
        "entity_data.bin",
        "map_animals.bin",
        "reanimated.bin",
        "players.db-journal",
        "vehicles.db-journal"
    );

    private static final List<String> STRIPPED_DIRECTORIES = List.of(
        "zpop/",
        "apop/",
        "isoregiondata/",
        "metagrid/",
        "radio/"
    );

    private WorldBaseFilter() {
    }

    public static boolean keep(String relativePath) {
        String path = normalize(relativePath);
        if (path.isEmpty() || path.endsWith("/")) {
            return false;
        }
        for (String directory : STRIPPED_DIRECTORIES) {
            if (path.startsWith(directory)) {
                return false;
            }
        }
        return !STRIPPED_FILES.contains(path);
    }

    public static String normalize(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String path = relativePath.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }
}
