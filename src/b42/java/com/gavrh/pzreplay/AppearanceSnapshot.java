package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.iso.IsoWorld;

final class AppearanceSnapshot {
    private static final int MAX_BYTES = 128 * 1024;

    private final boolean female;
    private final String torso;
    private final byte[] humanVisual;
    private final byte[] wornVisuals;

    private AppearanceSnapshot(boolean female, String torso, byte[] humanVisual,
        byte[] wornVisuals) {
        this.female = female;
        this.torso = torso;
        this.humanVisual = humanVisual;
        this.wornVisuals = wornVisuals;
    }

    record Captured(byte[] payload, ReplayAppearance appearance) {
    }

    static ReplayAppearance capture(IsoPlayer player) throws IOException {
        return captureWithPayload(player).appearance();
    }

    static Captured captureWithPayload(IsoPlayer player) throws IOException {
        SurvivorDesc desc = player.getDescriptor();
        if (desc == null) {
            throw new IOException("no character descriptor");
        }
        ByteBuffer payload = ByteBuffer.allocate(2 * MAX_BYTES + 4096).order(ByteOrder.BIG_ENDIAN);
        payload.put((byte) (player.isFemale() ? 1 : 0));
        GameWindow.WriteString(payload, desc.getTorso() == null ? "" : desc.getTorso());

        ByteBuffer human = ByteBuffer.allocate(MAX_BYTES).order(ByteOrder.BIG_ENDIAN);
        player.getHumanVisual().save(human);
        human.flip();
        payload.putInt(human.remaining());
        payload.put(human);

        ItemVisuals visuals = new ItemVisuals();
        player.getItemVisuals(visuals);
        ByteBuffer worn = ByteBuffer.allocate(MAX_BYTES).order(ByteOrder.BIG_ENDIAN);
        visuals.save(worn);
        worn.flip();
        payload.putInt(worn.remaining());
        payload.put(worn);

        payload.flip();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        ReplayAppearance appearance = new ReplayAppearance(ReplayAppearance.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
        return new Captured(bytes, appearance);
    }

    static AppearanceSnapshot decode(ReplayAppearance appearance) throws ReplayFormatException {
        ByteBuffer payload = ByteBuffer.wrap(appearance.decode()).order(ByteOrder.BIG_ENDIAN);
        try {
            int flags = payload.get() & 0xFF;
            String torso = GameWindow.ReadString(payload);
            byte[] human = readChunk(payload, payload.getInt());
            byte[] worn = readChunk(payload, payload.getInt());
            if (payload.hasRemaining()) {
                throw new ReplayFormatException("appearance data is corrupt or incomplete");
            }
            return new AppearanceSnapshot((flags & 1) != 0, torso, human, worn);
        } catch (RuntimeException exception) {
            throw new ReplayFormatException("appearance data is corrupt or incomplete", exception);
        }
    }

    void applyTo(SurvivorDesc desc) throws IOException {
        desc.setFemale(female);
        desc.setTorso(torso);
        int worldVersion = IsoWorld.getWorldVersion();

        ByteBuffer human = ByteBuffer.wrap(humanVisual).order(ByteOrder.BIG_ENDIAN);
        desc.getHumanVisual().load(human, worldVersion);
        if (human.hasRemaining()) {
            throw new IOException("appearance data is corrupt or incomplete");
        }

        ItemVisuals visuals = new ItemVisuals();
        ByteBuffer worn = ByteBuffer.wrap(wornVisuals).order(ByteOrder.BIG_ENDIAN);
        visuals.load(worn, worldVersion);
        if (worn.hasRemaining()) {
            throw new IOException("appearance data is corrupt or incomplete");
        }
        desc.getWornItems().setFromItemVisuals(visuals);
    }

    void applyTo(ReplayGhost ghost) throws IOException {
        SurvivorDesc desc = ghost.getDescriptor();
        if (desc == null) {
            throw new IOException("no character descriptor");
        }
        if (ghost.getHumanVisual() == null) {
            throw new IOException("no character visual");
        }
        applyTo(desc);
        ghost.getHumanVisual().copyFrom(desc.getHumanVisual());
        ghost.setWornItems(desc.getWornItems());
        ghost.onWornItemsChanged();
        ghost.resetModelNextFrame();
    }

    private static byte[] readChunk(ByteBuffer buffer, int length) throws ReplayFormatException {
        if (length < 0 || length > buffer.remaining()) {
            throw new ReplayFormatException("appearance data is corrupt or incomplete");
        }
        byte[] chunk = new byte[length];
        buffer.get(chunk);
        return chunk;
    }
}
