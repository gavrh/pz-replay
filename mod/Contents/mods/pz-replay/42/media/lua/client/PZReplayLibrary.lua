require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISLabel"
require "PZReplayBrowser"
require "PZReplayLoad"

if PZReplayLibrary then return end

PZReplayLibrary = ISPanel:derive("PZReplayLibrary")

local FONT = UIFont.Small
local PAD = 10
local TITLE_HEIGHT = 28
local ENTRY_ID = "PZREPLAY_LIBRARY"

local function fontHeight(font)
    return getTextManager():getFontHeight(font)
end

local function fitText(text, width, font)
    if not text then return "" end
    local value = tostring(text)
    if getTextManager():MeasureStringX(font, value) <= width then
        return value
    end
    while #value > 1 and getTextManager():MeasureStringX(font, value .. "...") > width do
        value = value:sub(1, #value - 1)
    end
    return value .. "..."
end

local function onEntryMouseDown(item, x, y)
    local screen = MainScreen and MainScreen.instance
    if not screen or (screen.delay and screen.delay > 0) then
        return
    end
    getSoundManager():playUISound("UIActivateMainMenuItem")
    PZReplayLibrary.openFromMenu()
end

local function attachEntry()
    local screen = MainScreen and MainScreen.instance
    if not screen or not screen.bottomPanel then
        return
    end
    if screen.pzReplayLibraryEntry then
        return
    end
    local panel = screen.bottomPanel
    for _, child in pairs(panel:getChildren()) do
        if child.internal == ENTRY_ID then
            screen.pzReplayLibraryEntry = child
            return
        end
    end

    local anchor = screen.inGame and screen.returnOption
        or screen.onlineCoopOption or screen.survivalOption
    if not anchor then
        return
    end

    local labelHeight = getTextManager():getFontHeight(UIFont.Large) + 8 * 2
    local insertionY = anchor:getBottom()
    local label = ISLabel:new(0, insertionY, labelHeight, "REPLAYS",
        1, 1, 1, 1, UIFont.Large, true)
    label.internal = ENTRY_ID
    label:initialise()
    label.onMouseDown = onEntryMouseDown
    label:setWidth(math.max(panel:getWidth(), 1))
    panel:addChild(label)
    label:setVisible(true)
    label.fade = UITransition.new()
    label.fade:setFadeIn(false)
    label.prerender = MainScreen.prerenderBottomPanelLabel

    for _, child in pairs(panel:getChildren()) do
        if child ~= label and child.Type == "ISLabel" and child:getY() >= insertionY then
            child:setY(child:getY() + labelHeight)
        end
    end
    panel:setHeight(panel:getHeight() + labelHeight)
    screen.maxMenuItemWidth = math.max(screen.maxMenuItemWidth or 0,
        getTextManager():MeasureStringX(UIFont.Large, "REPLAYS"))
    screen.pzReplayLibraryEntry = label
end

function PZReplayLibrary.ensure()
    if PZReplayLibrary.instance then
        return PZReplayLibrary.instance
    end
    local panel = PZReplayLibrary:new()
    panel:initialise()
    panel:instantiate()
    panel:addToUIManager()
    panel:setVisible(false)
    PZReplayLibrary.instance = panel
    return panel
end

function PZReplayLibrary.isOpen()
    return PZReplayLibrary.instance ~= nil and PZReplayLibrary.instance:getIsVisible()
end

function PZReplayLibrary.openFromMenu()
    local screen = MainScreen and MainScreen.instance
    if not screen or not screen.bottomPanel then
        return false
    end
    if screen.inGame then
        if not screen:getIsVisible() then
            return false
        end
        screen.bottomPanel:setVisible(false)
        PZReplayUi.ensure()
        if PZReplayUi.panel then
            PZReplayUi.panel:setView("replays")
        end
        PZReplayUi.openPanel(function()
            screen.bottomPanel:setVisible(true)
        end)
        return true
    end

    screen.bottomPanel:setVisible(false)
    local library = PZReplayLibrary.ensure()
    library.onClosed = function()
        screen.bottomPanel:setVisible(true)
    end
    library:open()
    return true
end

function PZReplayLibrary:createChildren()
    local screenWidth = getCore():getScreenWidth()
    local screenHeight = getCore():getScreenHeight()
    self.width = math.min(900, screenWidth - 40)
    self.height = math.min(620, screenHeight - 40)
    self.x = math.floor((screenWidth - self.width) / 2)
    self.y = math.floor((screenHeight - self.height) / 2)
    self:setX(self.x)
    self:setY(self.y)
    self:setWidth(self.width)
    self:setHeight(self.height)

    self.closeButton = ISButton:new(self.width - PAD - 54, 4, 50, TITLE_HEIGHT - 8,
        "Close", self, PZReplayLibrary.onClose)
    self.closeButton:initialise()
    self.closeButton:instantiate()
    self:addChild(self.closeButton)

    local playWidth = getTextManager():MeasureStringX(FONT, "Play") + 24
    self.playButton = ISButton:new(self.closeButton:getX() - 6 - playWidth, 4, playWidth,
        TITLE_HEIGHT - 8, "Play", self, PZReplayLibrary.onPlay)
    self.playButton:initialise()
    self.playButton:instantiate()
    self:addChild(self.playButton)

    local hintHeight = fontHeight(FONT) + 8
    local browserY = TITLE_HEIGHT + PAD
    local browserHeight = self.height - browserY - hintHeight - PAD
    self.browser = PZReplayBrowser:new(PAD, browserY, self.width - PAD * 2, browserHeight, {
        allowPlayback = false
    })
    self.browser:initialise()
    self.browser:instantiate()
    self:addChild(self.browser)
end

function PZReplayLibrary:open()
    self.loadHidden = false
    self.browser:refresh()
    self:setVisible(true)
    self:setCapture(true)
    self:setAlwaysOnTop(true)
    self:setWantKeyEvents(true)
    self:setWantMouseEvents(true)
    self:bringToTop()
    self:updatePlayButton()
end

function PZReplayLibrary:close()
    local callback = self.onClosed
    self.onClosed = nil
    self.loadHidden = false
    self:setCapture(false)
    self:setWantKeyEvents(false)
    self:setWantMouseEvents(false)
    self:setVisible(false)
    if callback then
        callback()
    end
end

function PZReplayLibrary:onClose()
    if PZReplayLoad and PZReplayLoad.isPending() then
        PZReplayLoad.cancel("library closed")
    end
    self:close()
end

function PZReplayLibrary:isHiddenForLoad()
    return self.loadHidden == true
end

function PZReplayLibrary:hideForLoad()
    self.loadHidden = true
    self:setCapture(false)
    self:setWantKeyEvents(false)
    self:setWantMouseEvents(false)
    self:setVisible(false)
end

function PZReplayLibrary:restoreAfterLoadFailure(message)
    self.loadHidden = false
    self:setVisible(true)
    self:setCapture(true)
    self:setAlwaysOnTop(true)
    self:setWantKeyEvents(true)
    self:setWantMouseEvents(true)
    self:bringToTop()
    self.browser:setStatus(message or "The replay could not be loaded.", true)
    self:updatePlayButton()
end

function PZReplayLibrary:closeCancelledLoad()
    if not self.loadHidden then
        return
    end
    self:close()
end

function PZReplayLibrary:playSelected()
    local entry = self.browser and self.browser:getSelected()
    if not entry then
        self.browser:setStatus("Select a replay first.", true)
        return false, "Select a replay first."
    end
    if not entry.valid then
        local message = "Cannot play: " .. entry.error
        self.browser:setStatus(message, true)
        return false, message
    end
    if PZReplayLoad.isPending() then
        local message = "A replay load is already in progress."
        self.browser:setStatus(message, true)
        return false, message
    end
    local started, message = PZReplayLoad.load(entry.path, self)
    if not started then
        self.browser:setStatus(message or "The replay could not be loaded.", true)
    end
    self:updatePlayButton()
    return started, message
end

function PZReplayLibrary:onPlay()
    self:playSelected()
end

function PZReplayLibrary:updatePlayButton()
    if not self.playButton then
        return
    end
    local entry = self.browser and self.browser:getSelected()
    local dialogOpen = self.browser and self.browser:isDialogOpen()
    local pending = PZReplayLoad and PZReplayLoad.isPending()
    self.playButton:setEnable(entry ~= nil and entry.valid and not dialogOpen
        and not pending)
end

function PZReplayLibrary:update()
    self:updatePlayButton()
end

function PZReplayLibrary:onKeyPress(key)
    if not self:getIsVisible() then
        return false
    end
    if self.browser then
        if key == Keyboard.KEY_UP then
            self.browser:moveSelection(-1)
        elseif key == Keyboard.KEY_DOWN then
            self.browser:moveSelection(1)
        elseif key == Keyboard.KEY_PAGEUP then
            self.browser:moveSelection(-5)
        elseif key == Keyboard.KEY_PAGEDOWN then
            self.browser:moveSelection(5)
        elseif key == Keyboard.KEY_HOME then
            self.browser:selectIndex(1)
        elseif key == Keyboard.KEY_END then
            self.browser:selectIndex(self.browser.list.count)
        elseif key == Keyboard.KEY_RETURN then
            self:playSelected()
        elseif key == Keyboard.KEY_F and not self.browser:isSearchFocused() then
            self.browser:toggleFavorite()
        end
    end
    return true
end

function PZReplayLibrary:onKeyRelease(key)
    if not self:getIsVisible() then
        return false
    end
    if key == Keyboard.KEY_ESCAPE then
        self:close()
    end
    return true
end

function PZReplayLibrary:isKeyConsumed(key)
    return self:getIsVisible()
end

function PZReplayLibrary:onMouseWheel(del)
    return self:getIsVisible()
end

function PZReplayLibrary:prerender()
    self:drawRect(0, 0, self.width, self.height,
        self.backgroundColor.a, self.backgroundColor.r, self.backgroundColor.g,
        self.backgroundColor.b)
    self:drawRectBorder(0, 0, self.width, self.height,
        self.borderColor.a, self.borderColor.r, self.borderColor.g, self.borderColor.b)
    self:drawText("Replay Library", PAD, 7, 1, 1, 1, 1, UIFont.Medium)
end

function PZReplayLibrary:render()
    local text = "Select a replay and choose Play to load its recorded save."
    self:drawText(fitText(text, self.width - PAD * 2, FONT), PAD,
        self.height - PAD - fontHeight(FONT), 0.82, 0.82, 0.82, 1.0, FONT)
end

function PZReplayLibrary:new()
    local screenWidth = getCore():getScreenWidth()
    local screenHeight = getCore():getScreenHeight()
    local width = math.min(900, screenWidth - 40)
    local height = math.min(620, screenHeight - 40)
    local x = math.floor((screenWidth - width) / 2)
    local y = math.floor((screenHeight - height) / 2)
    local o = ISPanel:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0.07, g = 0.07, b = 0.08, a = 0.96 }
    o.borderColor = { r = 0.7, g = 0.7, b = 0.7, a = 1.0 }
    o:setWantKeyEvents(false)
    o:setWantMouseEvents(false)
    return o
end

PZReplayLibrary.attach = attachEntry

Events.OnMainMenuEnter.Add(attachEntry)
Events.OnGameStart.Add(attachEntry)
