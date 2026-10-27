require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISScrollingListBox"

if PZReplayBrowser then return end

PZReplayBrowser = ISPanel:derive("PZReplayBrowser")

local FONT = UIFont.Small
local PAD = 8

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

local function fileName(path)
    if not path or path == "" then return "" end
    return path:match("([^/\\]+)$") or path
end

local function formatDate(millis)
    if not millis or millis <= 0 then
        return "unknown date"
    end
    local ok, value = pcall(os.date, "%Y-%m-%d %H:%M", math.floor(millis / 1000))
    if not ok or not value then
        return "unknown date"
    end
    return value
end

local function formatSize(bytes)
    if not bytes or bytes < 0 then
        return "unknown size"
    end
    if bytes < 1024 then
        return string.format("%d B", bytes)
    end
    local kilobytes = bytes / 1024
    if kilobytes < 1024 then
        return string.format("%.1f KB", kilobytes)
    end
    return string.format("%.1f MB", kilobytes / 1024)
end

function PZReplayBrowser:createChildren()
    local buttonHeight = fontHeight(FONT) + 8
    local statusHeight = fontHeight(FONT) + 2
    local listHeight = self.height - buttonHeight - statusHeight - PAD * 2 - 4

    self.list = ISScrollingListBox:new(PAD, PAD, self.width - PAD * 2, listHeight)
    self.list:initialise()
    self.list:instantiate()
    self.list.itemheight = fontHeight(FONT) * 3 + 8
    self.list.font = FONT
    self.list.drawBorder = true
    self.list.backgroundColor = { r = 0.04, g = 0.04, b = 0.04, a = 0.85 }
    self.list.borderColor = { r = 0.55, g = 0.55, b = 0.55, a = 1.0 }
    self.list.textColor = { r = 1.0, g = 1.0, b = 1.0, a = 1.0 }
    self.list.selectedTextColor = { r = 1.0, g = 1.0, b = 1.0, a = 1.0 }
    self.list.selectionColor = { r = 0.2, g = 0.35, b = 0.55, a = 0.9 }
    self.list.mouseOverHighlightColor = { r = 0.25, g = 0.25, b = 0.25, a = 0.6 }
    self.list.doDrawItem = function(list, y, item, alt)
        return self:drawRow(list, y, item, alt)
    end
    self.list.onmousedown = function() self:updateButtons() end
    self.list.onmousedblclick = function()
        self:playSelected()
    end
    self:addChild(self.list)

    local buttonY = self.height - buttonHeight - PAD
    local refreshWidth = getTextManager():MeasureStringX(FONT, "Refresh") + 24
    local dismissWidth = getTextManager():MeasureStringX(FONT, "Dismiss") + 24
    local stopWidth = getTextManager():MeasureStringX(FONT, "Stop") + 24
    local playWidth = getTextManager():MeasureStringX(FONT, "Play") + 24
    local gap = 6

    self.refreshButton = ISButton:new(self.width - PAD - refreshWidth, buttonY,
        refreshWidth, buttonHeight, "Refresh", self, PZReplayBrowser.onRefresh)
    self.refreshButton:initialise()
    self.refreshButton:instantiate()
    self:addChild(self.refreshButton)

    self.dismissButton = ISButton:new(self.refreshButton.x - gap - dismissWidth, buttonY,
        dismissWidth, buttonHeight, "Dismiss", self, PZReplayBrowser.onDismiss)
    self.dismissButton:initialise()
    self.dismissButton:instantiate()
    self:addChild(self.dismissButton)

    if self.options.allowPlayback ~= false then
        self.stopButton = ISButton:new(self.dismissButton.x - gap - stopWidth, buttonY,
            stopWidth, buttonHeight, "Stop", self, PZReplayBrowser.onStop)
        self.stopButton:initialise()
        self.stopButton:instantiate()
        self:addChild(self.stopButton)

        self.playButton = ISButton:new(self.stopButton.x - gap - playWidth, buttonY,
            playWidth, buttonHeight, "Play", self, PZReplayBrowser.onPlay)
        self.playButton:initialise()
        self.playButton:instantiate()
        self:addChild(self.playButton)
    end
end

function PZReplayBrowser:drawRow(list, y, item, alt)
    local entry = item.item
    local rowHeight = item.height
    if not rowHeight or rowHeight <= 0 then
        return y + list.itemheight
    end
    if list.selected == item.index then
        list:drawSelection(0, y, list:getWidth(), rowHeight - 1)
    elseif list.mouseoverselected == item.index and list:isMouseOver() then
        list:drawMouseOverHighlight(0, y, list:getWidth(), rowHeight - 1)
    end
    list:drawRectBorder(0, y, list:getWidth(), rowHeight, 0.5,
        list.borderColor.r, list.borderColor.g, list.borderColor.b)

    local name = entry.name
    if entry.lastReplay then
        name = name .. "  (last)"
    end
    local nameColor = entry.valid and { r = 1.0, g = 1.0, b = 1.0, a = 1.0 }
        or { r = 1.0, g = 0.55, b = 0.45, a = 1.0 }
    local lineHeight = fontHeight(FONT)
    list:drawText(fitText(name, list:getWidth() - 16, FONT), 8, y + 3,
        nameColor.r, nameColor.g, nameColor.b, nameColor.a, FONT)

    local date = formatDate(entry.modifiedMillis)
    local size = formatSize(entry.sizeBytes)
    local detail
    if entry.valid then
        detail = string.format("%s  %s  %s",
            date, self:formatDuration(entry.duration), size)
    else
        detail = string.format("%s  %s", date, size)
    end
    list:drawText(fitText(detail, list:getWidth() - 16, FONT), 8, y + 3 + lineHeight,
        0.72, 0.72, 0.72, 1.0, FONT)

    local context
    if entry.valid then
        context = string.format("%s  %s  %s",
            entry.map ~= "" and entry.map or "unknown map",
            entry.player ~= "" and entry.player or "unknown player",
            string.format("%.0f Hz", entry.sampleRate))
    else
        context = "unreadable: " .. entry.error
    end
    list:drawText(fitText(context, list:getWidth() - 16, FONT), 8,
        y + 3 + lineHeight * 2, 0.72, 0.72, 0.72, 1.0, FONT)
    return y + rowHeight
end

function PZReplayBrowser:formatDuration(seconds)
    if not seconds or seconds <= 0 then
        return "0:00"
    end
    local total = math.floor(seconds + 0.5)
    return string.format("%d:%02d", math.floor(total / 60), total % 60)
end

function PZReplayBrowser:refresh()
    if not PZReplayCatalogue then
        self:setStatus("Replay catalogue is unavailable.", true)
        return
    end
    local previous = self:getSelected()
    local previousPath = previous and previous.path or ""
    local count = PZReplayCatalogue.refresh()
    self.list:clear()
    self.list.selected = -1
    for index = 0, count - 1 do
        local entry = {
            name = PZReplayCatalogue.getFileName(index),
            path = PZReplayCatalogue.getPath(index),
            modifiedMillis = PZReplayCatalogue.getModifiedMillis(index),
            sizeBytes = PZReplayCatalogue.getSizeBytes(index),
            valid = PZReplayCatalogue.isValid(index),
            error = PZReplayCatalogue.getRowError(index),
            duration = PZReplayCatalogue.getDuration(index),
            sampleRate = PZReplayCatalogue.getSampleRate(index),
            map = PZReplayCatalogue.getMap(index),
            save = PZReplayCatalogue.getSave(index),
            gameMode = PZReplayCatalogue.getGameMode(index),
            player = PZReplayCatalogue.getPlayer(index),
            lastReplay = PZReplayCatalogue.isLastReplay(index)
        }
        local item = self.list:addItem(entry.name, entry)
        item.height = self.list.itemheight
        if entry.lastReplay then
            self.list.selected = self.list.count
        end
    end
    local catalogueError = PZReplayCatalogue.getCatalogueError()
    if catalogueError ~= "" then
        self:setStatus(catalogueError, true)
    elseif count == 0 then
        self:setStatus("No replays found.")
    else
        self:setStatus(string.format("%d replay%s", count, count == 1 and "" or "s"))
    end
    if previousPath ~= "" then
        if self:selectPath(previousPath) == nil and catalogueError == "" then
            self:setStatus("Replay is no longer available: " .. fileName(previousPath), true)
        end
    elseif count > 0 and self.list.selected < 1 then
        self.list.selected = 1
    end
    if self.list.selected >= 1 then
        self.list:ensureVisible(self.list.selected)
    end
    self:updateButtons()
end

function PZReplayBrowser:selectIndex(index)
    if not self.list or self.list.count < 1 then
        return
    end
    self.list.selected = math.max(1, math.min(index, self.list.count))
    self.list:ensureVisible(self.list.selected)
    self:updateButtons()
end

function PZReplayBrowser:moveSelection(delta)
    if not self.list or self.list.count < 1 then
        return
    end
    local selected = self.list.selected
    if selected < 1 then
        selected = 1
    else
        selected = selected + delta
    end
    self:selectIndex(selected)
end

function PZReplayBrowser:getSelected()
    if not self.list or self.list.selected < 1 then
        return nil
    end
    local item = self.list.items[self.list.selected]
    return item and item.item or nil
end

function PZReplayBrowser:selectPath(path)
    for index, item in ipairs(self.list.items) do
        if item.item.path == path then
            self.list.selected = index
            self.list:ensureVisible(index)
            self:updateButtons()
            return item.item
        end
    end
    return nil
end

function PZReplayBrowser:setStatus(message, isError)
    self.status = message or ""
    self.statusIsError = isError == true
    if self.dismissButton then
        self.dismissButton:setEnable(self.statusIsError)
    end
end

function PZReplayBrowser:isPlaying()
    return self.options.isPlaying and self.options.isPlaying() or false
end

function PZReplayBrowser:playSelected()
    local entry = self:getSelected()
    if not entry then
        self:setStatus("Select a replay first.", true)
        return false
    end
    if not entry.valid then
        self:setStatus("Cannot play: " .. entry.error, true)
        return false
    end
    if self.options.allowPlayback == false then
        self:setStatus("Playback requires a loaded save. Open Replays from the in-game pause menu.")
        return false
    end
    if self.options.canPlay and not self.options.canPlay() then
        self:setStatus("Playback is unavailable.", true)
        return false
    end
    if not self.options.onPlay then
        self:setStatus("Playback is unavailable.", true)
        return false
    end
    local started, message = self.options.onPlay(entry.path)
    self:setStatus(message or (started and "Playback started." or "Playback failed."), not started)
    self:updateButtons()
    return started
end

function PZReplayBrowser:stopPlayback()
    if self.options.onStop then
        local stopped, message = self.options.onStop()
        self:setStatus(message or (stopped and "Playback stopped." or "Playback is not active."))
    end
    self:updateButtons()
end

function PZReplayBrowser:onRefresh()
    self:refresh()
end

function PZReplayBrowser:onPlay()
    self:playSelected()
end

function PZReplayBrowser:onStop()
    self:stopPlayback()
end

function PZReplayBrowser:onDismiss()
    self:setStatus("")
    if PZReplayCatalogue and PZReplayCatalogue.clearError then
        PZReplayCatalogue.clearError()
    end
end

function PZReplayBrowser:updateButtons()
    local entry = self:getSelected()
    local playing = self:isPlaying()
    if self.playButton then
        self.playButton:setEnable(entry ~= nil and entry.valid and not playing
            and (not self.options.canPlay or self.options.canPlay()))
    end
    if self.stopButton then
        self.stopButton:setEnable(playing)
    end
    if self.dismissButton then
        self.dismissButton:setEnable(self.statusIsError == true)
    end
end

function PZReplayBrowser:render()
    if self.list then
        local statusY = self.list.y + self.list.height + 2
        local red = self.statusIsError == true
        self:drawText(fitText(self.status or "", self.width - PAD * 2, FONT), PAD, statusY,
            red and 1.0 or 0.82, red and 0.5 or 0.82, red and 0.4 or 0.82, 1.0, FONT)
    end
end

function PZReplayBrowser:update()
    self:updateButtons()
end

function PZReplayBrowser:new(x, y, width, height, options)
    local o = ISPanel:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.options = options or {}
    o.status = ""
    o.background = false
    o.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    o:setWantMouseEvents(true)
    return o
end
