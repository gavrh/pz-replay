package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zombie.AttackType;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionGroup;
import zombie.characters.action.ActionState;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;

final class GhostActionController {
    private static final double ATTACK_TRIGGER_SECONDS = 0.1;
    private static final Set<String> ATTACK_STATES = Set.of("melee", "ranged", "shove",
        "shoveAim", "shoveWithFirearm", "shoveWithHandgun", "stomp");
    private static final Set<String> CLIMB_STATES = Set.of("climbFence", "climbwindow",
        "climbwall", "climbrope", "climbdownrope");
    private static final Set<String> FALL_STATES = Set.of("falling", "falldown");

    private record Pending(long sequence, ActionEvent.Kind kind, double clearAt,
        boolean releaseOnly) {
    }

    private final ActionEventScheduler scheduler;
    private final Map<ActionEvent.Kind, ActionEvent> persistent =
        new EnumMap<>(ActionEvent.Kind.class);
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
    private boolean attackActive;
    private boolean attackAiming;
    private ActionEventContext activeAttackContext;

    GhostActionController(ActionEventTimeline timeline) {
        scheduler = new ActionEventScheduler(timeline);
    }

    Boolean aimOverride() {
        return attackActive ? Boolean.valueOf(attackAiming) : null;
    }

    boolean hasActiveAttack() {
        return attackActive;
    }

    void applyActiveAttack(GhostActor ghost) {
        if (ghost == null || !attackActive || activeAttackContext == null) {
            return;
        }
        ActionEventContext context = activeAttackContext;
        ghost.setIsAiming(context.aiming() || context.ranged());
        ghost.setDoShove(context.shove());
        ghost.setAimAtFloor(context.aimAtFloor());
        ghost.setCriticalHit(context.critical());
        ghost.setAttackFromBehind(context.attackFromBehind());
        ghost.setRangedWeaponEmpty(context.rangedWeaponEmpty());
        ghost.setVariable("Weapon", context.weaponStyle());
        ghost.setVariable("rangedWeapon", context.ranged());
        AttackType attackType = resolvedAttackType(context);
        ghost.setAttackType(attackType == null ? AttackType.NONE : attackType);
    }

    void start(GhostActor ghost) {
        clear(ghost);
        List<ActionEvent> events = scheduler.start();
        apply(ghost, events, 0.0);
        expire(ghost, 0.0);
    }

    void restart(GhostActor ghost) {
        start(ghost);
    }

    void advance(GhostActor ghost, double timeSeconds) {
        List<ActionEvent> events = scheduler.advance(timeSeconds);
        apply(ghost, events, timeSeconds);
        applyDirection(ghost, timeSeconds);
        applyFallOffset(ghost, timeSeconds);
        expire(ghost, timeSeconds);
    }

    void seek(GhostActor ghost, double timeSeconds) {
        clearActions(ghost);
        List<ActionEvent> active = scheduler.seek(timeSeconds);
        for (ActionEvent event : active) {
            startPersistent(ghost, event);
        }
        expire(ghost, timeSeconds);
    }

    void complete(GhostActor ghost) {
        if (pending.isEmpty() && !attackActive && !directionOverride && !fallOffset) {
            return;
        }
        for (Pending entry : pending) {
            if (!entry.releaseOnly()) {
                finish(ghost, entry.kind());
            }
        }
        pending.clear();
        directionOverride = false;
        fallOffset = false;
        if (attackActive) {
            clearAttackState(ghost);
            if (ATTACK_STATES.contains(stateName(ghost))) {
                forceIdle(ghost);
            }
        }
    }

    void stop(GhostActor ghost) {
        clear(ghost);
        scheduler.reset();
    }

    void reset(GhostActor ghost) {
        clear(ghost);
        scheduler.reset();
    }

    private void apply(GhostActor ghost, List<ActionEvent> events, double timeSeconds) {
        for (ActionEvent event : events) {
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

    private void startPersistent(GhostActor ghost, ActionEvent event) {
        persistent.put(event.kind(), event);
        injected = true;
        if (event.kind() == ActionEvent.Kind.SIT) {
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

    private void endPersistent(GhostActor ghost, ActionEvent.Kind kind) {
        if (persistent.remove(kind) == null) {
            return;
        }
        if (kind == ActionEvent.Kind.SIT) {
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

    private void trigger(GhostActor ghost, ActionEvent event, double timeSeconds) {
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

    private void triggerAttack(GhostActor ghost, ActionEvent event, boolean ranged) {
        ActionEventContext context = event.context();
        boolean hasContext = context != null && context.isPresent();
        boolean rangedAttack = hasContext ? context.ranged() : ranged;
        String style = hasContext ? context.weaponStyle() : event.detail();
        if (!hasContext && style.isEmpty()) {
            style = rangedAttack ? "firearm" : "1handed";
        }
        ghost.setVariable("Weapon", style);
        ghost.setVariable("rangedWeapon", rangedAttack);
        ghost.setInitiateAttack(true);
        if (hasContext) {
            boolean aiming = context.aiming() || context.ranged();
            ghost.setIsAiming(aiming);
            ghost.setDoShove(context.shove());
            ghost.setAimAtFloor(context.aimAtFloor());
            ghost.setCriticalHit(context.critical());
            ghost.setAttackFromBehind(context.attackFromBehind());
            ghost.setRangedWeaponEmpty(context.rangedWeaponEmpty());
            AttackType attackType = resolvedAttackType(context);
            ghost.setAttackType(attackType == null ? AttackType.NONE : attackType);
            applyFireMode(ghost, context.fireMode());
            attackAiming = aiming;
            activeAttackContext = context;
        } else {
            if (rangedAttack) {
                ghost.setVariable("RangedWeaponEmpty", false);
            }
            attackAiming = ghost.isAiming();
            activeAttackContext = null;
        }
        attackActive = true;
    }

    private void triggerReload(GhostActor ghost, ActionEvent event) {
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

    private static void applyFireMode(GhostActor ghost, String fireMode) {
        if (fireMode == null || fireMode.isEmpty()) {
            return;
        }
        InventoryItem item = ghost.getPrimaryHandItem();
        if (item instanceof HandWeapon weapon) {
            try {
                weapon.setFireMode(fireMode);
            } catch (RuntimeException exception) {
                System.out.println("[pz-replay] fire mode could not be applied: " + exception);
            }
        }
    }

    private static AttackType resolvedAttackType(ActionEventContext context) {
        AttackType resolved = attackType(context.attackType());
        if (resolved == null && context.ranged()) {
            return AttackType.SHOT;
        }
        return resolved;
    }

    private static AttackType attackType(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "bite" -> AttackType.BITE;
            case "charge" -> AttackType.CHARGE;
            case "default" -> AttackType.DEFAULT;
            case "grapplegrab" -> AttackType.GRAPPLE_GRAB;
            case "meleestab" -> AttackType.MELEE_STAB;
            case "meleestabtofloor" -> AttackType.MELEE_STAB_TO_FLOOR;
            case "meleeswing" -> AttackType.MELEE_SWING;
            case "meleetofloor" -> AttackType.MELEE_TO_FLOOR;
            case "miss" -> AttackType.MISS;
            case "none" -> AttackType.NONE;
            case "overhead" -> AttackType.OVERHEAD;
            case "shot" -> AttackType.SHOT;
            case "shove" -> AttackType.SHOVE;
            case "spearstab" -> AttackType.SPEAR_STAB;
            case "stomp" -> AttackType.STOMP;
            case "uppercut" -> AttackType.UPPERCUT;
            default -> null;
        };
    }

    private void expire(GhostActor ghost, double timeSeconds) {
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
                if (entry.kind() == ActionEvent.Kind.MELEE_ATTACK
                    || entry.kind() == ActionEvent.Kind.RANGED_ATTACK) {
                    ghost.setInitiateAttack(false);
                }
                continue;
            }
            finish(ghost, entry.kind());
        }
    }

    private void finish(GhostActor ghost, ActionEvent.Kind kind) {
        switch (kind) {
            case MELEE_ATTACK, RANGED_ATTACK -> {
                ghost.setInitiateAttack(false);
                clearAttackState(ghost);
                attackActive = false;
                attackAiming = false;
                activeAttackContext = null;
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

    private void applyDirection(GhostActor ghost, double timeSeconds) {
        if (directionOverride && timeSeconds < directionUntil) {
            ghost.setDirectionAngle(direction);
        }
    }

    private void applyFallOffset(GhostActor ghost, double timeSeconds) {
        if (fallOffset && timeSeconds < fallUntil) {
            ghost.setZ(fallBaseZ + 1.0f);
        }
    }

    private static void clearAttackState(GhostActor ghost) {
        ghost.setAttackType(AttackType.NONE);
        ghost.setDoShove(false);
        ghost.setAimAtFloor(false);
        ghost.setCriticalHit(false);
        ghost.setAttackFromBehind(false);
        ghost.setRangedWeaponEmpty(false);
        ghost.clearVariable("rangedWeapon");
    }

    private void restoreWeapon(GhostActor ghost) {
        if (!weaponSuppressed) {
            return;
        }
        weaponSuppressed = false;
        if (savedWeapon != null && !savedWeapon.isEmpty()) {
            ghost.setVariable("Weapon", savedWeapon);
        }
        savedWeapon = null;
    }

    private void clear(GhostActor ghost) {
        clearActions(ghost, true);
        scheduler.reset();
    }

    private void clearActions(GhostActor ghost) {
        clearActions(ghost, false);
    }

    private void clearActions(GhostActor ghost, boolean force) {
        pending.clear();
        persistent.clear();
        directionOverride = false;
        fallOffset = false;
        attackActive = false;
        attackAiming = false;
        activeAttackContext = null;
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
        clearAttackState(ghost);
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

    private static void forceIdle(GhostActor ghost) {
        forceState(ghost, "idle");
    }

    private static void forceState(GhostActor ghost, String state) {
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

    private static String stateName(GhostActor ghost) {
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

    private static void log(String phase, ActionEvent event, String state) {
        log(phase, event.kind(), event.timeSeconds(), event.detail(), state);
    }

    private static void log(String phase, ActionEvent.Kind kind, float time, String detail,
        String state) {
        System.out.println("[pz-replay] action event phase=" + phase
            + " kind=" + kind.id()
            + " time=" + String.format(java.util.Locale.ROOT, "%.3f", time)
            + " detail=" + detail
            + " state=" + state);
    }
}
