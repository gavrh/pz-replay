require "PZReplayHud"
require "PZReplayPanel"

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

function ui.openPanel()
    ui.ensure()
    if not ui.panel then
        return
    end
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
    local playing = PZReplayPlayback and PZReplayPlayback.isPlaying()
    local panelOpen = ui.panel ~= nil and ui.panel:getIsVisible()
    local active = not killSwitch and not panelOpen and getPlayer() ~= nil
        and (recording or playing)
    ui.hud:setVisible(active)
end

local function stopCapture()
    if PZReplay and PZReplay.isRecording and PZReplay.isRecording() then
        PZReplay.stopRecording()
    end
end

local function stopCaptureOnDeath()
    if PZReplay and PZReplay.isRecording and PZReplay.isRecording() then
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
    stopCapture()
    cleanupPlayback()
    if ui.panel then
        ui.closePanel()
    end
    if ui.hud then
        ui.hud:setVisible(false)
    end
end

local function toggleCapture()
    if PZReplay.isRecording() then
        PZReplay.stopRecording()
        if ui.panel and ui.panel:getIsVisible() then
            ui.panel.browser:refresh()
            ui.panel.browser:setStatus("Recording stopped and saved.")
        end
    else
        local started = PZReplay.startRecording("")
        if ui.panel and ui.panel:getIsVisible() then
            ui.panel.browser:setStatus(started and "Recording started."
                or PZReplay.getCaptureError())
        end
    end
    ui.refreshPanel()
end

local function togglePlayback()
    if PZReplayPlayback.isPlaying() then
        PZReplayPlayback.stop()
        ui.refreshPanel()
        return
    end
    local path = PZReplay.getLastReplayPath()
    if path == "" then
        if ui.panel and ui.panel:getIsVisible() then
            ui.panel.browser:setStatus("Record a replay first.")
        end
        return
    end
    local started = PZReplayPlayback.play(path)
    if ui.panel and ui.panel:getIsVisible() then
        ui.panel.browser:setStatus(started and "Playback started."
            or PZReplayPlayback.getLastPlaybackError())
    end
    ui.refreshPanel()
end

local function toggleCameraFollow()
    if PZReplayPlayback.isPlaying() then
        PZReplayPlayback.setCameraFollow(not PZReplayPlayback.isCameraFollow())
    else
        PZReplay.setCameraFollowDefault(not PZReplay.isCameraFollowDefault())
    end
    ui.refreshPanel()
end

function ui.handleKey(key)
    local panelKey = PZReplay and PZReplay.getKeyBinding("panel")
    if ui.panel and ui.panel:getIsVisible() then
        if ui.panel.rebindingAction then
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
    local cameraKey = PZReplay and PZReplay.getKeyBinding("camera")
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
Events.OnSave.Add(cleanupPlayback)
Events.OnMainMenuEnter.Add(cleanupAll)

PZReplayUi.toggleCapture = toggleCapture
PZReplayUi.togglePlayback = togglePlayback
PZReplayUi.toggleCameraFollow = toggleCameraFollow
