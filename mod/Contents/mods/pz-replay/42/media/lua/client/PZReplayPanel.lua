require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISTextEntryBox"
require "RadioCom/ISUIRadio/ISSliderPanel"
require "PZReplayBrowser"
require "PZReplayText"

if PZReplayPanel then return end

local T = PZReplayText

PZReplayPanel = ISPanel:derive("PZReplayPanel")

local FONT = UIFont.Small
local PAD = 10
local TITLE_HEIGHT = 28
local STATUS_LINES = 5
local TAB_HEIGHT = 26
local TRANSPORT_SLIDER_HEIGHT = 18
local SPEEDS = { 0.25, 0.5, 1, 2, 4 }
local COUNTDOWN_MIN = 0
local COUNTDOWN_MAX = 10
local CAMERA_BUTTON_WIDTH = 84
local CAMERA_LABELS = {
    player = { key = "UI_pzreplay_panel_camera_player", fallback = "Player" },
    follow_ghost = { key = "UI_pzreplay_panel_camera_follow", fallback = "Follow" },
    free = { key = "UI_pzreplay_panel_camera_free", fallback = "Free" }
}

local function fontHeight(font)
    return getTextManager():getFontHeight(font)
end

local function fitText(text, width, font)
    if not text then return "" end
    local value = tostring(text)
    if getTextManager():MeasureStringX(font, value) <= width then
        return value
    end
    while #value > 1 and getTextManager():MeasureStringX(font, value .. "...") > width do
        value = value:sub(1, #value - 1)
    end
    return value .. "..."
end

local function formatTime(seconds)
    if not seconds or seconds < 0 then seconds = 0 end
    local total = math.floor(seconds)
    return string.format("%d:%02d", math.floor(total / 60), total % 60)
end

local function formatClock(seconds)
    if not seconds or seconds < 0 then seconds = 0 end
    local tenths = math.floor(seconds * 10 + 0.5)
    return string.format("%d:%04.1f", math.floor(tenths / 600), (tenths % 600) / 10)
end

local function formatSpeed(speed)
    if not speed or speed <= 0 then return "1x" end
    if math.abs(speed - math.floor(speed)) < 0.001 then
        return string.format("%dx", math.floor(speed))
    end
    return string.format("%gx", speed)
end

local function fileName(path)
    if not path or path == "" then return "" end
    return path:match("([^/\\]+)$") or path
end

local function actionLabel(action)
    if action == "record" then
        return T.get("UI_pzreplay_panel_key_label_record", "Record / stop")
    end
    if action == "play" then
        return T.get("UI_pzreplay_panel_key_label_play", "Play / stop")
    end
    if action == "camera" then
        return T.get("UI_pzreplay_panel_key_label_camera", "Camera follow")
    end
    return T.get("UI_pzreplay_panel_key_label_panel", "Open / close panel")
end

function PZReplayPanel:createChildren()
    local screenWidth = getCore():getScreenWidth()
    local screenHeight = getCore():getScreenHeight()
    self.width = math.min(760, screenWidth - 20)
    self.height = math.min(560, screenHeight - 20)
    self.x = math.floor((screenWidth - self.width) / 2)
    self.y = math.floor((screenHeight - self.height) / 2)
    self:setX(self.x)
    self:setY(self.y)
    self:setWidth(self.width)
    self:setHeight(self.height)

    self.titleHeight = TITLE_HEIGHT
    self.statusHeight = fontHeight(FONT) * STATUS_LINES + 10
    self.tabHeight = TAB_HEIGHT
    self.contentY = self.titleHeight + self.statusHeight + self.tabHeight + 6
    self.contentX = PAD
    self.contentWidth = self.width - PAD * 2
    self.contentHeight = self.height - self.contentY - PAD

    local buttonHeight = fontHeight(FONT) + 8

    self.closeButton = ISButton:new(self.width - PAD - 54, 4, 50, TITLE_HEIGHT - 8,
        T.get("UI_pzreplay_common_close", "Close"), self, PZReplayPanel.onClose)
    self.closeButton:initialise()
    self.closeButton:instantiate()
    self:addChild(self.closeButton)

    self.replayTab = ISButton:new(PAD, self.titleHeight + self.statusHeight + 2,
        86, buttonHeight, T.get("UI_pzreplay_panel_tab_replays", "Replays"),
        self, PZReplayPanel.onReplayTab)
    self.replayTab:initialise()
    self.replayTab:instantiate()
    self:addChild(self.replayTab)

    self.settingsTab = ISButton:new(PAD + 92, self.titleHeight + self.statusHeight + 2,
        86, buttonHeight, T.get("UI_pzreplay_panel_tab_settings", "Settings"),
        self, PZReplayPanel.onSettingsTab)
    self.settingsTab:initialise()
    self.settingsTab:instantiate()
    self:addChild(self.settingsTab)

    self.captureButton = ISButton:new(self.width - PAD - 126,
        self.titleHeight + self.statusHeight + 2, 126, buttonHeight,
        T.get("UI_pzreplay_common_record", "Record"), self, PZReplayPanel.onCapture)
    self.captureButton:initialise()
    self.captureButton:instantiate()
    self:addChild(self.captureButton)

    self.cameraPlayerButton = self:createCameraButton("player",
        T.get(CAMERA_LABELS.player.key, CAMERA_LABELS.player.fallback),
        self.captureButton.x - 6 - CAMERA_BUTTON_WIDTH * 3 - 8)
    self.cameraFollowButton = self:createCameraButton("follow_ghost",
        T.get(CAMERA_LABELS.follow_ghost.key, CAMERA_LABELS.follow_ghost.fallback),
        self.captureButton.x - 6 - CAMERA_BUTTON_WIDTH * 2 - 4)
    self.cameraFreeButton = self:createCameraButton("free",
        T.get(CAMERA_LABELS.free.key, CAMERA_LABELS.free.fallback),
        self.captureButton.x - 6 - CAMERA_BUTTON_WIDTH)

    self:createNameEntry(buttonHeight)
    self:createTransport(buttonHeight)
    local browserY = self.contentY + self.nameHeight + self.transportHeight + 4
    local browserHeight = self.height - browserY - PAD

    self.browser = PZReplayBrowser:new(self.contentX, browserY,
        self.contentWidth, browserHeight, {
            allowPlayback = true,
            isPlaying = function()
                return PZReplayPlayback and PZReplayPlayback.isActive()
            end,
            canPlay = function()
                return not PZReplay.isKillSwitchEnabled() and not PZReplay.isRecording()
                    and not PZReplay.isCountingDown()
            end,
            onPlay = function(path)
                local started = PZReplayPlayback and PZReplayPlayback.play(path)
                if started then
                    return true, T.format("UI_pzreplay_panel_status_playing_file",
                        "Playing %s", fileName(path))
                end
                local error = PZReplayPlayback and PZReplayPlayback.getLastPlaybackError()
                return false, error ~= "" and T.message(error)
                    or T.get("UI_pzreplay_browser_playback_failed", "Playback failed.")
            end,
            onStop = function()
                local stopped = PZReplayPlayback and PZReplayPlayback.stop()
                return stopped, stopped
                    and T.get("UI_pzreplay_browser_playback_stopped", "Playback stopped.")
                    or T.get("UI_pzreplay_browser_playback_not_active",
                        "Playback is not active.")
            end
        })
    self.browser:initialise()
    self.browser:instantiate()
    self:addChild(self.browser)

    self:createSettings(buttonHeight)
    self:setView("replays")
end

function PZReplayPanel:createCameraButton(mode, label, x)
    local buttonHeight = fontHeight(FONT) + 8
    local button = ISButton:new(x, self.titleHeight + self.statusHeight + 2,
        CAMERA_BUTTON_WIDTH, buttonHeight, label, self, PZReplayPanel.onCameraMode)
    button.internal = mode
    button:initialise()
    button:instantiate()
    self:addChild(button)
    return button
end

function PZReplayPanel:createNameEntry(buttonHeight)
    self.nameY = self.contentY
    self.nameHeight = buttonHeight + 4
    local entryX = PAD + 44
    local entryWidth = math.min(320, self.contentWidth - 44)
    self.nameEntry = ISTextEntryBox:new("", entryX, self.nameY, entryWidth, buttonHeight)
    self.nameEntry.font = FONT
    self.nameEntry:initialise()
    self.nameEntry:instantiate()
    self:addChild(self.nameEntry)
end

function PZReplayPanel:createTransport(buttonHeight)
    self.transportY = self.contentY + self.nameHeight
    self.transportHeight = buttonHeight + 4 + TRANSPORT_SLIDER_HEIGHT

    self.playPauseButton = ISButton:new(PAD, self.transportY, 90, buttonHeight,
        T.get("UI_pzreplay_common_play", "Play"), self, PZReplayPanel.onTransportPlayPause)
    self.playPauseButton:initialise()
    self.playPauseButton:instantiate()
    self:addChild(self.playPauseButton)

    self.restartButton = ISButton:new(PAD + 96, self.transportY, 84, buttonHeight,
        T.get("UI_pzreplay_panel_restart", "Restart"), self, PZReplayPanel.onTransportRestart)
    self.restartButton:initialise()
    self.restartButton:instantiate()
    self:addChild(self.restartButton)

    self.speedButton = ISButton:new(PAD + 186, self.transportY, 90, buttonHeight,
        T.format("UI_pzreplay_panel_speed", "Speed: %s", "1x"),
        self, PZReplayPanel.onTransportSpeed)
    self.speedButton:initialise()
    self.speedButton:instantiate()
    self:addChild(self.speedButton)

    self.transportSlider = ISSliderPanel:new(PAD,
        self.transportY + buttonHeight + 4, self.contentWidth, TRANSPORT_SLIDER_HEIGHT,
        self, PZReplayPanel.onTransportSeek)
    self.transportSlider.doButtons = false
    self.transportSlider:initialise()
    self.transportSlider:instantiate()
    self.transportSlider:setValues(0, 1, 0.1, 1, true)
    self.transportSlider:setCurrentValue(0, true)
    self:addChild(self.transportSlider)
end

function PZReplayPanel:createSettings(buttonHeight)
    local rowHeight = buttonHeight + 4
    local controlX = PAD + 156
    local controlWidth = 150
    local y = self.contentY

    self.rateMinus = self:addSettingsButton("-", controlX, y, 28, buttonHeight,
        PZReplayPanel.onRateDown)
    self.ratePlus = self:addSettingsButton("+", controlX + 96, y, 28, buttonHeight,
        PZReplayPanel.onRateUp)
    self.rateRowY = y

    y = y + rowHeight
    self.countdownRowY = y
    self.countdownMinus = self:addSettingsButton("-", controlX, y, 28, buttonHeight,
        PZReplayPanel.onCountdownDown)
    self.countdownPlus = self:addSettingsButton("+", controlX + 96, y, 28, buttonHeight,
        PZReplayPanel.onCountdownUp)

    y = y + rowHeight
    self.cameraDefaultRowY = y
    local off = T.get("UI_pzreplay_common_off", "Off")
    self.cameraDefaultButton = self:addSettingsButton(off, controlX, y, 80, buttonHeight,
        PZReplayPanel.onToggleCameraDefault)

    y = y + rowHeight
    self.debugRowY = y
    self.debugButton = self:addSettingsButton(off, controlX, y, 80, buttonHeight,
        PZReplayPanel.onToggleDebug)

    y = y + rowHeight
    self.killRowY = y
    self.killButton = self:addSettingsButton(off, controlX, y, 80, buttonHeight,
        PZReplayPanel.onToggleKillSwitch)

    self.keyRows = {}
    self.keyButtons = {}
    for _, action in ipairs({ "record", "play", "camera", "panel" }) do
        y = y + rowHeight
        self.keyRows[action] = y
        self.keyButtons[action] = self:addSettingsButton("", controlX, y,
            controlWidth, buttonHeight, PZReplayPanel.onRebindKey, action)
    end

    y = y + rowHeight
    self.resetRowY = y
    self.resetKeysButton = self:addSettingsButton(
        T.get("UI_pzreplay_panel_reset_keys", "Reset keys"),
        PAD, y, 120, buttonHeight, PZReplayPanel.onResetKeys)
end

function PZReplayPanel:addSettingsButton(title, x, y, width, height, onclick, action)
    local button = ISButton:new(x, y, width, height, title, self, onclick)
    button.internal = action
    button:initialise()
    button:instantiate()
    self:addChild(button)
    button:setVisible(false)
    return button
end

function PZReplayPanel:setView(view)
    self.view = view
    local replayView = view == "replays"
    self.browser:setVisible(replayView)
    self.nameEntry:setVisible(replayView)
    self.playPauseButton:setVisible(replayView)
    self.restartButton:setVisible(replayView)
    self.speedButton:setVisible(replayView)
    self.transportSlider:setVisible(replayView)
    self.replayTab:setEnable(not replayView)
    self.settingsTab:setEnable(replayView)
    self.rateMinus:setVisible(not replayView)
    self.ratePlus:setVisible(not replayView)
    self.countdownMinus:setVisible(not replayView)
    self.countdownPlus:setVisible(not replayView)
    self.cameraDefaultButton:setVisible(not replayView)
    self.debugButton:setVisible(not replayView)
    self.killButton:setVisible(not replayView)
    self.resetKeysButton:setVisible(not replayView)
    for _, button in pairs(self.keyButtons) do
        button:setVisible(not replayView)
    end
    self:refreshControls()
end

function PZReplayPanel:open()
    self:refreshControls()
    self:setView(self.view or "replays")
    self.browser:refresh()
    self:setVisible(true)
    self:setCapture(true)
    self:setAlwaysOnTop(true)
    self:setWantKeyEvents(true)
    self:setWantMouseEvents(true)
    self:bringToTop()
end

function PZReplayPanel:close()
    local callback = self.onPanelClosed
    self.onPanelClosed = nil
    self.rebindingAction = nil
    if self.nameEntry then
        self.nameEntry:unfocus()
    end
    self:setCapture(false)
    self:setWantKeyEvents(false)
    self:setWantMouseEvents(false)
    self:setVisible(false)
    if callback then
        callback()
    end
end

function PZReplayPanel:toggle()
    if self:getIsVisible() then
        self:close()
    else
        self:open()
    end
end

function PZReplayPanel:refreshControls()
    if not PZReplay then return end
    local killSwitch = PZReplay.isKillSwitchEnabled()
    local recording = PZReplay.isRecording()
    local countingDown = PZReplay.isCountingDown()
    local playing = PZReplayPlayback and PZReplayPlayback.isActive()
    local samples = T.format("UI_pzreplay_panel_hz", "%.0f Hz", PZReplay.getSampleRate())
    local on = T.get("UI_pzreplay_common_on", "On")
    local off = T.get("UI_pzreplay_common_off", "Off")

    self.rateValue = samples
    self.countdownValue = T.format("UI_pzreplay_panel_seconds", "%d s",
        PZReplay.getCountdownSeconds())
    self.cameraDefaultButton:setTitle(PZReplay.isCameraFollowDefault() and on or off)
    self.debugButton:setTitle(PZReplay.isDebugControlsEnabled() and on or off)
    self.killButton:setTitle(killSwitch and on or off)
    local captureTitle = T.get("UI_pzreplay_common_record", "Record")
    if countingDown then
        captureTitle = T.get("UI_pzreplay_panel_cancel_start", "Cancel start")
    elseif recording then
        captureTitle = T.get("UI_pzreplay_panel_stop_recording", "Stop recording")
    end
    self.captureButton:setTitle(captureTitle)
    self.captureButton:setEnable(recording or countingDown
        or (not killSwitch and not playing))
    self.nameEntry:setEditable(not killSwitch and not recording and not countingDown)
    local cameraMode = playing and PZReplayPlayback.getCameraMode() or "player"
    for mode, button in pairs({ player = self.cameraPlayerButton,
        follow_ghost = self.cameraFollowButton, free = self.cameraFreeButton }) do
        local entry = CAMERA_LABELS[mode]
        local label = entry and T.get(entry.key, entry.fallback) or mode
        button:setTitle(mode == cameraMode and ("[" .. label .. "]") or label)
        button:setEnable(not killSwitch and playing)
    end
    self.rateMinus:setEnable(not killSwitch and PZReplay.getSampleRate() > 20)
    self.ratePlus:setEnable(not killSwitch and PZReplay.getSampleRate() < 30)
    self.countdownMinus:setEnable(not killSwitch
        and PZReplay.getCountdownSeconds() > COUNTDOWN_MIN)
    self.countdownPlus:setEnable(not killSwitch
        and PZReplay.getCountdownSeconds() < COUNTDOWN_MAX)
    self.cameraDefaultButton:setEnable(not killSwitch)
    self.debugButton:setEnable(not killSwitch)
    self.killButton:setEnable(true)
    for action, button in pairs(self.keyButtons) do
        if self.rebindingAction == action then
            button:setTitle(T.get("UI_pzreplay_panel_press_key", "Press a key..."))
        else
            local binding = PZReplay.getKeyBindingName(action)
            if PZReplay.hasKeyBindingConflict
                and PZReplay.hasKeyBindingConflict(action) then
                binding = binding .. " !"
            end
            button:setTitle(binding)
        end
        button:setEnable(self.rebindingAction == nil)
    end
    self.resetKeysButton:setEnable(self.rebindingAction == nil)

    local paused = playing and PZReplayPlayback.isPaused()
    local finished = playing and PZReplayPlayback.isFinished()
    local advancing = playing and PZReplayPlayback.isPlaying()
    local transportLabel = T.get("UI_pzreplay_common_play", "Play")
    if finished then
        transportLabel = T.get("UI_pzreplay_panel_restart", "Restart")
    elseif paused then
        transportLabel = T.get("UI_pzreplay_panel_resume", "Resume")
    elseif advancing then
        transportLabel = T.get("UI_pzreplay_panel_pause", "Pause")
    end
    self.playPauseButton:setTitle(transportLabel)
    self.playPauseButton:setEnable(not killSwitch and not recording and not countingDown
        and (playing or PZReplay.getLastReplayPath() ~= ""))
    self.restartButton:setEnable(playing)
    local speed = PZReplayPlayback and PZReplayPlayback.getSpeed() or 1
    self.speedButton:setTitle(T.format("UI_pzreplay_panel_speed", "Speed: %s",
        formatSpeed(speed)))
    self.speedButton:setEnable(playing)
    self.transportSlider.disabled = not playing
    if not playing then
        self.transportSlider:setValues(0, 1, 0.1, 1, true)
        self.transportSlider:setCurrentValue(0, true)
    else
        local duration = PZReplayPlayback.getDuration()
        self.transportSlider:setValues(0, math.max(duration, 0.1), 0.1, 1, true)
        if not self.transportSlider.dragInside then
            self.transportSlider:setCurrentValue(
                math.min(PZReplayPlayback.getCurrentTime(), duration), true)
        end
    end
end

function PZReplayPanel:onClose()
    self:close()
end

function PZReplayPanel:onReplayTab()
    self:setView("replays")
end

function PZReplayPanel:onSettingsTab()
    self:setView("settings")
end

function PZReplayPanel:onCameraMode(button)
    if not PZReplayPlayback or not PZReplayPlayback.isActive() then
        return
    end
    local mode = button.internal
    if not PZReplayPlayback.setCameraMode(mode) then
        self.browser:setStatus(T.get("UI_pzreplay_message_camera_unavailable",
            "Camera mode unavailable."))
        self:refreshControls()
        return
    end
    if mode == "free" then
        self:close()
        return
    end
    self:refreshControls()
end

function PZReplayPanel:onTransportPlayPause()
    if not PZReplayPlayback then return end
    if PZReplayPlayback.isActive() then
        if PZReplayPlayback.isFinished() then
            PZReplayPlayback.restart()
        elseif PZReplayPlayback.isPaused() then
            PZReplayPlayback.resume()
        elseif PZReplayPlayback.isPlaying() then
            PZReplayPlayback.pause()
        else
            PZReplayPlayback.resume()
        end
    else
        local path = PZReplay.getLastReplayPath()
        if path ~= "" then
            local started = PZReplayPlayback.play(path)
            self.browser:setStatus(started
                and T.get("UI_pzreplay_browser_playback_started", "Playback started.")
                or T.message(PZReplayPlayback.getLastPlaybackError()))
        end
    end
    self:refreshControls()
end

function PZReplayPanel:onTransportRestart()
    if not PZReplayPlayback then return end
    local restarted
    if PZReplayPlayback.isActive() then
        restarted = PZReplayPlayback.restart()
    else
        local path = PZReplay.getLastReplayPath()
        if path ~= "" then
            restarted = PZReplayPlayback.play(path)
        end
    end
    if not restarted then
        self.browser:setStatus(T.message(PZReplayPlayback.getLastPlaybackError()))
    end
    self:refreshControls()
end

function PZReplayPanel:onTransportSpeed()
    if not PZReplayPlayback or not PZReplayPlayback.isActive() then return end
    local current = PZReplayPlayback.getSpeed()
    local nextSpeed = SPEEDS[1]
    for index, value in ipairs(SPEEDS) do
        if math.abs(value - current) < 0.001 then
            nextSpeed = SPEEDS[(index % #SPEEDS) + 1]
            break
        end
    end
    PZReplayPlayback.setSpeed(nextSpeed)
    self:refreshControls()
end

function PZReplayPanel:onTransportSeek(value)
    if PZReplayPlayback and PZReplayPlayback.isActive() then
        PZReplayPlayback.seek(value)
    end
end

function PZReplayPanel:getName()
    if not self.nameEntry then
        return ""
    end
    return self.nameEntry:getText() or ""
end

function PZReplayPanel:onCapture()
    if PZReplay.isCountingDown() then
        PZReplay.cancelCountdown()
        self.browser:setStatus(T.get("UI_pzreplay_panel_status_countdown_cancelled",
            "Countdown cancelled."))
    elseif PZReplay.isRecording() then
        PZReplay.stopRecording()
        self.browser:refresh()
        self.browser:setStatus(T.message(PZReplay.getLastRecordingResult()))
    else
        local started = PZReplay.startRecording(self:getName())
        if started then
            self.nameEntry:unfocus()
        end
        if started and PZReplay.isCountingDown() then
            local name = PZReplay.getRecordingName()
            self.browser:setStatus(T.format("UI_pzreplay_panel_status_starting_short",
                "Starting in %.0fs as %s",
                PZReplay.getCountdownRemainingSeconds(), name))
        elseif started then
            self.browser:setStatus(T.format("UI_pzreplay_panel_status_recording_started",
                "Recording started as %s.", PZReplay.getRecordingName()))
        else
            self.browser:setStatus(T.message(PZReplay.getCaptureError()))
        end
    end
    self:refreshControls()
end

function PZReplayPanel:onRateDown()
    local rate = math.max(20, math.floor(PZReplay.getSampleRate() - 1))
    PZReplay.setSampleRate(rate)
    self:refreshControls()
end

function PZReplayPanel:onRateUp()
    local rate = math.min(30, math.floor(PZReplay.getSampleRate() + 1))
    PZReplay.setSampleRate(rate)
    self:refreshControls()
end

function PZReplayPanel:onCountdownDown()
    local seconds = math.max(COUNTDOWN_MIN, PZReplay.getCountdownSeconds() - 1)
    PZReplay.setCountdownSeconds(seconds)
    self:refreshControls()
end

function PZReplayPanel:onCountdownUp()
    local seconds = math.min(COUNTDOWN_MAX, PZReplay.getCountdownSeconds() + 1)
    PZReplay.setCountdownSeconds(seconds)
    self:refreshControls()
end

function PZReplayPanel:onToggleCameraDefault()
    PZReplay.setCameraFollowDefault(not PZReplay.isCameraFollowDefault())
    self:refreshControls()
end

function PZReplayPanel:onToggleDebug()
    PZReplay.setDebugControlsEnabled(not PZReplay.isDebugControlsEnabled())
    self:refreshControls()
end

function PZReplayPanel:onToggleKillSwitch()
    PZReplay.setKillSwitchEnabled(not PZReplay.isKillSwitchEnabled())
    self:refreshControls()
end

function PZReplayPanel:onRebindKey(button)
    self.rebindingAction = button.internal
    self.rebindNotice = ""
    self:refreshControls()
end

function PZReplayPanel:onResetKeys()
    PZReplay.resetKeyBindings()
    self.rebindNotice = T.get("UI_pzreplay_panel_status_binding_reset",
        "Key bindings reset.")
    self:refreshControls()
end

function PZReplayPanel:finishRebind(key)
    local action = self.rebindingAction
    self.rebindingAction = nil
    if key == Keyboard.KEY_ESCAPE then
        self.rebindNotice = T.get("UI_pzreplay_panel_status_binding_unchanged",
            "Key binding unchanged.")
        self:refreshControls()
        return
    end
    if key <= 0 or PZReplayPanel.isModifierKey(key) then
        self.rebindNotice = T.get("UI_pzreplay_panel_status_key_unusable",
            "That key cannot be used.")
        self:refreshControls()
        return
    end
    if not PZReplay.setKeyBinding(action, key) then
        self.rebindNotice = T.get("UI_pzreplay_panel_status_key_duplicate",
            "That key is already used by another pz-replay action.")
        self:refreshControls()
        return
    end
    local warning = PZReplay.checkKeyBinding(action, key)
    if warning ~= "" then
        self.rebindNotice = T.format("UI_pzreplay_panel_status_bound_warning",
            "Bound with warning: %s", T.message(warning))
    else
        self.rebindNotice = T.get("UI_pzreplay_panel_status_binding_updated",
            "Key binding updated.")
    end
    self:refreshControls()
end

function PZReplayPanel.isModifierKey(key)
    return key == Keyboard.KEY_LSHIFT
        or key == Keyboard.KEY_RSHIFT
        or key == Keyboard.KEY_LCONTROL
        or key == Keyboard.KEY_RCONTROL
        or key == Keyboard.KEY_LMENU
        or key == Keyboard.KEY_RMENU
        or key == Keyboard.KEY_LMETA
        or key == Keyboard.KEY_RMETA
end

function PZReplayPanel:onKeyRelease(key)
    if not self:getIsVisible() then
        return false
    end
    if self.rebindingAction then
        self:finishRebind(key)
        return true
    end
    local panelKey = PZReplay and PZReplay.getKeyBinding("panel")
    if key == Keyboard.KEY_ESCAPE or (panelKey and key == panelKey) then
        if PZReplay.isCountingDown() then
            PZReplay.cancelCountdown()
            self.browser:setStatus(T.get("UI_pzreplay_panel_status_countdown_cancelled",
                "Countdown cancelled."))
        end
        self:close()
        return true
    end
    return false
end

function PZReplayPanel:isEditingText()
    return self.nameEntry ~= nil and self.nameEntry:getIsVisible()
        and self.nameEntry:isFocused()
end

function PZReplayPanel:onKeyPress(key)
    if not self:getIsVisible() then
        return false
    end
    if not self.rebindingAction and not self:isEditingText()
        and self.view == "replays" and self.browser then
        if key == Keyboard.KEY_UP then
            self.browser:moveSelection(-1)
        elseif key == Keyboard.KEY_DOWN then
            self.browser:moveSelection(1)
        elseif key == Keyboard.KEY_PAGEUP then
            self.browser:moveSelection(-5)
        elseif key == Keyboard.KEY_PAGEDOWN then
            self.browser:moveSelection(5)
        elseif key == Keyboard.KEY_HOME then
            self.browser:selectIndex(1)
        elseif key == Keyboard.KEY_END then
            self.browser:selectIndex(self.browser.list.count)
        elseif key == Keyboard.KEY_RETURN then
            self.browser:playSelected()
        end
    end
    return true
end

function PZReplayPanel:isKeyConsumed(key)
    return self:getIsVisible()
end

function PZReplayPanel:onMouseWheel(del)
    return self:getIsVisible()
end

function PZReplayPanel:prerender()
    self:drawRect(0, 0, self.width, self.height,
        self.backgroundColor.a, self.backgroundColor.r, self.backgroundColor.g,
        self.backgroundColor.b)
    self:drawRectBorder(0, 0, self.width, self.height,
        self.borderColor.a, self.borderColor.r, self.borderColor.g, self.borderColor.b)
    self:drawText(T.get("UI_pzreplay_panel_title", "Replay"), PAD, 7, 1, 1, 1, 1,
        UIFont.Medium)
end

function PZReplayPanel:render()
    self:renderStatus()
    if self.view == "settings" then
        self:renderSettings()
    elseif self.view == "replays" then
        self:renderName()
        self:renderTransport()
    end
end

function PZReplayPanel:renderName()
    local lineHeight = fontHeight(FONT)
    local y = self.nameY + math.floor((self.nameHeight - lineHeight) / 2)
    self:drawText(T.get("UI_pzreplay_panel_name", "Name"), PAD, y,
        0.86, 0.86, 0.86, 1.0, FONT)
    local hintX = self.nameEntry:getX() + self.nameEntry:getWidth() + 8
    if hintX < self.width - PAD then
        self:drawText(fitText(T.get("UI_pzreplay_panel_blank_auto_name",
            "blank = auto name"), self.width - PAD - hintX, FONT),
            hintX, y, 0.58, 0.58, 0.58, 1.0, FONT)
    end
end

function PZReplayPanel:renderTransport()
    if not PZReplayPlayback or not PZReplayPlayback.isActive() then
        return
    end
    local text = T.format("UI_pzreplay_panel_status_time", "%s / %s",
        formatClock(PZReplayPlayback.getCurrentTime()),
        formatClock(PZReplayPlayback.getDuration()))
    local x = PAD + 286
    self:drawText(fitText(text, self.width - PAD - x, FONT), x, self.transportY + 5,
        0.9, 0.9, 0.9, 1.0, FONT)
end

function PZReplayPanel:renderStatus()
    local lineHeight = fontHeight(FONT)
    local y = self.titleHeight + 4
    local killSwitch = PZReplay.isKillSwitchEnabled()
    local recording = PZReplay.isRecording()
    local countingDown = PZReplay.isCountingDown()
    local playing = PZReplayPlayback and PZReplayPlayback.isActive()

    local captureText
    if killSwitch then
        captureText = T.get("UI_pzreplay_panel_status_disabled",
            "pz-replay is disabled by the kill switch.")
    elseif countingDown then
        captureText = T.format("UI_pzreplay_panel_status_starting",
            "Starting as %s in %.1fs  (%.0f Hz)",
            PZReplay.getRecordingName(), PZReplay.getCountdownRemainingSeconds(),
            PZReplay.getSampleRate())
    elseif recording then
        captureText = T.format("UI_pzreplay_panel_status_recording",
            "Recording as %s  %s  %d frames  %.0f/%.1f Hz",
            PZReplay.getRecordingName(), formatTime(PZReplay.getRecordingSeconds()),
            PZReplay.getRecordedFrames(), PZReplay.getConfiguredSampleRate(),
            PZReplay.getActualSampleRate())
    else
        local result = PZReplay.getLastRecordingResult()
        if result ~= "" then
            captureText = T.message(result)
        else
            captureText = T.format("UI_pzreplay_panel_status_capture_idle",
                "Capture idle  %.0f Hz configured", PZReplay.getSampleRate())
        end
    end
    self:drawText(fitText(captureText, self.width - PAD * 2, FONT), PAD, y,
        (recording or countingDown) and 1.0 or 0.86,
        recording and 0.55 or (countingDown and 0.85 or 0.86),
        recording and 0.45 or (countingDown and 0.4 or 0.86), 1.0, FONT)
    y = y + lineHeight

    local playbackText
    if playing then
        local state = T.get("UI_pzreplay_panel_status_ready", "Ready")
        if PZReplayPlayback.isFinished() then
            state = T.get("UI_pzreplay_panel_status_finished", "Finished")
        elseif PZReplayPlayback.isPaused() then
            state = T.get("UI_pzreplay_panel_status_paused", "Paused")
        elseif PZReplayPlayback.isPlaying() then
            state = T.get("UI_pzreplay_panel_status_playing", "Playing")
        end
        local camera = T.format("UI_pzreplay_panel_status_camera", "  camera: %s",
            T.message(PZReplayPlayback.getCameraModeLabel() or "Player"))
        playbackText = T.format("UI_pzreplay_panel_status_playback",
            "%s  %s / %s  %s%s", state,
            formatClock(PZReplayPlayback.getCurrentTime()),
            formatClock(PZReplayPlayback.getDuration()),
            formatSpeed(PZReplayPlayback.getSpeed()), camera)
    else
        playbackText = T.get("UI_pzreplay_panel_status_playback_idle", "Playback idle")
    end
    self:drawText(fitText(playbackText, self.width - PAD * 2, FONT), PAD, y,
        playing and 0.55 or 0.86, playing and 0.9 or 0.86, playing and 0.55 or 0.86,
        1.0, FONT)
    y = y + lineHeight

    local lastPath = PZReplay.getLastReplayPath()
    local lastText = lastPath ~= ""
        and T.format("UI_pzreplay_panel_status_last_replay", "Last replay  %s",
            fileName(lastPath))
        or T.get("UI_pzreplay_panel_status_last_replay_none", "Last replay  none")
    self:drawText(fitText(lastText, self.width - PAD * 2, FONT), PAD, y,
        0.78, 0.78, 0.78, 1.0, FONT)
    y = y + lineHeight

    local errorText = PZReplay.getCaptureError()
    if errorText == "" and PZReplayPlayback then
        errorText = PZReplayPlayback.getLastPlaybackError()
    end
    if errorText == "" and PZReplayCatalogue then
        errorText = PZReplayCatalogue.getCatalogueError()
    end
    if errorText ~= "" then
        self:drawText(fitText(T.format("UI_pzreplay_common_error", "Error: %s",
            T.message(errorText)), self.width - PAD * 2, FONT),
            PAD, y, 1.0, 0.45, 0.35, 1.0, FONT)
    end
    y = y + lineHeight

    local warnings = 0
    if PZReplayPlayback then
        warnings = PZReplayPlayback.getCompatibilityWarningCount()
    end
    if warnings > 0 then
        local warning = T.message(PZReplayPlayback.getCompatibilityWarning(0))
        if warnings > 1 then
            warning = warning .. T.format("UI_pzreplay_common_more", " (+%d more)",
                warnings - 1)
        end
        self:drawText(fitText(T.format("UI_pzreplay_common_warning", "Warning: %s",
            warning), self.width - PAD * 2, FONT),
            PAD, y, 1.0, 0.82, 0.4, 1.0, FONT)
    elseif self.rebindNotice and self.rebindNotice ~= "" then
        self:drawText(fitText(self.rebindNotice, self.width - PAD * 2, FONT),
            PAD, y, 0.7, 0.9, 1.0, 1.0, FONT)
    elseif killSwitch then
        self:drawText(T.get("UI_pzreplay_panel_status_open_settings_kill_switch",
            "Open Settings to disable the kill switch."),
            PAD, y, 0.9, 0.9, 0.9, 1.0, FONT)
    end
end

function PZReplayPanel:renderSettings()
    local lineHeight = fontHeight(FONT)
    local labelX = PAD
    local valueX = PAD + 188

    self:drawText(T.get("UI_pzreplay_panel_sample_rate", "Sample rate"), labelX,
        self.rateRowY + 5, 0.9, 0.9, 0.9, 1, FONT)
    self:drawText(self.rateValue or "", valueX, self.rateRowY + 5, 1, 1, 1, 1, FONT)

    self:drawText(T.get("UI_pzreplay_panel_countdown", "Countdown"), labelX,
        self.countdownRowY + 5, 0.9, 0.9, 0.9, 1, FONT)
    self:drawText(self.countdownValue or "", valueX, self.countdownRowY + 5,
        1, 1, 1, 1, FONT)

    self:drawText(T.get("UI_pzreplay_panel_camera_follow_default",
        "Camera follow default"), labelX, self.cameraDefaultRowY + 5,
        0.9, 0.9, 0.9, 1, FONT)
    self:drawText(T.get("UI_pzreplay_panel_debug_controls", "Debug controls"), labelX,
        self.debugRowY + 5, 0.9, 0.9, 0.9, 1, FONT)
    self:drawText(T.get("UI_pzreplay_panel_kill_switch", "Kill switch"), labelX,
        self.killRowY + 5, 0.9, 0.9, 0.9, 1, FONT)

    for _, action in ipairs({ "record", "play", "camera", "panel" }) do
        self:drawText(actionLabel(action), labelX, self.keyRows[action] + 5,
            0.9, 0.9, 0.9, 1, FONT)
    end

    local warnings = PZReplay.getKeyBindingWarningCount()
    local warningY = self.resetRowY + lineHeight + 12
    local shown = math.min(warnings, 3)
    for index = 0, shown - 1 do
        local warning = T.message(PZReplay.getKeyBindingWarning(index))
        self:drawText(fitText(T.format("UI_pzreplay_common_warning", "Warning: %s",
            warning), self.width - PAD * 2, FONT), PAD, warningY + index * lineHeight,
            1.0, 0.82, 0.4, 1.0, FONT)
    end
    if warnings > shown then
        self:drawText(T.format("UI_pzreplay_panel_warning_more", "%d more warnings",
            warnings - shown), PAD, warningY + shown * lineHeight,
            1.0, 0.82, 0.4, 1.0, FONT)
    end
end

function PZReplayPanel:update()
    if self:getIsVisible() then
        self:refreshControls()
    end
end

function PZReplayPanel:new()
    local screenWidth = getCore():getScreenWidth()
    local screenHeight = getCore():getScreenHeight()
    local width = math.min(760, screenWidth - 20)
    local height = math.min(560, screenHeight - 20)
    local x = math.floor((screenWidth - width) / 2)
    local y = math.floor((screenHeight - height) / 2)
    local o = ISPanel:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0.07, g = 0.07, b = 0.08, a = 0.96 }
    o.borderColor = { r = 0.7, g = 0.7, b = 0.7, a = 1.0 }
    o.view = "replays"
    o.rebindNotice = ""
    o:setWantKeyEvents(true)
    return o
end
