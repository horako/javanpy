package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyFormatIoTest {
    @Test
    void propagatesTransientFailuresAtEveryPrefixAndHeaderPosition() throws IOException {
        for (int version = 1; version <= 3; version++) {
            byte[] data = fixture(version);
            int payloadStart = payloadStart(data);
            for (int position = 0; position < payloadStart; position++) {
                if (position < 8) {
                    TransientFailure input = new TransientFailure(data, position);
                    assertSame(input.failure, assertThrows(IOException.class, () -> NpyFormat.readVersion(input)),
                            "version " + version + ", prefix byte " + position);
                    assertEquals(position, input.position);
                    assertFalse(input.closed);
                }

                TransientFailure headerInput = new TransientFailure(data, position);
                assertSame(headerInput.failure, assertThrows(IOException.class, () -> NpyFormat.readHeader(headerInput)),
                        "version " + version + ", header byte " + position);
                assertEquals(position, headerInput.position);
                assertFalse(headerInput.closed);

                TransientFailure readerInput = new TransientFailure(data, position);
                assertSame(readerInput.failure, assertThrows(IOException.class, () -> NpyReader.open(readerInput)),
                        "version " + version + ", reader header byte " + position);
                assertEquals(position, readerInput.position);
                assertFalse(readerInput.closed);

                TransientFailure pathInput = new TransientFailure(data, position);
                assertSame(pathInput.failure,
                        assertThrows(IOException.class, () -> NpyReader.open(TrackingPath.forInput(pathInput))),
                        "version " + version + ", path header byte " + position);
                assertEquals(position, pathInput.position);
                assertTrue(pathInput.closed);
            }
        }
    }

    @Test
    void handlesExplicitlyShortBulkReadsWithoutCrossingHeaderOrPayload() throws IOException {
        byte[] fixture = fixture(1);
        byte[] data = Arrays.copyOf(fixture, fixture.length + 1);
        data[fixture.length] = 77;
        ShortReadInput probe = new ShortReadInput(data);
        assertEquals(2, probe.read(new byte[8], 0, 8));

        ShortReadInput versionInput = new ShortReadInput(data);
        assertEquals(NpyVersion.V1_0, NpyFormat.readVersion(versionInput));
        assertEquals(8, versionInput.position);
        assertFalse(versionInput.closed);

        ShortReadInput headerInput = new ShortReadInput(data);
        assertEquals("<i4", NpyFormat.readHeader(headerInput).descr());
        assertEquals(payloadStart(data), headerInput.position);
        assertFalse(headerInput.closed);

        ShortReadInput readerInput = new ShortReadInput(data);
        try (NpyReader reader = NpyReader.open(readerInput)) {
            assertEquals(payloadStart(data), readerInput.position);
            for (int value = 1; value <= 6; value++) {
                assertEquals(value, reader.readLong());
            }
            assertFalse(reader.hasNext());
            assertEquals(fixture.length, readerInput.position);
        }
        assertFalse(readerInput.closed);
        assertEquals(77, readerInput.read());
    }

    private static byte[] fixture(int version) throws IOException {
        try (InputStream input = NpyFormatIoTest.class.getResourceAsStream(
                "/com/horako/javanpy/npy-header/v" + version + "_matrix_c.npy")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static int payloadStart(byte[] data) {
        int width = data[6] == 1 ? 2 : 4;
        int length = 0;
        for (int i = 0; i < width; i++) {
            length |= Byte.toUnsignedInt(data[8 + i]) << (8 * i);
        }
        return 8 + width + length;
    }

    private static final class TransientFailure extends InputStream {
        private final byte[] bytes;
        private final int failAt;
        private final IOException failure = new IOException("injected transient I/O failure");
        private int position;
        private boolean failed;
        private boolean closed;

        private TransientFailure(byte[] bytes, int failAt) {
            this.bytes = bytes;
            this.failAt = failAt;
        }

        @Override
        public int read() throws IOException {
            if (position == failAt && !failed) {
                failed = true;
                throw failure;
            }
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class ShortReadInput extends InputStream {
        private final ByteArrayInputStream delegate;
        private int position;
        private boolean closed;

        private ShortReadInput(byte[] bytes) {
            delegate = new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length));
        }

        @Override
        public int read() {
            int value = delegate.read();
            if (value >= 0) {
                position++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            int count = delegate.read(bytes, offset, Math.min(2, length));
            if (count > 0) {
                position += count;
            }
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
