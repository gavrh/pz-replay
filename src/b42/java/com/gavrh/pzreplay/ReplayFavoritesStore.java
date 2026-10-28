package com.gavrh.pzreplay;

import java.io.IOException;

final class ReplayFavoritesStore {
    private static ReplayFavorites favorites;
    private static String error = "";

    private ReplayFavoritesStore() {
    }

    static synchronized ReplayFavorites get() {
        if (favorites == null) {
            favorites = ReplayFavorites.load(ReplayStorage.favoritesPath());
            error = favorites.error();
            if (!error.isEmpty()) {
                System.out.println("[pz-replay] favorites unavailable: " + error);
            }
        }
        return favorites;
    }

    static synchronized String error() {
        get();
        return error;
    }

    static synchronized boolean save() {
        try {
            get().save(ReplayStorage.favoritesPath());
            error = "";
            return true;
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] favorites save failed: " + exception.getMessage());
            return false;
        }
    }

    static synchronized void reload() {
        favorites = null;
        error = "";
    }
}
