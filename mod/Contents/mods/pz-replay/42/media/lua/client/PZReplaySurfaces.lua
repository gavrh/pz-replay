if PZReplaySurfaces then return end

PZReplaySurfaces = {}

local S = PZReplaySurfaces
local LAYER = {
    hud = 5,
    panel = 10,
    library = 10,
    dialog = 20
}

local function api()
    return PZReplaySurface
end

function S.layer(name)
    return LAYER[name] or 0
end

function S.isOpen(name)
    local bridge = api()
    return bridge ~= nil and bridge.isOpen(name) == true
end

function S.isDialogOpen()
    local bridge = api()
    return bridge ~= nil and bridge.isDialogOpen() == true
end

function S.active()
    local bridge = api()
    return bridge ~= nil and bridge.active() or ""
end

function S.open(name, element)
    local bridge = api()
    if not element or not bridge or not bridge.open(name) then
        return false
    end
    element:setVisible(true)
    element:setCapture(true)
    element:setAlwaysOnTop(S.layer(name) > 0)
    element:setWantKeyEvents(true)
    element:setWantMouseEvents(true)
    element:bringToTop()
    return true
end

function S.close(name, element)
    local bridge = api()
    if bridge then
        bridge.close(name)
    end
    if element then
        element:setCapture(false)
        element:setWantKeyEvents(false)
        element:setWantMouseEvents(false)
        element:setAlwaysOnTop(false)
        element:setVisible(false)
    end
end

function S.openDialog(owner, element)
    local bridge = api()
    if not element or not bridge or not bridge.openDialog(owner) then
        return false
    end
    element:setVisible(true)
    element:setWantMouseEvents(true)
    element:setAlwaysOnTop(S.layer("dialog") > 0)
    element:bringToTop()
    return true
end

function S.closeDialog(element)
    local bridge = api()
    if bridge then
        bridge.closeDialog()
    end
    if element then
        element:setWantMouseEvents(false)
        element:setAlwaysOnTop(false)
        element:setVisible(false)
    end
end

function S.escapeAction()
    local bridge = api()
    return bridge ~= nil and bridge.escapeAction() or "none"
end

function S.allowsGlobalShortcut(textEntryFocused)
    local bridge = api()
    return bridge ~= nil
        and bridge.allowsGlobalShortcut(textEntryFocused == true) == true
end

function S.reset()
    local bridge = api()
    if bridge then
        bridge.reset()
    end
end

function S.configureHud(hud)
    if not hud then
        return
    end
    hud:setWantKeyEvents(false)
    hud:setWantMouseEvents(false)
    hud:setAlwaysOnTop(S.layer("hud") > 0)
end
