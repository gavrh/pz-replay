package com.gavrh.pzreplay;

public record ReplaySample(
    long tick,
    float timeSeconds,
    float gameTimeSeconds,
    float x,
    float y,
    float z,
    float angle,
    float speed,
    Locomotion locomotion,
    Stance stance,
    int flags
) {
    public enum Locomotion {
        IDLE(0),
        WALK(1),
        RUN(2),
        SPRINT(3),
        SNEAK(4);

        private final int id;

        Locomotion(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Locomotion fromId(int id) {
            for (Locomotion value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown locomotion id " + id);
        }
    }

    public enum Stance {
        STAND(0),
        CROUCH(1),
        PRONE(2);

        private final int id;

        Stance(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Stance fromId(int id) {
            for (Stance value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown stance id " + id);
        }
    }

    public static final int FLAG_AIMING = 1;
    public static final int FLAG_SNEAKING = 1 << 1;
    public static final int FLAG_SPRINTING = 1 << 2;
    public static final int FLAG_RUNNING = 1 << 3;
    public static final int FLAG_PRONE = 1 << 4;
    public static final int FLAG_MOVING = 1 << 5;
    public static final int FLAG_IN_VEHICLE = 1 << 6;

    public boolean aiming() {
        return (flags & FLAG_AIMING) != 0;
    }

    public boolean inVehicle() {
        return (flags & FLAG_IN_VEHICLE) != 0;
    }

    public static int flags(boolean aiming, boolean sneaking, boolean sprinting,
        boolean running, boolean prone, boolean moving) {
        return flags(aiming, sneaking, sprinting, running, prone, moving, false);
    }

    public static int flags(boolean aiming, boolean sneaking, boolean sprinting,
        boolean running, boolean prone, boolean moving, boolean inVehicle) {
        int flags = 0;
        if (aiming) {
            flags |= FLAG_AIMING;
        }
        if (sneaking) {
            flags |= FLAG_SNEAKING;
        }
        if (sprinting) {
            flags |= FLAG_SPRINTING;
        }
        if (running) {
            flags |= FLAG_RUNNING;
        }
        if (prone) {
            flags |= FLAG_PRONE;
        }
        if (moving) {
            flags |= FLAG_MOVING;
        }
        if (inVehicle) {
            flags |= FLAG_IN_VEHICLE;
        }
        return flags;
    }
}
