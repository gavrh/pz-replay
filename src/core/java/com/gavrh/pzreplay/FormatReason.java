package com.gavrh.pzreplay;

public final class FormatReason {
    public static final String NOT_CONTAINER = "not_container";
    public static final String OLD_LAYOUT = "old_layout";
    public static final String UNSUPPORTED_VERSION = "unsupported_version";
    public static final String UNSUPPORTED_FLAGS = "unsupported_flags";
    public static final String TRUNCATED = "truncated";
    public static final String GZIP_CRC = "gzip_crc";
    public static final String TRAILING_BYTES = "trailing_bytes";
    public static final String TOO_LARGE = "too_large";
    public static final String MANIFEST_LENGTH = "manifest_length";
    public static final String MANIFEST_GRAMMAR = "manifest_grammar";
    public static final String MANIFEST_DUPLICATE_KEY = "manifest_duplicate_key";
    public static final String MANIFEST_MISSING_FIELD = "manifest_missing_field";
    public static final String MANIFEST_FIELD_INVALID = "manifest_field_invalid";
    public static final String MANIFEST_HASH = "manifest_hash";
    public static final String FOOTER = "footer";
    public static final String SECTION_TABLE = "section_table";
    public static final String SECTION_DUPLICATE = "section_duplicate";
    public static final String SECTION_FLAG = "section_flag";
    public static final String SECTION_HASH = "section_hash";
    public static final String SECTION_SCHEMA_NEWER = "section_schema_newer";
    public static final String ACTOR_SECTION_MISSING = "actor_section_missing";
    public static final String ACTOR_PAYLOAD_INVALID = "actor_payload_invalid";
    public static final String UNKNOWN_REQUIRED_SECTION = "unknown_required_section";
    public static final String WORLD_UNSUPPORTED = "world_unsupported";
    public static final String WORLD_SECTION_MISSING = "world_section_missing";
    public static final String WORLD_SECTION_HASH = "world_section_hash";
    public static final String WORLD_SCHEMA_NEWER = "world_schema_newer";
    public static final String WORLD_COUNT_MISMATCH = "world_count_mismatch";
    public static final String WORLD_BASE_INVALID = "world_base_invalid";
    public static final String WORLD_LOG_INVALID = "world_log_invalid";

    private FormatReason() {
    }
}
