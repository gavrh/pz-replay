PZReplayActions = PZReplayActions or {}

local installed = false
local wrapped = {}

local function localPlayer()
    local player = getSpecificPlayer(0)
    if not player or player:isDead() then
        return nil
    end
    return player
end

local function record(kind, detail, duration)
    if not PZReplay or not PZReplay.recordAction then
        return
    end
    local player = localPlayer()
    if not player then
        return
    end
    pcall(PZReplay.recordAction, kind, detail or "",
        player:getDirectionAngle(), duration or 0)
end

local function alreadyWrapped(class, method)
    wrapped[class] = wrapped[class] or {}
    if wrapped[class][method] then
        return true
    end
    wrapped[class][method] = true
    return false
end

local function wrap(class, method, handler)
    if not class or type(class[method]) ~= "function" then
        return
    end
    if alreadyWrapped(class, method) then
        return
    end
    local original = class[method]
    class[method] = function(self, ...)
        local result = original(self, ...)
        pcall(handler, self)
        return result
    end
end

local function ownedByLocalPlayer(action)
    return action and action.character and action.character == getSpecificPlayer(0)
end

local function wrapReload()
    require "TimedActions/ISReloadWeaponAction"
    require "TimedActions/ISRackFirearm"

    wrap(ISReloadWeaponAction, "start", function(self)
        if ownedByLocalPlayer(self) then
            record("reload", "", self.maxTime)
        end
    end)
    wrap(ISRackFirearm, "start", function(self)
        if ownedByLocalPlayer(self) then
            record("reload", "rack", self.maxTime)
        end
    end)
end

local function wrapDoor()
    require "TimedActions/ISOpenCloseDoor"
    wrap(ISOpenCloseDoor, "start", function(self)
        if ownedByLocalPlayer(self) then
            record("door", "", self.maxTime)
        end
    end)
end

local function wrapClimb()
    require "TimedActions/ISClimbOverFence"
    require "TimedActions/ISClimbThroughWindow"
    require "TimedActions/ISClimbSheetRopeAction"

    wrap(ISClimbOverFence, "start", function(self)
        if ownedByLocalPlayer(self) then
            record("climb", "fence", self.maxTime)
        end
    end)
    wrap(ISClimbThroughWindow, "start", function(self)
        if ownedByLocalPlayer(self) then
            record("climb", "window", self.maxTime)
        end
    end)
    wrap(ISClimbSheetRopeAction, "start", function(self)
        if ownedByLocalPlayer(self) then
            record("climb", self.down and "rope_down" or "rope_up", self.maxTime)
        end
    end)
end

local function install()
    if installed then
        return
    end
    installed = true
    pcall(wrapReload)
    pcall(wrapDoor)
    pcall(wrapClimb)
end

local function onWeaponSwing(player, weapon)
    if not player or player ~= getSpecificPlayer(0) then
        return
    end
    if weapon and weapon.isRanged and weapon:isRanged() then
        record("ranged_attack", "", 0)
    else
        record("melee_attack", "", 0)
    end
end

local function onAIStateChange(character, current)
    if not character or character ~= getSpecificPlayer(0) or not current then
        return
    end
    local text = tostring(current)
    if string.find(text, "PlayerFallingState", 1, true) then
        record("fall", "", 1.0)
    end
end

Events.OnWeaponSwing.Add(onWeaponSwing)
Events.OnAIStateChange.Add(onAIStateChange)
Events.OnGameBoot.Add(install)
Events.OnGameStart.Add(install)
