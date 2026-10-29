if PZReplayText then return PZReplayText end

PZReplayText = {}

local T = PZReplayText
local unpackValues = table.unpack or unpack

local function asTextArguments(...)
    local arguments = { ... }
    for index = 1, #arguments do
        arguments[index] = tostring(arguments[index])
    end
    return unpackValues(arguments)
end

local function translated(key, fallback, ...)
    local value
    local hasArguments = select("#", ...) > 0
    if getText then
        local ok, result
        if hasArguments then
            ok, result = pcall(getText, key, asTextArguments(...))
        else
            ok, result = pcall(getText, key)
        end
        if ok then
            value = result
        end
    end
    if type(value) == "string" and value ~= "" and value ~= key then
        return value, true
    end
    if type(fallback) ~= "string" then
        return "", false
    end
    return fallback, false
end

function T.get(key, fallback)
    local value = translated(key, fallback)
    return value
end

function T.format(key, fallback, ...)
    local value, fromTranslation = translated(key, fallback, ...)
    if fromTranslation or select("#", ...) == 0 then
        return value
    end
    local ok, result = pcall(string.format, fallback, ...)
    if ok then
        return result
    end
    return value
end

local MESSAGES = {
    ["Countdown cancelled."] = {
        "UI_pzreplay_message_countdown_cancelled", "Countdown cancelled."
    },
    ["Recording stopped."] = {
        "UI_pzreplay_message_recording_stopped", "Recording stopped."
    },
    ["Player"] = { "UI_pzreplay_camera_player", "Player" },
    ["Follow ghost"] = { "UI_pzreplay_camera_follow_ghost", "Follow ghost" },
    ["Free camera"] = { "UI_pzreplay_camera_free", "Free camera" },
    ["kill switch is enabled"] = {
        "UI_pzreplay_message_kill_switch_enabled", "kill switch is enabled"
    },
    ["recording is active"] = {
        "UI_pzreplay_message_recording_active", "recording is active"
    },
    ["playback already active"] = {
        "UI_pzreplay_message_playback_active", "playback already active"
    },
    ["a ghost is already spawned"] = {
        "UI_pzreplay_message_ghost_active", "a ghost is already spawned"
    },
    ["replay path is empty"] = {
        "UI_pzreplay_message_replay_path_empty", "replay path is empty"
    },
    ["invalid replay path"] = {
        "UI_pzreplay_message_invalid_replay_path", "invalid replay path"
    },
    ["replay has no frames"] = {
        "UI_pzreplay_message_replay_no_frames", "replay has no frames"
    },
    ["ghost spawn failed"] = {
        "UI_pzreplay_message_ghost_spawn_failed", "ghost spawn failed"
    },
    ["ghost motion start failed"] = {
        "UI_pzreplay_message_ghost_motion_failed", "ghost motion start failed"
    },
    ["ghost was removed"] = {
        "UI_pzreplay_message_ghost_removed", "ghost was removed"
    },
    ["appearance data is corrupt or incomplete"] = {
        "UI_pzreplay_message_appearance_corrupt",
        "appearance data is corrupt or incomplete"
    },
    ["replay is no longer available"] = {
        "UI_pzreplay_message_replay_gone", "replay is no longer available"
    },
    ["replay is being played"] = {
        "UI_pzreplay_message_replay_played", "replay is being played"
    },
    ["favorites could not be saved"] = {
        "UI_pzreplay_message_favorites_save_failed", "favorites could not be saved"
    },
    ["replay has no game version"] = {
        "UI_pzreplay_message_no_game_version", "replay has no game version"
    },
    ["replay has no map"] = {
        "UI_pzreplay_message_no_map", "replay has no map"
    },
    ["replay has no save name"] = {
        "UI_pzreplay_message_no_save", "replay has no save name"
    },
    ["unknown date"] = { "UI_pzreplay_browser_unknown_date", "unknown date" },
    ["unknown map"] = { "UI_pzreplay_browser_unknown_map", "unknown map" },
    ["unknown character"] = {
        "UI_pzreplay_browser_details_unknown_character", "unknown character"
    },
    ["unknown save"] = {
        "UI_pzreplay_browser_details_unknown_save", "unknown save"
    },
    ["unknown mode"] = {
        "UI_pzreplay_browser_details_unknown_mode", "unknown mode"
    },
    ["unknown error"] = {
        "UI_pzreplay_browser_details_unknown_error", "unknown error"
    },
    ["valid"] = { "UI_pzreplay_browser_details_valid", "valid" },
    ["yes"] = { "UI_pzreplay_common_yes", "yes" },
    ["no"] = { "UI_pzreplay_common_no", "no" }
}

local PREFIXES = {
    { "Saved ", "UI_pzreplay_message_saved", "Saved %s" },
    { "Recording failed: ", "UI_pzreplay_message_recording_failed",
        "Recording failed: %s" },
    { "cannot read replay: ", "UI_pzreplay_message_cannot_read_replay",
        "cannot read replay: %s" },
    { "invalid replay timeline: ", "UI_pzreplay_message_invalid_timeline",
        "invalid replay timeline: %s" },
    { "invalid replay: ", "UI_pzreplay_message_invalid_replay",
        "invalid replay: %s" },
    { "unreadable: ", "UI_pzreplay_browser_details_unreadable", "unreadable: %s" },
    { "format version mismatch replay=",
        "UI_pzreplay_message_format_mismatch",
        "format version mismatch replay=%s" },
    { "game version mismatch replay=", "UI_pzreplay_message_game_mismatch",
        "game version mismatch replay=%s" },
    { "map mismatch replay=", "UI_pzreplay_message_map_mismatch",
        "map mismatch replay=%s" },
    { "save mismatch replay=", "UI_pzreplay_message_save_mismatch",
        "save mismatch replay=%s" },
    { "game mode mismatch replay=", "UI_pzreplay_message_mode_mismatch",
        "game mode mismatch replay=%s" }
}

local DETAIL_LABELS = {
    ["Name"] = { "UI_pzreplay_browser_details_name", "Name" },
    ["Date / duration"] = {
        "UI_pzreplay_browser_details_date_duration", "Date / duration"
    },
    ["Map"] = { "UI_pzreplay_browser_details_map", "Map" },
    ["Character"] = { "UI_pzreplay_browser_details_character", "Character" },
    ["Save / mode"] = { "UI_pzreplay_browser_details_save_mode", "Save / mode" },
    ["Size / rate"] = { "UI_pzreplay_browser_details_size_rate", "Size / rate" },
    ["Status"] = { "UI_pzreplay_browser_details_status", "Status" },
    ["Favorite / last"] = {
        "UI_pzreplay_browser_details_favorite_last", "Favorite / last"
    }
}

function T.message(value)
    if type(value) ~= "string" or value == "" then
        return ""
    end
    local exact = MESSAGES[value]
    if exact then
        return T.get(exact[1], exact[2])
    end
    for _, prefix in ipairs(PREFIXES) do
        if value:sub(1, #prefix[1]) == prefix[1] then
            return T.format(prefix[2], prefix[3], value:sub(#prefix[1] + 1))
        end
    end
    return value
end

function T.detailLabel(value)
    local entry = DETAIL_LABELS[value]
    if entry then
        return T.get(entry[1], entry[2])
    end
    return value
end

return PZReplayText
