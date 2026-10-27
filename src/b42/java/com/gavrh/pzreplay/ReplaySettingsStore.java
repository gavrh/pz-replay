package com.gavrh.pzreplay;

import java.io.IOException;

final class ReplaySettingsStore {
    private static ReplaySettings settings;

    private ReplaySettingsStore() {
    }

    static synchronized ReplaySettings get() {
        if (settings == null) {
            settings = ReplaySettings.load(ReplayStorage.settingsPath());
        }
        return settings;
    }

    static synchronized boolean save() {
        try {
            get().save(ReplayStorage.settingsPath());
            return true;
        } catch (IOException | RuntimeException exception) {
            System.out.println("[pz-replay] settings save failed: " + exception.getMessage());
            return false;
        }
    }

    static synchronized void reload() {
        settings = null;
    }
}
