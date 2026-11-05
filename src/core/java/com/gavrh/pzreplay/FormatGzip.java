package com.gavrh.pzreplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

final class FormatGzip {
    private static final int MAX_DECOMPRESSED_BYTES = 1024 * 1024 * 1024;

    private FormatGzip() {
    }

    static byte[] deflate(byte[] content) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(content);
        }
        return compressed.toByteArray();
    }

    static byte[] inflate(byte[] compressed) throws FormatException {
        if (compressed.length < 18) {
            throw new FormatException(FormatReason.TRUNCATED, "gzip stream is truncated");
        }
        if ((compressed[0] & 0xFF) != 0x1F || (compressed[1] & 0xFF) != 0x8B) {
            throw new FormatException(FormatReason.NOT_CONTAINER,
                "file does not start with a gzip member");
        }
        if ((compressed[2] & 0xFF) != 8) {
            throw new FormatException(FormatReason.GZIP_CRC, "unsupported gzip method");
        }
        int flags = compressed[3] & 0xFF;
        if ((flags & 0xE0) != 0) {
            throw new FormatException(FormatReason.GZIP_CRC, "reserved gzip flags are set");
        }

        int position = 10;
        if ((flags & 0x04) != 0) {
            if (position + 2 > compressed.length) {
                throw new FormatException(FormatReason.TRUNCATED, "gzip extra field is truncated");
            }
            int extra = u16(compressed, position);
            position += 2 + extra;
        }
        if ((flags & 0x08) != 0) {
            position = skipZeroTerminated(compressed, position);
        }
        if ((flags & 0x10) != 0) {
            position = skipZeroTerminated(compressed, position);
        }
        if ((flags & 0x02) != 0) {
            position += 2;
        }
        if (position < 0 || position >= compressed.length) {
            throw new FormatException(FormatReason.TRUNCATED, "gzip header is truncated");
        }

        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed, position, compressed.length - position);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsDictionary()) {
                        throw new FormatException(FormatReason.GZIP_CRC,
                            "gzip stream needs a dictionary");
                    }
                    if (inflater.needsInput()) {
                        throw new FormatException(FormatReason.TRUNCATED,
                            "gzip stream is incomplete");
                    }
                    continue;
                }
                total += count;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new FormatException(FormatReason.TOO_LARGE,
                        "decompressed replay exceeds the cap");
                }
                output.write(buffer, 0, count);
            }

            long consumed = inflater.getBytesRead();
            long trailerOffset = position + consumed;
            if (trailerOffset + 8 > compressed.length) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "gzip trailer is truncated");
            }

            byte[] decoded = output.toByteArray();
            long storedCrc = u32LittleEndian(compressed, (int) trailerOffset);
            long storedSize = u32LittleEndian(compressed, (int) trailerOffset + 4);
            CRC32 crc = new CRC32();
            crc.update(decoded);
            if (crc.getValue() != storedCrc || (decoded.length & 0xFFFFFFFFL) != storedSize) {
                throw new FormatException(FormatReason.GZIP_CRC,
                    "gzip trailer checksum does not match");
            }
            if (trailerOffset + 8 < compressed.length) {
                throw new FormatException(FormatReason.TRAILING_BYTES,
                    "bytes follow the gzip member");
            }
            return decoded;
        } catch (DataFormatException exception) {
            throw new FormatException(FormatReason.GZIP_CRC,
                "gzip stream is corrupt", exception);
        } finally {
            inflater.end();
        }
    }

    private static int skipZeroTerminated(byte[] bytes, int position) throws FormatException {
        while (position < bytes.length) {
            if (bytes[position++] == 0) {
                return position;
            }
        }
        throw new FormatException(FormatReason.TRUNCATED, "gzip header string is truncated");
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long u32LittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
            | ((bytes[offset + 1] & 0xFFL) << 8)
            | ((bytes[offset + 2] & 0xFFL) << 16)
            | ((bytes[offset + 3] & 0xFFL) << 24);
    }
}
