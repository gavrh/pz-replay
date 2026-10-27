local function isDebugEnabled()
    return PZReplayGhost
        and PZReplayGhost.isDebugEnabled
        and PZReplayGhost.isDebugEnabled()
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
