package com.gavrh.pzreplay;

final class WorldIdentity {
    private WorldIdentity() {
    }

    static String of(WorldStateRecord record) {
        WorldTarget target = record.target();
        return switch (record.classId()) {
            case WorldLogCodec.CLASS_DOOR, WorldLogCodec.CLASS_WINDOW ->
                record.classId() + ":" + target.sx() + ":" + target.sy() + ":"
                    + target.sz() + ":" + target.orientation();
            case WorldLogCodec.CLASS_BARRICADE ->
                record.classId() + ":" + target.sx() + ":" + target.sy() + ":"
                    + target.sz() + ":" + target.parentClass() + ":" + target.orientation()
                    + ":" + target.opposite();
            case WorldLogCodec.CLASS_VEHICLE ->
                record.classId() + ":" + target.sqlId() + ":" + target.script();
            case WorldLogCodec.CLASS_GENERIC ->
                record.classId() + ":" + target.type() + ":" + target.sx() + ":"
                    + target.sy() + ":" + target.sz() + ":" + target.orientation();
            default -> throw new IllegalArgumentException("unknown world class "
                + record.classId());
        };
    }
}
