require "PZReplayHud"
require "PZReplayPanel"
require "PZReplayLibrary"
require "PZReplayOptions"
require "PZReplayText"
require "PZReplayActions"

local T = PZReplayText

local function bridgeVersion()
    if getPZReplayVersion then
        return getPZReplayVersion()
    end
    if PZReplay and PZReplay.version then
        return PZReplay.version()
    end
    return nil
end

local version = bridgeVersion()
if version then
    print("[pz-replay] loaded (java " .. tostring(version) .. ")")
else
    print("[pz-replay] loaded (java bridge pending)")
end

local reported = version ~= nil

local function reportWhenReady()
    if reported then
        return
    end
    local current = bridgeVersion()
    if current then
        reported = true
        print("[pz-replay] Java bridge ready (" .. tostring(current) .. ")")
    end
end

Events.OnGameBoot.Add(reportWhenReady)
Events.OnMainMenuEnter.Add(reportWhenReady)

PZReplayUi = PZReplayUi or {}

local ui = PZReplayUi
local RESULT_VISIBLE_MILLIS = 4000

local function isDebugEnabled()
    return PZReplay
        and PZReplay.isDebugEnabled
        and PZReplay.isDebugEnabled()
end

local function luaKeyInputAvailable()
    if PZReplay and PZReplay.isLuaKeyboardEnabled then
        return PZReplay.isLuaKeyboardEnabled()
    end
    return true
end

function ui.ensure()
    if ui.panel then
        return
    end
    if not PZReplayPanel or not PZReplayHud then
        return
    end
    local panel = PZReplayPanel:new()
    panel:initialise()
    panel:instantiate()
    panel:addToUIManager()
    panel:setVisible(false)
    ui.panel = panel

    local hud = PZReplayHud:new()
    hud:initialise()
    hud:instantiate()
    hud:addToUIManager()
    hud:setVisible(false)
    ui.hud = hud
end

function ui.isPanelOpen()
    return ui.panel ~= nil and ui.panel:getIsVisible()
end

function ui.openPanel(onClosed)
    ui.ensure()
    if not ui.panel then
        return
    end
    if PZReplayPlayback and PZReplayPlayback.isCameraFree
        and PZReplayPlayback.isCameraFree() then
        PZReplayPlayback.exitFreeCamera()
    end
    ui.panel.onPanelClosed = onClosed
    ui.panel:open()
end

function ui.closePanel()
    if not ui.panel then
        return
    end
    ui.panel:close()
end

function ui.togglePanel()
    ui.ensure()
    if not ui.panel then
        return
    end
    if ui.panel:getIsVisible() then
        ui.closePanel()
    else
        ui.openPanel()
    end
end

function ui.refreshPanel()
    if ui.panel and ui.panel:getIsVisible() then
        ui.panel:refreshControls()
    end
end

function ui.updateIndicator()
    if not ui.hud then
        return
    end
    local killSwitch = PZReplay and PZReplay.isKillSwitchEnabled()
    local recording = PZReplay and PZReplay.isRecording()
    local countingDown = PZReplay and PZReplay.isCountingDown()
    local playing = PZReplayPlayback and PZReplayPlayback.isActive()
    local panelOpen = ui.panel ~= nil and ui.panel:getIsVisible()

    local result = PZReplay and PZReplay.getLastRecordingResult
        and PZReplay.getLastRecordingResult() or ""
    local resultVersion = PZReplay and PZReplay.getRecordingResultVersion
        and PZReplay.getRecordingResultVersion() or 0
    if resultVersion ~= (ui.resultVersion or -1) then
        ui.resultVersion = resultVersion
        ui.resultText = result
        ui.resultUntil = result ~= "" and getTimestampMs() + RESULT_VISIBLE_MILLIS or 0
    end
    local showingResult = ui.resultText ~= "" and getTimestampMs() < ui.resultUntil

    local active = not killSwitch and not panelOpen and getPlayer() ~= nil
        and (recording or countingDown or playing or showingResult)
    ui.hud:setVisible(active)
end

local function cancelCountdown()
    if PZReplay and PZReplay.isCountingDown and PZReplay.isCountingDown() then
        PZReplay.cancelCountdown()
    end
end

local function stopCapture()
    if PZReplay and PZReplay.isRecording and PZReplay.isRecording() then
        PZReplay.stopRecording()
    end
end

local function stopCaptureOnDeath()
    if not PZReplay or not PZReplay.stopRecordingOnDeath then
        return
    end
    local countingDown = PZReplay.isCountingDown and PZReplay.isCountingDown()
    if PZReplay.isRecording() or countingDown then
        PZReplay.stopRecordingOnDeath()
    end
end

local function stopPlayback()
    if PZReplayPlayback and PZReplayPlayback.stop then
        PZReplayPlayback.stop()
    end
end

local function despawnGhost()
    if PZReplayGhost and PZReplayGhost.isSpawned and PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    end
end

local function cleanupPlayback()
    stopPlayback()
    despawnGhost()
    ui.refreshPanel()
end

local function cleanupAll()
    cancelCountdown()
    stopCapture()
    cleanupPlayback()
    if ui.panel then
        ui.closePanel()
    end
    if PZReplayLibrary and PZReplayLibrary.isOpen() then
        PZReplayLibrary.instance:close()
    end
    if ui.hud then
        ui.hud:setVisible(false)
    end
end

local function toggleCapture()
    if PZReplay.isCountingDown() then
        PZReplay.cancelCountdown()
        if ui.panel and ui.panel:getIsVisible() then
            ui.panel.browser:setStatus(T.get("UI_pzreplay_status_countdown_cancelled",
                "Countdown cancelled."))
        end
    elseif PZReplay.isRecording() then
        PZReplay.stopRecording()
        if ui.panel and ui.panel:getIsVisible() then
            ui.panel.browser:refresh()
            ui.panel.browser:setStatus(T.message(PZReplay.getLastRecordingResult()))
        end
    else
        local started = PZReplay.startRecording("")
        if ui.panel and ui.panel:getIsVisible() then
            local status
            if started and PZReplay.isCountingDown() then
                status = T.get("UI_pzreplay_status_countdown_started",
                    "Countdown started.")
            elseif started then
                status = T.get("UI_pzreplay_status_recording_started",
                    "Recording started.")
            else
                status = T.message(PZReplay.getCaptureError())
            end
            ui.panel.browser:setStatus(status)
        end
    end
    ui.refreshPanel()
end

local function togglePlayback()
    if PZReplay.isCountingDown() then
        return
    end
    if PZReplayPlayback.isActive() then
        PZReplayPlayback.stop()
        ui.refreshPanel()
        return
    end
    local path = PZReplay.getLastReplayPath()
    if path == "" then
        if ui.panel and ui.panel:getIsVisible() then
            ui.panel.browser:setStatus(T.get("UI_pzreplay_status_record_first",
                "Record a replay first."))
        end
        return
    end
    local started = PZReplayPlayback.play(path)
    if ui.panel and ui.panel:getIsVisible() then
        ui.panel.browser:setStatus(started
            and T.get("UI_pzreplay_status_playback_started", "Playback started.")
            or T.message(PZReplayPlayback.getLastPlaybackError()))
    end
    ui.refreshPanel()
end

local function toggleCameraFollow()
    if PZReplayPlayback.isCameraFree() then
        PZReplayPlayback.exitFreeCamera()
    elseif PZReplayPlayback.isActive() then
        PZReplayPlayback.setCameraFollow(not PZReplayPlayback.isCameraFollow())
    else
        PZReplay.setCameraFollowDefault(not PZReplay.isCameraFollowDefault())
    end
    ui.refreshPanel()
end

local function exitFreeCamera()
    if PZReplayPlayback and PZReplayPlayback.isCameraFree
        and PZReplayPlayback.isCameraFree() then
        PZReplayPlayback.exitFreeCamera()
        ui.refreshPanel()
        return true
    end
    return false
end

function ui.handleKey(key)
    if PZReplayLibrary and PZReplayLibrary.isOpen() then
        return false
    end
    local cameraKey = PZReplay and PZReplay.getKeyBinding("camera")
    if (key == Keyboard.KEY_ESCAPE or (cameraKey and key == cameraKey))
        and exitFreeCamera() then
        return true
    end
    local panelKey = PZReplay and PZReplay.getKeyBinding("panel")
    if ui.panel and ui.panel:getIsVisible() then
        if ui.panel.rebindingAction then
            return false
        end
        if ui.panel.isEditingText and ui.panel:isEditingText() then
            return false
        end
        if panelKey and key == panelKey then
            ui.closePanel()
            return true
        end
        return false
    end
    if not luaKeyInputAvailable() then
        return false
    end

    if panelKey and key == panelKey then
        ui.togglePanel()
        return true
    end
    local recordKey = PZReplay and PZReplay.getKeyBinding("record")
    if recordKey and key == recordKey then
        toggleCapture()
        return true
    end
    local playKey = PZReplay and PZReplay.getKeyBinding("play")
    if playKey and key == playKey then
        togglePlayback()
        return true
    end
    if cameraKey and key == cameraKey then
        toggleCameraFollow()
        return true
    end

    if key == Keyboard.KEY_F8 and isDebugEnabled() then
        toggleCapture()
        return true
    end
    return false
end

Events.OnTick.Add(function()
    if PZReplay and PZReplay.tick then
        PZReplay.tick()
    end
    ui.updateIndicator()
end)

Events.OnTickEvenPaused.Add(function()
    if PZReplay and PZReplay.isCountingDown and PZReplay.isCountingDown()
        and PZReplay.countdownTick then
        PZReplay.countdownTick()
    end
end)

Events.OnKeyStartPressed.Add(function(key)
    ui.handleKey(key)
end)

Events.OnGameStart.Add(function()
    ui.ensure()
    if ui.panel then
        ui.panel:refreshControls()
    end
end)

Events.OnPlayerDeath.Add(stopCaptureOnDeath)
Events.OnPlayerDeath.Add(cleanupPlayback)
Events.OnSave.Add(cancelCountdown)
Events.OnSave.Add(cleanupPlayback)
Events.OnMainMenuEnter.Add(cleanupAll)

PZReplayUi.toggleCapture = toggleCapture
PZReplayUi.togglePlayback = togglePlayback
PZReplayUi.toggleCameraFollow = toggleCameraFollow
PZReplayUi.exitFreeCamera = exitFreeCamera
PZReplayUi.cancelCountdown = cancelCountdown
