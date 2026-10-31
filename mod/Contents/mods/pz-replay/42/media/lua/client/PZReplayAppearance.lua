PZReplayAppearance = PZReplayAppearance or {}

local function onClothingUpdated(player)
    if not player or player ~= getSpecificPlayer(0) then
        return
    end
    if player:isDead() then
        return
    end
    if PZReplay and PZReplay.recordAppearanceChange then
        pcall(PZReplay.recordAppearanceChange)
    end
end

Events.OnClothingUpdated.Add(onClothingUpdated)
