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
        if (ghost != null) {
            return false;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (player == null || cell == null) {
            return false;
        }

        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());

        SurvivorDesc desc = new SurvivorDesc(player.getDescriptor());
        desc.getHumanVisual().copyFrom(player.getHumanVisual());
        desc.getWornItems().copyFrom(player.getWornItems());

        try {
            ghost = new ReplayGhost(cell, desc, x, y, z);
            ghost.setX(player.getX() + 1.0f);
            ghost.setY(player.getY() + 1.0f);
            ghost.setCurrentSquareFromPosition();
            ghost.setDirectionAngle(player.getDirectionAngle());
            System.out.println("[pz-replay] replay ghost spawned");
            return true;
        } catch (Throwable t) {
            ReplayGhost failed = ghost;
            ghost = null;
            if (failed != null) {
                try {
                    failed.setSceneCulled(true);
                    failed.removeFromWorld();
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    public static boolean despawn() {
        ReplayGhost current = ghost;
        if (current == null) {
            return false;
        }
        ghost = null;
        try {
            current.setSceneCulled(true);
            current.removeFromWorld();
            if (current.getDescriptor() != null) {
                current.getDescriptor().setInstance(null);
            }
            System.out.println("[pz-replay] replay ghost despawned");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isSpawned() {
        return ghost != null;
    }
}
