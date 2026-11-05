package com.gavrh.pzreplay;

public record FormatMod(String id, String version) {
    public FormatMod {
        id = id == null ? "" : id;
        version = version == null ? "" : version;
    }
}
