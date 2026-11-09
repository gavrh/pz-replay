package com.gavrh.pzreplay;

import java.lang.reflect.Field;
import zombie.GameWindow;
import zombie.gameStates.GameLoadingState;
import zombie.gameStates.GameState;

final class LoadingGate {
    private static final Field LOADING_DONE = field(GameLoadingState.class, "done");
    private static final Field CLICK_TO_SKIP = field(GameLoadingState.class, "showedClickToSkip");
    private static final Field FORCE_DONE = field(GameLoadingState.class, "forceDone");
    private static final LoadingGateClicker CLICKER = new LoadingGateClicker();
    private static final long POLL_MILLIS = 50L;

    private static Thread worker;

    private LoadingGate() {
    }

    static synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(LoadingGate::run, "pz-replay-loading-gate");
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized void tick(boolean contextLoadActive) {
        boolean gatePresent = contextLoadActive && gatePresent();
        LoadingGateClicker.Action action = CLICKER.update(contextLoadActive, gatePresent,
            System.currentTimeMillis());
        apply(action);
    }

    static synchronized void clear() {
        apply(CLICKER.clear());
    }

    private static void run() {
        while (true) {
            LoadApi.guardVehicleWindow();
            tick(LoadApi.isContextLoading());
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean gatePresent() {
        GameState current = current();
        if (!(current instanceof GameLoadingState)) {
            return false;
        }
        return booleanValue(LOADING_DONE) && booleanValue(CLICK_TO_SKIP);
    }

    private static boolean booleanValue(Field field) {
        try {
            return field != null && field.getBoolean(null);
        } catch (IllegalAccessException | RuntimeException exception) {
            return false;
        }
    }

    private static void apply(LoadingGateClicker.Action action) {
        if (action == LoadingGateClicker.Action.PRESS) {
            if (press()) {
                System.out.println("[pz-replay] context loading gate click");
                MeasurementLogStore.event(MeasurementLog.EVENT_LOADING_GATE_CLICK, "");
            }
        } else if (action == LoadingGateClicker.Action.RELEASE) {
            release();
        }
    }

    private static boolean press() {
        GameState current = current();
        if (!(current instanceof GameLoadingState)) {
            return false;
        }
        if (FORCE_DONE == null) {
            System.out.println("[pz-replay] context loading gate force field unavailable");
            return false;
        }
        try {
            FORCE_DONE.setBoolean(current, true);
            return true;
        } catch (IllegalAccessException | RuntimeException exception) {
            System.out.println("[pz-replay] context loading gate press failed: "
                + exception.getMessage());
            return false;
        }
    }

    private static void release() {
        GameState current = current();
        if (!(current instanceof GameLoadingState) || FORCE_DONE == null) {
            return;
        }
        try {
            FORCE_DONE.setBoolean(current, false);
        } catch (IllegalAccessException | RuntimeException exception) {
            return;
        }
    }

    private static GameState current() {
        return GameWindow.states == null ? null : GameWindow.states.current;
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
}
