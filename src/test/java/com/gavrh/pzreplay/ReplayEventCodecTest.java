package com.gavrh.pzreplay;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEventCodecTest {
    private static ReplayEvent event(long sequence, float time, ReplayEvent.Kind kind,
        boolean end, String detail, float direction, float duration) {
        return new ReplayEvent(sequence, time, kind, end, detail, direction, duration);
    }

    @Test
    void roundTripsEveryFieldAndPreservesOrder() throws Exception {
        List<ReplayEvent> events = List.of(
            event(2, 0.25f, ReplayEvent.Kind.MELEE_ATTACK, false, "1handed", 45f, 1.2f),
            event(3, 0.5f, ReplayEvent.Kind.RANGED_ATTACK, false, "handgun", -10f, 0.9f),
            event(4, 0.75f, ReplayEvent.Kind.RELOAD, false, "handgun", 0f, 2.2f),
            event(5, 0.9f, ReplayEvent.Kind.DOOR, false, "door", 180f, 0.6f),
            event(6, 1.0f, ReplayEvent.Kind.SIT, false, "ground", 90f, 0f),
            event(7, 3.0f, ReplayEvent.Kind.SIT, true, "ground", 90f, 0f),
            event(8, 4.0f, ReplayEvent.Kind.FALL, false, "", 0f, 1.0f),
            event(9, 5.0f, ReplayEvent.Kind.CLIMB, false, "window", 270f, 1.5f)
        );

        ReplayEventCodec.Result result =
            ReplayEventCodec.decode(ReplayEventCodec.encode(events));

        assertEquals(events, result.events());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unknownEventTypeIsIgnoredWithAWarning() throws Exception {
        byte[] encoded = ReplayEventCodec.encode(List.of(
            event(1, 0.5f, ReplayEvent.Kind.MELEE_ATTACK, false, "1handed", 0f, 1f)));
        encoded[14] = 99;

        ReplayEventCodec.Result result = ReplayEventCodec.decode(encoded);

        assertTrue(result.events().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unknown action event type"));
    }

    @Test
    void invalidEventValuesAreIgnoredWithAWarning() throws Exception {
        byte[] encoded = ReplayEventCodec.encode(List.of(
            event(1, 0.5f, ReplayEvent.Kind.FALL, false, "", 0f, 1f)));
        int timeOffset = 2 + 4 + 4;
        java.nio.ByteBuffer.wrap(encoded).putFloat(timeOffset, Float.NaN);

        ReplayEventCodec.Result result = ReplayEventCodec.decode(encoded);

        assertTrue(result.events().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("invalid action event"));
    }

    @Test
    void truncatedPayloadIsRejected() {
        assertThrows(ReplayFormatException.class,
            () -> ReplayEventCodec.decode(new byte[] { 0, 1 }));
        assertThrows(ReplayFormatException.class,
            () -> ReplayEventCodec.decode(new byte[] { 0, 1, 0, 0, 0, 1, 0 }));
    }

    @Test
    void eventCountIsBounded() {
        byte[] encoded = new byte[6];
        java.nio.ByteBuffer.wrap(encoded).putShort(0, (short) 1)
            .putInt(2, ReplayEventCodec.MAX_EVENTS + 1);

        assertThrows(ReplayFormatException.class, () -> ReplayEventCodec.decode(encoded));
    }

    @Test
    void oversizedDetailIsTruncatedByTheModelAndStillRoundTrips() throws Exception {
        String detail = "x".repeat(ReplayEvent.MAX_DETAIL_LENGTH + 20);
        ReplayEvent event = event(1, 0f, ReplayEvent.Kind.CLIMB, false, detail, 0f, 0f);

        ReplayEvent decoded = ReplayEventCodec.decode(
            ReplayEventCodec.encode(List.of(event))).events().get(0);

        assertEquals(ReplayEvent.MAX_DETAIL_LENGTH, decoded.detail().length());
        assertEquals(event, decoded);
    }

    @Test
    void tooManyEventsCannotBeEncoded() {
        List<ReplayEvent> events = new java.util.ArrayList<>();
        for (int index = 0; index < ReplayEventCodec.MAX_EVENTS + 1; index++) {
            events.add(event(index, 0f, ReplayEvent.Kind.DOOR, false,
                Integer.toString(index), 0f, 0f));
        }

        assertThrows(ReplayFormatException.class, () -> ReplayEventCodec.encode(events));
    }
}
