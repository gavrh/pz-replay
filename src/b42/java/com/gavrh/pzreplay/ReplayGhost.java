package com.gavrh.pzreplay;

import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.iso.IsoCell;

public final class ReplayGhost extends IsoPlayer {
    public ReplayGhost(IsoCell cell, SurvivorDesc desc, int x, int y, int z) {
        super(cell, desc, x, y, z);
        setCollidable(false);
        setZombiesDontAttack(true);
        setBlockMovement(true);
        setDoRender(true);
        setSceneCulled(false);
    }

    @Override
    public void update() {
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
}
