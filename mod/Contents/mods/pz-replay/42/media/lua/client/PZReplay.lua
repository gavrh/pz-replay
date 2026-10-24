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
