package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReplaySettings {
    public static final String ACTION_RECORD = "record";
    public static final String ACTION_PLAY = "play";
    public static final String ACTION_CAMERA = "camera";
    public static final String ACTION_PANEL = "panel";

    public static final List<String> ACTIONS = List.of(
        ACTION_RECORD, ACTION_PLAY, ACTION_CAMERA, ACTION_PANEL);

    public static final int DEFAULT_KEY_RECORD = 210;
    public static final int DEFAULT_KEY_PLAY = 207;
    public static final int DEFAULT_KEY_CAMERA = 199;
    public static final int DEFAULT_KEY_PANEL = 65;

    private static final List<Integer> FALLBACK_KEYS = List.of(
        65, 199, 201, 209, 210, 207, 211, 41, 43, 39, 40);

    public record Conflict(String action, int key, String other) {
    }

    private final Map<String, Integer> keys = new LinkedHashMap<>();
    private double sampleRateHz = CaptureSession.DEFAULT_SAMPLE_RATE_HZ;
    private int countdownSeconds = RecordingCountdown.DEFAULT_SECONDS;
    private boolean cameraFollowDefault;
    private boolean debugControls;
    private boolean killSwitch;

    public ReplaySettings() {
        resetKeys();
    }

    public static ReplaySettings load(Path path) {
        ReplaySettings settings = new ReplaySettings();
        if (path == null) {
            return settings;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return settings;
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                values.put(trimmed.substring(0, separator).trim(),
                    trimmed.substring(separator + 1).trim());
            }
        }

        double rate = parseDouble(values.get("sample_rate_hz"),
            CaptureSession.DEFAULT_SAMPLE_RATE_HZ);
        if (isValidSampleRate(rate)) {
            settings.sampleRateHz = rate;
        }
        int countdown = parseInt(values.get("countdown_seconds"),
            RecordingCountdown.DEFAULT_SECONDS);
        if (RecordingCountdown.isValidSeconds(countdown)) {
            settings.countdownSeconds = countdown;
        }
        settings.cameraFollowDefault = parseBoolean(values.get("camera_follow_default"));
        settings.debugControls = parseBoolean(values.get("debug_controls"));
        settings.killSwitch = parseBoolean(values.get("kill_switch"));

        Map<String, Integer> requested = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            String value = values.get("key." + action);
            if (value != null) {
                requested.put(action, parseInt(value, -1));
            }
        }
        settings.applyKeys(requested);
        return settings;
    }

    public void save(Path path) throws IOException {
        if (path == null) {
            throw new IOException("settings path is unavailable");
        }
        StringBuilder text = new StringBuilder();
        text.append("sample_rate_hz=")
            .append(String.format(Locale.ROOT, "%.2f", sampleRateHz)).append('\n');
        text.append("countdown_seconds=").append(countdownSeconds).append('\n');
        text.append("camera_follow_default=").append(cameraFollowDefault).append('\n');
        text.append("debug_controls=").append(debugControls).append('\n');
        text.append("kill_switch=").append(killSwitch).append('\n');
        for (String action : ACTIONS) {
            text.append("key.").append(action).append('=').append(key(action)).append('\n');
        }
        ReplayFiles.writeAtomically(path, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isValidSampleRate(double sampleRateHz) {
        return Double.isFinite(sampleRateHz)
            && sampleRateHz >= CaptureSession.MIN_SAMPLE_RATE_HZ
            && sampleRateHz <= CaptureSession.MAX_SAMPLE_RATE_HZ;
    }

    public boolean setSampleRate(double sampleRateHz) {
        if (!isValidSampleRate(sampleRateHz)) {
            return false;
        }
        this.sampleRateHz = sampleRateHz;
        return true;
    }

    public double getSampleRate() {
        return sampleRateHz;
    }

    public boolean setCountdownSeconds(int seconds) {
        if (!RecordingCountdown.isValidSeconds(seconds)) {
            return false;
        }
        countdownSeconds = seconds;
        return true;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public boolean isCameraFollowDefault() {
        return cameraFollowDefault;
    }

    public void setCameraFollowDefault(boolean enabled) {
        cameraFollowDefault = enabled;
    }

    public boolean isDebugControls() {
        return debugControls;
    }

    public void setDebugControls(boolean enabled) {
        debugControls = enabled;
    }

    public boolean isKillSwitch() {
        return killSwitch;
    }

    public void setKillSwitch(boolean enabled) {
        killSwitch = enabled;
    }

    public int key(String action) {
        Integer value = keys.get(action);
        return value == null ? 0 : value;
    }

    public boolean bind(String action, int key) {
        if (!ACTIONS.contains(action) || key <= 0) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : keys.entrySet()) {
            if (!entry.getKey().equals(action) && entry.getValue() == key) {
                return false;
            }
        }
        keys.put(action, key);
        return true;
    }

    public void resetKeys() {
        keys.clear();
        keys.put(ACTION_RECORD, DEFAULT_KEY_RECORD);
        keys.put(ACTION_PLAY, DEFAULT_KEY_PLAY);
        keys.put(ACTION_CAMERA, DEFAULT_KEY_CAMERA);
        keys.put(ACTION_PANEL, DEFAULT_KEY_PANEL);
    }

    public List<Conflict> conflicts(Map<Integer, String> knownBindings) {
        List<Conflict> conflicts = new ArrayList<>();
        if (knownBindings != null) {
            for (String action : ACTIONS) {
                String other = knownBindings.get(key(action));
                if (other != null) {
                    conflicts.add(new Conflict(action, key(action), other));
                }
            }
        }
        for (int first = 0; first < ACTIONS.size(); first++) {
            for (int second = first + 1; second < ACTIONS.size(); second++) {
                String firstAction = ACTIONS.get(first);
                String secondAction = ACTIONS.get(second);
                if (key(firstAction) == key(secondAction)) {
                    conflicts.add(new Conflict(firstAction, key(firstAction),
                        "another pz-replay binding (" + secondAction + ")"));
                }
            }
        }
        return conflicts;
    }

    private void applyKeys(Map<String, Integer> requested) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            int candidate = requested.getOrDefault(action, defaultKey(action));
            if (candidate <= 0 || resolved.containsValue(candidate)) {
                candidate = defaultKey(action);
            }
            if (candidate <= 0 || resolved.containsValue(candidate)) {
                candidate = fallbackKey(resolved);
            }
            resolved.put(action, candidate);
        }
        keys.clear();
        keys.putAll(resolved);
    }

    private static int fallbackKey(Map<String, Integer> used) {
        for (Integer candidate : FALLBACK_KEYS) {
            if (!used.containsValue(candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    private static int defaultKey(String action) {
        return switch (action) {
            case ACTION_RECORD -> DEFAULT_KEY_RECORD;
            case ACTION_PLAY -> DEFAULT_KEY_PLAY;
            case ACTION_CAMERA -> DEFAULT_KEY_CAMERA;
            case ACTION_PANEL -> DEFAULT_KEY_PANEL;
            default -> 0;
        };
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            default -> false;
        };
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
