require "PZReplayText"

if PZReplayLoad then return end

local T = PZReplayText

PZReplayLoad = {}

local MENU_GRACE_SECONDS = 15
local HARD_TIMEOUT_SECONDS = 180

local worldStarted = false
local saveListFilterInstalled = false
local vehicleCheckStarted = 0
local vehicleCheckNext = 0
local vehicleCheckError = ""

local function api()
    return PZReplayLoadApi
end

local function installSaveListFilter()
    if saveListFilterInstalled then
        return true
    end
    if type(getFullSaveDirectoryTable) ~= "function" then
        return false
    end
    local original = getFullSaveDirectoryTable
    local ok = pcall(function()
        getFullSaveDirectoryTable = function()
            local listed, folders = pcall(original)
            if not listed or type(folders) ~= "table" then
                return original()
            end
            local filtered = {}
            for _, folder in ipairs(folders) do
                local name = tostring(folder)
                if not name:match("^~pzrp%-ctx%-") then
                    filtered[#filtered + 1] = folder
                end
            end
            return filtered
        end
    end)
    saveListFilterInstalled = ok
    return ok
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
        return false, T.get("UI_pzreplay_load_unavailable",
            "Load and play is unavailable.")
    end
    saves.beginSaves()

    local ok, folders = pcall(getFullSaveDirectoryTable)
    if not ok or type(folders) ~= "table" then
        return false, T.get("UI_pzreplay_load_save_list_unavailable",
            "The game save list is unavailable.")
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

local function restoreNoSave()
    local ok, core = pcall(getCore)
    if ok and core and core.setNoSave then
        pcall(core.setNoSave, core, false)
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
    restoreNoSave()
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
    local context = api().isContextPrepared()
    local expectedSave = context and api().getContextFolder() or api().getSaveName()
    local expectedMode = context and api().getLoadGameMode() or api().getGameMode()
    local expectedMap = api().getMapName()
    local expectedPlayer = api().getPlayerName()
    local world = getWorld()
    if not world then
        return T.get("UI_pzreplay_load_world_not_ready", "The world is not ready.")
    end

    local loadedSave = world:getWorld() or ""
    if expectedSave ~= "" and loadedSave ~= expectedSave then
        return T.get("UI_pzreplay_load_save_mismatch",
            "The loaded save does not match the replay.")
    end
    local loadedModeObject = world:getGameMode()
    local loadedMode = loadedModeObject == nil and "" or tostring(loadedModeObject)
    if expectedMode ~= "" and loadedMode ~= "" and loadedMode ~= expectedMode then
        return T.get("UI_pzreplay_load_mode_mismatch",
            "The loaded game mode does not match the replay.")
    end
    local loadedMap = world:getMap() or ""
    if not mapMatches(expectedMap, loadedMap) then
        return T.get("UI_pzreplay_load_map_mismatch",
            "The loaded map does not match the replay.")
    end
    if context then
        local verification = api().verifyContext()
        if verification ~= "" then
            log("context verification " .. verification)
            if verification:find("world identity mismatch", 1, true)
                or verification:find("map mismatch", 1, true) then
                return verification
            end
        end
    else
        local loadedPlayer = player.getFullName and player:getFullName() or ""
        if expectedPlayer ~= "" and loadedPlayer ~= expectedPlayer then
            return T.get("UI_pzreplay_load_player_mismatch",
                "The loaded player does not match the replay.")
        end
    end
    return nil
end

local function verifyMods()
    if type(getActivatedMods) ~= "function" then
        return ""
    end
    local ok, activated = pcall(getActivatedMods)
    if not ok or type(activated) ~= "table" then
        return ""
    end
    local active = {}
    for _, id in ipairs(activated) do
        active[tostring(id)] = true
    end
    local missing = {}
    for index = 0, api().getExpectedModCount() - 1 do
        local id = api().getExpectedModId(index)
        if id ~= "" and not active[id] then
            missing[#missing + 1] = id
        end
    end
    if #missing > 0 then
        return "mods missing: " .. table.concat(missing, ", ")
    end
    return ""
end

local function startLoadedPlayback(player)
    if not api().isPending() then
        return
    end
    local path = api().getPendingPath()
    local mismatch = worldMismatch(player)
    if mismatch then
        failAfterLoad(player, T.format("UI_pzreplay_load_not_started",
            "Replay not started: %s", mismatch))
        return
    end
    if api().isContextPrepared() then
        local coreOk, core = pcall(getCore)
        if not coreOk or not core or not core.isNoSave or not core:isNoSave() then
            failAfterLoad(player, T.get("UI_pzreplay_load_no_save_failed",
                "Replay world not loaded: no-save could not be verified."))
            return
        end
        local warnings = api().verifyContext()
        local now = getTimestampMs()
        if now >= vehicleCheckNext then
            vehicleCheckError = api().verifyContextVehicles()
            vehicleCheckNext = now + 500
        end
        if vehicleCheckError ~= "" then
            if vehicleCheckStarted == 0 then
                vehicleCheckStarted = now
            end
            if now - vehicleCheckStarted < 10000 then
                return
            end
            failAfterLoad(player, T.format("UI_pzreplay_load_not_started",
                "Replay not started: %s", vehicleCheckError))
            return
        end
        vehicleCheckStarted = 0
        vehicleCheckNext = 0
        local modWarning = verifyMods()
        if modWarning ~= "" then
            warnings = warnings ~= "" and (warnings .. "; " .. modWarning) or modWarning
        end
        api().markContextLoaded()
        api().finishPending()
        if PZReplayPlayback.isActive() then
            PZReplayPlayback.stop()
        end
        local seekSeconds = api().getPendingSeekSeconds()
        local started = PZReplayPlayback.play(path)
        if not started then
            local errorMessage = PZReplayPlayback.getLastPlaybackError()
            if not errorMessage or errorMessage == "" then
                errorMessage = T.get("UI_pzreplay_load_playback_could_not_start",
                    "playback could not start")
            end
            failAfterLoad(player, T.format("UI_pzreplay_load_not_started",
                "Replay not started: %s", T.message(errorMessage)))
            return
        end
        if seekSeconds > 0 then
            if not PZReplayPlayback.seek(seekSeconds) then
                failAfterLoad(player, T.format("UI_pzreplay_load_not_started",
                    "Replay not started: %s",
                    T.get("UI_pzreplay_load_playback_could_not_start",
                        "playback could not start")))
                return
            end
        end
        api().finishReload()
        log("context loaded path=" .. path .. " warnings=" .. tostring(warnings)
            .. " seek=" .. tostring(seekSeconds))
        if warnings ~= "" then
            notify(player, T.format("UI_pzreplay_load_context_warning",
                "Replay world loaded with warnings: %s", warnings))
        else
            notify(player, T.get("UI_pzreplay_load_context_loaded",
                "Replay world loaded."))
        end
        return
    end
    if PZReplayPlayback.isActive() then
        failAfterLoad(player, T.get("UI_pzreplay_load_playback_active",
            "Replay not started: playback is already active."))
        return
    end
    if PZReplayGhost.isSpawned() then
        failAfterLoad(player, T.get("UI_pzreplay_load_ghost_active",
            "Replay not started: a ghost is already spawned."))
        return
    end

    local started = PZReplayPlayback.play(path)
    if not started then
        local errorMessage = PZReplayPlayback.getLastPlaybackError()
        if not errorMessage or errorMessage == "" then
            errorMessage = T.get("UI_pzreplay_load_playback_could_not_start",
                "playback could not start")
        end
        failAfterLoad(player, T.format("UI_pzreplay_load_not_started",
            "Replay not started: %s", T.message(errorMessage)))
        return
    end
    if not PZReplayGhost.isSpawned() then
        failAfterLoad(player, T.get("UI_pzreplay_load_ghost_missing",
            "Replay not started: the replay ghost was not created."))
        return
    end

    api().finishPending()
    local warnings = api().getWarningCount()
    log("playback started path=" .. path .. " warnings=" .. tostring(warnings))
    if warnings > 0 then
        if warnings == 1 then
            notify(player, T.format("UI_pzreplay_load_started_warning_one",
                "Replay started with %d warning.", warnings))
        else
            notify(player, T.format("UI_pzreplay_load_started_warning_many",
                "Replay started with %d warnings.", warnings))
        end
    else
        notify(player, T.get("UI_pzreplay_load_started", "Replay started."))
    end
end

function PZReplayLoad.isPending()
    return api() ~= nil and api().isPending()
end

function PZReplayLoad.cancel(reason)
    if api() and api().cancelPending(reason or "cancelled") then
        restoreNoSave()
        restoreLibrary(T.get("UI_pzreplay_load_cancelled", "Replay load cancelled."))
    end
end

function PZReplayLoad.load(path, library)
    if not path or path == "" then
        return false, T.get("UI_pzreplay_common_select_first", "Select a replay first.")
    end
    if PZReplayLoad.isPending() then
        return false, T.get("UI_pzreplay_load_already_in_progress",
            "A replay load is already in progress.")
    end
    if not api() then
        return false, T.get("UI_pzreplay_load_unavailable",
            "Load and play is unavailable.")
    end
    if PZReplay and PZReplay.isKillSwitchEnabled and PZReplay.isKillSwitchEnabled() then
        return false, T.get("UI_pzreplay_panel_status_disabled",
            "pz-replay is disabled by the kill switch.")
    end
    if not MainScreen or not MainScreen.instance or not MainScreen.continueLatestSave then
        return false, T.get("UI_pzreplay_load_menu_not_ready", "The main menu is not ready.")
    end

    local savesOk, savesError = collectSaves()
    if not api().resolve(path) then
        local message = T.message(api().getErrorMessage())
        return false, message ~= ""
            and message or T.get("UI_pzreplay_load_could_not_load",
                "The replay could not be loaded.")
    end
    if not api().isWorldReplay() and not savesOk then
        return false, savesError
    end
    if api().isWorldReplay() and getPlayer() ~= nil then
        api().cleanupContext("world already loaded")
        return false, T.get("UI_pzreplay_load_world_from_main_menu",
            "World replays must be loaded from the main menu.")
    end
    if api().isWorldReplay() then
        if not installSaveListFilter() then
            log("save-list filter unavailable; reserved context rows stay visible")
        end
        if not api().prepareContext() then
            return false, T.get("UI_pzreplay_load_context_failed",
                "The replay world context could not be prepared.")
        end
        local coreOk, core = pcall(getCore)
        if not coreOk or not core or not core.setNoSave then
            api().cleanupContext("no-save unavailable")
            return false, T.get("UI_pzreplay_load_no_save_failed",
                "Replay world not loaded: no-save could not be verified.")
        end
        core:setNoSave(true)
        if not core:isNoSave() then
            api().cleanupContext("no-save unavailable")
            return false, T.get("UI_pzreplay_load_no_save_failed",
                "Replay world not loaded: no-save could not be verified.")
        end
    end
    if not api().beginPending() then
        api().cleanupContext("load busy")
        restoreNoSave()
        return false, T.get("UI_pzreplay_load_already_in_progress",
            "A replay load is already in progress.")
    end

    if library and library.hideForLoad then
        library:hideForLoad()
    end
    worldStarted = false
    local saveName = api().getLoadSaveName()
    local gameMode = api().getLoadGameMode()
    log("request path=" .. path .. " mode=" .. gameMode .. " save=" .. saveName
        .. " context=" .. tostring(api().isContextPrepared())
        .. " warnings=" .. tostring(api().getWarningCount()))

    local previousCheckSavePlayerExists = checkSavePlayerExists
    local previousCheckSaveFile = MainScreen.checkSaveFile
    if api().isContextPrepared() then
        checkSavePlayerExists = function() return true end
        MainScreen.checkSaveFile = function() return true end
    end
    local ok, errorMessage = pcall(MainScreen.continueLatestSave, gameMode, saveName)
    checkSavePlayerExists = previousCheckSavePlayerExists
    MainScreen.checkSaveFile = previousCheckSaveFile
    if not ok then
        api().cancelPending("load request failed")
        restoreNoSave()
        if library and library.restoreAfterLoadFailure then
            library:restoreAfterLoadFailure(T.format(
                "UI_pzreplay_load_save_could_not_load", "The save could not be loaded: %s",
                tostring(errorMessage)))
        end
        return false, T.get("UI_pzreplay_load_save_failed",
            "The save could not be loaded.")
    end
    return true, T.format("UI_pzreplay_load_loading", "Loading %s...", saveName)
end

function PZReplayLoad.abandon(message)
    if not PZReplayLoad.isPending() then
        return
    end
    if api() and api().failReload then
        api().failReload(message or "load abandoned")
    end
    api().cancelPending("load abandoned")
    restoreNoSave()
    log("fallback menu-restored reason=" .. message)
    restoreLibrary(message)
end

local function reloadInProgress()
    if not api() or not api().hasReloadRequest() then
        return false
    end
    if api().isReloadStarted() then
        return false
    end
    if getPlayer() ~= nil then
        if not api().isReloadExiting() then
            api().markReloadExiting()
            if PZReplayPlayback and PZReplayPlayback.isActive() then
                PZReplayPlayback.stop()
            end
            local ok, errorMessage = pcall(function()
                getCore():exitToMenu()
            end)
            if not ok then
                api().failReload(tostring(errorMessage))
                log("world reload exit failed reason=" .. tostring(errorMessage))
            else
                log("world reload leaving world path=" .. api().getReloadPath()
                    .. " target=" .. tostring(api().getReloadTargetSeconds()))
            end
        end
        return true
    end
    local screen = MainScreen and MainScreen.instance
    if not screen or not screen.getIsVisible or not screen:getIsVisible() then
        return true
    end
    if getPlayer() ~= nil then
        return true
    end
    api().markReloadStarted()
    local path = api().getReloadPath()
    local library = PZReplayLibrary and PZReplayLibrary.instance or nil
    local ok, message = PZReplayLoad.load(path, library)
    if not ok then
        api().failReload(message or "reload could not start")
        log("world reload could not start path=" .. path
            .. " reason=" .. tostring(message))
    else
        log("world reload loading path=" .. path
            .. " target=" .. tostring(api().getReloadTargetSeconds()))
    end
    return true
end

function PZReplayLoad.tick()
    if reloadInProgress() then
        return
    end
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
    local failedMessage = T.get("UI_pzreplay_load_did_not_load",
        "The save did not load. Replay playback was not started.")
    if screen and screen.getIsVisible and screen:getIsVisible()
        and elapsed > MENU_GRACE_SECONDS then
        PZReplayLoad.abandon(failedMessage)
    elseif elapsed > HARD_TIMEOUT_SECONDS then
        PZReplayLoad.abandon(failedMessage)
    end
end

local function cancelAtMenu()
    worldStarted = false
    installSaveListFilter()
    if api() and api().installLifecycle then
        pcall(api().installLifecycle)
    end
    if api() and api().isContextPrepared() then
        api().cleanupContext("main menu entered")
    end
    restoreNoSave()
    if not PZReplayLoad.isPending() then
        return
    end
    api().cancelPending("main menu entered")
    local library = PZReplayLibrary and PZReplayLibrary.instance
    if library and library:isHiddenForLoad() then
        library:closeCancelledLoad()
    end
end

Events.OnGameBoot.Add(function()
    if api() and api().installLifecycle then
        pcall(api().installLifecycle)
    end
    installSaveListFilter()
end)

Events.OnGameStart.Add(function()
    worldStarted = true
    PZReplayLoad.tick()
end)

Events.OnPostDistributionMerge.Add(function()
    if api() and api().enterVehicleWindow then
        api().enterVehicleWindow()
    end
end)

Events.OnInitWorld.Add(function()
    if api() and api().exitVehicleWindow then
        api().exitVehicleWindow()
    end
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
