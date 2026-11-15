package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyValueDecoderTest {
    @Test
    void decodesSignedIntegerBoundariesInEveryWidthAndOrder() throws IOException {
        for (String marker : List.of("<", ">", "=", "")) {
            for (int width : List.of(1, 2, 4, 8)) {
                int shift = Long.SIZE - width * Byte.SIZE;
                long min = Long.MIN_VALUE >> shift;
                long max = Long.MAX_VALUE >> shift;
                long[] values = {min, -1, 0, 1, max};
                NpyValueDecoder decoder = decoder(marker + "i" + width, values);
                for (long value : values) {
                    assertTrue(decoder.hasNext());
                    assertEquals(value, decoder.readLong(), marker + "i" + width);
                }
                assertFalse(decoder.hasNext());
            }
        }
        assertEquals(-128, decoder("|i1", 0x80).readLong());
    }

    @Test
    void decodesUnsignedIntegerBoundariesInEveryWidthAndOrder() throws IOException {
        for (String marker : List.of("<", ">", "=", "")) {
            for (int width : List.of(1, 2, 4)) {
                long max = (1L << (width * Byte.SIZE)) - 1;
                long[] values = {0, 1, max / 2 + 1, max};
                NpyValueDecoder decoder = decoder(marker + "u" + width, values);
                for (long value : values) {
                    assertEquals(value, decoder.readLong(), marker + "u" + width);
                }
                assertFalse(decoder.hasNext());
            }
            NpyValueDecoder decoder = decoder(marker + "u8", 0, 1, Long.MAX_VALUE,
                    Long.MIN_VALUE, -1);
            for (BigInteger expected : List.of(BigInteger.ZERO, BigInteger.ONE,
                    BigInteger.valueOf(Long.MAX_VALUE), BigInteger.ONE.shiftLeft(63),
                    BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))) {
                assertEquals(expected, decoder.readUnsignedLong(), marker + "u8");
            }
            assertFalse(decoder.hasNext());
        }
        assertEquals(255, decoder("|u1", 0xff).readLong());
    }

    @Test
    void decodesFloatingPointBitsAndSpecialValues() throws IOException {
        int[] floats = {0, 0x80000000, 0x3f800000, 0x00000001, 0x7f7fffff,
                0x7f800000, 0xff800000, 0x7fc12345};
        long[] doubles = {0, 0x8000000000000000L, 0x3ff0000000000000L, 1,
                0x7fefffffffffffffL, 0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff8123456789abCL};
        for (String marker : List.of("<", ">", "=", "")) {
            long[] floatBits = Arrays.stream(floats).mapToLong(Integer::toUnsignedLong).toArray();
            NpyValueDecoder floatDecoder = decoder(marker + "f4", floatBits);
            for (int bits : floats) {
                assertEquals(bits, Float.floatToRawIntBits(floatDecoder.readFloat()), marker + "f4");
            }
            NpyValueDecoder doubleDecoder = decoder(marker + "f8", doubles);
            for (long bits : doubles) {
                assertEquals(bits, Double.doubleToRawLongBits(doubleDecoder.readDouble()), marker + "f8");
            }
            assertFalse(floatDecoder.hasNext());
            assertFalse(doubleDecoder.hasNext());
        }
    }

    @Test
    void decodesBooleanAliasesAndNoncanonicalValues() throws IOException {
        for (String descriptor : List.of("|b1", "|?", "?", "<b1", ">b1", "=b1")) {
            NpyValueDecoder decoder = decoder(descriptor, 0, 1, 2, 255);
            assertFalse(decoder.readBoolean(), descriptor);
            assertTrue(decoder.readBoolean(), descriptor);
            assertTrue(decoder.readBoolean(), descriptor);
            assertTrue(decoder.readBoolean(), descriptor);
            assertFalse(decoder.hasNext());
        }
    }

    @Test
    void constructorAndHasNextDoNotReadOrCloseTheStream() {
        ProbeInput input = new ProbeInput(new byte[]{1, 2}, -1);
        NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse("|u1"), 2);
        assertTrue(decoder.hasNext());
        assertTrue(decoder.hasNext());
        assertEquals(0, input.readCalls);
        assertFalse(input.closed);

        NpyValueDecoder empty = new NpyValueDecoder(input, NpyDType.parse("<i8"), 0);
        assertFalse(empty.hasNext());
        assertThrows(NoSuchElementException.class, empty::readLong);
        assertEquals(0, input.readCalls);
        assertFalse(input.closed);
    }

    @Test
    void validatesConstructorArgumentsWithoutReading() {
        ProbeInput input = new ProbeInput(new byte[]{42}, -1);
        NpyDType dtype = NpyDType.parse("<i8");
        assertThrows(NullPointerException.class, () -> new NpyValueDecoder(null, dtype, 1));
        assertThrows(NullPointerException.class, () -> new NpyValueDecoder(input, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new NpyValueDecoder(input, dtype, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new NpyValueDecoder(input, dtype, Long.MAX_VALUE / 8 + 1));
        assertThrows(IllegalArgumentException.class,
                () -> new NpyValueDecoder(input, NpyDType.parse("|S0"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NpyValueDecoder(input, NpyDType.parse("<U1"), 1));
        NpyValueDecoder maxCount = new NpyValueDecoder(input, NpyDType.parse("|u1"), Long.MAX_VALUE);
        assertTrue(maxCount.hasNext());
        NpyValueDecoder maxPayload = new NpyValueDecoder(input, dtype, Long.MAX_VALUE / 8);
        assertTrue(maxPayload.hasNext());
        assertEquals(0, input.readCalls);
        assertFalse(input.closed);
    }

    @Test
    void rejectsEveryIncompatibleMethodWithoutConsumingAnElement() throws IOException {
        for (String descriptor : List.of("|i1", "<i8", "|u1", "<u4", "<u8", "<f4", "<f8", "|b1")) {
            NpyDType dtype = NpyDType.parse(descriptor);
            ProbeInput input = new ProbeInput(new byte[(int) dtype.itemWidthBytes()], -1);
            NpyValueDecoder decoder = new NpyValueDecoder(input, dtype, 1);
            List<ReadOperation> operations = List.of(
                    new ReadOperation(dtype.kind() == NpyDType.Kind.SIGNED_INTEGER
                            || (dtype.kind() == NpyDType.Kind.UNSIGNED_INTEGER && dtype.itemWidthBytes() < 8),
                            decoder::readLong),
                    new ReadOperation(dtype.kind() == NpyDType.Kind.UNSIGNED_INTEGER
                            && dtype.itemWidthBytes() == 8, decoder::readUnsignedLong),
                    new ReadOperation(dtype.kind() == NpyDType.Kind.FLOAT
                            && dtype.itemWidthBytes() == 4, decoder::readFloat),
                    new ReadOperation(dtype.kind() == NpyDType.Kind.FLOAT
                            && dtype.itemWidthBytes() == 8, decoder::readDouble),
                    new ReadOperation(dtype.kind() == NpyDType.Kind.BOOLEAN, decoder::readBoolean));
            for (ReadOperation operation : operations) {
                if (!operation.compatible()) {
                    assertThrows(IllegalArgumentException.class, operation.read()::run, descriptor);
                    assertEquals(0, input.readCalls, descriptor);
                    assertTrue(decoder.hasNext());
                }
            }
            for (ReadOperation operation : operations) {
                if (operation.compatible()) {
                    operation.read().run();
                    assertFalse(decoder.hasNext());
                    int calls = input.readCalls;
                    assertThrows(NoSuchElementException.class, operation.read()::run, descriptor);
                    assertEquals(calls, input.readCalls, descriptor);
                }
            }
            // Failed state takes precedence over dtype compatibility, which takes precedence over exhaustion.
            int calls = input.readCalls;
            for (ReadOperation operation : operations) {
                if (!operation.compatible()) {
                    assertThrows(IllegalArgumentException.class, operation.read()::run, descriptor);
                    assertFalse(decoder.hasNext());
                    assertEquals(calls, input.readCalls, descriptor);
                }
            }
            for (ReadOperation operation : operations) {
                if (operation.compatible()) {
                    assertThrows(NoSuchElementException.class, operation.read()::run, descriptor);
                    assertEquals(calls, input.readCalls, descriptor);
                }
            }
            assertFalse(input.closed);
        }
    }

    @Test
    void leavesTrailingBytesUntouchedOnNonSeekableStream() throws IOException {
        ProbeInput input = new ProbeInput(new byte[]{1, 2, 3, 4, 99, 100}, -1);
        assertFalse(input.markSupported());
        NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse("<u2"), 2);
        assertEquals(0x0201, decoder.readLong());
        assertEquals(0x0403, decoder.readLong());
        assertFalse(decoder.hasNext());
        assertEquals(4, input.readCalls);
        assertThrows(NoSuchElementException.class, decoder::readLong);
        assertEquals(4, input.readCalls);
        assertEquals(99, input.read());
        assertEquals(100, input.read());
        assertFalse(input.closed);
    }

    @Test
    void truncationAtEveryBytePermanentlyFailsDecoder() {
        for (int available = 0; available < 8; available++) {
            ProbeInput input = new ProbeInput(new byte[available], -1);
            NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(">i8"), 1);
            EOFException failure = assertThrows(EOFException.class, decoder::readLong);
            assertFailed(decoder, input, failure);
        }
    }

    @Test
    void truncationAfterSuccessfulElementsPermanentlyFailsDecoder() throws IOException {
        for (byte[] bytes : List.of(
                new byte[]{1, 0, 2, 0},
                new byte[]{1, 0, 2, 0, 3})) {
            ProbeInput input = new ProbeInput(bytes, -1);
            NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse("<u2"), 3);
            assertEquals(1, decoder.readLong());
            assertEquals(2, decoder.readLong());
            assertTrue(decoder.hasNext());
            EOFException failure = assertThrows(EOFException.class, decoder::readLong);
            assertFailed(decoder, input, failure);
        }
    }

    @Test
    void propagatesInjectedIoFailureAndPermanentlyFailsDecoder() {
        for (int failAfter = 0; failAfter < 8; failAfter++) {
            ProbeInput input = new ProbeInput(new byte[8], failAfter);
            NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse("<u8"), 1);
            IOException failure = assertThrows(IOException.class, decoder::readUnsignedLong);
            assertSame(input.failure, failure);
            assertFailed(decoder, input, failure);
        }
    }

    @Test
    void failureAfterSuccessfulElementDoesNotReadFurther() throws IOException {
        ProbeInput input = new ProbeInput(new byte[]{1, 0, 2, 0, 99}, 3);
        NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse("<u2"), 2);
        assertEquals(1, decoder.readLong());
        assertTrue(decoder.hasNext());
        IOException failure = assertThrows(IOException.class, decoder::readLong);
        assertSame(input.failure, failure);
        assertFailed(decoder, input, failure);
    }

    @Test
    void decodesRealNumpyGeneratedFixturesInPhysicalOrder() throws IOException {
        for (Fixture fixture : List.of(
                new Fixture("v1_scalar.npy", new long[]{7}),
                new Fixture("v1_vector.npy", new long[]{1, 2, 3, 4}),
                new Fixture("v1_matrix_c.npy", new long[]{1, 2, 3, 4, 5, 6}),
                new Fixture("v1_matrix_fortran.npy", new long[]{1, 4, 2, 5, 3, 6}),
                new Fixture("v2_matrix_fortran.npy", new long[]{1, 4, 2, 5, 3, 6}),
                new Fixture("v3_matrix_c.npy", new long[]{1, 2, 3, 4, 5, 6}))) {
            try (InputStream input = getClass().getResourceAsStream(
                    "/com/horako/javanpy/npy-header/" + fixture.name())) {
                assertNotNull(input, fixture.name());
                NpyHeader header = NpyFormat.readHeader(input);
                NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()),
                        fixture.values().length);
                for (long value : fixture.values()) {
                    assertEquals(value, decoder.readLong(), fixture.name());
                }
                assertFalse(decoder.hasNext(), fixture.name());
                assertEquals(-1, input.read(), fixture.name());
            }
        }
    }

    @Test
    void decodesNumpyGeneratedBooleanAndUnsignedFixtures() throws IOException {
        for (String name : List.of("bool.npy", "bool_noncanonical.npy")) {
            try (InputStream input = openValueFixture(name)) {
                NpyHeader header = NpyFormat.readHeader(input);
                assertEquals("|b1", header.descr());
                NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()), 4);
                assertFalse(decoder.readBoolean());
                assertTrue(decoder.readBoolean());
                assertEquals(name.equals("bool_noncanonical.npy"), decoder.readBoolean());
                assertTrue(decoder.readBoolean());
                assertFalse(decoder.hasNext());
                assertEquals(-1, input.read());
            }
        }
        try (InputStream input = openValueFixture("uint64_be.npy")) {
            NpyHeader header = NpyFormat.readHeader(input);
            assertEquals(">u8", header.descr());
            NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()), 5);
            for (BigInteger expected : List.of(BigInteger.ZERO, BigInteger.ONE,
                    BigInteger.valueOf(Long.MAX_VALUE), BigInteger.ONE.shiftLeft(63),
                    BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))) {
                assertEquals(expected, decoder.readUnsignedLong());
            }
            assertFalse(decoder.hasNext());
            assertEquals(-1, input.read());
        }
    }

    @Test
    void decodesNumpyGeneratedMixedByteIntegerFixtures() throws IOException {
        for (String order : List.of("le", "be")) {
            String marker = order.equals("le") ? "<" : ">";
            for (Fixture fixture : List.of(
                    new Fixture("int16_" + order + ".npy", new long[]{0x1234, -0x1234}),
                    new Fixture("int32_" + order + ".npy", new long[]{0x12345678, -0x1234567}))) {
                try (InputStream input = openValueFixture(fixture.name())) {
                    NpyHeader header = NpyFormat.readHeader(input);
                    assertEquals(marker + (fixture.name().startsWith("int16") ? "i2" : "i4"),
                            header.descr());
                    NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()),
                            fixture.values().length);
                    for (long expected : fixture.values()) {
                        assertEquals(expected, decoder.readLong(), fixture.name());
                    }
                    assertFalse(decoder.hasNext());
                    assertEquals(-1, input.read());
                }
            }
            try (InputStream input = openValueFixture("uint64_mixed_" + order + ".npy")) {
                NpyHeader header = NpyFormat.readHeader(input);
                assertEquals(marker + "u8", header.descr());
                NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()), 2);
                assertEquals(new BigInteger("0123456789abcdef", 16), decoder.readUnsignedLong());
                assertEquals(new BigInteger("fedcba9876543210", 16), decoder.readUnsignedLong());
                assertFalse(decoder.hasNext());
                assertEquals(-1, input.read());
            }
        }
    }

    @Test
    void decodesNumpyGeneratedFloatingPointFixtures() throws IOException {
        try (InputStream input = openValueFixture("float32_le.npy")) {
            NpyHeader header = NpyFormat.readHeader(input);
            assertEquals("<f4", header.descr());
            NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()), 6);
            for (float expected : new float[]{0.0f, -0.0f, 1.5f,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(decoder.readFloat()));
            }
            assertTrue(Float.isNaN(decoder.readFloat()));
            assertFalse(decoder.hasNext());
            assertEquals(-1, input.read());
        }
        try (InputStream input = openValueFixture("float64_be.npy")) {
            NpyHeader header = NpyFormat.readHeader(input);
            assertEquals(">f8", header.descr());
            NpyValueDecoder decoder = new NpyValueDecoder(input, NpyDType.parse(header.descr()), 6);
            for (double expected : new double[]{0.0, -0.0, 1.5,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(decoder.readDouble()));
            }
            assertTrue(Double.isNaN(decoder.readDouble()));
            assertFalse(decoder.hasNext());
            assertEquals(-1, input.read());
        }
    }

    private InputStream openValueFixture(String name) {
        InputStream input = getClass().getResourceAsStream("/com/horako/javanpy/npy-values/" + name);
        assertNotNull(input, name);
        return input;
    }

    private static void assertFailed(NpyValueDecoder decoder, ProbeInput input, IOException failure) {
        int calls = input.readCalls;
        assertSame(failure, assertThrows(IllegalStateException.class, decoder::hasNext).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, decoder::readLong).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, decoder::readUnsignedLong).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, decoder::readFloat).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, decoder::readDouble).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, decoder::readBoolean).getCause());
        assertEquals(calls, input.readCalls);
        assertFalse(input.closed);
    }

    private static NpyValueDecoder decoder(String descriptor, long... values) {
        NpyDType dtype = NpyDType.parse(descriptor);
        int width = (int) dtype.itemWidthBytes();
        boolean littleEndian = dtype.byteOrder() == NpyDType.ByteOrder.LITTLE_ENDIAN
                || (dtype.byteOrder() == NpyDType.ByteOrder.NATIVE
                && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
        byte[] bytes = new byte[width * values.length];
        for (int element = 0; element < values.length; element++) {
            for (int i = 0; i < width; i++) {
                int shift = (littleEndian ? i : width - 1 - i) * Byte.SIZE;
                bytes[element * width + i] = (byte) (values[element] >>> shift);
            }
        }
        return new NpyValueDecoder(new ByteArrayInputStream(bytes), dtype, values.length);
    }

    private record Fixture(String name, long[] values) {
    }

    private record ReadOperation(boolean compatible, ThrowingRead read) {
    }

    @FunctionalInterface
    private interface ThrowingRead {
        void run() throws IOException;
    }

    private static final class ProbeInput extends InputStream {
        private final byte[] bytes;
        private final int failAfter;
        private final IOException failure = new IOException("Injected I/O failure");
        private int position;
        private int readCalls;
        private boolean closed;

        private ProbeInput(byte[] bytes, int failAfter) {
            this.bytes = bytes;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            readCalls++;
            if (position == failAfter) {
                throw failure;
            }
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
