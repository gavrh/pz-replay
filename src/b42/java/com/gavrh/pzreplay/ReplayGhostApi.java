package com.gavrh.pzreplay;

import me.zed_0xff.zombie_buddy.Exposer;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

@Exposer.LuaClass(name = "PZReplayGhost")
public final class ReplayGhostApi {
    private static ReplayGhost ghost;

    private ReplayGhostApi() {
    }

    public static boolean spawn() {
        if (killSwitchEnabled()) {
            return false;
        }
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
        if (ghost != null || motion == null || killSwitchEnabled()) {
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
            ghost = created;
            created.setX(x);
            created.setY(y);
            created.setZ(z);
            created.setCurrentSquareFromPosition();
            created.setMovingSquare(created.getCurrentSquare());
            created.setDirectionAngle(facing);
            created.installMotion(motion);
            System.out.println("[pz-replay] replay ghost spawned identity=" + created.getIdentity());
            return created;
        } catch (Throwable t) {
            ReplayGhost failed = ghost;
            ghost = null;
            removeGhost(failed);
            return null;
        }
    }

    private static void removeGhost(ReplayGhost current) {
        if (current == null) {
            return;
        }
        try {
            current.setSceneCulled(true);
            current.removeFromWorld();
            current.removeFromSquare();
            if (current.getDescriptor() != null) {
                current.getDescriptor().setInstance(null);
            }
        } catch (Throwable ignored) {
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
            removeGhost(current);
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
        if (!PZReplayLua.isDebugEnabled() || ghost == null) {
            return false;
        }
        return ghost.startMotion();
    }

    public static boolean pauseMotion() {
        if (!PZReplayLua.isDebugEnabled() || ghost == null) {
            return false;
        }
        return ghost.pauseMotion();
    }

    public static boolean stopMotion() {
        if (!PZReplayLua.isDebugEnabled() || ghost == null) {
            return false;
        }
        return despawn();
    }

    public static boolean resetMotion() {
        if (!PZReplayLua.isDebugEnabled() || ghost == null) {
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
        return PZReplayLua.isDebugEnabled();
    }

    static ReplayGhost getGhost() {
        return ghost;
    }

    private static boolean killSwitchEnabled() {
        return ReplaySettingsStore.get().isKillSwitch();
    }
}
