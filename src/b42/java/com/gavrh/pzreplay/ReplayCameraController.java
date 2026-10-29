package com.gavrh.pzreplay;

import java.util.Locale;
import org.lwjglx.input.Keyboard;
import zombie.characters.IsoPlayer;
import zombie.input.GameKeyboard;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;

public final class ReplayCameraController {
    private static final float PAN_SPEED_TILES_PER_SECOND = 8.0f;
    private static final float MAX_STEP_SECONDS = 0.1f;

    private final FreeCamera freeCamera = new FreeCamera();
    private ReplayCameraMode mode = ReplayCameraMode.PLAYER;
    private ReplayCameraMode modeBeforeFree = ReplayCameraMode.PLAYER;

    public boolean setMode(ReplayCameraMode requested, ReplayGhost ghost) {
        if (requested == null) {
            return false;
        }
        IsoPlayer player = localPlayer();
        PlayerCamera camera = player == null ? null : cameraAt(player.getIndex());
        if (player == null || camera == null) {
            return false;
        }
        if (requested != ReplayCameraMode.PLAYER && ghost == null) {
            return false;
        }

        ReplayCameraMode previous = mode;
        ReplayCameraMode previousBeforeFree = modeBeforeFree;
        try {
            if (requested == ReplayCameraMode.FREE) {
                if (mode != ReplayCameraMode.FREE) {
                    modeBeforeFree = mode;
                    if (!freeCamera.moveTo(player.getX() + camera.deferedX,
                        player.getY() + camera.deferedY)) {
                        modeBeforeFree = previousBeforeFree;
                        return false;
                    }
                    freeCamera.clampTo(player.getX(), player.getY());
                    camera.deferedX = freeCamera.offsetX(player.getX());
                    camera.deferedY = freeCamera.offsetY(player.getY());
                }
            } else if (requested == ReplayCameraMode.FOLLOW_GHOST) {
                applyFollowOffset(ghost, player, camera);
            } else {
                clearCameraOffsets();
            }
            mode = requested;
            IsoCamera.setCameraCharacter(player);
            camera.center();
            logMode("mode", ghost);
            return true;
        } catch (Throwable failure) {
            mode = previous;
            modeBeforeFree = previousBeforeFree;
            if (previous != ReplayCameraMode.FREE) {
                freeCamera.reset();
            }
            restorePlayerCamera();
            if (mode == ReplayCameraMode.FOLLOW_GHOST && ghost != null) {
                applyFollowOffset(ghost, player, camera);
                camera.center();
            }
            System.out.println("[pz-replay] replay camera mode failed mode="
                + requested.id() + " error=" + failure);
            return false;
        }
    }

    public boolean setFollow(boolean enabled, ReplayGhost ghost) {
        return setMode(enabled ? ReplayCameraMode.FOLLOW_GHOST : ReplayCameraMode.PLAYER, ghost);
    }

    public boolean exitFree(ReplayGhost ghost) {
        if (mode != ReplayCameraMode.FREE) {
            return false;
        }
        ReplayCameraMode target = modeBeforeFree;
        if (target == ReplayCameraMode.FREE) {
            target = ReplayCameraMode.PLAYER;
        }
        return setMode(target, ghost);
    }

    public void update(ReplayGhost ghost, double deltaSeconds) {
        IsoPlayer player = localPlayer();
        PlayerCamera camera = player == null ? null : cameraAt(player.getIndex());
        if (player == null || camera == null) {
            return;
        }
        if (mode == ReplayCameraMode.FOLLOW_GHOST) {
            if (ghost != null) {
                applyFollowOffset(ghost, player, camera);
            }
        } else if (mode == ReplayCameraMode.FREE) {
            updateFreeCamera(player, camera, deltaSeconds);
        } else {
            camera.deferedX = 0.0f;
            camera.deferedY = 0.0f;
        }
    }

    public void restore() {
        mode = ReplayCameraMode.PLAYER;
        modeBeforeFree = ReplayCameraMode.PLAYER;
        freeCamera.reset();
        restorePlayerCamera();
        System.out.println("[pz-replay] replay camera restored mode=player");
    }

    public ReplayCameraMode getMode() {
        return mode;
    }

    public boolean isFollowing() {
        return mode == ReplayCameraMode.FOLLOW_GHOST;
    }

    public boolean isFree() {
        return mode == ReplayCameraMode.FREE;
    }

    public String status(ReplayGhost ghost) {
        IsoPlayer player = localPlayer();
        PlayerCamera camera = player == null ? null : cameraAt(player.getIndex());
        if (player == null || camera == null) {
            return "camera=" + mode.id() + " player=none";
        }
        String screen = "unknown";
        String loaded = "unknown";
        String visible = "unknown";
        if (ghost != null) {
            try {
                float screenX = IsoUtils.XToScreen(ghost.getX(), ghost.getY(), ghost.getZ(), 0)
                    - IsoCamera.frameState.offX;
                float screenY = IsoUtils.YToScreen(ghost.getX(), ghost.getY(), ghost.getZ(), 0)
                    - IsoCamera.frameState.offY;
                boolean onScreen = screenX >= 0.0f && screenY >= 0.0f
                    && screenX <= IsoCamera.frameState.offscreenWidth
                    && screenY <= IsoCamera.frameState.offscreenHeight;
                IsoGridSquare square = IsoWorld.instance == null
                    || IsoWorld.instance.currentCell == null ? null
                    : IsoWorld.instance.currentCell.getGridSquare(
                        (int) Math.floor(ghost.getX()), (int) Math.floor(ghost.getY()),
                        (int) Math.floor(ghost.getZ()));
                screen = onScreen ? "true" : "false";
                loaded = square == null ? "false" : "true";
                visible = onScreen && square != null ? "true" : "false";
            } catch (Throwable ignored) {
            }
        }
        return String.format(Locale.ROOT,
            "camera=%s offset=%.3f,%.3f player=%.3f,%.3f ghost=%s on_screen=%s"
                + " loaded=%s visible=%s",
            mode.id(), camera.deferedX, camera.deferedY, player.getX(), player.getY(),
            ghost == null ? "none" : String.format(Locale.ROOT, "%.3f,%.3f",
                ghost.getX(), ghost.getY()),
            screen, loaded, visible);
    }

    public static void restorePlayerCamera() {
        clearCameraOffsets();
        IsoPlayer player = localPlayer();
        if (player == null) {
            IsoCamera.clearCameraCharacter();
            return;
        }
        IsoCamera.setCameraCharacter(player);
        PlayerCamera camera = cameraAt(player.getIndex());
        if (camera != null) {
            camera.center();
        }
    }

    private void updateFreeCamera(IsoPlayer player, PlayerCamera camera, double deltaSeconds) {
        float delta = clampDelta(deltaSeconds);
        float inputX = 0.0f;
        float inputY = 0.0f;
        if (GameKeyboard.isKeyDown(Keyboard.KEY_LEFT)) {
            inputX -= 1.0f;
        }
        if (GameKeyboard.isKeyDown(Keyboard.KEY_RIGHT)) {
            inputX += 1.0f;
        }
        if (GameKeyboard.isKeyDown(Keyboard.KEY_UP)) {
            inputY -= 1.0f;
        }
        if (GameKeyboard.isKeyDown(Keyboard.KEY_DOWN)) {
            inputY += 1.0f;
        }
        if (inputX != 0.0f && inputY != 0.0f) {
            inputX *= 0.7071f;
            inputY *= 0.7071f;
        }
        if (inputX != 0.0f || inputY != 0.0f) {
            freeCamera.pan(inputX * PAN_SPEED_TILES_PER_SECOND * delta,
                inputY * PAN_SPEED_TILES_PER_SECOND * delta, player.getX(), player.getY());
        }
        freeCamera.clampTo(player.getX(), player.getY());
        camera.deferedX = freeCamera.offsetX(player.getX());
        camera.deferedY = freeCamera.offsetY(player.getY());
    }

    private static void applyFollowOffset(ReplayGhost ghost, IsoPlayer player,
        PlayerCamera camera) {
        camera.deferedX = ghost.getX() - player.getX();
        camera.deferedY = ghost.getY() - player.getY();
    }

    private void logMode(String action, ReplayGhost ghost) {
        System.out.println("[pz-replay] replay camera " + action + " "
            + status(ghost));
    }

    private static float clampDelta(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return 0.0f;
        }
        return (float) Math.min(deltaSeconds, MAX_STEP_SECONDS);
    }

    private static void clearCameraOffsets() {
        if (IsoCamera.cameras == null) {
            return;
        }
        for (PlayerCamera camera : IsoCamera.cameras) {
            if (camera != null) {
                camera.deferedX = 0.0f;
                camera.deferedY = 0.0f;
            }
        }
    }

    private static IsoPlayer localPlayer() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null || player instanceof ReplayGhost) {
            IsoPlayer[] players = IsoPlayer.players;
            player = players == null || players.length == 0 ? null : players[0];
        }
        return player instanceof ReplayGhost ? null : player;
    }

    private static PlayerCamera cameraAt(int index) {
        if (IsoCamera.cameras == null || index < 0 || index >= IsoCamera.cameras.length) {
            return null;
        }
        return IsoCamera.cameras[index];
    }
}
