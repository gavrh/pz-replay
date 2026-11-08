package com.gavrh.pzreplay;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import org.joml.Quaternionf;
import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.interfaces.BarricadeAble;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.pathfind.PolygonalMap2;
import zombie.vehicles.BaseVehicle;

final class WorldApplier {
    private static final Field WINDOW_OPEN = field(IsoWindow.class, "open");
    private static final Field WINDOW_PERMA_LOCKED = field(IsoWindow.class, "permaLocked");
    private static final Field WINDOW_CLOSED_SPRITE = field(IsoWindow.class, "closedSprite");
    private static final Field WINDOW_OPEN_SPRITE = field(IsoWindow.class, "openSprite");
    private static final Field BARRICADE_PLANK_HEALTH =
        field(IsoBarricade.class, "plankHealth");

    private final Consumer<String> warning;

    WorldApplier(Consumer<String> warning) {
        this.warning = warning;
    }

    void apply(WorldApplyCommand command, WorldApplySummary summary) {
        if (command.action() == WorldApplyCommand.Action.VEHICLE_REMOVE) {
            summary.recordedOnlyReloadRequired(command.partial());
            return;
        }
        switch (command.action()) {
            case SKIP_RECORDED_ONLY -> summary.recordedOnly(command.partial());
            case SKIP_RELOAD_REQUIRED -> summary.reloadRequired(command.partial());
            case SKIP_UNSUPPORTED -> summary.unsupported();
            case DOOR_STATE -> applyDoorState(command, summary);
            case DOOR_REMOVE -> removeDoor(command, summary);
            case DOOR_ADD -> addDoor(command, summary);
            case WINDOW_STATE -> applyWindowState(command, summary);
            case WINDOW_REMOVE -> removeWindow(command, summary);
            case WINDOW_ADD -> addWindow(command, summary);
            case BARRICADE_STATE -> applyBarricadeState(command, summary);
            case BARRICADE_REMOVE -> removeBarricade(command, summary);
            case BARRICADE_ADD -> addBarricade(command, summary);
            case VEHICLE_STATE -> applyVehicleState(command, summary);
            case GENERIC_ADD -> addGeneric(command, summary);
            case GENERIC_REMOVE -> removeGeneric(command, summary);
            default -> {
                System.out.println("[pz-replay] world apply unsupported action="
                    + command.action());
                summary.unsupported();
            }
        }
    }

    private void applyDoorState(WorldApplyCommand command, WorldApplySummary summary) {
        WorldDoorState state = (WorldDoorState) command.record();
        IsoDoor door = findDoor(state.target());
        if (door == null) {
            skipMissingOrMismatched(state, summary, "door");
            return;
        }
        applyDoor(door, state);
        applied(command, summary);
    }

    private void removeDoor(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectDestroyed destroyed = (WorldObjectDestroyed) command.record();
        IsoDoor door = findDoor(destroyed.target());
        if (door == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "door removal target is missing");
            return;
        }
        IsoGridSquare square = door.getSquare();
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "door removal square is missing");
            return;
        }
        int result = square.RemoveTileObject(door, false);
        if (result < 0) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "door removal was rejected");
            return;
        }
        applied(command, summary);
    }

    private void addDoor(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectAdded added = (WorldObjectAdded) command.record();
        IsoGridSquare square = square(added.target());
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(added), "door addition square is missing");
            return;
        }
        IsoDoor existing = findDoor(added.target());
        if (existing == null) {
            String sprite = added.sprite();
            if (sprite.isBlank()) {
                summary.missing();
                warnOnce(summary, identity(added), "door addition has no sprite");
                return;
            }
            existing = new IsoDoor(cell(), square, sprite, isNorth(added.target()));
            square.AddSpecialObject(existing, added.objectIndex());
        }
        WorldStateRecord state = added.state();
        if (state instanceof WorldDoorState doorState) {
            applyDoor(existing, doorState);
        }
        applied(command, summary);
    }

    private void applyWindowState(WorldApplyCommand command, WorldApplySummary summary) {
        WorldWindowState state = (WorldWindowState) command.record();
        IsoWindow window = findWindow(state.target());
        if (window == null) {
            skipMissingOrMismatched(state, summary, "window");
            return;
        }
        boolean partial = applyWindow(window, state, summary);
        applied(command, summary, partial);
    }

    private void removeWindow(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectDestroyed destroyed = (WorldObjectDestroyed) command.record();
        IsoWindow window = findWindow(destroyed.target());
        if (window == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "window removal target is missing");
            return;
        }
        IsoGridSquare square = window.getSquare();
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "window removal square is missing");
            return;
        }
        int result = square.RemoveTileObject(window, false);
        if (result < 0) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "window removal was rejected");
            return;
        }
        applied(command, summary);
    }

    private void addWindow(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectAdded added = (WorldObjectAdded) command.record();
        IsoGridSquare square = square(added.target());
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(added), "window addition square is missing");
            return;
        }
        IsoWindow existing = findWindow(added.target());
        boolean partial = false;
        if (existing == null) {
            String sprite = added.sprite();
            IsoSprite iso = sprite.isBlank() ? null
                : IsoSpriteManager.instance.getSprite(sprite);
            if (iso == null) {
                summary.missing();
                warnOnce(summary, identity(added), "window addition sprite is unavailable");
                return;
            }
            existing = new IsoWindow(cell(), square, iso, isNorth(added.target()));
            square.AddSpecialObject(existing, added.objectIndex());
            String actual = existing.getSpriteName();
            if (!sprite.equals(actual)) {
                summary.warn("window sprite mismatch expected=" + sprite + " actual=" + actual);
                partial = true;
            }
        }
        WorldStateRecord state = added.state();
        if (state instanceof WorldWindowState windowState) {
            partial |= applyWindow(existing, windowState, summary);
        }
        applied(command, summary, partial);
    }

    private void applyBarricadeState(WorldApplyCommand command, WorldApplySummary summary) {
        WorldBarricadeState state = (WorldBarricadeState) command.record();
        Parent parent = findParent(state.target());
        if (parent == null) {
            skipMissingOrMismatched(state, summary, "barricade parent");
            return;
        }
        IsoBarricade barricade = barricade(parent, state.target().opposite());
        if (barricade == null) {
            summary.missing();
            warnOnce(summary, identity(state), "barricade target is missing");
            return;
        }
        boolean partial = composeBarricade(parent, barricade, state, summary);
        applied(command, summary, partial);
    }

    private void removeBarricade(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectDestroyed destroyed = (WorldObjectDestroyed) command.record();
        Parent parent = findParent(destroyed.target());
        if (parent == null) {
            skipMissingOrMismatched(destroyed, summary, "barricade parent");
            return;
        }
        IsoBarricade barricade = barricade(parent, destroyed.target().opposite());
        if (barricade == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "barricade removal target is missing");
            return;
        }
        IsoGridSquare square = barricade.getSquare();
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "barricade removal square is missing");
            return;
        }
        int result = square.RemoveTileObject(barricade);
        if (result < 0) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "barricade removal was rejected");
            return;
        }
        applied(command, summary);
    }

    private void addBarricade(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectAdded added = (WorldObjectAdded) command.record();
        Parent parent = findParent(added.target());
        if (parent == null) {
            skipMissingOrMismatched(added, summary, "barricade parent");
            return;
        }
        IsoBarricade barricade = barricade(parent, added.target().opposite());
        if (barricade == null) {
            barricade = IsoBarricade.AddBarricadeToObject(parent.value(),
                added.target().opposite() == 1);
        }
        if (barricade == null) {
            summary.missing();
            warnOnce(summary, identity(added), "barricade addition failed");
            return;
        }
        WorldStateRecord state = added.state();
        boolean partial = false;
        if (state instanceof WorldBarricadeState barricadeState) {
            partial = composeBarricade(parent, barricade, barricadeState, summary);
        }
        applied(command, summary, partial);
    }

    private void applyVehicleState(WorldApplyCommand command, WorldApplySummary summary) {
        WorldVehicleState state = (WorldVehicleState) command.record();
        BaseVehicle vehicle = findVehicle(state);
        if (vehicle == null) {
            IsoCell cell = cell();
            System.out.println("[pz-replay] world vehicle missing sql="
                + state.target().sqlId() + " script=" + state.target().script()
                + " cell_vehicles=" + (cell == null ? -1 : cell.getVehicles().size()));
            summary.missing();
            warnOnce(summary, identity(state), "vehicle target is missing");
            return;
        }
        WorldVehiclePose pose;
        try {
            WorldSimulation simulation = WorldSimulation.instance;
            pose = WorldVehiclePose.of(state,
                simulation == null ? 0f : simulation.offsetX,
                simulation == null ? 0f : simulation.offsetY);
        } catch (RuntimeException exception) {
            summary.mismatched();
            warnOnce(summary, identity(state), "vehicle pose is invalid");
            return;
        }
        Transform transform = new Transform();
        vehicle.getWorldTransform(transform);
        transform.origin.set(pose.originX(), pose.originY(), pose.originZ());
        transform.setRotation(new Quaternionf(pose.quaternionX(), pose.quaternionY(),
            pose.quaternionZ(), pose.quaternionW()));
        vehicle.setWorldTransform(transform);
        vehicle.polyDirty = true;
        vehicle.setHeadlightsOn(state.headlightsOn());
        applied(command, summary);
    }

    private void addGeneric(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectAdded added = (WorldObjectAdded) command.record();
        IsoGridSquare square = square(added.target());
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(added), "generic addition square is missing");
            return;
        }
        IsoObject existing = findGeneric(added.target());
        if (existing == null) {
            String sprite = added.sprite();
            IsoSprite iso = sprite.isBlank() ? null
                : IsoSpriteManager.instance.getSprite(sprite);
            if (iso == null) {
                summary.missing();
                warnOnce(summary, identity(added), "generic addition sprite is unavailable");
                return;
            }
            existing = new IsoObject(cell(), square, iso);
            existing.setDir(engineDirection(added.target().orientation()));
            square.AddTileObject(existing, added.objectIndex());
        }
        applied(command, summary);
    }

    private void removeGeneric(WorldApplyCommand command, WorldApplySummary summary) {
        WorldObjectDestroyed destroyed = (WorldObjectDestroyed) command.record();
        IsoObject object = findGeneric(destroyed.target());
        if (object == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "generic removal target is missing");
            return;
        }
        IsoGridSquare square = object.getSquare();
        if (square == null) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "generic removal square is missing");
            return;
        }
        int result = square.transmitRemoveItemFromSquare(object);
        if (result < 0) {
            summary.missing();
            warnOnce(summary, identity(destroyed), "generic removal was rejected");
            return;
        }
        applied(command, summary);
    }

    private static void applyDoor(IsoDoor door, WorldDoorState state) {
        door.setLockedByKey(state.lockedByKey());
        door.setLocked(state.locked());
        door.setOpen(state.open());
        door.setHealth(state.health());
        if (state.hasKey()) {
            door.setKeyId((int) state.keyId());
        }
    }

    private boolean applyWindow(IsoWindow window, WorldWindowState state,
        WorldApplySummary summary) {
        boolean partial = false;
        WorldWindowOpen open = WorldWindowOpen.of(window.IsOpen(), state.open(),
            WINDOW_OPEN != null);
        if (open.strategy() == WorldWindowOpen.Strategy.REFLECTION) {
            try {
                setWindowOpen(window, state.open());
            } catch (ReflectiveOperationException | RuntimeException exception) {
                partial = true;
            }
        } else if (open.strategy() == WorldWindowOpen.Strategy.BLOCK) {
            partial = true;
        }
        window.setSmashed(state.smashed());
        window.setGlassRemoved(state.glassRemoved());
        window.setIsLocked(state.locked());
        if (state.permaLocked() == 2) {
            if (!WorldWindowPermaLock.clear(window, WINDOW_PERMA_LOCKED)) {
                partial = true;
                warnOnce(summary, WorldIdentity.of(state),
                    "window perma-lock field is unavailable");
            }
        } else {
            window.setPermaLocked(state.permaLocked() == 1);
        }
        return partial;
    }

    private static void setWindowOpen(IsoWindow window, boolean open)
        throws ReflectiveOperationException {
        if (WINDOW_OPEN == null) {
            throw new NoSuchFieldException("IsoWindow.open");
        }
        IsoGridSquare square = window.getSquare();
        window.DirtySlice();
        IsoGridSquare.setRecalcLightTime(-1.0f);
        WINDOW_OPEN.setBoolean(window, open);
        Object sprite = open
            ? (WINDOW_OPEN_SPRITE == null ? null : WINDOW_OPEN_SPRITE.get(window))
            : (WINDOW_CLOSED_SPRITE == null ? null : WINDOW_CLOSED_SPRITE.get(window));
        if (sprite instanceof IsoSprite iso) {
            window.sprite = iso;
        }
        if (square != null) {
            square.InvalidateSpecialObjectPaths();
            square.RecalcProperties();
            if (PolygonalMap2.instance != null) {
                PolygonalMap2.instance.squareChanged(square);
            }
        }
    }

    private boolean composeBarricade(Parent parent, IsoBarricade barricade,
        WorldBarricadeState state, WorldApplySummary summary) {
        int currentMaterial = material(barricade);
        int currentPlanks = barricade.getNumPlanks();
        WorldBarricadeApplyPlan plan = WorldBarricadeApplyPlan.of(state, currentMaterial,
            currentPlanks);
        if (plan.removeFirst()) {
            IsoGridSquare square = barricade.getSquare();
            if (square != null) {
                square.RemoveTileObject(barricade);
            }
            barricade = IsoBarricade.AddBarricadeToObject(parent.value(),
                state.target().opposite() == 1);
            currentMaterial = material(barricade);
            currentPlanks = barricade == null ? 0 : barricade.getNumPlanks();
        }
        if (barricade == null) {
            summary.missing();
            return false;
        }
        if (currentMaterial == 0 || plan.removeFirst()) {
            for (int index = 0; index < plan.addPlanks(); index++) {
                barricade.addPlank(null);
            }
            if (plan.addMetal()) {
                barricade.addMetal(null, null);
            }
            if (plan.addMetalBar()) {
                barricade.addMetalBar(null, null);
            }
        }
        barricade.setHealth(state.aggregateHealth());
        boolean partial = false;
        if (plan.exactPlankHealth()) {
            if (!setPlankHealth(barricade, state.plankHealth())) {
                partial = true;
            }
        }
        return partial;
    }

    private static boolean setPlankHealth(IsoBarricade barricade, int[] health) {
        if (BARRICADE_PLANK_HEALTH == null) {
            return false;
        }
        try {
            int[] target = (int[]) BARRICADE_PLANK_HEALTH.get(barricade);
            if (target == null) {
                return false;
            }
            for (int index = 0; index < target.length; index++) {
                target[index] = index < health.length ? health[index] : 0;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static int material(IsoBarricade barricade) {
        if (barricade == null) {
            return 0;
        }
        if (barricade.isMetalBar()) {
            return 3;
        }
        if (barricade.isMetal()) {
            return 2;
        }
        return barricade.getNumPlanks() > 0 ? 1 : 0;
    }

    private void skipMissingOrMismatched(WorldStateRecord record, WorldApplySummary summary,
        String name) {
        if (mismatched(record)) {
            summary.mismatched();
            warnOnce(summary, identity(record), name + " class or type does not match");
        } else {
            summary.missing();
            warnOnce(summary, identity(record), name + " target is missing");
        }
    }

    private boolean mismatched(WorldStateRecord record) {
        WorldTarget target = record.target();
        IsoGridSquare square = square(target);
        if (square == null) {
            return false;
        }
        if (record.classId() == WorldLogCodec.CLASS_DOOR) {
            return hasDoor(square);
        }
        if (record.classId() == WorldLogCodec.CLASS_WINDOW) {
            return hasWindow(square);
        }
        if (record.classId() == WorldLogCodec.CLASS_BARRICADE) {
            for (int index = 0; index < square.getObjects().size(); index++) {
                if (square.getObjects().get(index) instanceof IsoBarricade) {
                    return true;
                }
            }
            return false;
        }
        if (record.classId() == WorldLogCodec.CLASS_GENERIC) {
            for (int index = 0; index < square.getObjects().size(); index++) {
                IsoObject object = square.getObjects().get(index);
                if (object.getClass().getName().equals(target.type())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasDoor(IsoGridSquare square) {
        for (int index = 0; index < square.getObjects().size(); index++) {
            if (square.getObjects().get(index) instanceof IsoDoor) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWindow(IsoGridSquare square) {
        for (int index = 0; index < square.getObjects().size(); index++) {
            if (square.getObjects().get(index) instanceof IsoWindow) {
                return true;
            }
        }
        return false;
    }

    private IsoDoor findDoor(WorldTarget target) {
        IsoGridSquare square = square(target);
        if (square == null) {
            return null;
        }
        boolean north = isNorth(target);
        for (int index = 0; index < square.getObjects().size(); index++) {
            IsoObject object = square.getObjects().get(index);
            if (object instanceof IsoDoor door && door.getNorth() == north) {
                return door;
            }
        }
        return null;
    }

    private IsoWindow findWindow(WorldTarget target) {
        IsoGridSquare square = square(target);
        if (square == null) {
            return null;
        }
        boolean north = isNorth(target);
        for (int index = 0; index < square.getObjects().size(); index++) {
            IsoObject object = square.getObjects().get(index);
            if (object instanceof IsoWindow window && window.isNorth() == north) {
                return window;
            }
        }
        return null;
    }

    private IsoObject findGeneric(WorldTarget target) {
        IsoGridSquare square = square(target);
        if (square == null) {
            return null;
        }
        for (int index = 0; index < square.getObjects().size(); index++) {
            IsoObject object = square.getObjects().get(index);
            if (!object.getClass().getName().equals(target.type())) {
                continue;
            }
            if (object.getDir() != null
                && WorldOrientation.index(object.getDir().ordinal()) == target.orientation()) {
                return object;
            }
        }
        return null;
    }

    private Parent findParent(WorldTarget target) {
        if (target.parentClass() == WorldLogCodec.CLASS_DOOR) {
            IsoDoor door = findDoor(target);
            return door == null ? null : new Parent(door);
        }
        IsoWindow window = findWindow(target);
        return window == null ? null : new Parent(window);
    }

    private static IsoBarricade barricade(Parent parent, int opposite) {
        if (parent.value() instanceof IsoDoor door) {
            return opposite == 1 ? door.getBarricadeOnOppositeSquare()
                : door.getBarricadeOnSameSquare();
        }
        IsoWindow window = (IsoWindow) parent.value();
        return opposite == 1 ? window.getBarricadeOnOppositeSquare()
            : window.getBarricadeOnSameSquare();
    }

    private BaseVehicle findVehicle(WorldVehicleState state) {
        IsoCell cell = cell();
        if (cell == null) {
            return null;
        }
        long sqlId = state.target().sqlId();
        for (BaseVehicle vehicle : cell.getVehicles()) {
            if (vehicle == null) {
                continue;
            }
            if (Integer.toUnsignedLong(vehicle.getSqlId()) == sqlId
                && state.target().script().equals(vehicle.getScriptName())) {
                return vehicle;
            }
        }
        return null;
    }

    private static IsoCell cell() {
        return IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
    }

    private static IsoGridSquare square(WorldTarget target) {
        IsoCell cell = cell();
        return cell == null ? null : cell.getGridSquare(target.sx(), target.sy(), target.sz());
    }

    private static boolean isNorth(WorldTarget target) {
        return target.orientation() == 0;
    }

    private static int engineDirection(int orientation) {
        return WorldOrientation.engineIndex(orientation);
    }

    private void applied(WorldApplyCommand command, WorldApplySummary summary) {
        applied(command, summary, false);
    }

    private void applied(WorldApplyCommand command, WorldApplySummary summary,
        boolean partial) {
        summary.applied(command.partial() || partial);
    }

    private void warnOnce(WorldApplySummary summary, String identity, String message) {
        if (summary.warnOnce(identity, message) && warning != null) {
            warning.accept(message);
        }
    }

    private static String identity(WorldStateRecord record) {
        return WorldIdentity.of(record);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private record Parent(BarricadeAble value) {
    }
}
