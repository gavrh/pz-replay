package com.gavrh.pzreplay;

import java.nio.file.Path;
import java.nio.file.Paths;
import zombie.core.Core;

final class ReplayStorage {
    private static final String DIRECTORY = "pz-replay";
    private static final String EXTENSION = ".pzrp.gz";

    private ReplayStorage() {
    }

    static Path pathFor(String captureName) {
        return Paths.get(Core.getMyDocumentFolder(), DIRECTORY, captureName + EXTENSION);
    }
}
