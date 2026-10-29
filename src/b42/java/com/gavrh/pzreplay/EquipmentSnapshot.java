package com.gavrh.pzreplay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.function.Consumer;
import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.iso.IsoWorld;

final class EquipmentSnapshot {
    private static final int MAX_BYTES = 128 * 1024;
    private static final int MAX_ITEM_BYTES = 64 * 1024;
    private static final int FLAG_PRIMARY = 1;
    private static final int FLAG_SECONDARY = 1 << 1;
    private static final int FLAG_SHARED = 1 << 2;

    private record ItemData(String fullType, String staticModel, int modelIndex,
        byte[] visual) {
    }

    private final ItemData primary;
    private final ItemData secondary;
    private final boolean shared;

    private EquipmentSnapshot(ItemData primary, ItemData secondary, boolean shared) {
        this.primary = primary;
        this.secondary = secondary;
        this.shared = shared;
    }

    static ReplayEquipment capture(IsoPlayer player) throws IOException {
        InventoryItem primaryItem = player.getPrimaryHandItem();
        InventoryItem secondaryItem = player.getSecondaryHandItem();
        boolean shared = primaryItem != null && primaryItem == secondaryItem;

        ByteBuffer payload = ByteBuffer.allocate(2 * MAX_BYTES + 4096)
            .order(ByteOrder.BIG_ENDIAN);
        int flags = 0;
        if (primaryItem != null) {
            flags |= FLAG_PRIMARY;
        }
        if (secondaryItem != null && !shared) {
            flags |= FLAG_SECONDARY;
        }
        if (shared) {
            flags |= FLAG_SHARED;
        }
        payload.put((byte) flags);
        if (primaryItem != null) {
            writeItem(payload, primaryItem);
        }
        if (secondaryItem != null && !shared) {
            writeItem(payload, secondaryItem);
        }

        payload.flip();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new ReplayEquipment(ReplayEquipment.CURRENT_VERSION,
            Base64.getEncoder().encodeToString(bytes));
    }

    static EquipmentSnapshot decode(ReplayEquipment equipment) throws ReplayFormatException {
        ByteBuffer payload = ByteBuffer.wrap(equipment.decode()).order(ByteOrder.BIG_ENDIAN);
        try {
            int flags = payload.get() & 0xFF;
            if ((flags & ~(FLAG_PRIMARY | FLAG_SECONDARY | FLAG_SHARED)) != 0) {
                throw new ReplayFormatException("held-item data is corrupt or incomplete");
            }
            boolean shared = (flags & FLAG_SHARED) != 0;
            ItemData primary = (flags & FLAG_PRIMARY) != 0 ? readItem(payload) : null;
            ItemData secondary = (flags & FLAG_SECONDARY) != 0 ? readItem(payload) : null;
            if (shared) {
                if ((flags & FLAG_SECONDARY) != 0) {
                    throw new ReplayFormatException(
                        "held-item data is corrupt or incomplete");
                }
                secondary = primary;
            }
            if (payload.hasRemaining()) {
                throw new ReplayFormatException("held-item data is corrupt or incomplete");
            }
            return new EquipmentSnapshot(primary, secondary, shared);
        } catch (RuntimeException exception) {
            throw new ReplayFormatException("held-item data is corrupt or incomplete",
                exception);
        }
    }

    void applyTo(ReplayGhost ghost, Consumer<String> warning) {
        InventoryItem primaryItem = build(primary, warning);
        InventoryItem secondaryItem = shared ? primaryItem : build(secondary, warning);
        try {
            ghost.setPrimaryHandItem(primaryItem);
            ghost.setSecondaryHandItem(secondaryItem);
        } catch (RuntimeException exception) {
            warn(warning, "held items could not be applied");
            System.out.println("[pz-replay] held items could not be applied: " + exception);
        }
    }

    private static InventoryItem build(ItemData data, Consumer<String> warning) {
        if (data == null) {
            return null;
        }
        InventoryItem item;
        try {
            item = InventoryItemFactory.CreateItem(data.fullType());
        } catch (RuntimeException exception) {
            warn(warning, "held item could not be reconstructed: " + data.fullType());
            System.out.println("[pz-replay] held item could not be reconstructed type="
                + data.fullType() + " error=" + exception);
            return null;
        }
        if (item == null) {
            warn(warning, "held item is unavailable: " + data.fullType());
            System.out.println("[pz-replay] held item is unavailable type=" + data.fullType());
            return null;
        }
        try {
            if (data.modelIndex() >= 0) {
                item.setModelIndex(data.modelIndex());
            }
            if (!data.staticModel().isEmpty()) {
                item.setStaticModel(data.staticModel());
            }
            applyVisual(item, data, warning);
        } catch (IOException | RuntimeException exception) {
            warn(warning, "held-item visual was omitted: " + data.fullType());
            System.out.println("[pz-replay] held-item visual was omitted type="
                + data.fullType() + " error=" + exception);
        }
        return item;
    }

    private static void applyVisual(InventoryItem item, ItemData data,
        Consumer<String> warning) throws IOException {
        if (data.visual().length == 0) {
            return;
        }
        ItemVisual visual = item.getVisual();
        if (visual == null) {
            warn(warning, "held-item visual was omitted: " + data.fullType());
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data.visual()).order(ByteOrder.BIG_ENDIAN);
        visual.load(buffer, IsoWorld.getWorldVersion());
        if (buffer.hasRemaining()) {
            throw new IOException("held-item visual has trailing data");
        }
        item.synchWithVisual();
    }

    private static void writeItem(ByteBuffer payload, InventoryItem item) {
        GameWindow.WriteString(payload, safe(item.getFullType()));
        GameWindow.WriteString(payload, safe(item.getStaticModel()));
        payload.putInt(item.getModelIndex());
        ItemVisual visual = item.getVisual();
        if (visual == null) {
            payload.putInt(-1);
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAX_ITEM_BYTES).order(ByteOrder.BIG_ENDIAN);
        try {
            visual.save(buffer);
        } catch (IOException | RuntimeException exception) {
            payload.putInt(-1);
            return;
        }
        buffer.flip();
        payload.putInt(buffer.remaining());
        payload.put(buffer);
    }

    private static ItemData readItem(ByteBuffer payload) throws ReplayFormatException {
        String fullType = safe(GameWindow.ReadString(payload));
        String staticModel = safe(GameWindow.ReadString(payload));
        int modelIndex = payload.getInt();
        int length = payload.getInt();
        if (fullType.isEmpty()) {
            throw new ReplayFormatException("held-item data is corrupt or incomplete");
        }
        if (modelIndex < -1 || length < -1 || length > MAX_ITEM_BYTES
            || length > payload.remaining()) {
            throw new ReplayFormatException("held-item data is corrupt or incomplete");
        }
        byte[] visual = new byte[Math.max(0, length)];
        if (length > 0) {
            payload.get(visual);
        }
        return new ItemData(fullType, staticModel, modelIndex, visual);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static void warn(Consumer<String> warning, String message) {
        if (warning != null) {
            warning.accept(message);
        }
    }
}
