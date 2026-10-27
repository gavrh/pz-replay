require "ISUI/ISPanel"

if PZReplayHud then return end

PZReplayHud = ISPanel:derive("PZReplayHud")

local FONT = UIFont.Small
local PAD = 8

local function fontHeight(font)
    return getTextManager():getFontHeight(font)
end

local function formatTime(seconds)
    if not seconds or seconds < 0 then seconds = 0 end
    local total = math.floor(seconds)
    return string.format("%d:%02d", math.floor(total / 60), total % 60)
end

function PZReplayHud:createChildren()
    local height = fontHeight(FONT) * 2 + PAD * 2
    self.width = 230
    self.height = height
    self:setWidth(self.width)
    self:setHeight(self.height)
    self:setX(math.floor((getCore():getScreenWidth() - self.width) / 2))
    self:setY(24)
    self.backgroundColor = { r = 0.03, g = 0.03, b = 0.03, a = 0.62 }
    self.borderColor = { r = 0.7, g = 0.7, b = 0.7, a = 0.8 }
    self:setWantKeyEvents(false)
    self:setWantMouseEvents(false)
    self:setAlwaysOnTop(true)
end

function PZReplayHud:update()
    if not self:getIsVisible() then
        return
    end
    local recording = PZReplay and PZReplay.isRecording()
    local playing = PZReplayPlayback and PZReplayPlayback.isPlaying()
    self.recording = recording
    self.playing = playing
end

function PZReplayHud:render()
    local lineHeight = fontHeight(FONT)
    local y = PAD
    if self.recording then
        local text = string.format("REC  %s  %d frames",
            formatTime(PZReplay.getRecordingSeconds()), PZReplay.getRecordedFrames())
        self:drawText(text, PAD, y, 1.0, 0.5, 0.4, 1.0, FONT)
        y = y + lineHeight
    end
    if self.playing then
        local text = string.format("REPLAY  %s / %s",
            formatTime(PZReplayPlayback.getCurrentTime()),
            formatTime(PZReplayPlayback.getDuration()))
        self:drawText(text, PAD, y, 0.6, 1.0, 0.6, 1.0, FONT)
    end
end

function PZReplayHud:prerender()
    if not self.recording and not self.playing then
        return
    end
    self:drawRect(0, 0, self.width, self.height,
        self.backgroundColor.a, self.backgroundColor.r, self.backgroundColor.g,
        self.backgroundColor.b)
    self:drawRectBorder(0, 0, self.width, self.height,
        self.borderColor.a, self.borderColor.r, self.borderColor.g, self.borderColor.b)
end

function PZReplayHud:new()
    local o = ISPanel:new(8, 8, 230, 32)
    setmetatable(o, self)
    self.__index = self
    o.recording = false
    o.playing = false
    o:setWantKeyEvents(false)
    o:setWantMouseEvents(false)
    return o
end
