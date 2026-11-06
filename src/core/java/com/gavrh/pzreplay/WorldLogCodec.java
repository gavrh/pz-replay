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
    public static final long MAX_RECORDS = 2_000_000L;
    public static final long MAX_PAYLOAD_BYTES = 256L * 1024 * 1024;

    private static final int BODY_HEADER_BYTES = 16;
    private static final String ASCII8_ALLOWED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._$-";
    private static final Comparator<WorldStateRecord> ORDER =
        Comparator.comparingInt((WorldStateRecord record) -> record.target().sx())
            .thenComparingInt(record -> record.target().sy())
            .thenComparingInt(record -> record.target().sz())
            .thenComparingInt(WorldStateRecord::classId)
            .thenComparingInt(record -> record.target().orientation())
            .thenComparing(record -> record.target().type())
            .thenComparing(record -> record.target().script())
            .thenComparingLong(record -> record.target().sqlId());
    private static final Comparator<WorldLogEntry> DIFF_ORDER =
        Comparator.comparingInt((WorldLogEntry entry) -> isRemoval(entry) ? 0 : 1)
            .thenComparingInt(WorldLogCodec::phaseRank)
            .thenComparing(WorldLogEntry::record, ORDER);

    private WorldLogCodec() {
    }

    public static byte[] encodeT0(List<WorldStateRecord> records)
        throws ReplayFormatException {
        List<WorldLogEntry> entries = new ArrayList<>();
        for (WorldStateRecord record : records == null ? List.<WorldStateRecord>of() : records) {
            boolean stateRecord = record.kind() == KIND_OBJECT_STATE
                || record.kind() == KIND_VEHICLE_STATE;
            if (!stateRecord || record.classId() == CLASS_GENERIC) {
                throw new ReplayFormatException("T0 sample contains a non-state record");
            }
            entries.add(new WorldLogEntry(record, recordFlags(record)));
        }
        return encodeEntries(entries, 1L, 0L,
            Comparator.comparing(WorldLogEntry::record, ORDER));
    }

    public static byte[] encodeDiff(List<WorldLogEntry> entries, long firstSequence,
        long timeMillis) throws ReplayFormatException {
        return encodeEntries(entries, firstSequence, timeMillis, DIFF_ORDER);
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

    public static List<WorldLogRecord> decodeAll(byte[] payload) throws ReplayFormatException {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        List<WorldLogRecord> records = new ArrayList<>();
        while (buffer.hasRemaining()) {
            require(buffer, 4);
            long bodyLength = uint32(buffer);
            if (bodyLength < BODY_HEADER_BYTES || bodyLength > MAX_BODY_BYTES) {
                throw new ReplayFormatException("world log record length is invalid");
            }
            require(buffer, (int) bodyLength);
            ByteBuffer body = slice(buffer, (int) bodyLength);
            int kind = (int) uint32(body);
            long sequence = uint32(body);
            long time = body.getLong();
            Target target = readTarget(body);
            WorldStateRecord record = switch (kind) {
                case KIND_OBJECT_STATE -> decodeStateBody(body, target.target(),
                    target.classId(), target.flags());
                case KIND_VEHICLE_STATE -> decodeVehicleBody(body, target.target());
                case KIND_OBJECT_DESTROYED -> decodeObjectDestroyed(body, target);
                case KIND_OBJECT_ADDED -> decodeObjectAdded(body, target);
                default -> null;
            };
            if (record != null) {
                records.add(new WorldLogRecord(kind, sequence, time, target.flags(), record));
            }
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
            if (count > MAX_RECORDS) {
                throw new ReplayFormatException("world log record count exceeds the cap");
            }
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

    private static byte[] encodeEntries(List<WorldLogEntry> entries, long firstSequence,
        long timeMillis, Comparator<WorldLogEntry> comparator) throws ReplayFormatException {
        List<WorldLogEntry> ordered = new ArrayList<>(entries == null ? List.of() : entries);
        ordered.sort(comparator);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long sequence = firstSequence;
        for (WorldLogEntry entry : ordered) {
            byte[] body = encodeBody(entry.record(), sequence, timeMillis, entry.flags());
            writeU32(output, body.length);
            output.writeBytes(body);
            sequence++;
        }
        return output.toByteArray();
    }

    private static byte[] encodeBody(WorldStateRecord record, long sequence, long time,
        int flags) throws ReplayFormatException {
        validateTarget(record.target(), record.classId());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeU32(output, record.kind());
        writeU32(output, sequence);
        writeU64(output, time);
        writeTarget(output, record.target(), record.classId(), flags);
        if (record instanceof WorldDoorState
            || record instanceof WorldWindowState
            || record instanceof WorldBarricadeState) {
            writeStateBody(output, record);
        } else if (record instanceof WorldVehicleState vehicle) {
            int vehicleFlags = (vehicle.present() ? 0x01 : 0)
                | (vehicle.headlightsOn() ? 0x02 : 0);
            writeU8(output, vehicleFlags);
            writeF32(output, vehicle.poseX());
            writeF32(output, vehicle.poseY());
            writeF32(output, vehicle.poseZ());
            writeF32(output, vehicle.posePhysicsZ());
            writeF32(output, vehicle.quaternionX());
            writeF32(output, vehicle.quaternionY());
            writeF32(output, vehicle.quaternionZ());
            writeF32(output, vehicle.quaternionW());
        } else if (record instanceof WorldObjectDestroyed destroyed) {
            validateObjectIndex(destroyed.objectIndex());
            writeAscii8(output, destroyed.sprite(), 0, MAX_STRING_BYTES);
            writeAscii8(output, destroyed.type(), 0, MAX_STRING_BYTES);
            writeI32(output, destroyed.objectIndex());
            writeU8(output, 1);
            if (destroyed.classId() == CLASS_GENERIC) {
                if (destroyed.restoreState() != null) {
                    throw new ReplayFormatException(
                        "generic destroy record carries a restore state");
                }
            } else {
                validateStateForClass(destroyed.restoreState(), destroyed.classId());
                writeStateBody(output, destroyed.restoreState());
            }
        } else if (record instanceof WorldObjectAdded added) {
            validateObjectIndex(added.objectIndex());
            validateRange(added.addMode(), 1, 2, "add mode");
            writeAscii8(output, added.sprite(), 0, MAX_STRING_BYTES);
            writeAscii8(output, added.type(), 0, MAX_STRING_BYTES);
            writeI32(output, added.objectIndex());
            writeU8(output, added.addMode());
            if (added.classId() == CLASS_GENERIC) {
                if (added.state() != null) {
                    throw new ReplayFormatException(
                        "generic add record carries a class state");
                }
            } else {
                validateStateForClass(added.state(), added.classId());
                writeStateBody(output, added.state());
            }
        } else {
            throw new ReplayFormatException("unsupported world state record");
        }
        byte[] body = output.toByteArray();
        int payloadBytes = body.length - BODY_HEADER_BYTES;
        int cap = record.kind() == KIND_VEHICLE_STATE
            || record.kind() == KIND_OBJECT_STATE
            ? MAX_STATE_BODY_BYTES : MAX_COMPLEX_BODY_BYTES;
        if (payloadBytes > cap || body.length > MAX_BODY_BYTES) {
            throw new ReplayFormatException("world log record exceeds the cap");
        }
        return body;
    }

    private static void writeStateBody(ByteArrayOutputStream output, WorldStateRecord record)
        throws ReplayFormatException {
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
        } else {
            throw new ReplayFormatException("world state record has no class body");
        }
    }

    private static WorldStateRecord decodeObjectState(ByteBuffer body)
        throws ReplayFormatException {
        Target target = readTarget(body);
        return decodeStateBody(body, target.target(), target.classId(), target.flags());
    }

    private static WorldStateRecord decodeStateBody(ByteBuffer body, WorldTarget target,
        int classId, int recordFlags) throws ReplayFormatException {
        String sprite = readAscii8(body, 0, MAX_STRING_BYTES);
        require(body, 4);
        int objectIndex = body.getInt();
        if (classId == CLASS_DOOR) {
            require(body, 10);
            int flags = u8(body);
            int health = body.getInt();
            long keyId = uint32(body);
            boolean hasKey = u8(body) == 1;
            return new WorldDoorState(target, sprite, objectIndex,
                (flags & 0x01) != 0, (flags & 0x02) != 0, (flags & 0x04) != 0,
                health, keyId, hasKey);
        }
        if (classId == CLASS_WINDOW) {
            require(body, 2);
            int flags = u8(body);
            int permaLocked = u8(body);
            return new WorldWindowState(target, sprite, objectIndex,
                (flags & 0x01) != 0, (flags & 0x02) != 0, (flags & 0x04) != 0,
                (flags & 0x08) != 0, permaLocked);
        }
        if (classId == CLASS_BARRICADE) {
            require(body, 5);
            int material = u8(body);
            int plankCount = u8(body);
            int aggregateHealth = u16(body);
            int healthCount = u8(body);
            require(body, healthCount * 2);
            int[] health = new int[healthCount];
            for (int index = 0; index < healthCount; index++) {
                health[index] = u16(body);
            }
            return new WorldBarricadeState(target, sprite, objectIndex,
                material, plankCount, aggregateHealth, health,
                (recordFlags & FLAG_AGGREGATE_ONLY) != 0);
        }
        throw new ReplayFormatException("world object state class is invalid");
    }

    private static WorldVehicleState decodeVehicleState(ByteBuffer body)
        throws ReplayFormatException {
        Target target = readTarget(body);
        return decodeVehicleBody(body, target.target());
    }

    private static WorldVehicleState decodeVehicleBody(ByteBuffer body, WorldTarget target)
        throws ReplayFormatException {
        if (target.orientation() != 255) {
            throw new ReplayFormatException("vehicle state class is invalid");
        }
        require(body, 1 + 8 * 4);
        int flags = u8(body);
        float poseX = readFinite(body);
        float poseY = readFinite(body);
        float poseZ = readFinite(body);
        float posePhysicsZ = readFinite(body);
        float quaternionX = readFinite(body);
        float quaternionY = readFinite(body);
        float quaternionZ = readFinite(body);
        float quaternionW = readFinite(body);
        return new WorldVehicleState(target,
            (flags & 0x01) != 0, (flags & 0x02) != 0,
            poseX, poseY, poseZ, posePhysicsZ,
            quaternionX, quaternionY, quaternionZ, quaternionW);
    }

    private static WorldStateRecord decodeObjectDestroyed(ByteBuffer body, Target target)
        throws ReplayFormatException {
        String sprite = readAscii8(body, 0, MAX_STRING_BYTES);
        String type = readAscii8(body, 0, MAX_STRING_BYTES);
        require(body, 5);
        int objectIndex = body.getInt();
        int removalMode = u8(body);
        if (removalMode != 1) {
            throw new ReplayFormatException("world removal mode is invalid");
        }
        WorldStateRecord restoreState = null;
        if (target.classId() != CLASS_GENERIC) {
            restoreState = decodeStateBody(body, target.target(), target.classId(),
                target.flags());
        }
        return new WorldObjectDestroyed(target.target(), target.classId(), sprite, type,
            objectIndex, restoreState);
    }

    private static WorldStateRecord decodeObjectAdded(ByteBuffer body, Target target)
        throws ReplayFormatException {
        String sprite = readAscii8(body, 0, MAX_STRING_BYTES);
        String type = readAscii8(body, 0, MAX_STRING_BYTES);
        require(body, 5);
        int objectIndex = body.getInt();
        int addMode = u8(body);
        WorldStateRecord state = null;
        if (target.classId() != CLASS_GENERIC) {
            state = decodeStateBody(body, target.target(), target.classId(),
                target.flags());
        }
        return new WorldObjectAdded(target.target(), target.classId(), sprite, type,
            objectIndex, addMode, state);
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

    private static boolean isRemoval(WorldLogEntry entry) {
        if (entry.record().kind() == KIND_OBJECT_DESTROYED) {
            return true;
        }
        return entry.record() instanceof WorldVehicleState vehicle && !vehicle.present();
    }

    private static int phaseRank(WorldLogEntry entry) {
        boolean barricade = entry.record().classId() == CLASS_BARRICADE;
        if (isRemoval(entry)) {
            return barricade ? 0 : 1;
        }
        return barricade ? 1 : 0;
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

    private static void validateStateForClass(WorldStateRecord state, int classId)
        throws ReplayFormatException {
        if (state == null || state.classId() != classId) {
            throw new ReplayFormatException("world record state class is invalid");
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

    private static float readFinite(ByteBuffer buffer) throws ReplayFormatException {
        float value = buffer.getFloat();
        if (!Float.isFinite(value)) {
            throw new ReplayFormatException("world log float is not finite");
        }
        return value;
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
