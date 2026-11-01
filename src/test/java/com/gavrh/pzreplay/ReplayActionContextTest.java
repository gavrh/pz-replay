package com.gavrh.pzreplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayActionContextTest {
    @Test
    void distinguishesShoveStompAndAimedPush() {
        ReplayActionContext shove = new ReplayActionContext("", "shove", "",
            false, true, false, false, false, false, false);
        ReplayActionContext stomp = new ReplayActionContext("", "stomp", "",
            false, true, true, false, false, false, false);
        ReplayActionContext aimed = new ReplayActionContext("handgun", "shove", "",
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
        ReplayActionContext context = new ReplayActionContext("1handed", "overhead",
            "Single", true, false, false, false, true, true, true);

        ReplayActionContext decoded = ReplayActionContext.fromFlags(
            context.weaponStyle(), context.attackType(), context.fireMode(),
            context.flags());

        assertEquals(context, decoded);
        assertTrue(decoded.rangedWeaponEmpty());
        assertTrue(decoded.critical());
        assertTrue(decoded.attackFromBehind());
    }

    @Test
    void rejectsUnknownAndOversizedTokens() {
        ReplayActionContext context = new ReplayActionContext(
            "1handed!", "overhead attack", "Full Auto Mode",
            false, false, false, false, false, false, false);

        assertEquals("1handed", context.weaponStyle());
        assertEquals("overheadattack", context.attackType());
        assertEquals("FullAuto", context.fireMode());
        assertFalse(ReplayActionContext.isSafeToken("bad token"));
        assertTrue(ReplayActionContext.isSafeToken("melee_swing-1"));
    }

    @Test
    void defaultsAreEmptyAndNotPresent() {
        ReplayActionContext none = ReplayActionContext.none();

        assertEquals(0, none.flags());
        assertFalse(none.isPresent());
        assertFalse(none.stomp());
        assertFalse(none.aimedShove());
        assertEquals(none, new ReplayActionContext(null, null, null, false, false,
            false, false, false, false, false));
    }
}
