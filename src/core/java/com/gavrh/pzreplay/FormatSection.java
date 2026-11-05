package com.gavrh.pzreplay;

public record FormatSection(String id, boolean required, int schemaVersion, byte[] payload) {
    public FormatSection {
        id = id == null ? "" : id;
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
