package com.gavrh.pzreplay;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo {
    public static final String VERSION = loadVersion();

    private BuildInfo() {
    }

    private static String loadVersion() {
        try (InputStream input = BuildInfo.class.getResourceAsStream("build-info.properties")) {
            if (input == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "dev");
        } catch (IOException exception) {
            return "dev";
        }
    }
}
