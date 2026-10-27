require "ISUI/ISPanel"
require "ISUI/ISButton"
require "PZReplayBrowser"

if PZReplayPanel then return end

PZReplayPanel = ISPanel:derive("PZReplayPanel")

local FONT = UIFont.Small
local PAD = 10
local TITLE_HEIGHT = 28
local STATUS_LINES = 5
local TAB_HEIGHT = 26

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

local function fileName(path)
    if not path or path == "" then return "" end
    return path:match("([^/\\]+)$") or path
end

local function actionLabel(action)
    if action == "record" then return "Record / stop" end
    if action == "play" then return "Play / stop" end
    if action == "camera" then return "Camera follow" end
    return "Open / close panel"
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
        "Close", self, PZReplayPanel.onClose)
    self.closeButton:initialise()
    self.closeButton:instantiate()
    self:addChild(self.closeButton)

    self.replayTab = ISButton:new(PAD, self.titleHeight + self.statusHeight + 2,
        86, buttonHeight, "Replays", self, PZReplayPanel.onReplayTab)
    self.replayTab:initialise()
    self.replayTab:instantiate()
    self:addChild(self.replayTab)

    self.settingsTab = ISButton:new(PAD + 92, self.titleHeight + self.statusHeight + 2,
        86, buttonHeight, "Settings", self, PZReplayPanel.onSettingsTab)
    self.settingsTab:initialise()
    self.settingsTab:instantiate()
    self:addChild(self.settingsTab)

    self.captureButton = ISButton:new(self.width - PAD - 126,
        self.titleHeight + self.statusHeight + 2, 126, buttonHeight,
        "Record", self, PZReplayPanel.onCapture)
    self.captureButton:initialise()
    self.captureButton:instantiate()
    self:addChild(self.captureButton)

    self.cameraButton = ISButton:new(self.captureButton.x - 6 - 120,
        self.titleHeight + self.statusHeight + 2, 120, buttonHeight,
        "Camera: Off", self, PZReplayPanel.onCamera)
    self.cameraButton:initialise()
    self.cameraButton:instantiate()
    self:addChild(self.cameraButton)

    self.browser = PZReplayBrowser:new(self.contentX, self.contentY,
        self.contentWidth, self.contentHeight, {
            allowPlayback = true,
            isPlaying = function()
                return PZReplayPlayback and PZReplayPlayback.isPlaying()
            end,
            canPlay = function()
                return not PZReplay.isKillSwitchEnabled() and not PZReplay.isRecording()
            end,
            onPlay = function(path)
                local started = PZReplayPlayback and PZReplayPlayback.play(path)
                if started then
                    return true, "Playing " .. fileName(path)
                end
                local error = PZReplayPlayback and PZReplayPlayback.getLastPlaybackError()
                return false, error ~= "" and error or "Playback failed."
            end,
            onStop = function()
                local stopped = PZReplayPlayback and PZReplayPlayback.stop()
                return stopped, stopped and "Playback stopped." or "Playback is not active."
            end
        })
    self.browser:initialise()
    self.browser:instantiate()
    self:addChild(self.browser)

    self:createSettings(buttonHeight)
    self:setView("replays")
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
    self.cameraDefaultRowY = y
    self.cameraDefaultButton = self:addSettingsButton("Off", controlX, y, 80, buttonHeight,
        PZReplayPanel.onToggleCameraDefault)

    y = y + rowHeight
    self.debugRowY = y
    self.debugButton = self:addSettingsButton("Off", controlX, y, 80, buttonHeight,
        PZReplayPanel.onToggleDebug)

    y = y + rowHeight
    self.killRowY = y
    self.killButton = self:addSettingsButton("Off", controlX, y, 80, buttonHeight,
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
    self.resetKeysButton = self:addSettingsButton("Reset keys", PAD, y, 120,
        buttonHeight, PZReplayPanel.onResetKeys)
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
    self.replayTab:setEnable(not replayView)
    self.settingsTab:setEnable(replayView)
    self.rateMinus:setVisible(not replayView)
    self.ratePlus:setVisible(not replayView)
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
    local playing = PZReplayPlayback and PZReplayPlayback.isPlaying()
    local samples = string.format("%.0f Hz", PZReplay.getSampleRate())

    self.rateValue = samples
    self.cameraDefaultButton:setTitle(PZReplay.isCameraFollowDefault() and "On" or "Off")
    self.debugButton:setTitle(PZReplay.isDebugControlsEnabled() and "On" or "Off")
    self.killButton:setTitle(killSwitch and "On" or "Off")
    self.captureButton:setTitle(recording and "Stop recording" or "Record")
    self.captureButton:setEnable(recording or (not killSwitch and not playing))
    local cameraOn = playing and PZReplayPlayback.isCameraFollow()
        or (not playing and PZReplay.isCameraFollowDefault())
    self.cameraButton:setTitle(cameraOn and "Camera: On" or "Camera: Off")
    self.cameraButton:setEnable(not killSwitch)
    self.rateMinus:setEnable(not killSwitch and PZReplay.getSampleRate() > 20)
    self.ratePlus:setEnable(not killSwitch and PZReplay.getSampleRate() < 30)
    self.cameraDefaultButton:setEnable(not killSwitch)
    self.debugButton:setEnable(not killSwitch)
    self.killButton:setEnable(true)
    for action, button in pairs(self.keyButtons) do
        if self.rebindingAction == action then
            button:setTitle("Press a key...")
        else
            button:setTitle(PZReplay.getKeyBindingName(action))
        end
        button:setEnable(self.rebindingAction == nil)
    end
    self.resetKeysButton:setEnable(self.rebindingAction == nil)
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

function PZReplayPanel:onCamera()
    if PZReplayPlayback.isPlaying() then
        PZReplayPlayback.setCameraFollow(not PZReplayPlayback.isCameraFollow())
    else
        PZReplay.setCameraFollowDefault(not PZReplay.isCameraFollowDefault())
    end
    self:refreshControls()
end

function PZReplayPanel:onCapture()
    if PZReplay.isRecording() then
        PZReplay.stopRecording()
        self.browser:refresh()
        self.browser:setStatus("Recording stopped and saved.")
    else
        local started = PZReplay.startRecording("")
        if started then
            self.browser:setStatus("Recording started.")
        else
            self.browser:setStatus(PZReplay.getCaptureError())
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
    self.rebindNotice = "Key bindings reset."
    self:refreshControls()
end

function PZReplayPanel:finishRebind(key)
    local action = self.rebindingAction
    self.rebindingAction = nil
    if key == Keyboard.KEY_ESCAPE then
        self.rebindNotice = "Key binding unchanged."
        self:refreshControls()
        return
    end
    if key <= 0 or PZReplayPanel.isModifierKey(key) then
        self.rebindNotice = "That key cannot be used."
        self:refreshControls()
        return
    end
    if not PZReplay.setKeyBinding(action, key) then
        self.rebindNotice = "That key is already used by another pz-replay action."
        self:refreshControls()
        return
    end
    local warning = PZReplay.checkKeyBinding(action, key)
    if warning ~= "" then
        self.rebindNotice = "Bound with warning: " .. warning
    else
        self.rebindNotice = "Key binding updated."
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
        self:close()
        return true
    end
    return false
end

function PZReplayPanel:onKeyPress(key)
    if not self:getIsVisible() then
        return false
    end
    if not self.rebindingAction and self.view == "replays" and self.browser then
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
    self:drawText("Replay", PAD, 7, 1, 1, 1, 1, UIFont.Medium)
end

function PZReplayPanel:render()
    self:renderStatus()
    if self.view == "settings" then
        self:renderSettings()
    end
end

function PZReplayPanel:renderStatus()
    local lineHeight = fontHeight(FONT)
    local y = self.titleHeight + 4
    local killSwitch = PZReplay.isKillSwitchEnabled()
    local recording = PZReplay.isRecording()
    local playing = PZReplayPlayback and PZReplayPlayback.isPlaying()

    local captureText
    if killSwitch then
        captureText = "pz-replay is disabled by the kill switch."
    elseif recording then
        captureText = string.format("Recording  %s  %d frames  %.0f Hz configured  %.1f Hz actual",
            formatTime(PZReplay.getRecordingSeconds()), PZReplay.getRecordedFrames(),
            PZReplay.getConfiguredSampleRate(), PZReplay.getActualSampleRate())
    else
        captureText = string.format("Capture idle  %.0f Hz configured",
            PZReplay.getSampleRate())
    end
    self:drawText(fitText(captureText, self.width - PAD * 2, FONT), PAD, y,
        recording and 1.0 or 0.86, recording and 0.55 or 0.86,
        recording and 0.45 or 0.86, 1.0, FONT)
    y = y + lineHeight

    local playbackText
    if playing then
        local camera = PZReplayPlayback.isCameraFollow() and "  camera follow on" or ""
        playbackText = string.format("Playing  %s / %s%s",
            formatTime(PZReplayPlayback.getCurrentTime()),
            formatTime(PZReplayPlayback.getDuration()), camera)
    else
        playbackText = "Playback idle"
    end
    self:drawText(fitText(playbackText, self.width - PAD * 2, FONT), PAD, y,
        playing and 0.55 or 0.86, playing and 0.9 or 0.86, playing and 0.55 or 0.86,
        1.0, FONT)
    y = y + lineHeight

    local lastPath = PZReplay.getLastReplayPath()
    local lastText = lastPath ~= "" and ("Last replay  " .. fileName(lastPath))
        or "Last replay  none"
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
        self:drawText(fitText("Error: " .. errorText, self.width - PAD * 2, FONT),
            PAD, y, 1.0, 0.45, 0.35, 1.0, FONT)
    end
    y = y + lineHeight

    local warnings = 0
    if PZReplayPlayback then
        warnings = PZReplayPlayback.getCompatibilityWarningCount()
    end
    if warnings > 0 then
        local warning = PZReplayPlayback.getCompatibilityWarning(0)
        if warnings > 1 then
            warning = warning .. " (+" .. (warnings - 1) .. " more)"
        end
        self:drawText(fitText("Warning: " .. warning, self.width - PAD * 2, FONT),
            PAD, y, 1.0, 0.82, 0.4, 1.0, FONT)
    elseif self.rebindNotice and self.rebindNotice ~= "" then
        self:drawText(fitText(self.rebindNotice, self.width - PAD * 2, FONT),
            PAD, y, 0.7, 0.9, 1.0, 1.0, FONT)
    elseif killSwitch then
        self:drawText("Open Settings to disable the kill switch.",
            PAD, y, 0.9, 0.9, 0.9, 1.0, FONT)
    end
end

function PZReplayPanel:renderSettings()
    local lineHeight = fontHeight(FONT)
    local labelX = PAD
    local valueX = PAD + 188

    self:drawText("Sample rate", labelX, self.rateRowY + 5, 0.9, 0.9, 0.9, 1, FONT)
    self:drawText(self.rateValue or "", valueX, self.rateRowY + 5, 1, 1, 1, 1, FONT)

    self:drawText("Camera follow default", labelX, self.cameraDefaultRowY + 5,
        0.9, 0.9, 0.9, 1, FONT)
    self:drawText("Debug controls", labelX, self.debugRowY + 5,
        0.9, 0.9, 0.9, 1, FONT)
    self:drawText("Kill switch", labelX, self.killRowY + 5,
        0.9, 0.9, 0.9, 1, FONT)

    for _, action in ipairs({ "record", "play", "camera", "panel" }) do
        self:drawText(actionLabel(action), labelX, self.keyRows[action] + 5,
            0.9, 0.9, 0.9, 1, FONT)
    end

    local warnings = PZReplay.getKeyBindingWarningCount()
    local warningY = self.resetRowY + lineHeight + 12
    for index = 0, math.min(warnings, 2) - 1 do
        self:drawText(fitText("Warning: " .. PZReplay.getKeyBindingWarning(index),
            self.width - PAD * 2, FONT), PAD, warningY + index * lineHeight,
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
