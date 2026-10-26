package com.gavrh.pzreplay;

import me.zed_0xff.zombie_buddy.Exposer;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

@Exposer.LuaClass(name = "PZReplayGhost")
public final class ReplayGhostApi {
    private static final boolean DEBUG_ENABLED = Boolean.getBoolean("pzreplay.debug");
    private static ReplayGhost ghost;

    private ReplayGhostApi() {
    }

    public static boolean spawn() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return false;
        }
        float x = player.getX() + 1.0f;
        float y = player.getY() + 1.0f;
        float z = player.getZ();
        GhostMotionController motion = new GhostMotionController(
            ScriptedGhostPath.defaultRoute(x, y, z));
        return spawnWithMotion(motion, x, y, z, player.getDirectionAngle()) != null;
    }

    static ReplayGhost spawnWithMotion(GhostMotionController motion, float x, float y, float z,
        float facing) {
        if (ghost != null || motion == null) {
            return null;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (player == null || cell == null) {
            return null;
        }

        try {
            SurvivorDesc desc = new SurvivorDesc(player.getDescriptor());
            desc.getHumanVisual().copyFrom(player.getHumanVisual());
            desc.getWornItems().copyFrom(player.getWornItems());
            ReplayGhost created = new ReplayGhost(cell, desc, (int) Math.floor(x),
                (int) Math.floor(y), (int) Math.floor(z));
            created.setX(x);
            created.setY(y);
            created.setZ(z);
            created.setCurrentSquareFromPosition();
            created.setMovingSquare(created.getCurrentSquare());
            created.setDirectionAngle(facing);
            created.installMotion(motion);
            ghost = created;
            System.out.println("[pz-replay] replay ghost spawned identity=" + created.getIdentity());
            return created;
        } catch (Throwable t) {
            if (ghost != null) {
                try {
                    ghost.setSceneCulled(true);
                    ghost.removeFromWorld();
                } catch (Throwable ignored) {
                }
                ghost = null;
            }
            return null;
        }
    }

    public static boolean despawn() {
        ReplayGhost current = ghost;
        if (current == null) {
            return false;
        }
        ghost = null;
        try {
            current.stopMotion();
            current.setSceneCulled(true);
            current.removeFromWorld();
            current.removeFromSquare();
            if (current.getDescriptor() != null) {
                current.getDescriptor().setInstance(null);
            }
            System.out.println("[pz-replay] replay ghost despawned identity="
                + current.getIdentity());
            ReplayPlaybackApi.onGhostDespawned();
            return true;
        } catch (Throwable t) {
            ReplayPlaybackApi.onGhostDespawned();
            return false;
        }
    }

    public static boolean startMotion() {
        if (!DEBUG_ENABLED || ghost == null) {
            return false;
        }
        return ghost.startMotion();
    }

    public static boolean pauseMotion() {
        if (!DEBUG_ENABLED || ghost == null) {
            return false;
        }
        return ghost.pauseMotion();
    }

    public static boolean stopMotion() {
        if (!DEBUG_ENABLED || ghost == null) {
            return false;
        }
        return despawn();
    }

    public static boolean resetMotion() {
        if (!DEBUG_ENABLED || ghost == null) {
            return false;
        }
        return ghost.resetMotion();
    }

    public static boolean isSpawned() {
        return ghost != null;
    }

    public static String getIdentity() {
        return ghost == null ? "" : ghost.getIdentity();
    }

    public static boolean isDebugEnabled() {
        return DEBUG_ENABLED;
    }

    static ReplayGhost getGhost() {
        return ghost;
    }
}
