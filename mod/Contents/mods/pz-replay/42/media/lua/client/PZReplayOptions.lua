require "PZReplayText"

local ok, modOptions = pcall(require, "PZAPI/ModOptions")
if not ok or not PZAPI or not PZAPI.ModOptions then
    return
end

local T = PZReplayText

PZReplayOptions = PZReplayOptions or {}
local M = PZReplayOptions

M.MOD_OPTIONS_ID = "pz-replay"

local registered = false
local hooked = false
local options

local function javaValue(getter, fallback)
    if PZReplay and PZReplay[getter] then
        local success, value = pcall(PZReplay[getter])
        if success and value ~= nil then
            return value
        end
    end
    return fallback
end

local function apply(setter, value)
    if not (PZReplay and PZReplay[setter]) then
        return false
    end
    local success, applied = pcall(PZReplay[setter], value)
    if not success then
        return false
    end
    M.syncFromSettings()
    return applied ~= false
end

local function hookLoad()
    if hooked then
        return
    end
    local original = PZAPI.ModOptions.load
    if not original then
        return
    end
    PZAPI.ModOptions.load = function(self, ...)
        original(self, ...)
        M.syncFromSettings()
    end
    hooked = true
end

function M.syncFromSettings()
    if not registered or not options then
        return
    end
    local rate = options.dict.sample_rate_hz
    if rate then
        rate:setValue(javaValue("getSampleRate", 30))
    end
    local countdown = options.dict.countdown_seconds
    if countdown then
        countdown:setValue(javaValue("getCountdownSeconds", 3))
    end
    local camera = options.dict.camera_follow_default
    if camera then
        camera:setValue(javaValue("isCameraFollowDefault", false))
    end
    local debug = options.dict.debug_controls
    if debug then
        debug:setValue(javaValue("isDebugControlsEnabled", false))
    end
    local killSwitch = options.dict.kill_switch
    if killSwitch then
        killSwitch:setValue(javaValue("isKillSwitchEnabled", false))
    end
end

function M.register()
    if registered or not PZAPI or not PZAPI.ModOptions then
        return registered
    end
    options = PZAPI.ModOptions:create(M.MOD_OPTIONS_ID,
        T.get("UI_pzreplay_modoptions_title", "pz-replay"))
    options:addTitle(T.get("UI_pzreplay_modoptions_settings", "Replay settings"))
    options:addDescription(T.get("UI_pzreplay_modoptions_keybinds_note",
        "Key bindings are managed in the pz-replay panel."))

    local sampleRate = options:addSlider("sample_rate_hz",
        T.get("UI_pzreplay_panel_sample_rate", "Sample rate"),
        20, 30, 1, javaValue("getSampleRate", 30),
        T.get("UI_pzreplay_modoptions_sample_rate_tooltip",
            "Replay capture samples per second (20-30)."))
    sampleRate.onChangeApply = function(_, value)
        return apply("setSampleRate", tonumber(value))
    end

    local countdown = options:addSlider("countdown_seconds",
        T.get("UI_pzreplay_panel_countdown", "Countdown"),
        0, 10, 1, javaValue("getCountdownSeconds", 3),
        T.get("UI_pzreplay_modoptions_countdown_tooltip",
            "Seconds before a recording starts (0 disables the countdown)."))
    countdown.onChangeApply = function(_, value)
        return apply("setCountdownSeconds", math.floor(tonumber(value) or 0))
    end

    local camera = options:addTickBox("camera_follow_default",
        T.get("UI_pzreplay_panel_camera_follow_default", "Camera follow default"),
        javaValue("isCameraFollowDefault", false),
        T.get("UI_pzreplay_modoptions_camera_tooltip",
            "Follow the replay ghost by default during playback."))
    camera.onChangeApply = function(_, value)
        return apply("setCameraFollowDefault", value == true)
    end

    local debug = options:addTickBox("debug_controls",
        T.get("UI_pzreplay_panel_debug_controls", "Debug controls"),
        javaValue("isDebugControlsEnabled", false),
        T.get("UI_pzreplay_modoptions_debug_tooltip",
            "Enable the developer debug controls."))
    debug.onChangeApply = function(_, value)
        return apply("setDebugControlsEnabled", value == true)
    end

    local killSwitch = options:addTickBox("kill_switch",
        T.get("UI_pzreplay_panel_kill_switch", "Kill switch"),
        javaValue("isKillSwitchEnabled", false),
        T.get("UI_pzreplay_modoptions_kill_switch_tooltip",
            "Disable recording, playback, and ghost controls."))
    killSwitch.onChangeApply = function(_, value)
        return apply("setKillSwitchEnabled", value == true)
    end

    registered = true
    hookLoad()
    M.syncFromSettings()
    return true
end

M.register()

Events.OnGameBoot.Add(function()
    M.register()
end)

Events.OnMainMenuEnter.Add(function()
    M.register()
end)
