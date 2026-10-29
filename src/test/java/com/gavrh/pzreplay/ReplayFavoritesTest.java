package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayFavoritesTest {
    @TempDir
    Path tempDir;

    @Test
    void missingFileLoadsAsEmptyWithoutError() {
        ReplayFavorites favorites = ReplayFavorites.load(tempDir.resolve("missing.properties"));

        assertEquals(0, favorites.size());
        assertEquals("", favorites.error());
    }

    @Test
    void corruptFileFallsBackToEmptyWithWarning() throws Exception {
        Path path = tempDir.resolve("favorites.properties");
        Files.writeString(path, "this is not a favorites file\n");

        ReplayFavorites favorites = ReplayFavorites.load(path);

        assertEquals(0, favorites.size());
        assertFalse(favorites.error().isBlank());
    }

    @Test
    void unreadableFileFallsBackToEmptyWithWarning() {
        ReplayFavorites favorites = ReplayFavorites.load(tempDir);

        assertEquals(0, favorites.size());
        assertFalse(favorites.error().isBlank());
    }

    @Test
    void roundTripPersistsNormalizedPaths() throws Exception {
        Path replay = tempDir.resolve("run one.pzrp.gz");
        Path other = tempDir.resolve("other.pzrp.gz");
        Path path = tempDir.resolve("pz-replay/favorites.properties");

        ReplayFavorites favorites = new ReplayFavorites();
        assertTrue(favorites.toggle(replay.toString()));
        assertTrue(favorites.toggle(other.resolveSibling("sub/../other.pzrp.gz").toString()));
        favorites.save(path);

        ReplayFavorites loaded = ReplayFavorites.load(path);

        assertEquals(2, loaded.size());
        assertTrue(loaded.contains(replay.toString()));
        assertTrue(loaded.contains(other.toString()));
        assertEquals("", loaded.error());
        assertEquals(Set.of(ReplayFavorites.normalize(replay.toString()),
            ReplayFavorites.normalize(other.toString())), loaded.paths());
    }

    @Test
    void toggleAddsAndRemovesFavorite() {
        ReplayFavorites favorites = new ReplayFavorites();
        String path = tempDir.resolve("one.pzrp.gz").toString();

        assertTrue(favorites.toggle(path));
        assertTrue(favorites.contains(path));
        assertFalse(favorites.toggle(path));
        assertFalse(favorites.contains(path));
        assertFalse(favorites.toggle("  "));
        assertEquals(0, favorites.size());
    }

    @Test
    void renameRemapsFavoriteAndDeleteRemovesIt() {
        ReplayFavorites favorites = new ReplayFavorites();
        Path original = tempDir.resolve("original.pzrp.gz");
        Path renamed = tempDir.resolve("renamed.pzrp.gz");
        favorites.toggle(original.toString());

        assertTrue(favorites.remap(original.toString(), renamed.toString()));
        assertFalse(favorites.contains(original.toString()));
        assertTrue(favorites.contains(renamed.toString()));
        assertFalse(favorites.remap(original.toString(), renamed.toString()));

        assertTrue(favorites.remove(renamed.toString()));
        assertFalse(favorites.contains(renamed.toString()));
        assertFalse(favorites.remove(renamed.toString()));
    }

    @Test
    void pruneRemovesOnlyStalePaths() {
        ReplayFavorites favorites = new ReplayFavorites();
        String kept = tempDir.resolve("kept.pzrp.gz").toString();
        String stale = tempDir.resolve("stale.pzrp.gz").toString();
        favorites.toggle(kept);
        favorites.toggle(stale);

        assertEquals(0, favorites.prune(null));
        assertEquals(1, favorites.prune(List.of(kept)));
        assertEquals(Set.of(ReplayFavorites.normalize(kept)), favorites.paths());
    }

    @Test
    void saveFailureIsReported() {
        ReplayFavorites favorites = new ReplayFavorites();
        favorites.toggle(tempDir.resolve("one.pzrp.gz").toString());

        assertThrows(IOException.class, () -> favorites.save(tempDir));
    }
}
