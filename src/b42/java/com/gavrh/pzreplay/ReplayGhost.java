package com.gavrh.pzreplay;

import java.util.UUID;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.iso.IsoCell;

public final class ReplayGhost extends IsoPlayer {
    private final String identity;
    private GhostMotionController motion;

    public ReplayGhost(IsoCell cell, SurvivorDesc desc, int x, int y, int z) {
        super(cell, desc, x, y, z);
        identity = UUID.randomUUID().toString();
        setCollidable(false);
        setZombiesDontAttack(true);
        setBlockMovement(true);
        setDoRender(true);
        setSceneCulled(false);
    }

    public String getIdentity() {
        return identity;
    }

    void installMotion(GhostMotionController motion) {
        this.motion = motion;
    }

    boolean startMotion() {
        if (motion == null) {
            return false;
        }
        try {
            motion.start(this);
            return true;
        } catch (Throwable t) {
            failMotion(t);
            return false;
        }
    }

    boolean pauseMotion() {
        if (motion == null) {
            return false;
        }
        try {
            return motion.pause();
        } catch (Throwable t) {
            failMotion(t);
            return false;
        }
    }

    boolean resetMotion() {
        if (motion == null) {
            return false;
        }
        try {
            motion.reset(this);
            return true;
        } catch (Throwable t) {
            failMotion(t);
            return false;
        }
    }

    void stopMotion() {
        if (motion != null) {
            try {
                motion.stop(this);
            } catch (Throwable t) {
                System.out.println("[pz-replay] replay ghost motion stop failed: " + t);
            }
        }
    }

    void setScriptedMovement(boolean moving) {
        isPlayerMoving = moving;
        setJustMoved(moving);
    }

    @Override
    public void update() {
        if (motion == null) {
            return;
        }
        try {
            motion.update(this);
        } catch (Throwable t) {
            failMotion(t);
        }
    }

    @Override
    public void preupdate() {
        super.preupdate();
        setCollidable(false);
    }

    @Override
    public void postupdate() {
        super.postupdate();
        setCollidable(false);
    }

    private void failMotion(Throwable t) {
        System.out.println("[pz-replay] replay ghost motion failed: " + t);
        stopMotion();
    }
}
