package com.gavrh.pzreplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

final class FormatGzip {
    private static final long MAX_DECOMPRESSED_BYTES = 2_000_000_000L;

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
        try (Stream stream = new Stream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer, 0, buffer.length)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new FormatException(FormatReason.GZIP_CRC,
                "gzip stream could not be read", exception);
        }
    }

    static Stream stream(InputStream input) throws IOException, FormatException {
        return new Stream(input);
    }

    static final class Stream implements AutoCloseable {
        private final InputStream input;
        private final Inflater inflater = new Inflater(true);
        private final byte[] inputBuffer = new byte[8192];
        private final CRC32 crc = new CRC32();
        private int inputLength;
        private long total;
        private boolean finished;
        private boolean trailerChecked;

        Stream(InputStream input) throws IOException, FormatException {
            this.input = input;
            readHeader();
        }

        int read(byte[] buffer, int offset, int length) throws IOException, FormatException {
            if (length == 0) {
                return 0;
            }
            if (finished) {
                return -1;
            }
            while (true) {
                if (inflater.needsInput()) {
                    inputLength = input.read(inputBuffer);
                    if (inputLength < 0) {
                        throw new FormatException(FormatReason.TRUNCATED,
                            "gzip stream is incomplete");
                    }
                    inflater.setInput(inputBuffer, 0, inputLength);
                }
                int count;
                try {
                    count = inflater.inflate(buffer, offset, length);
                } catch (DataFormatException exception) {
                    throw new FormatException(FormatReason.GZIP_CRC,
                        "gzip stream is corrupt", exception);
                }
                if (count > 0) {
                    total += count;
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        throw new FormatException(FormatReason.TOO_LARGE,
                            "decompressed replay exceeds the cap");
                    }
                    crc.update(buffer, offset, count);
                    return count;
                }
                if (inflater.finished()) {
                    finish();
                    finished = true;
                    return -1;
                }
                if (inflater.needsDictionary()) {
                    throw new FormatException(FormatReason.GZIP_CRC,
                        "gzip stream needs a dictionary");
                }
                if (!inflater.needsInput()) {
                    throw new FormatException(FormatReason.GZIP_CRC,
                        "gzip stream made no progress");
                }
            }
        }

        @Override
        public void close() throws IOException {
            inflater.end();
            input.close();
        }

        private void readHeader() throws IOException, FormatException {
            byte[] header = new byte[10];
            readFully(input, header, 0, header.length);
            if ((header[0] & 0xFF) != 0x1F || (header[1] & 0xFF) != 0x8B) {
                throw new FormatException(FormatReason.NOT_CONTAINER,
                    "file does not start with a gzip member");
            }
            if ((header[2] & 0xFF) != 8) {
                throw new FormatException(FormatReason.GZIP_CRC,
                    "unsupported gzip method");
            }
            int flags = header[3] & 0xFF;
            if ((flags & 0xE0) != 0) {
                throw new FormatException(FormatReason.GZIP_CRC,
                    "reserved gzip flags are set");
            }
            if ((flags & 0x04) != 0) {
                byte[] extraLength = new byte[2];
                readFully(input, extraLength, 0, 2);
                int extra = u16(extraLength, 0);
                skipFully(input, extra);
            }
            if ((flags & 0x08) != 0) {
                skipZeroTerminated(input);
            }
            if ((flags & 0x10) != 0) {
                skipZeroTerminated(input);
            }
            if ((flags & 0x02) != 0) {
                skipFully(input, 2);
            }
        }

        private void finish() throws IOException, FormatException {
            if (trailerChecked) {
                return;
            }
            byte[] trailer = new byte[8];
            int remaining = inflater.getRemaining();
            int fromBuffer = Math.min(remaining, trailer.length);
            int trailingFromBuffer = remaining - fromBuffer;
            if (fromBuffer > 0) {
                int offset = inputLength - remaining;
                System.arraycopy(inputBuffer, offset, trailer, 0, fromBuffer);
            }
            readFully(input, trailer, fromBuffer, trailer.length - fromBuffer);

            long storedCrc = u32LittleEndian(trailer, 0);
            long storedSize = u32LittleEndian(trailer, 4);
            if (crc.getValue() != storedCrc || (total & 0xFFFFFFFFL) != storedSize) {
                throw new FormatException(FormatReason.GZIP_CRC,
                    "gzip trailer checksum does not match");
            }
            if (trailingFromBuffer > 0 || input.read() != -1) {
                throw new FormatException(FormatReason.TRAILING_BYTES,
                    "bytes follow the gzip member");
            }
            trailerChecked = true;
        }
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length)
        throws IOException, FormatException {
        int position = 0;
        while (position < length) {
            int count = input.read(buffer, offset + position, length - position);
            if (count < 0) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "gzip stream is truncated");
            }
            position += count;
        }
    }

    private static void skipFully(InputStream input, int length) throws IOException, FormatException {
        byte[] buffer = new byte[Math.min(length, 8192)];
        int remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "gzip header is truncated");
            }
            remaining -= count;
        }
    }

    private static void skipZeroTerminated(InputStream input) throws IOException, FormatException {
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new FormatException(FormatReason.TRUNCATED,
                    "gzip header string is truncated");
            }
            if (value == 0) {
                return;
            }
        }
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
