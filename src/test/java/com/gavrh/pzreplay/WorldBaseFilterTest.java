package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBaseFilterTest {
    @Test
    void keepsTheScaffoldMapTilesAndChunks() {
        assertTrue(WorldBaseFilter.keep("WorldDictionary.bin"));
        assertTrue(WorldBaseFilter.keep("players.db"));
        assertTrue(WorldBaseFilter.keep("vehicles.db"));
        assertTrue(WorldBaseFilter.keep("mods.txt"));
        assertTrue(WorldBaseFilter.keep("metadata.bin"));
        assertTrue(WorldBaseFilter.keep("map.bin"));
        assertTrue(WorldBaseFilter.keep("map_sand.bin"));
        assertTrue(WorldBaseFilter.keep("map_ver.bin"));
        assertTrue(WorldBaseFilter.keep("map_worldgen.bin"));
        assertTrue(WorldBaseFilter.keep("map_t.bin"));
        assertTrue(WorldBaseFilter.keep("map_symbols.bin"));
        assertTrue(WorldBaseFilter.keep("map_zone.bin"));
        assertTrue(WorldBaseFilter.keep("map_basements.bin"));
        assertTrue(WorldBaseFilter.keep("map/1342/1350.bin"));
        assertTrue(WorldBaseFilter.keep("chunkdata/10_20.bin"));
        assertTrue(WorldBaseFilter.keep("global_mod_data.bin"));
        assertTrue(WorldBaseFilter.keep("gos_campfire.bin"));
        assertTrue(WorldBaseFilter.keep("statistics.bin"));
        assertTrue(WorldBaseFilter.keep("erosion.ini"));
        assertTrue(WorldBaseFilter.keep("fishingData.bin"));
    }

    @Test
    void stripsMetadataFogLogsMediaAndPopulation() {
        assertFalse(WorldBaseFilter.keep("WorldDictionaryLog.lua"));
        assertFalse(WorldBaseFilter.keep("WorldDictionaryReadable.lua"));
        assertFalse(WorldBaseFilter.keep("thumb.png"));
        assertFalse(WorldBaseFilter.keep("map_meta.bin"));
        assertFalse(WorldBaseFilter.keep("map_visited.bin"));
        assertFalse(WorldBaseFilter.keep("recorded_media.bin"));
        assertFalse(WorldBaseFilter.keep("z_outfits.bin"));
        assertFalse(WorldBaseFilter.keep("iTrack.bin"));
        assertFalse(WorldBaseFilter.keep("entity_data.bin"));
        assertFalse(WorldBaseFilter.keep("map_animals.bin"));
        assertFalse(WorldBaseFilter.keep("reanimated.bin"));
        assertFalse(WorldBaseFilter.keep("players.db-journal"));
        assertFalse(WorldBaseFilter.keep("vehicles.db-journal"));
        assertFalse(WorldBaseFilter.keep("zpop/10_20.bin"));
        assertFalse(WorldBaseFilter.keep("apop/10_20.bin"));
        assertFalse(WorldBaseFilter.keep("isoregiondata/10_20.bin"));
        assertFalse(WorldBaseFilter.keep("metagrid/10_20.bin"));
        assertFalse(WorldBaseFilter.keep("radio/radio.bin"));
    }

    @Test
    void normalizesSeparatorsWithoutAllowingEmptyPaths() {
        assertTrue(WorldBaseFilter.keep("./map\\1342\\1350.bin"));
        assertFalse(WorldBaseFilter.keep(""));
        assertFalse(WorldBaseFilter.keep("map/"));
    }
}
