package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpyFormatTest {
    @Test
    void readsVersion10() throws IOException {
        NpyVersion version = NpyFormat.readVersion(inputForVersion(1, 0));

        assertEquals(NpyVersion.V1_0, version);
        assertEquals(1, version.major());
        assertEquals(0, version.minor());
    }

    @Test
    void readsVersion20() throws IOException {
        NpyVersion version = NpyFormat.readVersion(inputForVersion(2, 0));

        assertEquals(NpyVersion.V2_0, version);
        assertEquals(2, version.major());
        assertEquals(0, version.minor());
    }

    @Test
    void readsVersion30() throws IOException {
        NpyVersion version = NpyFormat.readVersion(inputForVersion(3, 0));

        assertEquals(NpyVersion.V3_0, version);
        assertEquals(3, version.major());
        assertEquals(0, version.minor());
    }

    @Test
    void consumesOnlyTheEightBytePrefixAndLeavesStreamOpen() throws IOException {
        TrackingInputStream input = new TrackingInputStream(inputBytes(1, 0));

        NpyFormat.readVersion(input);

        assertEquals(0x10, input.read());
        assertEquals(0x11, input.read());
        assertFalse(input.closed);
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] invalidMagic = inputPrefix(1, 0);
        invalidMagic[0] = 0;

        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readVersion(new ByteArrayInputStream(invalidMagic)));

        assertEquals("Invalid NPY magic prefix", exception.getMessage());
    }

    @Test
    void rejectsEveryTruncatedEightBytePrefix() {
        byte[] completePrefix = inputPrefix(1, 0);

        for (int length = 0; length < completePrefix.length; length++) {
            byte[] truncated = Arrays.copyOf(completePrefix, length);
            assertThrows(EOFException.class,
                    () -> NpyFormat.readVersion(new ByteArrayInputStream(truncated)),
                    "Expected EOFException for prefix length " + length);
        }
    }

    @Test
    void rejectsUnsupportedVersion() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readVersion(inputForVersion(1, 1)));

        assertEquals("Unsupported NPY format version: 1.1", exception.getMessage());
    }

    private static ByteArrayInputStream inputForVersion(int major, int minor) {
        return new ByteArrayInputStream(inputPrefix(major, minor));
    }

    private static byte[] inputPrefix(int major, int minor) {
        return Arrays.copyOf(inputBytes(major, minor), 8);
    }

    private static byte[] inputBytes(int major, int minor) {
        return new byte[]{
                (byte) 0x93, 'N', 'U', 'M', 'P', 'Y',
                (byte) major, (byte) minor,
                0x10, 0x11, 0x12, 0x13
        };
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
