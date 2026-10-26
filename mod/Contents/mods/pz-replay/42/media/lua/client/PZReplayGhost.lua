local function isDebugEnabled()
    return PZReplayGhost
        and PZReplayGhost.isDebugEnabled
        and PZReplayGhost.isDebugEnabled()
end

local function despawn()
    if PZReplayGhost and PZReplayGhost.isSpawned and PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    end
end

local function stopPlayback()
    if PZReplayPlayback and PZReplayPlayback.stop then
        PZReplayPlayback.stop()
    end
end

local function cleanup()
    stopPlayback()
    despawn()
end

local function toggleDebugGhost()
    if not isDebugEnabled() then
        return
    end
    if PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    else
        PZReplayGhost.spawn()
    end
end

local function startMotion()
    if isDebugEnabled() and PZReplayGhost.isSpawned() then
        PZReplayGhost.startMotion()
    end
end

local function pauseMotion()
    if isDebugEnabled() and PZReplayGhost.isSpawned() then
        PZReplayGhost.pauseMotion()
    end
end

local function stopMotion()
    if isDebugEnabled() and PZReplayGhost.isSpawned() then
        PZReplayGhost.stopMotion()
    end
end

Events.OnKeyPressed.Add(function(key)
    if key == Keyboard.KEY_F9 then
        toggleDebugGhost()
    elseif key == Keyboard.KEY_F10 then
        startMotion()
    elseif key == Keyboard.KEY_F11 then
        pauseMotion()
    elseif key == Keyboard.KEY_F12 then
        stopMotion()
    end
end)

Events.OnTick.Add(function()
    if PZReplayPlayback and PZReplayPlayback.tick then
        PZReplayPlayback.tick()
    end
end)

Events.OnSave.Add(cleanup)
Events.OnPlayerDeath.Add(cleanup)
Events.OnMainMenuEnter.Add(cleanup)
