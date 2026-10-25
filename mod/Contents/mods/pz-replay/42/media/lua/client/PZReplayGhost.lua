local function despawn()
    if PZReplayGhost and PZReplayGhost.isSpawned and PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    end
end

Events.OnSave.Add(despawn)
Events.OnPlayerDeath.Add(despawn)
Events.OnMainMenuEnter.Add(despawn)
