package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorldLogCodec {
    public static final int KIND_OBJECT_STATE = 1;
    public static final int KIND_VEHICLE_STATE = 2;
    public static final int KIND_OBJECT_MOVED = 3;
    public static final int KIND_OBJECT_DESTROYED = 4;
    public static final int KIND_OBJECT_ADDED = 5;
    public static final int KIND_CHECKPOINT = 6;

    public static final int CLASS_DOOR = 1;
    public static final int CLASS_WINDOW = 2;
    public static final int CLASS_BARRICADE = 3;
    public static final int CLASS_VEHICLE = 4;
    public static final int CLASS_GENERIC = 5;

    public static final int FLAG_RECORDED_ONLY = 0x0001;
    public static final int FLAG_PARTIAL = 0x0002;
    public static final int FLAG_AGGREGATE_ONLY = 0x0004;
    public static final int FLAG_RELOAD_REQUIRED = 0x0008;

    public static final int MAX_BODY_BYTES = 65_535;
    public static final int MAX_STATE_BODY_BYTES = 512;
    public static final int MAX_COMPLEX_BODY_BYTES = 768;
    public static final int MAX_STRING_BYTES = 96;

    private static final int BODY_HEADER_BYTES = 16;
    private static final String ASCII8_ALLOWED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._$-";
    private static final Comparator<WorldStateRecord> ORDER =
        Comparator.comparingInt((WorldStateRecord record) -> record.target().sx())
            .thenComparingInt(record -> record.target().sy())
            .thenComparingInt(record -> record.target().sz())
            .thenComparingInt(WorldStateRecord::classId)
            .thenComparingInt(record -> record.target().orientation())
            .thenComparing(record -> record.target().script())
            .thenComparing(record -> record.target().type())
            .thenComparingLong(record -> record.target().sqlId());

    private WorldLogCodec() {
    }

    public static byte[] encodeT0(List<WorldStateRecord> records)
        throws ReplayFormatException {
        List<WorldStateRecord> ordered = new ArrayList<>(records == null ? List.of() : records);
        ordered.sort(ORDER);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long sequence = 1L;
        for (WorldStateRecord record : ordered) {
            byte[] body = encodeBody(record, sequence, 0L);
            writeU32(output, body.length);
            output.writeBytes(body);
            sequence++;
        }
        return output.toByteArray();
    }

    public static List<WorldStateRecord> decodeT0(byte[] payload) throws ReplayFormatException {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        List<WorldStateRecord> records = new ArrayList<>();
        long expectedSequence = 1L;
        long previousTime = 0L;
        while (buffer.hasRemaining()) {
            require(buffer, 4);
            long bodyLength = uint32(buffer);
            if (bodyLength < BODY_HEADER_BYTES || bodyLength > MAX_BODY_BYTES) {
                throw new ReplayFormatException("world log record length is invalid");
            }
            require(buffer, (int) bodyLength);
            ByteBuffer body = slice(buffer, (int) bodyLength);
            long kind = uint32(body);
            long sequence = uint32(body);
            long time = body.getLong();
            if (sequence != expectedSequence) {
                throw new ReplayFormatException("world log sequence is not contiguous");
            }
            if (time < previousTime) {
                throw new ReplayFormatException("world log time is decreasing");
            }
            if (time != 0L) {
                throw new ReplayFormatException("T0 sample contains a non-zero time");
            }
            if (kind == KIND_OBJECT_STATE) {
                records.add(decodeObjectState(body));
            } else if (kind == KIND_VEHICLE_STATE) {
                records.add(decodeVehicleState(body));
            } else {
                throw new ReplayFormatException("T0 sample contains an unknown record kind");
            }
            expectedSequence++;
            previousTime = time;
        }
        return List.copyOf(records);
    }

    public static int countRecords(byte[] payload) throws ReplayFormatException {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int count = 0;
        while (buffer.hasRemaining()) {
            require(buffer, 4);
            long bodyLength = uint32(buffer);
            if (bodyLength < BODY_HEADER_BYTES || bodyLength > MAX_BODY_BYTES) {
                throw new ReplayFormatException("world log record length is invalid");
            }
            require(buffer, (int) bodyLength);
            buffer.position(buffer.position() + (int) bodyLength);
            count++;
        }
        return count;
    }

    public static void validate(byte[] payload, long expectedRecords, long expectedObjects)
        throws ReplayFormatException {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long sequence = 1L;
        long previousTime = 0L;
        long count = 0L;
        long t0Objects = 0L;
        while (buffer.hasRemaining()) {
            require(buffer, 4);
            long bodyLength = uint32(buffer);
            if (bodyLength < BODY_HEADER_BYTES || bodyLength > MAX_BODY_BYTES) {
                throw new ReplayFormatException("world log record length is invalid");
            }
            require(buffer, (int) bodyLength);
            ByteBuffer body = slice(buffer, (int) bodyLength);
            long kind = uint32(body);
            long recordSequence = uint32(body);
            long time = body.getLong();
            if (recordSequence != sequence) {
                throw new ReplayFormatException("world log sequence is not contiguous");
            }
            if (time < previousTime) {
                throw new ReplayFormatException("world log time is decreasing");
            }
            int remaining = body.remaining();
            if (kind == KIND_OBJECT_STATE || kind == KIND_VEHICLE_STATE) {
                if (remaining > MAX_STATE_BODY_BYTES) {
                    throw new ReplayFormatException("world log state record exceeds the cap");
                }
                if (time == 0L) {
                    t0Objects++;
                }
            } else if (kind == KIND_OBJECT_MOVED || kind == KIND_OBJECT_DESTROYED
                || kind == KIND_OBJECT_ADDED) {
                if (remaining > MAX_COMPLEX_BODY_BYTES) {
                    throw new ReplayFormatException("world log record exceeds the cap");
                }
            } else if (kind == KIND_CHECKPOINT) {
                if (remaining > 8_192) {
                    throw new ReplayFormatException("world log checkpoint exceeds the cap");
                }
            }
            count++;
            sequence++;
            previousTime = time;
        }
        if (expectedRecords >= 0L && count != expectedRecords) {
            throw new ReplayFormatException("world log count does not match the manifest");
        }
        if (expectedObjects >= 0L && t0Objects != expectedObjects) {
            throw new ReplayFormatException("world log T0 count does not match the manifest");
        }
    }

    private static byte[] encodeBody(WorldStateRecord record, long sequence, long time)
        throws ReplayFormatException {
        validateTarget(record.target(), record.classId());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeU32(output, record.kind());
        writeU32(output, sequence);
        writeU64(output, time);
        writeTarget(output, record.target(), record.classId(), recordFlags(record));
        if (record instanceof WorldDoorState door) {
            validateObjectIndex(door.objectIndex());
            validateRange(door.health(), 0, 100_000, "door health");
            validateRange(door.keyId(), 0L, 0xFFFFFFFFL, "door key id");
            writeAscii8(output, door.sprite(), 0, MAX_STRING_BYTES);
            writeI32(output, door.objectIndex());
            int flags = (door.locked() ? 0x01 : 0)
                | (door.lockedByKey() ? 0x02 : 0)
                | (door.open() ? 0x04 : 0);
            writeU8(output, flags);
            writeI32(output, door.health());
            writeU32(output, door.keyId());
            writeU8(output, door.hasKey() ? 1 : 0);
        } else if (record instanceof WorldWindowState window) {
            validateObjectIndex(window.objectIndex());
            validateRange(window.permaLocked(), 0, 2, "window perma lock");
            writeAscii8(output, window.sprite(), 0, MAX_STRING_BYTES);
            writeI32(output, window.objectIndex());
            int flags = (window.open() ? 0x01 : 0)
                | (window.smashed() ? 0x02 : 0)
                | (window.locked() ? 0x04 : 0)
                | (window.glassRemoved() ? 0x08 : 0);
            writeU8(output, flags);
            writeU8(output, window.permaLocked());
        } else if (record instanceof WorldBarricadeState barricade) {
            validateObjectIndex(barricade.objectIndex());
            validateRange(barricade.material(), 1, 3, "barricade material");
            validateRange(barricade.plankCount(), 0, 4, "barricade plank count");
            validateRange(barricade.aggregateHealth(), 0, 65_535,
                "barricade aggregate health");
            writeAscii8(output, barricade.sprite(), 0, MAX_STRING_BYTES);
            writeI32(output, barricade.objectIndex());
            writeU8(output, barricade.material());
            writeU8(output, barricade.plankCount());
            writeU16(output, barricade.aggregateHealth());
            int[] health = barricade.plankHealth();
            if (health.length > 4) {
                throw new ReplayFormatException("barricade plank health exceeds the cap");
            }
            writeU8(output, health.length);
            for (int value : health) {
                validateRange(value, 0, 65_535, "barricade plank health");
                writeU16(output, value);
            }
        } else if (record instanceof WorldVehicleState vehicle) {
            int flags = (vehicle.present() ? 0x01 : 0)
                | (vehicle.headlightsOn() ? 0x02 : 0);
            writeU8(output, flags);
            writeF32(output, vehicle.poseX());
            writeF32(output, vehicle.poseY());
            writeF32(output, vehicle.poseZ());
            writeF32(output, vehicle.posePhysicsZ());
            writeF32(output, vehicle.quaternionX());
            writeF32(output, vehicle.quaternionY());
            writeF32(output, vehicle.quaternionZ());
            writeF32(output, vehicle.quaternionW());
        } else {
            throw new ReplayFormatException("unsupported world state record");
        }
        byte[] body = output.toByteArray();
        int payloadBytes = body.length - BODY_HEADER_BYTES;
        int cap = record.kind() == KIND_VEHICLE_STATE || record.kind() == KIND_OBJECT_STATE
            ? MAX_STATE_BODY_BYTES : MAX_COMPLEX_BODY_BYTES;
        if (payloadBytes > cap || body.length > MAX_BODY_BYTES) {
            throw new ReplayFormatException("world log record exceeds the cap");
        }
        return body;
    }

    private static WorldStateRecord decodeObjectState(ByteBuffer body) throws ReplayFormatException {
        Target target = readTarget(body);
        int classId = target.classId();
        String sprite = readAscii8(body, 0, MAX_STRING_BYTES);
        int objectIndex = body.getInt();
        if (classId == CLASS_DOOR) {
            int flags = u8(body);
            int health = body.getInt();
            long keyId = uint32(body);
            boolean hasKey = u8(body) == 1;
            return new WorldDoorState(target.target(), sprite, objectIndex,
                (flags & 0x01) != 0, (flags & 0x02) != 0, (flags & 0x04) != 0,
                health, keyId, hasKey);
        }
        if (classId == CLASS_WINDOW) {
            int flags = u8(body);
            int permaLocked = u8(body);
            return new WorldWindowState(target.target(), sprite, objectIndex,
                (flags & 0x01) != 0, (flags & 0x02) != 0, (flags & 0x04) != 0,
                (flags & 0x08) != 0, permaLocked);
        }
        if (classId == CLASS_BARRICADE) {
            int material = u8(body);
            int plankCount = u8(body);
            int aggregateHealth = u16(body);
            int healthCount = u8(body);
            int[] health = new int[healthCount];
            for (int index = 0; index < healthCount; index++) {
                health[index] = u16(body);
            }
            return new WorldBarricadeState(target.target(), sprite, objectIndex,
                material, plankCount, aggregateHealth, health,
                (target.flags() & FLAG_AGGREGATE_ONLY) != 0);
        }
        throw new ReplayFormatException("world object state class is invalid");
    }

    private static WorldStateRecord decodeVehicleState(ByteBuffer body)
        throws ReplayFormatException {
        Target target = readTarget(body);
        if (target.classId() != CLASS_VEHICLE) {
            throw new ReplayFormatException("vehicle state class is invalid");
        }
        int flags = u8(body);
        return new WorldVehicleState(target.target(),
            (flags & 0x01) != 0, (flags & 0x02) != 0,
            body.getFloat(), body.getFloat(), body.getFloat(), body.getFloat(),
            body.getFloat(), body.getFloat(), body.getFloat(), body.getFloat());
    }

    private static Target readTarget(ByteBuffer body) throws ReplayFormatException {
        require(body, 13);
        int classId = u8(body);
        int flags = u16(body);
        int sx = body.getInt();
        int sy = body.getInt();
        int sz = body.get();
        int orientation = u8(body);
        int parentClass = 0;
        int opposite = 0;
        long sqlId = 0L;
        String script = "";
        String type = "";
        if (classId == CLASS_BARRICADE) {
            require(body, 2);
            parentClass = u8(body);
            opposite = u8(body);
        } else if (classId == CLASS_VEHICLE) {
            require(body, 8);
            sqlId = body.getLong();
            script = readAscii8(body, 1, 64);
        } else if (classId == CLASS_GENERIC) {
            type = readAscii8(body, 1, MAX_STRING_BYTES);
        } else if (classId != CLASS_DOOR && classId != CLASS_WINDOW) {
            throw new ReplayFormatException("world log class is invalid");
        }
        WorldTarget target = new WorldTarget(sx, sy, sz, orientation, parentClass, opposite,
            sqlId, script, type);
        validateTarget(target, classId);
        return new Target(target, classId, flags);
    }

    private static int recordFlags(WorldStateRecord record) {
        if (record instanceof WorldBarricadeState barricade && barricade.aggregateOnly()) {
            return FLAG_AGGREGATE_ONLY;
        }
        return 0;
    }

    private static void writeTarget(ByteArrayOutputStream output, WorldTarget target,
        int classId, int flags) throws ReplayFormatException {
        writeU8(output, classId);
        writeU16(output, flags);
        writeI32(output, target.sx());
        writeI32(output, target.sy());
        writeU8(output, target.sz());
        writeU8(output, target.orientation());
        if (classId == CLASS_BARRICADE) {
            writeU8(output, target.parentClass());
            writeU8(output, target.opposite());
        } else if (classId == CLASS_VEHICLE) {
            writeU64(output, target.sqlId());
            writeAscii8(output, target.script(), 1, 64);
        } else if (classId == CLASS_GENERIC) {
            writeAscii8(output, target.type(), 1, MAX_STRING_BYTES);
        }
    }

    private static void validateObjectIndex(int index) throws ReplayFormatException {
        validateRange(index, -1, 1_048_575, "object index");
    }

    private static void validateRange(long value, long minimum, long maximum, String name)
        throws ReplayFormatException {
        if (value < minimum || value > maximum) {
            throw new ReplayFormatException(name + " is out of range");
        }
    }

    private static void validateTarget(WorldTarget target, int classId)
        throws ReplayFormatException {
        if (target.sx() < 0 || target.sx() > 1_048_575
            || target.sy() < 0 || target.sy() > 1_048_575
            || target.sz() < -32 || target.sz() > 31) {
            throw new ReplayFormatException("world target square is invalid");
        }
        if (classId == CLASS_VEHICLE) {
            if (target.orientation() != 255) {
                throw new ReplayFormatException("vehicle orientation is invalid");
            }
            if (target.sqlId() < 1L) {
                throw new ReplayFormatException("vehicle sql id is invalid");
            }
            validateString(target.script(), 1, 64);
        } else {
            if (target.orientation() < 0 || target.orientation() > 7) {
                throw new ReplayFormatException("world target orientation is invalid");
            }
        }
        if (classId == CLASS_BARRICADE) {
            if (target.parentClass() != CLASS_DOOR && target.parentClass() != CLASS_WINDOW) {
                throw new ReplayFormatException("barricade parent class is invalid");
            }
            if (target.opposite() < 0 || target.opposite() > 1) {
                throw new ReplayFormatException("barricade side is invalid");
            }
        }
        if (classId == CLASS_GENERIC) {
            validateString(target.type(), 1, MAX_STRING_BYTES);
        }
        if (classId < CLASS_DOOR || classId > CLASS_GENERIC) {
            throw new ReplayFormatException("world target class is invalid");
        }
    }

    private static void validateString(String value, int minimum, int maximum)
        throws ReplayFormatException {
        if (value == null) {
            throw new ReplayFormatException("world log string is missing");
        }
        int length = value.getBytes(StandardCharsets.US_ASCII).length;
        if (length < minimum || length > maximum) {
            throw new ReplayFormatException("world log string is outside the cap");
        }
        for (int index = 0; index < value.length(); index++) {
            if (ASCII8_ALLOWED.indexOf(value.charAt(index)) < 0) {
                throw new ReplayFormatException("world log string contains an invalid byte");
            }
        }
    }

    private static void writeAscii8(ByteArrayOutputStream output, String value, int minimum,
        int maximum) throws ReplayFormatException {
        validateString(value, minimum, maximum);
        output.write(value.length());
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String readAscii8(ByteBuffer buffer, int minimum, int maximum)
        throws ReplayFormatException {
        require(buffer, 1);
        int length = u8(buffer);
        if (length < minimum || length > maximum) {
            throw new ReplayFormatException("world log string is outside the cap");
        }
        require(buffer, length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String value = new String(bytes, StandardCharsets.US_ASCII);
        validateString(value, minimum, maximum);
        return value;
    }

    private static void require(ByteBuffer buffer, int length) throws ReplayFormatException {
        if (buffer.remaining() < length) {
            throw new ReplayFormatException("world log record is truncated");
        }
    }

    private static ByteBuffer slice(ByteBuffer buffer, int length) {
        ByteBuffer slice = buffer.slice().order(ByteOrder.BIG_ENDIAN);
        slice.limit(length);
        buffer.position(buffer.position() + length);
        return slice;
    }

    private static long uint32(ByteBuffer buffer) {
        return buffer.getInt() & 0xFFFFFFFFL;
    }

    private static int u8(ByteBuffer buffer) {
        return buffer.get() & 0xFF;
    }

    private static int u16(ByteBuffer buffer) {
        return buffer.getShort() & 0xFFFF;
    }

    private static void writeU8(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >>> 24) & 0xFF));
        output.write((int) ((value >>> 16) & 0xFF));
        output.write((int) ((value >>> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    private static void writeU64(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) ((value >>> shift) & 0xFF));
        }
    }

    private static void writeI32(ByteArrayOutputStream output, int value) {
        writeU32(output, value & 0xFFFFFFFFL);
    }

    private static void writeF32(ByteArrayOutputStream output, float value)
        throws ReplayFormatException {
        if (!Float.isFinite(value)) {
            throw new ReplayFormatException("world log float is not finite");
        }
        writeU32(output, Float.floatToIntBits(value) & 0xFFFFFFFFL);
    }

    private record Target(WorldTarget target, int classId, int flags) {
    }
}
