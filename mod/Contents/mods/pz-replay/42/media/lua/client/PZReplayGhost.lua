local function despawn()
    if PZReplayGhost and PZReplayGhost.isSpawned and PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    end
end

local function toggleDebugGhost()
    if not PZReplayGhost
        or not PZReplayGhost.isDebugEnabled
        or not PZReplayGhost.isDebugEnabled() then
        return
    end
    if PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    else
        PZReplayGhost.spawn()
    end
end

Events.OnKeyPressed.Add(function(key)
    if key == Keyboard.KEY_F9 then
        toggleDebugGhost()
    end
end)

Events.OnSave.Add(despawn)
Events.OnPlayerDeath.Add(despawn)
Events.OnMainMenuEnter.Add(despawn)
