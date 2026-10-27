package com.gavrh.pzreplay;

import java.nio.file.Path;
import java.nio.file.Paths;
import zombie.core.Core;

final class ReplayStorage {
    private static final String DIRECTORY = "pz-replay";
    private static final String EXTENSION = ".pzrp.gz";
    private static final String SETTINGS_FILE = "settings.properties";

    private ReplayStorage() {
    }

    static Path directory() {
        return Paths.get(Core.getMyDocumentFolder(), DIRECTORY);
    }

    static Path pathFor(String captureName) {
        return directory().resolve(captureName + EXTENSION);
    }

    static Path settingsPath() {
        return directory().resolve(SETTINGS_FILE);
    }
}
