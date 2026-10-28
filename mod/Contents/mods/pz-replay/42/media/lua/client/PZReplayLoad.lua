if PZReplayLoad then return end

PZReplayLoad = {}

local MENU_GRACE_SECONDS = 15
local HARD_TIMEOUT_SECONDS = 180

local worldStarted = false

local function api()
    return PZReplayLoadApi
end

local function log(message)
    print("[pz-replay] load-play " .. message)
end

local function notify(player, message)
    if player and HaloTextHelper and HaloTextHelper.addText then
        pcall(HaloTextHelper.addText, player, message)
    end
end

local function collectSaves()
    local saves = api()
    if not saves then
        return false, "Load and play is unavailable."
    end
    saves.beginSaves()

    local ok, folders = pcall(getFullSaveDirectoryTable)
    if not ok or type(folders) ~= "table" then
        return false, "The game save list is unavailable."
    end

    for _, folder in ipairs(folders) do
        local infoOk, info = pcall(getSaveInfo, folder)
        if infoOk and info then
            local names = {}
            local deadNames = {}
            for _, player in ipairs(info.players or {}) do
                local name = player.name
                if name and name ~= "" then
                    names[#names + 1] = name
                    if player.isDead then
                        deadNames[#deadNames + 1] = name
                    end
                end
            end
            saves.addSave(folder, info.saveName or "", info.gameMode or "",
                info.mapName or "", table.concat(names, "\n"),
                table.concat(deadNames, "\n"))
        end
    end
    return true
end

local function restoreLibrary(message)
    local library = PZReplayLibrary and PZReplayLibrary.instance
    if library and library:isHiddenForLoad() then
        library:restoreAfterLoadFailure(message)
    end
end

local function failAfterLoad(player, message)
    api().cancelPending(message)
    if PZReplayPlayback and PZReplayPlayback.isActive() then
        PZReplayPlayback.stop()
    end
    if PZReplayGhost and PZReplayGhost.isSpawned() then
        PZReplayGhost.despawn()
    end
    log("world validation failed message=" .. message)
    notify(player, message)
end

local function mapMatches(expected, loaded)
    if expected == "" then
        return true
    end
    for part in string.gmatch(expected, "[^;]+") do
        if part:match("^%s*(.-)%s*$") == loaded then
            return true
        end
    end
    return false
end

local function worldMismatch(player)
    local expectedSave = api().getSaveName()
    local expectedMode = api().getGameMode()
    local expectedMap = api().getMapName()
    local expectedPlayer = api().getPlayerName()
    local world = getWorld()
    if not world then
        return "The world is not ready."
    end

    local loadedSave = world:getWorld() or ""
    if expectedSave ~= "" and loadedSave ~= expectedSave then
        return "The loaded save does not match the replay."
    end
    local loadedMode = world:getGameMode() or ""
    if expectedMode ~= "" and loadedMode ~= "" and loadedMode ~= expectedMode then
        return "The loaded game mode does not match the replay."
    end
    local loadedMap = world:getMap() or ""
    if not mapMatches(expectedMap, loadedMap) then
        return "The loaded map does not match the replay."
    end
    local loadedPlayer = player.getFullName and player:getFullName() or ""
    if expectedPlayer ~= "" and loadedPlayer ~= expectedPlayer then
        return "The loaded player does not match the replay."
    end
    return nil
end

local function startLoadedPlayback(player)
    if not api().isPending() then
        return
    end
    local path = api().getPendingPath()
    local mismatch = worldMismatch(player)
    if mismatch then
        failAfterLoad(player, "Replay not started: " .. mismatch)
        return
    end
    if PZReplayPlayback.isActive() then
        failAfterLoad(player, "Replay not started: playback is already active.")
        return
    end
    if PZReplayGhost.isSpawned() then
        failAfterLoad(player, "Replay not started: a ghost is already spawned.")
        return
    end

    local started = PZReplayPlayback.play(path)
    if not started then
        local errorMessage = PZReplayPlayback.getLastPlaybackError()
        if not errorMessage or errorMessage == "" then
            errorMessage = "playback could not start"
        end
        failAfterLoad(player, "Replay not started: " .. errorMessage)
        return
    end
    if not PZReplayGhost.isSpawned() then
        failAfterLoad(player, "Replay not started: the replay ghost was not created.")
        return
    end

    api().finishPending()
    local warnings = api().getWarningCount()
    log("playback started path=" .. path .. " warnings=" .. tostring(warnings))
    if warnings > 0 then
        notify(player, "Replay started with " .. tostring(warnings) .. " warning"
            .. (warnings == 1 and "." or "s."))
    else
        notify(player, "Replay started.")
    end
end

function PZReplayLoad.isPending()
    return api() ~= nil and api().isPending()
end

function PZReplayLoad.cancel(reason)
    if api() and api().cancelPending(reason or "cancelled") then
        restoreLibrary("Replay load cancelled.")
    end
end

function PZReplayLoad.load(path, library)
    if not path or path == "" then
        return false, "Select a replay first."
    end
    if PZReplayLoad.isPending() then
        return false, "A replay load is already in progress."
    end
    if not api() then
        return false, "Load and play is unavailable."
    end
    if PZReplay and PZReplay.isKillSwitchEnabled and PZReplay.isKillSwitchEnabled() then
        return false, "pz-replay is disabled by the kill switch."
    end
    if not MainScreen or not MainScreen.instance or not MainScreen.continueLatestSave then
        return false, "The main menu is not ready."
    end

    local savesOk, savesError = collectSaves()
    if not savesOk then
        return false, savesError
    end
    if not api().resolve(path) then
        local message = api().getErrorMessage()
        return false, message ~= "" and message or "The replay could not be loaded."
    end
    if not api().beginPending() then
        return false, "A replay load is already in progress."
    end

    library:hideForLoad()
    worldStarted = false
    local saveName = api().getSaveName()
    local gameMode = api().getGameMode()
    log("request path=" .. path .. " mode=" .. gameMode .. " save=" .. saveName
        .. " warnings=" .. tostring(api().getWarningCount()))

    local ok, errorMessage = pcall(MainScreen.continueLatestSave, gameMode, saveName)
    if not ok then
        api().cancelPending("load request failed")
        library:restoreAfterLoadFailure("The save could not be loaded: "
            .. tostring(errorMessage))
        return false, "The save could not be loaded."
    end
    return true, "Loading " .. saveName .. "..."
end

function PZReplayLoad.abandon(message)
    if not PZReplayLoad.isPending() then
        return
    end
    api().cancelPending("load abandoned")
    log("fallback menu-restored reason=" .. message)
    restoreLibrary(message)
end

function PZReplayLoad.tick()
    if not PZReplayLoad.isPending() then
        return
    end
    local player = getPlayer()
    if worldStarted and player ~= nil and getWorld() ~= nil then
        startLoadedPlayback(player)
        return
    end

    local screen = MainScreen and MainScreen.instance
    local modal = screen and screen.checkSavefileModal
    if modal and modal.getIsVisible and modal:getIsVisible() then
        return
    end
    local elapsed = api().getPendingSeconds()
    if screen and screen.getIsVisible and screen:getIsVisible()
        and elapsed > MENU_GRACE_SECONDS then
        PZReplayLoad.abandon("The save did not load. Replay playback was not started.")
    elseif elapsed > HARD_TIMEOUT_SECONDS then
        PZReplayLoad.abandon("The save did not load. Replay playback was not started.")
    end
end

local function cancelAtMenu()
    worldStarted = false
    if not PZReplayLoad.isPending() then
        return
    end
    api().cancelPending("main menu entered")
    local library = PZReplayLibrary and PZReplayLibrary.instance
    if library and library:isHiddenForLoad() then
        library:closeCancelledLoad()
    end
end

Events.OnGameStart.Add(function()
    worldStarted = true
    PZReplayLoad.tick()
end)

Events.OnRenderTick.Add(function()
    PZReplayLoad.tick()
end)

Events.OnMainMenuEnter.Add(cancelAtMenu)

Events.OnSave.Add(function()
    if PZReplayLoad.isPending() then
        api().cancelPending("save started")
    end
end)

Events.OnPlayerDeath.Add(function()
    if PZReplayLoad.isPending() then
        api().cancelPending("player died")
    end
end)
