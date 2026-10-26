package com.gavrh.pzreplay;

import java.util.UUID;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.iso.IsoCell;

public final class ReplayGhost extends IsoPlayer {
    private final String identity;

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
