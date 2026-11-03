package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEventContextTest {
    @Test
    void distinguishesShoveStompAndAimedPush() {
        ActionEventContext shove = new ActionEventContext("", "shove", "",
            false, true, false, false, false, false, false);
        ActionEventContext stomp = new ActionEventContext("", "stomp", "",
            false, true, true, false, false, false, false);
        ActionEventContext aimed = new ActionEventContext("handgun", "shove", "",
            true, true, false, true, false, false, false);

        assertTrue(shove.isPresent());
        assertTrue(shove.shove());
        assertFalse(shove.stomp());
        assertFalse(shove.aimedShove());
        assertTrue(stomp.stomp());
        assertFalse(stomp.aimedShove());
        assertTrue(aimed.aimedShove());
        assertFalse(aimed.stomp());
    }

    @Test
    void roundTripsKnownFlagsWithoutLosingWeaponOrAttackState() {
        ActionEventContext context = new ActionEventContext("1handed", "overhead",
            "Single", true, false, false, false, true, true, true);

        ActionEventContext decoded = ActionEventContext.fromFlags(
            context.weaponStyle(), context.attackType(), context.fireMode(),
            context.flags());

        assertEquals(context, decoded);
        assertTrue(decoded.rangedWeaponEmpty());
        assertTrue(decoded.critical());
        assertTrue(decoded.attackFromBehind());
    }

    @Test
    void rejectsUnknownAndOversizedTokens() {
        ActionEventContext context = new ActionEventContext(
            "1handed!", "overhead attack", "Full Auto Mode",
            false, false, false, false, false, false, false);

        assertEquals("1handed", context.weaponStyle());
        assertEquals("overheadattack", context.attackType());
        assertEquals("FullAuto", context.fireMode());
        assertFalse(ActionEventContext.isSafeToken("bad token"));
        assertTrue(ActionEventContext.isSafeToken("melee_swing-1"));
    }

    @Test
    void defaultsAreEmptyAndNotPresent() {
        ActionEventContext none = ActionEventContext.none();

        assertEquals(0, none.flags());
        assertFalse(none.isPresent());
        assertFalse(none.stomp());
        assertFalse(none.aimedShove());
        assertEquals(none, new ActionEventContext(null, null, null, false, false,
            false, false, false, false, false));
    }
}
