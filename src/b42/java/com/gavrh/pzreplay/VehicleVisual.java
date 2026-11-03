package com.gavrh.pzreplay;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import zombie.core.skinnedmodel.ModelManager;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

final class VehicleVisual {
    private static Method updateTransform;

    private final BaseVehicle vehicle;
    private final String key;
    private IsoGridSquare square;

    private VehicleVisual(BaseVehicle vehicle, String key) {
        this.vehicle = vehicle;
        this.key = key;
    }

    static VehicleVisual spawn(VehicleSegment segment, Consumer<String> warning) {
        if (segment == null || segment.scriptName().isBlank()) {
            warn(warning, "recorded vehicle type is missing");
            return null;
        }
        VehicleScript script = ScriptManager.instance.getVehicle(segment.scriptName());
        if (script == null || script.getModel() == null) {
            warn(warning, "recorded vehicle type is unavailable: " + segment.scriptName());
            return null;
        }
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) {
            warn(warning, "recorded vehicle cannot be shown without a loaded world");
            return null;
        }

        BaseVehicle vehicle = null;
        try {
            vehicle = new BaseVehicle(cell);
            vehicle.setScript(segment.scriptName());
            if (vehicle.getScript() == null) {
                warn(warning, "recorded vehicle type is unavailable: " + segment.scriptName());
                cleanup(vehicle);
                return null;
            }
            vehicle.setSkinIndex(segment.skinIndex());
            vehicle.setCollidable(false);
            vehicle.setSolid(false);
            vehicle.isStatic = true;
            vehicle.setDoRender(true);
            vehicle.savedRot.set(0.0f, 0.0f, 0.0f, 1.0f);
            vehicle.jniTransform.setRotation(vehicle.savedRot);
            VehicleVisual visual = new VehicleVisual(vehicle, segment.key());
            if (!visual.attachToSquare(cell, segment.samples().get(0).x(),
                segment.samples().get(0).y(), segment.samples().get(0).z())) {
                warn(warning, "recorded vehicle position is not loaded");
                cleanup(vehicle);
                return null;
            }
            ModelManager.instance.addVehicle(vehicle);
            if (vehicle.sprite == null || vehicle.sprite.modelSlot == null) {
                warn(warning, "recorded vehicle model is unavailable: "
                    + segment.scriptName());
                cleanup(vehicle);
                return null;
            }
            visual.updateTransform();
            return visual;
        } catch (Throwable t) {
            warn(warning, "recorded vehicle could not be shown: " + t);
            cleanup(vehicle);
            return null;
        }
    }

    String getScriptName() {
        return vehicle.getScriptName();
    }

    boolean matches(VehicleSegment segment) {
        return segment != null && key.equals(segment.key());
    }

    void apply(VehicleTimeline.State state) {
        vehicle.setX(state.x());
        vehicle.setY(state.y());
        vehicle.setZ(state.z());
        vehicle.savedRot.set(state.rotationX(), state.rotationY(), state.rotationZ(),
            state.rotationW());
        vehicle.jniTransform.origin.set(state.x(), state.z(), state.y());
        vehicle.jniTransform.setRotation(vehicle.savedRot);
        attachToSquare(vehicle.getCell(), state.x(), state.y(), state.z());
        updateTransform();
    }

    void despawn() {
        cleanup(vehicle);
    }

    private boolean attachToSquare(IsoCell cell, float x, float y, float z) {
        IsoGridSquare next = cell.getGridSquare(x, y, z);
        if (next == null) {
            if (square != null) {
                square.getStaticMovingObjects().remove(vehicle);
                square = null;
                vehicle.setSquare(null);
                vehicle.setCurrent(null);
                vehicle.setMovingSquare(null);
            }
            return false;
        }
        if (next == square) {
            return true;
        }
        if (square != null) {
            square.getStaticMovingObjects().remove(vehicle);
        }
        square = next;
        vehicle.setSquare(square);
        vehicle.setCurrent(square);
        vehicle.setMovingSquare(square);
        if (!square.getStaticMovingObjects().contains(vehicle)) {
            square.getStaticMovingObjects().add(vehicle);
        }
        return true;
    }

    private void updateTransform() {
        try {
            if (updateTransform == null) {
                updateTransform = BaseVehicle.class.getDeclaredMethod("updateTransform");
                updateTransform.setAccessible(true);
            }
            updateTransform.invoke(vehicle);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("[pz-replay] replay vehicle transform update failed: "
                + exception);
        }
    }

    private static void cleanup(BaseVehicle vehicle) {
        if (vehicle == null) {
            return;
        }
        try {
            ModelManager.instance.Remove(vehicle);
        } catch (RuntimeException exception) {
            System.out.println("[pz-replay] replay vehicle model cleanup failed: " + exception);
        }
        try {
            vehicle.releaseAnimationPlayers();
            vehicle.setDoRender(false);
            IsoGridSquare square = vehicle.getSquare();
            if (square != null) {
                square.getStaticMovingObjects().remove(vehicle);
            }
            vehicle.setSquare(null);
            vehicle.setCurrent(null);
            vehicle.setMovingSquare(null);
        } catch (RuntimeException exception) {
            System.out.println("[pz-replay] replay vehicle cleanup failed: " + exception);
        }
    }

    private static void warn(Consumer<String> warning, String message) {
        if (warning != null) {
            warning.accept(message);
        }
        System.out.println("[pz-replay] " + message);
    }
}
