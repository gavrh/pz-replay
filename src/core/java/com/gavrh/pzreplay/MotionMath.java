package com.gavrh.pzreplay;

final class MotionMath {
    private MotionMath() {
    }

    static float lerp(float start, float end, double alpha) {
        return (float) (start + (end - start) * alpha);
    }

    static float interpolateFacing(float start, float end, double alpha) {
        double delta = (end - start + 540.0) % 360.0 - 180.0;
        return normalizeFacing((float) (start + delta * alpha));
    }

    static float normalizeFacing(float angle) {
        float normalized = angle % 360.0f;
        if (normalized <= -180.0f) {
            normalized += 360.0f;
        } else if (normalized > 180.0f) {
            normalized -= 360.0f;
        }
        return normalized;
    }
}
