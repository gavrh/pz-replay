require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISScrollingListBox"
require "ISUI/ISTextEntryBox"

if PZReplayBrowser then return end

PZReplayBrowser = ISPanel:derive("PZReplayBrowser")

local FONT = UIFont.Small
local PAD = 8
local SORT_FIELDS = { "date", "name", "duration" }
local SORT_LABELS = { date = "Date", name = "Name", duration = "Duration" }
local SORT_DIRECTIONS = {
    date = { asc = "Oldest", desc = "Newest" },
    name = { asc = "A-Z", desc = "Z-A" },
    duration = { asc = "Shortest", desc = "Longest" }
}

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

local function baseName(path)
    local name = fileName(path)
    return (name:gsub("%.pzrp%.gz$", ""))
end

local function samePath(left, right)
    if not left or not right then return false end
    return left:gsub("\\", "/") == right:gsub("\\", "/")
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
    local searchY = PAD
    local listY = searchY + buttonHeight + 4
    local buttonY = self.height - buttonHeight - PAD
    local statusY = buttonY - statusHeight - 2
    local listHeight = statusY - listY - 2
    local gap = 6

    local searchLabelWidth = getTextManager():MeasureStringX(FONT, "Search") + 6
    local sortWidth = getTextManager():MeasureStringX(FONT, "Sort: Duration") + 24
    local directionWidth = getTextManager():MeasureStringX(FONT, "Shortest") + 24
    local directionX = self.width - PAD - directionWidth
    local sortX = directionX - gap - sortWidth
    local searchX = PAD + searchLabelWidth
    local searchWidth = math.max(120, sortX - gap - searchX)

    self.searchLabelX = PAD
    self.searchLabelY = searchY
    self.searchEntry = ISTextEntryBox:new(self.searchText or "", searchX, searchY,
        searchWidth, buttonHeight)
    self.searchEntry.font = FONT
    self.searchEntry:initialise()
    self.searchEntry:instantiate()
    self.searchEntry.onTextChange = function() self:onSearchChanged() end
    self:addChild(self.searchEntry)

    self.sortButton = ISButton:new(sortX, searchY, sortWidth, buttonHeight, "Sort: Date",
        self, PZReplayBrowser.onSort)
    self.sortButton:initialise()
    self.sortButton:instantiate()
    self:addChild(self.sortButton)

    self.directionButton = ISButton:new(directionX, searchY, directionWidth, buttonHeight,
        "Newest", self, PZReplayBrowser.onSortDirection)
    self.directionButton:initialise()
    self.directionButton:instantiate()
    self:addChild(self.directionButton)

    self.list = ISScrollingListBox:new(PAD, listY, self.width - PAD * 2, listHeight)
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

    local nextX = self.width - PAD
    local refreshWidth = getTextManager():MeasureStringX(FONT, "Refresh") + 24
    nextX = nextX - refreshWidth
    self.refreshButton = ISButton:new(nextX, buttonY, refreshWidth, buttonHeight,
        "Refresh", self, PZReplayBrowser.onRefresh)
    self.refreshButton:initialise()
    self.refreshButton:instantiate()
    self:addChild(self.refreshButton)

    local dismissWidth = getTextManager():MeasureStringX(FONT, "Dismiss") + 24
    nextX = nextX - gap - dismissWidth
    self.dismissButton = ISButton:new(nextX, buttonY, dismissWidth, buttonHeight,
        "Dismiss", self, PZReplayBrowser.onDismiss)
    self.dismissButton:initialise()
    self.dismissButton:instantiate()
    self:addChild(self.dismissButton)

    if self.options.allowPlayback ~= false then
        local stopWidth = getTextManager():MeasureStringX(FONT, "Stop") + 24
        nextX = nextX - gap - stopWidth
        self.stopButton = ISButton:new(nextX, buttonY, stopWidth, buttonHeight,
            "Stop", self, PZReplayBrowser.onStop)
        self.stopButton:initialise()
        self.stopButton:instantiate()
        self:addChild(self.stopButton)

        local playWidth = getTextManager():MeasureStringX(FONT, "Play") + 24
        nextX = nextX - gap - playWidth
        self.playButton = ISButton:new(nextX, buttonY, playWidth, buttonHeight,
            "Play", self, PZReplayBrowser.onPlay)
        self.playButton:initialise()
        self.playButton:instantiate()
        self:addChild(self.playButton)
    end

    local deleteWidth = getTextManager():MeasureStringX(FONT, "Delete") + 24
    nextX = nextX - gap - deleteWidth
    self.deleteButton = ISButton:new(nextX, buttonY, deleteWidth, buttonHeight,
        "Delete", self, PZReplayBrowser.onDelete)
    self.deleteButton:initialise()
    self.deleteButton:instantiate()
    self:addChild(self.deleteButton)

    local renameWidth = getTextManager():MeasureStringX(FONT, "Rename") + 24
    nextX = nextX - gap - renameWidth
    self.renameButton = ISButton:new(nextX, buttonY, renameWidth, buttonHeight,
        "Rename", self, PZReplayBrowser.onRename)
    self.renameButton:initialise()
    self.renameButton:instantiate()
    self:addChild(self.renameButton)

    self:updateSortButtons()
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
    self:closeDialog()
    local previous = self:getSelected()
    local previousPath = previous and previous.path or ""
    local previousIndex = self.list and self.list.selected > 0
        and self.list.selected - 1 or -1
    self:reload(previousPath, previousIndex)
end

function PZReplayBrowser:reload(preferredPath, fallbackIndex)
    if not PZReplayCatalogue then
        self:setStatus("Replay catalogue is unavailable.", true)
        return
    end
    self:closeDialog()
    PZReplayCatalogue.applyQuery(self.searchText or "", self.sortField,
        self.sortAscending)
    PZReplayCatalogue.refresh()
    self:rebuildList(preferredPath or "", fallbackIndex or -1)
end

function PZReplayBrowser:applyView()
    if not PZReplayCatalogue then
        self:setStatus("Replay catalogue is unavailable.", true)
        return
    end
    if not self.list then
        return
    end
    local previous = self:getSelected()
    local previousPath = previous and previous.path or ""
    local previousIndex = self.list and self.list.selected > 0
        and self.list.selected - 1 or -1
    PZReplayCatalogue.applyQuery(self.searchText or "", self.sortField,
        self.sortAscending)
    self:rebuildList(previousPath, previousIndex)
end

function PZReplayBrowser:rebuildList(preferredPath, fallbackIndex)
    local count = PZReplayCatalogue.getCount()
    local total = PZReplayCatalogue.getTotalCount()
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
    end

    local selected = PZReplayCatalogue.selectionIndex(preferredPath or "",
        fallbackIndex)
    if selected >= 0 then
        self.list.selected = selected + 1
    end

    local catalogueError = PZReplayCatalogue.getCatalogueError()
    if catalogueError ~= "" then
        self:setStatus(catalogueError, true)
    elseif total == 0 then
        self:setStatus("No replays found.")
    elseif count == 0 then
        self:setStatus("No replays match the search.")
    elseif count ~= total then
        self:setStatus(string.format("%d of %d replays", count, total))
    else
        self:setStatus(string.format("%d replay%s", count, count == 1 and "" or "s"))
    end

    if preferredPath ~= "" and catalogueError == "" and total > 0
        and not PZReplayCatalogue.hasPath(preferredPath) then
        self:setStatus("Replay is no longer available: " .. fileName(preferredPath), true)
    end

    if self.list.selected >= 1 then
        self.list:ensureVisible(self.list.selected)
    end
    self:updateButtons()
end

function PZReplayBrowser:selectIndex(index)
    if self:isDialogOpen() then return end
    if not self.list or self.list.count < 1 then
        return
    end
    self.list.selected = math.max(1, math.min(index, self.list.count))
    self.list:ensureVisible(self.list.selected)
    self:updateButtons()
end

function PZReplayBrowser:moveSelection(delta)
    if self:isDialogOpen() then return end
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

function PZReplayBrowser:activePlaybackPath()
    if PZReplayPlayback and PZReplayPlayback.getCurrentPath then
        return PZReplayPlayback.getCurrentPath() or ""
    end
    return ""
end

function PZReplayBrowser:isRecording()
    return PZReplay and PZReplay.isRecording and PZReplay.isRecording() or false
end

function PZReplayBrowser:managementBlockReason(entry)
    if self:isRecording() then
        return "Recording is active. Stop recording before managing replays."
    end
    local activePath = self:activePlaybackPath()
    if entry and activePath ~= "" and samePath(activePath, entry.path) then
        return "This replay is being played. Stop playback before managing it."
    end
    return nil
end

function PZReplayBrowser:playSelected()
    if self:isDialogOpen() then
        return false
    end
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
    if self:isDialogOpen() then return end
    if self.options.onStop then
        local stopped, message = self.options.onStop()
        self:setStatus(message or (stopped and "Playback stopped." or "Playback is not active."))
    end
    self:updateButtons()
end

function PZReplayBrowser:onRefresh()
    if self:isDialogOpen() then return end
    self:refresh()
end

function PZReplayBrowser:onPlay()
    self:playSelected()
end

function PZReplayBrowser:onStop()
    self:stopPlayback()
end

function PZReplayBrowser:onDismiss()
    if self:isDialogOpen() then return end
    self:setStatus("")
    if PZReplayCatalogue and PZReplayCatalogue.clearError then
        PZReplayCatalogue.clearError()
    end
end

function PZReplayBrowser:onSearchChanged()
    if not self.searchEntry then return end
    self.searchText = self.searchEntry:getText() or ""
    self:applyView()
end

function PZReplayBrowser:onSort()
    if self:isDialogOpen() then return end
    local nextField = SORT_FIELDS[1]
    for index, field in ipairs(SORT_FIELDS) do
        if field == self.sortField then
            nextField = SORT_FIELDS[index % #SORT_FIELDS + 1]
            break
        end
    end
    self.sortField = nextField
    self:updateSortButtons()
    self:applyView()
end

function PZReplayBrowser:onSortDirection()
    if self:isDialogOpen() then return end
    self.sortAscending = not self.sortAscending
    self:updateSortButtons()
    self:applyView()
end

function PZReplayBrowser:updateSortButtons()
    if not self.sortButton or not self.directionButton then
        return
    end
    self.sortButton:setTitle("Sort: " .. SORT_LABELS[self.sortField])
    local direction = SORT_DIRECTIONS[self.sortField]
    self.directionButton:setTitle(self.sortAscending and direction.asc or direction.desc)
end

function PZReplayBrowser:onRename()
    if self:isDialogOpen() then return end
    local entry = self:getSelected()
    if not entry then
        self:setStatus("Select a replay first.", true)
        return
    end
    local reason = self:managementBlockReason(entry)
    if reason then
        self:setStatus(reason, true)
        return
    end
    self:openDialog("rename", entry)
end

function PZReplayBrowser:onDelete()
    if self:isDialogOpen() then return end
    local entry = self:getSelected()
    if not entry then
        self:setStatus("Select a replay first.", true)
        return
    end
    local reason = self:managementBlockReason(entry)
    if reason then
        self:setStatus(reason, true)
        return
    end
    self:openDialog("delete", entry)
end

function PZReplayBrowser:performRename(entry, newName)
    if self:managementBlockReason(entry) then
        local reason = self:managementBlockReason(entry)
        self:setStatus(reason, true)
        return false, reason
    end
    if not PZReplayCatalogue or not PZReplayCatalogue.rename then
        local error = "Replay management is unavailable."
        self:setStatus(error, true)
        return false, error
    end
    if not PZReplayCatalogue.rename(entry.path, newName or "") then
        local error = PZReplayCatalogue.getLastOperationError()
        if error == "" then
            error = "Rename failed."
        end
        self:setStatus(error, true)
        return false, error
    end
    local newPath = PZReplayCatalogue.getLastOperationPath()
    self:reload(newPath, -1)
    self:setStatus("Renamed " .. entry.name .. " to " .. fileName(newPath))
    return true, ""
end

function PZReplayBrowser:performDelete(entry)
    local reason = self:managementBlockReason(entry)
    if reason then
        self:setStatus(reason, true)
        return false, reason
    end
    if not PZReplayCatalogue or not PZReplayCatalogue.delete then
        local error = "Replay management is unavailable."
        self:setStatus(error, true)
        return false, error
    end
    if not PZReplayCatalogue.delete(entry.path) then
        local error = PZReplayCatalogue.getLastOperationError()
        if error == "" then
            error = "Delete failed."
        end
        self:setStatus(error, true)
        return false, error
    end
    local fallbackIndex = self.list and self.list.selected > 0
        and self.list.selected - 1 or -1
    self:reload("", fallbackIndex)
    self:setStatus("Deleted " .. entry.name)
    return true, ""
end

function PZReplayBrowser:openDialog(mode, entry)
    if not self.dialog then
        self.dialog = PZReplayManageDialog:new(0, 0, self.width, self.height, self)
        self.dialog:initialise()
        self.dialog:instantiate()
        self:addChild(self.dialog)
    end
    self.dialog:setX(0)
    self.dialog:setY(0)
    self.dialog:setWidth(self.width)
    self.dialog:setHeight(self.height)
    self.dialog:open(mode, entry)
    self.dialog:bringToTop()
    self:updateButtons()
end

function PZReplayBrowser:closeDialog()
    if self.dialog and self.dialog:getIsVisible() then
        self.dialog:close()
    end
    self:updateButtons()
end

function PZReplayBrowser:isDialogOpen()
    return self.dialog ~= nil and self.dialog:getIsVisible()
end

function PZReplayBrowser:updateButtons()
    local entry = self:getSelected()
    local dialogOpen = self:isDialogOpen()
    local playing = self:isPlaying()
    local blocked = entry ~= nil and not dialogOpen
        and self:managementBlockReason(entry) == nil
    if self.playButton then
        self.playButton:setEnable(entry ~= nil and entry.valid and not playing
            and not dialogOpen
            and (not self.options.canPlay or self.options.canPlay()))
    end
    if self.stopButton then
        self.stopButton:setEnable(playing and not dialogOpen)
    end
    if self.renameButton then
        self.renameButton:setEnable(blocked)
    end
    if self.deleteButton then
        self.deleteButton:setEnable(blocked)
    end
    if self.refreshButton then
        self.refreshButton:setEnable(not dialogOpen)
    end
    if self.dismissButton then
        self.dismissButton:setEnable(self.statusIsError == true and not dialogOpen)
    end
end

function PZReplayBrowser:render()
    if self.list then
        local statusY = self.list.y + self.list.height + 2
        local red = self.statusIsError == true
        self:drawText(fitText(self.status or "", self.width - PAD * 2, FONT), PAD, statusY,
            red and 1.0 or 0.82, red and 0.5 or 0.82, red and 0.4 or 0.82, 1.0, FONT)
        if self.searchEntry then
            local y = self.searchEntry:getY()
                + math.floor((self.searchEntry:getHeight() - fontHeight(FONT)) / 2)
            self:drawText("Search", self.searchLabelX, y, 0.82, 0.82, 0.82, 1.0, FONT)
        end
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
    o.searchText = ""
    o.sortField = "date"
    o.sortAscending = false
    o.background = false
    o.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    o:setWantMouseEvents(true)
    return o
end

PZReplayManageDialog = ISPanel:derive("PZReplayManageDialog")

local DIALOG_PAD = 14

function PZReplayManageDialog:createChildren()
    local buttonHeight = fontHeight(FONT) + 8
    self.textEntry = ISTextEntryBox:new("", 0, 0, 10, buttonHeight)
    self.textEntry.font = FONT
    self.textEntry:initialise()
    self.textEntry:instantiate()
    self:addChild(self.textEntry)

    self.confirmButton = ISButton:new(0, 0, 110, buttonHeight, "Rename", self,
        PZReplayManageDialog.onConfirm)
    self.confirmButton:initialise()
    self.confirmButton:instantiate()
    self:addChild(self.confirmButton)

    self.cancelButton = ISButton:new(0, 0, 110, buttonHeight, "Cancel", self,
        PZReplayManageDialog.onCancel)
    self.cancelButton:initialise()
    self.cancelButton:instantiate()
    self:addChild(self.cancelButton)
    self:setVisible(false)
end

function PZReplayManageDialog:layout()
    local buttonHeight = fontHeight(FONT) + 8
    self.innerWidth = math.min(560, self.width - 40)
    self.innerHeight = 180
    self.innerX = math.floor((self.width - self.innerWidth) / 2)
    self.innerY = math.floor((self.height - self.innerHeight) / 2)

    self.textEntry:setX(self.innerX + DIALOG_PAD)
    self.textEntry:setY(self.innerY + 78)
    self.textEntry:setWidth(self.innerWidth - DIALOG_PAD * 2)
    self.textEntry:setHeight(buttonHeight)

    local buttonY = self.innerY + self.innerHeight - buttonHeight - DIALOG_PAD
    self.cancelButton:setX(self.innerX + self.innerWidth - DIALOG_PAD - 110)
    self.cancelButton:setY(buttonY)
    self.confirmButton:setX(self.cancelButton:getX() - 6 - 110)
    self.confirmButton:setY(buttonY)
end

function PZReplayManageDialog:open(mode, entry)
    self.mode = mode
    self.entryData = entry
    self.errorText = ""
    self:setWidth(self.owner.width)
    self:setHeight(self.owner.height)
    self:layout()
    local renaming = mode == "rename"
    self.textEntry:setVisible(renaming)
    self.confirmButton:setTitle(renaming and "Rename" or "Delete")
    self.confirmButton:setEnable(true)
    if renaming then
        self.textEntry:setText(baseName(entry.name))
    else
        self.textEntry:unfocus()
    end
    self:setVisible(true)
    self:setCapture(true)
    self:setAlwaysOnTop(true)
    self:setWantKeyEvents(true)
    self:setWantMouseEvents(true)
    self:bringToTop()
    if renaming then
        self.textEntry:focus()
    end
end

function PZReplayManageDialog:close()
    if self.textEntry then
        self.textEntry:unfocus()
    end
    self:setCapture(false)
    self:setWantKeyEvents(false)
    self:setWantMouseEvents(false)
    self:setVisible(false)
end

function PZReplayManageDialog:onConfirm()
    local ok, message
    if self.mode == "rename" then
        ok, message = self.owner:performRename(self.entryData, self.textEntry:getText())
    else
        ok, message = self.owner:performDelete(self.entryData)
    end
    if not ok then
        self.errorText = message or "Operation failed."
    end
end

function PZReplayManageDialog:onCancel()
    local mode = self.mode
    self.owner:closeDialog()
    if mode == "delete" then
        self.owner:setStatus("Delete cancelled.")
    else
        self.owner:setStatus("Rename cancelled.")
    end
end

function PZReplayManageDialog:onKeyRelease(key)
    if not self:getIsVisible() then
        return false
    end
    if key == Keyboard.KEY_ESCAPE then
        self:onCancel()
        return true
    end
    if key == Keyboard.KEY_RETURN then
        self:onConfirm()
        return true
    end
    return false
end

function PZReplayManageDialog:prerender()
    self:drawRect(0, 0, self.width, self.height, 0.55, 0, 0, 0)
    self:drawRect(self.innerX, self.innerY, self.innerWidth, self.innerHeight,
        0.96, 0.07, 0.07, 0.08)
    self:drawRectBorder(self.innerX, self.innerY, self.innerWidth, self.innerHeight,
        1.0, 0.7, 0.7, 0.7)

    local name = self.entryData and self.entryData.name or ""
    local title
    if self.mode == "rename" then
        title = "Rename replay"
        self:drawText(fitText(name, self.innerWidth - DIALOG_PAD * 2, FONT),
            self.innerX + DIALOG_PAD, self.innerY + 32, 0.82, 0.82, 0.82, 1.0, FONT)
        self:drawText("New name", self.innerX + DIALOG_PAD, self.innerY + 58,
            0.9, 0.9, 0.9, 1.0, FONT)
    else
        title = "Delete replay"
        self:drawText(fitText("Delete " .. name .. "?", self.innerWidth - DIALOG_PAD * 2,
            FONT), self.innerX + DIALOG_PAD, self.innerY + 32,
            1.0, 0.85, 0.8, 1.0, FONT)
        self:drawText("This cannot be undone.", self.innerX + DIALOG_PAD,
            self.innerY + 52, 0.82, 0.82, 0.82, 1.0, FONT)
    end
    self:drawText(title, self.innerX + DIALOG_PAD, self.innerY + 10,
        1, 1, 1, 1, UIFont.Medium)

    if self.errorText and self.errorText ~= "" then
        self:drawText(fitText(self.errorText, self.innerWidth - DIALOG_PAD * 2, FONT),
            self.innerX + DIALOG_PAD, self.innerY + 108, 1.0, 0.45, 0.35, 1.0, FONT)
    end
end

function PZReplayManageDialog:new(x, y, width, height, owner)
    local o = ISPanel:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.owner = owner
    o.background = false
    o.errorText = ""
    o:setWantKeyEvents(false)
    o:setWantMouseEvents(false)
    return o
end
