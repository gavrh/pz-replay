package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEventCodecTest {
    private static ActionEvent event(long sequence, float time, ActionEvent.Kind kind,
        boolean end, String detail, float direction, float duration) {
        return new ActionEvent(sequence, time, kind, end, detail, direction, duration);
    }

    @Test
    void roundTripsEveryFieldAndPreservesOrder() throws Exception {
        ActionEventContext melee = new ActionEventContext("1handed", "overhead", "",
            true, false, false, false, false, false, false);
        ActionEventContext ranged = new ActionEventContext("handgun", "shot", "Single",
            true, false, false, true, false, false, false);
        List<ActionEvent> events = List.of(
            new ActionEvent(2, 0.25f, ActionEvent.Kind.MELEE_ATTACK, false, "1handed",
                45f, 1.2f, melee),
            new ActionEvent(3, 0.5f, ActionEvent.Kind.RANGED_ATTACK, false, "handgun",
                -10f, 0.9f, ranged),
            event(4, 0.75f, ActionEvent.Kind.RELOAD, false, "handgun", 0f, 2.2f),
            event(5, 0.9f, ActionEvent.Kind.DOOR, false, "door", 180f, 0.6f),
            event(6, 1.0f, ActionEvent.Kind.SIT, false, "ground", 90f, 0f),
            event(7, 3.0f, ActionEvent.Kind.SIT, true, "ground", 90f, 0f),
            event(8, 4.0f, ActionEvent.Kind.FALL, false, "", 0f, 1.0f),
            event(9, 5.0f, ActionEvent.Kind.CLIMB, false, "window", 270f, 1.5f)
        );

        ActionEventCodec.Result result =
            ActionEventCodec.decode(ActionEventCodec.encode(events));

        assertEquals(events, result.events());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void decodesVersionOneEventsWithoutAnimationContext() throws Exception {
        byte[] encoded = versionOneEvent(1L, 0.5f, 1, false, "1handed", 45f, 1.2f);

        ActionEventCodec.Result result = ActionEventCodec.decode(encoded);

        assertEquals(1, result.events().size());
        assertEquals(ActionEventContext.none(), result.events().get(0).context());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unknownContextFlagsKeepKnownStateAndWarn() throws Exception {
        ActionEventContext context = new ActionEventContext("knife", "miss", "",
            false, false, false, false, false, false, false);
        byte[] encoded = ActionEventCodec.encode(List.of(
            new ActionEvent(1, 0.5f, ActionEvent.Kind.MELEE_ATTACK, false, "knife",
                0f, 1f, context)));
        int flagsOffset = 2 + 4 + 4 + 4 + 1 + 1 + 4 + 4 + 2
            + "knife".getBytes(StandardCharsets.UTF_8).length;
        encoded[flagsOffset] = (byte) 0x80;

        ActionEventCodec.Result result = ActionEventCodec.decode(encoded);

        assertEquals(1, result.events().size());
        assertEquals(context, result.events().get(0).context());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unknown animation context flags"));
    }

    @Test
    void invalidContextDataDegradesTheEventWithAWarning() throws Exception {
        ActionEventContext context = new ActionEventContext("axe", "overhead", "",
            true, false, false, false, false, false, false);
        byte[] encoded = ActionEventCodec.encode(List.of(
            new ActionEvent(1, 0.5f, ActionEvent.Kind.MELEE_ATTACK, false, "axe",
                0f, 1f, context)));
        int weaponOffset = 2 + 4 + 4 + 4 + 1 + 1 + 4 + 4 + 2
            + "axe".getBytes(StandardCharsets.UTF_8).length + 4;
        encoded[weaponOffset] = (byte) 0x7F;

        ActionEventCodec.Result result = ActionEventCodec.decode(encoded);

        assertEquals(1, result.events().size());
        assertEquals(ActionEventContext.none(), result.events().get(0).context());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("invalid animation context"));
    }

    @Test
    void unknownEventTypeIsIgnoredWithAWarning() throws Exception {
        byte[] encoded = ActionEventCodec.encode(List.of(
            event(1, 0.5f, ActionEvent.Kind.MELEE_ATTACK, false, "1handed", 0f, 1f)));
        encoded[14] = 99;

        ActionEventCodec.Result result = ActionEventCodec.decode(encoded);

        assertTrue(result.events().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unknown action event type"));
    }

    @Test
    void invalidEventValuesAreIgnoredWithAWarning() throws Exception {
        byte[] encoded = ActionEventCodec.encode(List.of(
            event(1, 0.5f, ActionEvent.Kind.FALL, false, "", 0f, 1f)));
        int timeOffset = 2 + 4 + 4;
        java.nio.ByteBuffer.wrap(encoded).putFloat(timeOffset, Float.NaN);

        ActionEventCodec.Result result = ActionEventCodec.decode(encoded);

        assertTrue(result.events().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("invalid action event"));
    }

    @Test
    void truncatedPayloadIsRejected() {
        assertThrows(ReplayFormatException.class,
            () -> ActionEventCodec.decode(new byte[] { 0, 1 }));
        assertThrows(ReplayFormatException.class,
            () -> ActionEventCodec.decode(new byte[] { 0, 1, 0, 0, 0, 1, 0 }));
    }

    @Test
    void eventCountIsBounded() {
        byte[] encoded = new byte[6];
        java.nio.ByteBuffer.wrap(encoded).putShort(0, (short) 1)
            .putInt(2, ActionEventCodec.MAX_EVENTS + 1);

        assertThrows(ReplayFormatException.class, () -> ActionEventCodec.decode(encoded));
    }

    @Test
    void oversizedDetailIsTruncatedByTheModelAndStillRoundTrips() throws Exception {
        String detail = "x".repeat(ActionEvent.MAX_DETAIL_LENGTH + 20);
        ActionEvent event = event(1, 0f, ActionEvent.Kind.CLIMB, false, detail, 0f, 0f);

        ActionEvent decoded = ActionEventCodec.decode(
            ActionEventCodec.encode(List.of(event))).events().get(0);

        assertEquals(ActionEvent.MAX_DETAIL_LENGTH, decoded.detail().length());
        assertEquals(event, decoded);
    }

    private static byte[] versionOneEvent(long sequence, float time, int kind, boolean end,
        String detail, float direction, float duration) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            byte[] detailBytes = detail.getBytes(StandardCharsets.UTF_8);
            out.writeShort(1);
            out.writeInt(1);
            out.writeInt((int) sequence);
            out.writeFloat(time);
            out.writeByte(kind);
            out.writeByte(end ? 1 : 0);
            out.writeFloat(direction);
            out.writeFloat(duration);
            out.writeShort(detailBytes.length);
            out.write(detailBytes);
        }
        return bytes.toByteArray();
    }

    @Test
    void tooManyEventsCannotBeEncoded() {
        List<ActionEvent> events = new java.util.ArrayList<>();
        for (int index = 0; index < ActionEventCodec.MAX_EVENTS + 1; index++) {
            events.add(event(index, 0f, ActionEvent.Kind.DOOR, false,
                Integer.toString(index), 0f, 0f));
        }

        assertThrows(ReplayFormatException.class, () -> ActionEventCodec.encode(events));
    }
}
