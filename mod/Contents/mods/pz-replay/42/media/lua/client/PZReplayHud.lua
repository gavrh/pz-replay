require "ISUI/ISPanel"

if PZReplayHud then return end

PZReplayHud = ISPanel:derive("PZReplayHud")

local FONT = UIFont.Small
local PAD = 8
local WIDTH = 320
local RESULT_VISIBLE_MILLIS = 4000

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

local function formatTime(seconds)
    if not seconds or seconds < 0 then seconds = 0 end
    local total = math.floor(seconds)
    return string.format("%d:%02d", math.floor(total / 60), total % 60)
end

local function resultColor(text)
    if text:find("failed") then
        return 1.0, 0.55, 0.45
    end
    if text:find("Saved") then
        return 0.55, 1.0, 0.6
    end
    return 1.0, 0.85, 0.4
end

function PZReplayHud:createChildren()
    self.width = WIDTH
    self.height = fontHeight(FONT) * 3 + PAD * 2
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

    local lines = {}
    local recording = PZReplay and PZReplay.isRecording()
    local countingDown = PZReplay and PZReplay.isCountingDown()
    local playing = PZReplayPlayback and PZReplayPlayback.isActive()
    local name = PZReplay and PZReplay.getRecordingName() or ""

    if countingDown then
        lines[#lines + 1] = {
            text = string.format("STARTING  %.1fs",
                PZReplay.getCountdownRemainingSeconds()),
            r = 1.0, g = 0.85, b = 0.4
        }
    elseif recording then
        lines[#lines + 1] = {
            text = string.format("REC  %s  %d frames  %.1f/%.0f Hz",
                formatTime(PZReplay.getRecordingSeconds()), PZReplay.getRecordedFrames(),
                PZReplay.getActualSampleRate(), PZReplay.getConfiguredSampleRate()),
            r = 1.0, g = 0.5, b = 0.4
        }
    end

    if (countingDown or recording) and name ~= "" then
        lines[#lines + 1] = {
            text = "Name  " .. name,
            r = 0.85, g = 0.85, b = 0.85
        }
    end

    if playing then
        lines[#lines + 1] = {
            text = string.format("REPLAY  %s / %s",
                formatTime(PZReplayPlayback.getCurrentTime()),
                formatTime(PZReplayPlayback.getDuration())),
            r = 0.6, g = 1.0, b = 0.6
        }
        local label = PZReplayPlayback.getCameraModeLabel() or "Player"
        if PZReplayPlayback.isCameraFree() then
            label = label .. "  arrows pan, ESC exits"
        end
        lines[#lines + 1] = {
            text = "CAMERA  " .. label,
            r = PZReplayPlayback.isCameraFree() and 0.55 or 0.75,
            g = PZReplayPlayback.isCameraFree() and 0.9 or 0.85,
            b = 1.0
        }
    end

    local result = PZReplay and PZReplay.getLastRecordingResult
        and PZReplay.getLastRecordingResult() or ""
    local resultVersion = PZReplay and PZReplay.getRecordingResultVersion
        and PZReplay.getRecordingResultVersion() or 0
    if resultVersion ~= self.resultVersion then
        self.resultVersion = resultVersion
        self.resultText = result
        self.resultUntil = result ~= "" and getTimestampMs() + RESULT_VISIBLE_MILLIS or 0
    end
    if self.resultText ~= "" and getTimestampMs() < self.resultUntil then
        local r, g, b = resultColor(self.resultText)
        lines[#lines + 1] = { text = self.resultText, r = r, g = g, b = b }
    end

    self.lines = lines
    local height = math.max(#lines, 1) * fontHeight(FONT) + PAD * 2
    if height ~= self.height then
        self:setHeight(height)
    end
end

function PZReplayHud:render()
    local lineHeight = fontHeight(FONT)
    local y = PAD
    for _, line in ipairs(self.lines or {}) do
        self:drawText(fitText(line.text, self.width - PAD * 2, FONT), PAD, y,
            line.r, line.g, line.b, 1.0, FONT)
        y = y + lineHeight
    end
end

function PZReplayHud:prerender()
    if not self.lines or #self.lines == 0 then
        return
    end
    self:drawRect(0, 0, self.width, self.height,
        self.backgroundColor.a, self.backgroundColor.r, self.backgroundColor.g,
        self.backgroundColor.b)
    self:drawRectBorder(0, 0, self.width, self.height,
        self.borderColor.a, self.borderColor.r, self.borderColor.g, self.borderColor.b)
end

function PZReplayHud:new()
    local o = ISPanel:new(8, 8, WIDTH, 32)
    setmetatable(o, self)
    self.__index = self
    o.lines = {}
    o.resultVersion = -1
    o.resultText = ""
    o.resultUntil = 0
    o:setWantKeyEvents(false)
    o:setWantMouseEvents(false)
    return o
end
