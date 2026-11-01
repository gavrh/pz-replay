package com.gavrh.pzreplay;

import java.util.Locale;

public record ReplayActionContext(
    String weaponStyle,
    String attackType,
    String fireMode,
    boolean aiming,
    boolean shove,
    boolean aimAtFloor,
    boolean ranged,
    boolean rangedWeaponEmpty,
    boolean critical,
    boolean attackFromBehind
) {
    public static final int MAX_WEAPON_STYLE_LENGTH = 16;
    public static final int MAX_ATTACK_TYPE_LENGTH = 16;
    public static final int MAX_FIRE_MODE_LENGTH = 8;

    public static final int FLAG_AIMING = 1;
    public static final int FLAG_SHOVE = 1 << 1;
    public static final int FLAG_AIM_AT_FLOOR = 1 << 2;
    public static final int FLAG_RANGED = 1 << 3;
    public static final int FLAG_RANGED_WEAPON_EMPTY = 1 << 4;
    public static final int FLAG_CRITICAL = 1 << 5;
    public static final int FLAG_ATTACK_FROM_BEHIND = 1 << 6;
    public static final int KNOWN_FLAGS = FLAG_AIMING | FLAG_SHOVE | FLAG_AIM_AT_FLOOR
        | FLAG_RANGED | FLAG_RANGED_WEAPON_EMPTY | FLAG_CRITICAL
        | FLAG_ATTACK_FROM_BEHIND;

    public ReplayActionContext {
        weaponStyle = safeToken(weaponStyle, MAX_WEAPON_STYLE_LENGTH);
        attackType = safeToken(attackType, MAX_ATTACK_TYPE_LENGTH);
        fireMode = safeTokenPreservingCase(fireMode, MAX_FIRE_MODE_LENGTH);
    }

    public static ReplayActionContext none() {
        return new ReplayActionContext("", "", "", false, false, false, false, false,
            false, false);
    }

    public boolean isPresent() {
        return !weaponStyle.isEmpty()
            || !attackType.isEmpty()
            || !fireMode.isEmpty()
            || flags() != 0;
    }

    public boolean stomp() {
        return shove && aimAtFloor;
    }

    public boolean aimedShove() {
        return shove && aiming && !aimAtFloor;
    }

    public int flags() {
        int value = 0;
        if (aiming) {
            value |= FLAG_AIMING;
        }
        if (shove) {
            value |= FLAG_SHOVE;
        }
        if (aimAtFloor) {
            value |= FLAG_AIM_AT_FLOOR;
        }
        if (ranged) {
            value |= FLAG_RANGED;
        }
        if (rangedWeaponEmpty) {
            value |= FLAG_RANGED_WEAPON_EMPTY;
        }
        if (critical) {
            value |= FLAG_CRITICAL;
        }
        if (attackFromBehind) {
            value |= FLAG_ATTACK_FROM_BEHIND;
        }
        return value;
    }

    public static ReplayActionContext fromFlags(String weaponStyle, String attackType,
        String fireMode, int flags) {
        return new ReplayActionContext(
            weaponStyle,
            attackType,
            fireMode,
            (flags & FLAG_AIMING) != 0,
            (flags & FLAG_SHOVE) != 0,
            (flags & FLAG_AIM_AT_FLOOR) != 0,
            (flags & FLAG_RANGED) != 0,
            (flags & FLAG_RANGED_WEAPON_EMPTY) != 0,
            (flags & FLAG_CRITICAL) != 0,
            (flags & FLAG_ATTACK_FROM_BEHIND) != 0
        );
    }

    public static boolean isSafeToken(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isAsciiLetterOrDigit(character) && character != '_'
                && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }

    public static String safeToken(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return safeTokenPreservingCase(value.toLowerCase(Locale.ROOT), maxLength);
    }

    public static String safeTokenPreservingCase(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        StringBuilder builder = new StringBuilder(Math.min(trimmed.length(), maxLength));
        for (int index = 0; index < trimmed.length() && builder.length() < maxLength; index++) {
            char character = trimmed.charAt(index);
            if (isAsciiLetterOrDigit(character) || character == '_'
                || character == '-' || character == '.') {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= 'a' && character <= 'z')
            || (character >= 'A' && character <= 'Z')
            || (character >= '0' && character <= '9');
    }
}
