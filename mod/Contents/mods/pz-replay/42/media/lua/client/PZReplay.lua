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

local function isDebugEnabled()
    return PZReplay
        and PZReplay.isDebugEnabled
        and PZReplay.isDebugEnabled()
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

local function toggleCapture()
    if not isDebugEnabled() then
        return
    end
    if PZReplay.isRecording() then
        PZReplay.stopRecording()
    else
        PZReplay.startRecording("capture")
    end
end

Events.OnTick.Add(function()
    if PZReplay and PZReplay.recordTick then
        PZReplay.recordTick()
    end
end)

Events.OnKeyPressed.Add(function(key)
    if key == Keyboard.KEY_F8 then
        toggleCapture()
    end
end)

Events.OnPlayerDeath.Add(stopCaptureOnDeath)
Events.OnMainMenuEnter.Add(stopCapture)
