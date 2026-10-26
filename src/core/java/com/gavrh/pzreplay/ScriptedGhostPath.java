package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.List;

public final class ScriptedGhostPath {
    private final List<Point> points;
    private final boolean loop;
    private final double duration;

    public ScriptedGhostPath(List<Point> points, boolean loop) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("path requires at least two points");
        }
        this.points = List.copyOf(points);
        this.loop = loop;
        this.duration = this.points.get(this.points.size() - 1).time();

        Point first = this.points.get(0);
        if (first.time() != 0.0) {
            throw new IllegalArgumentException("path must start at time 0");
        }
        if (duration <= 0.0) {
            throw new IllegalArgumentException("path duration must be positive");
        }
        for (int index = 1; index < this.points.size(); index++) {
            if (this.points.get(index).time() <= this.points.get(index - 1).time()) {
                throw new IllegalArgumentException("path times must increase");
            }
        }
    }

    public double getDuration() {
        return duration;
    }

    public boolean isLoop() {
        return loop;
    }

    public Sample sample(double time) {
        double sampleTime = time;
        if (loop) {
            sampleTime %= duration;
            if (sampleTime < 0.0) {
                sampleTime += duration;
            }
        } else if (sampleTime <= 0.0) {
            return sampleOf(points.get(0));
        } else if (sampleTime >= duration) {
            return sampleOf(points.get(points.size() - 1));
        }

        for (int index = 0; index < points.size() - 1; index++) {
            Point start = points.get(index);
            Point end = points.get(index + 1);
            if (sampleTime < end.time()) {
                return interpolate(start, end, sampleTime);
            }
        }
        return sampleOf(points.get(points.size() - 1));
    }

    public static ScriptedGhostPath defaultRoute(float originX, float originY, float originZ) {
        float right = originX + 3.0f;
        float down = originY + 3.0f;
        List<Point> points = new ArrayList<>();
        points.add(new Point(0.0, originX, originY, originZ, 0.0f,
            ReplaySample.Locomotion.IDLE));
        points.add(new Point(1.0, originX, originY, originZ, 0.0f,
            ReplaySample.Locomotion.WALK));
        points.add(new Point(4.0, right, originY, originZ, 0.0f,
            ReplaySample.Locomotion.IDLE));
        points.add(new Point(4.4, right, originY, originZ, 90.0f,
            ReplaySample.Locomotion.RUN));
        points.add(new Point(6.2, right, down, originZ, 90.0f,
            ReplaySample.Locomotion.IDLE));
        points.add(new Point(6.6, right, down, originZ, 180.0f,
            ReplaySample.Locomotion.SPRINT));
        points.add(new Point(7.7, originX, down, originZ, 180.0f,
            ReplaySample.Locomotion.IDLE));
        points.add(new Point(8.1, originX, down, originZ, -90.0f,
            ReplaySample.Locomotion.SNEAK));
        points.add(new Point(11.7, originX, originY, originZ, -90.0f,
            ReplaySample.Locomotion.IDLE));
        points.add(new Point(12.1, originX, originY, originZ, 0.0f,
            ReplaySample.Locomotion.IDLE));
        points.add(new Point(13.1, originX, originY, originZ, 0.0f,
            ReplaySample.Locomotion.IDLE));
        return new ScriptedGhostPath(points, true);
    }

    private static Sample interpolate(Point start, Point end, double time) {
        double span = end.time() - start.time();
        double alpha = (time - start.time()) / span;
        float x = lerp(start.x(), end.x(), alpha);
        float y = lerp(start.y(), end.y(), alpha);
        float z = lerp(start.z(), end.z(), alpha);
        float facing = interpolateFacing(start.facing(), end.facing(), alpha);
        return new Sample(time, x, y, z, facing, start.locomotion());
    }

    private static Sample sampleOf(Point point) {
        return new Sample(point.time(), point.x(), point.y(), point.z(), point.facing(),
            point.locomotion());
    }

    private static float lerp(float start, float end, double alpha) {
        return (float) (start + (end - start) * alpha);
    }

    private static float interpolateFacing(float start, float end, double alpha) {
        double delta = (end - start + 540.0) % 360.0 - 180.0;
        double facing = start + delta * alpha;
        facing %= 360.0;
        if (facing <= -180.0) {
            facing += 360.0;
        } else if (facing > 180.0) {
            facing -= 360.0;
        }
        return (float) facing;
    }

    public record Point(
        double time,
        float x,
        float y,
        float z,
        float facing,
        ReplaySample.Locomotion locomotion
    ) {
        public Point {
            if (!Double.isFinite(time)) {
                throw new IllegalArgumentException("point time must be finite");
            }
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("point position must be finite");
            }
            if (!Float.isFinite(facing)) {
                throw new IllegalArgumentException("point facing must be finite");
            }
            if (locomotion == null) {
                throw new IllegalArgumentException("point locomotion is required");
            }
        }
    }

    public record Sample(
        double time,
        float x,
        float y,
        float z,
        float facing,
        ReplaySample.Locomotion locomotion
    ) {
    }
}
