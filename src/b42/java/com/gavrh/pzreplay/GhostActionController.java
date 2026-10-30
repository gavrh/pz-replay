package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionGroup;
import zombie.characters.action.ActionState;

final class GhostActionController {
    private static final double ATTACK_TRIGGER_SECONDS = 0.1;
    private static final Set<String> ATTACK_STATES = Set.of("melee", "ranged");
    private static final Set<String> CLIMB_STATES = Set.of("climbFence", "climbwindow",
        "climbwall", "climbrope", "climbdownrope");
    private static final Set<String> FALL_STATES = Set.of("falling", "falldown");

    private record Pending(long sequence, ReplayEvent.Kind kind, double clearAt,
        boolean releaseOnly) {
    }

    private final ReplayEventScheduler scheduler;
    private final Map<ReplayEvent.Kind, ReplayEvent> persistent =
        new EnumMap<>(ReplayEvent.Kind.class);
    private final List<Pending> pending = new ArrayList<>();
    private boolean directionOverride;
    private float direction;
    private double directionUntil;
    private boolean fallOffset;
    private float fallBaseZ;
    private double fallUntil;
    private boolean weaponSuppressed;
    private String savedWeapon;
    private boolean injected;

    GhostActionController(ReplayEventTimeline timeline) {
        scheduler = new ReplayEventScheduler(timeline);
    }

    void start(ReplayGhost ghost) {
        clear(ghost);
        List<ReplayEvent> events = scheduler.start();
        apply(ghost, events, 0.0);
        expire(ghost, 0.0);
    }

    void restart(ReplayGhost ghost) {
        start(ghost);
    }

    void advance(ReplayGhost ghost, double timeSeconds) {
        List<ReplayEvent> events = scheduler.advance(timeSeconds);
        apply(ghost, events, timeSeconds);
        applyDirection(ghost, timeSeconds);
        applyFallOffset(ghost, timeSeconds);
        expire(ghost, timeSeconds);
    }

    void seek(ReplayGhost ghost, double timeSeconds) {
        clearActions(ghost);
        List<ReplayEvent> active = scheduler.seek(timeSeconds);
        for (ReplayEvent event : active) {
            startPersistent(ghost, event);
        }
        expire(ghost, timeSeconds);
    }

    void stop(ReplayGhost ghost) {
        clear(ghost);
        scheduler.reset();
    }

    void reset(ReplayGhost ghost) {
        clear(ghost);
        scheduler.reset();
    }

    private void apply(ReplayGhost ghost, List<ReplayEvent> events, double timeSeconds) {
        for (ReplayEvent event : events) {
            if (event.kind().persistent()) {
                if (event.end()) {
                    endPersistent(ghost, event.kind());
                } else {
                    startPersistent(ghost, event);
                }
            } else {
                trigger(ghost, event, timeSeconds);
            }
        }
    }

    private void startPersistent(ReplayGhost ghost, ReplayEvent event) {
        persistent.put(event.kind(), event);
        injected = true;
        if (event.kind() == ReplayEvent.Kind.SIT) {
            boolean ground = event.detail().startsWith("ground");
            savedWeapon = ghost.GetVariable("Weapon");
            if (savedWeapon != null && !savedWeapon.isEmpty()) {
                ghost.clearVariable("Weapon");
                weaponSuppressed = true;
            }
            ghost.setSitOnGround(ground);
            ghost.setSittingOnFurniture(!ground);
            forceState(ghost, ground ? "sitonground-sitting" : "sitonfurniture");
            log("start", event, stateName(ghost));
        }
    }

    private void endPersistent(ReplayGhost ghost, ReplayEvent.Kind kind) {
        if (persistent.remove(kind) == null) {
            return;
        }
        if (kind == ReplayEvent.Kind.SIT) {
            ghost.clearVariable("SitOnFurnitureDirection");
            ghost.clearVariable("forceGetUp");
            ghost.setSitOnGround(false);
            ghost.setSittingOnFurniture(false);
            ghost.setIsResting(false);
            restoreWeapon(ghost);
            forceIdle(ghost);
            log("end", kind, 0.0f, "", stateName(ghost));
        }
    }

    private void trigger(ReplayGhost ghost, ReplayEvent event, double timeSeconds) {
        injected = true;
        switch (event.kind()) {
            case MELEE_ATTACK -> {
                triggerAttack(ghost, event, false);
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + ATTACK_TRIGGER_SECONDS, true));
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + event.effectiveDurationSeconds(), false));
            }
            case RANGED_ATTACK -> {
                triggerAttack(ghost, event, true);
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + ATTACK_TRIGGER_SECONDS, true));
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + event.effectiveDurationSeconds(), false));
            }
            case RELOAD -> {
                triggerReload(ghost, event);
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + event.effectiveDurationSeconds(), false));
            }
            case DOOR -> {
                directionOverride = true;
                direction = event.direction();
                directionUntil = timeSeconds + event.effectiveDurationSeconds();
                ghost.setDirectionAngle(direction);
            }
            case FALL -> {
                fallOffset = true;
                fallBaseZ = ghost.getZ();
                fallUntil = timeSeconds + event.effectiveDurationSeconds();
                ghost.setRagdollFall(false);
                ghost.setbFalling(true);
                ghost.setLastFallSpeed(3.0f);
                ghost.setZ(fallBaseZ + 1.0f);
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + event.effectiveDurationSeconds(), false));
            }
            case CLIMB -> {
                ghost.reportEvent(climbEvent(event.detail()));
                pending.add(new Pending(event.sequence(), event.kind(),
                    timeSeconds + event.effectiveDurationSeconds(), false));
            }
            default -> {
            }
        }
        log("start", event, stateName(ghost));
    }

    private void triggerAttack(ReplayGhost ghost, ReplayEvent event, boolean ranged) {
        String style = event.detail().isEmpty()
            ? (ranged ? "firearm" : "1handed") : event.detail();
        ghost.setVariable("Weapon", style);
        ghost.setVariable("rangedWeapon", ranged);
        if (ranged) {
            ghost.setVariable("RangedWeaponEmpty", false);
        }
        ghost.setInitiateAttack(true);
    }

    private void triggerReload(ReplayGhost ghost, ReplayEvent event) {
        String detail = event.detail().isEmpty() ? "handgun" : event.detail();
        boolean rack = detail.startsWith("rack");
        String reloadType = detail.startsWith("rack:")
            ? detail.substring("rack:".length()) : detail;
        ghost.setPerformingAnAction(true);
        ghost.setVariable("PerformingAction", "Reload");
        ghost.setVariable("WeaponReloadType", reloadType);
        ghost.setVariable("isLoading", !rack);
        ghost.setVariable("isRacking", rack);
    }

    private void expire(ReplayGhost ghost, double timeSeconds) {
        if (directionOverride && timeSeconds >= directionUntil) {
            directionOverride = false;
        }
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next();
            if (timeSeconds < entry.clearAt()) {
                continue;
            }
            iterator.remove();
            if (entry.releaseOnly()) {
                if (entry.kind() == ReplayEvent.Kind.MELEE_ATTACK
                    || entry.kind() == ReplayEvent.Kind.RANGED_ATTACK) {
                    ghost.setInitiateAttack(false);
                }
                continue;
            }
            finish(ghost, entry.kind());
        }
    }

    private void finish(ReplayGhost ghost, ReplayEvent.Kind kind) {
        switch (kind) {
            case MELEE_ATTACK, RANGED_ATTACK -> {
                ghost.setInitiateAttack(false);
                ghost.clearVariable("rangedWeapon");
                if (ATTACK_STATES.contains(stateName(ghost))) {
                    forceIdle(ghost);
                }
            }
            case RELOAD -> {
                ghost.setPerformingAnAction(false);
                ghost.clearVariable("PerformingAction");
                ghost.clearVariable("WeaponReloadType");
                ghost.clearVariable("isLoading");
                ghost.clearVariable("isRacking");
                forceIdle(ghost);
            }
            case FALL -> {
                if (fallOffset) {
                    ghost.setZ(fallBaseZ);
                    fallOffset = false;
                }
                ghost.setbFalling(false);
                if (FALL_STATES.contains(stateName(ghost))) {
                    forceIdle(ghost);
                }
            }
            case CLIMB -> {
                if (CLIMB_STATES.contains(stateName(ghost))) {
                    forceIdle(ghost);
                }
            }
            default -> {
            }
        }
        log("end", kind, 0.0f, "", stateName(ghost));
    }

    private void applyDirection(ReplayGhost ghost, double timeSeconds) {
        if (directionOverride && timeSeconds < directionUntil) {
            ghost.setDirectionAngle(direction);
        }
    }

    private void applyFallOffset(ReplayGhost ghost, double timeSeconds) {
        if (fallOffset && timeSeconds < fallUntil) {
            ghost.setZ(fallBaseZ + 1.0f);
        }
    }

    private void restoreWeapon(ReplayGhost ghost) {
        if (!weaponSuppressed) {
            return;
        }
        weaponSuppressed = false;
        if (savedWeapon != null && !savedWeapon.isEmpty()) {
            ghost.setVariable("Weapon", savedWeapon);
        }
        savedWeapon = null;
    }

    private void clear(ReplayGhost ghost) {
        clearActions(ghost, true);
        scheduler.reset();
    }

    private void clearActions(ReplayGhost ghost) {
        clearActions(ghost, false);
    }

    private void clearActions(ReplayGhost ghost, boolean force) {
        pending.clear();
        persistent.clear();
        directionOverride = false;
        fallOffset = false;
        if (ghost == null) {
            return;
        }
        ghost.setInitiateAttack(false);
        ghost.setPerformingAnAction(false);
        ghost.setbFalling(false);
        ghost.setRagdollFall(false);
        ghost.setSitOnGround(false);
        ghost.setSittingOnFurniture(false);
        ghost.setIsResting(false);
        restoreWeapon(ghost);
        ghost.clearVariable("rangedWeapon");
        ghost.clearVariable("PerformingAction");
        ghost.clearVariable("WeaponReloadType");
        ghost.clearVariable("isLoading");
        ghost.clearVariable("isRacking");
        ghost.clearVariable("SitOnFurnitureDirection");
        ghost.clearVariable("forceGetUp");
        if (force || injected) {
            forceIdle(ghost);
        }
        injected = false;
    }

    private static void forceIdle(ReplayGhost ghost) {
        forceState(ghost, "idle");
    }

    private static void forceState(ReplayGhost ghost, String state) {
        try {
            ActionContext context = ghost.getActionContext();
            if (context == null) {
                return;
            }
            ActionGroup group = context.getGroup();
            ActionState target = group == null ? null : group.findState(state);
            if (target != null) {
                context.setCurrentState(target);
            }
        } catch (RuntimeException exception) {
            System.out.println("[pz-replay] action state reset failed: " + exception);
        }
    }

    private static String stateName(ReplayGhost ghost) {
        try {
            String name = ghost.getCurrentActionContextStateName();
            return name == null ? "" : name;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String climbEvent(String detail) {
        if (detail.startsWith("window")) {
            return "EventClimbWindow";
        }
        if (detail.startsWith("wall")) {
            return "EventClimbWall";
        }
        if (detail.startsWith("rope_down") || detail.startsWith("downrope")) {
            return "EventClimbDownRope";
        }
        if (detail.startsWith("rope")) {
            return "EventClimbRope";
        }
        return "EventClimbFence";
    }

    private static void log(String phase, ReplayEvent event, String state) {
        log(phase, event.kind(), event.timeSeconds(), event.detail(), state);
    }

    private static void log(String phase, ReplayEvent.Kind kind, float time, String detail,
        String state) {
        System.out.println("[pz-replay] action event phase=" + phase
            + " kind=" + kind.id()
            + " time=" + String.format(java.util.Locale.ROOT, "%.3f", time)
            + " detail=" + detail
            + " state=" + state);
    }
}
