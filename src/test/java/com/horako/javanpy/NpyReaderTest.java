package com.horako.javanpy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class NpyReaderTest {
    @Test
    void streamsNumpyFixturesInPhysicalOrderAndExposesMetadata() throws IOException {
        for (int version = 1; version <= 3; version++) {
            for (boolean fortran : List.of(false, true)) {
                String name = "v" + version + "_matrix_" + (fortran ? "fortran" : "c") + ".npy";
                try (InputStream input = fixture("npy-header", name);
                     NpyReader reader = NpyReader.open(input)) {
                    assertEquals(NpyVersion.values()[version - 1], reader.version());
                    assertEquals(List.of(2L, 3L), reader.header().shape());
                    assertEquals(fortran, reader.header().fortranOrder());
                    assertEquals("<i4", reader.dtype().descriptor());
                    long[] expected = fortran ? new long[]{1, 4, 2, 5, 3, 6} : new long[]{1, 2, 3, 4, 5, 6};
                    for (long value : expected) {
                        assertTrue(reader.hasNext());
                        assertEquals(value, reader.readLong());
                    }
                    assertFalse(reader.hasNext());
                    assertFalse(reader.hasNext());
                    assertThrows(NoSuchElementException.class, reader::readLong);
                    assertEquals(-1, input.read());
                }
            }
        }
    }

    @Test
    void streamsNumpyNumericAndBooleanFixtures() throws IOException {
        for (String order : List.of("le", "be")) {
            for (int width : List.of(16, 32)) {
                try (NpyReader reader = NpyReader.open(fixture("npy-values", "int" + width + "_" + order + ".npy"))) {
                    assertEquals(width == 16 ? 0x1234 : 0x12345678, reader.readLong());
                    assertEquals(width == 16 ? -0x1234 : -0x1234567, reader.readLong());
                    assertFalse(reader.hasNext());
                }
            }
            try (NpyReader reader = NpyReader.open(fixture("npy-values", "uint64_mixed_" + order + ".npy"))) {
                assertEquals(new BigInteger("0123456789ABCDEF", 16), reader.readUnsignedLong());
                assertEquals(new BigInteger("FEDCBA9876543210", 16), reader.readUnsignedLong());
                assertFalse(reader.hasNext());
            }
        }
        try (NpyReader reader = NpyReader.open(fixture("npy-values", "uint64_be.npy"))) {
            for (BigInteger expected : List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE),
                    BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))) {
                assertEquals(expected, reader.readUnsignedLong());
            }
            assertFalse(reader.hasNext());
        }
        for (String name : List.of("bool.npy", "bool_noncanonical.npy")) {
            try (NpyReader reader = NpyReader.open(fixture("npy-values", name))) {
                assertFalse(reader.readBoolean());
                assertTrue(reader.readBoolean());
                assertEquals(name.equals("bool_noncanonical.npy"), reader.readBoolean());
                assertTrue(reader.readBoolean());
                assertFalse(reader.hasNext());
            }
        }
        try (NpyReader reader = NpyReader.open(fixture("npy-values", "float32_le.npy"))) {
            for (float value : new float[]{0f, -0f, 1.5f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits(reader.readFloat()));
            }
            assertTrue(Float.isNaN(reader.readFloat()));
            assertFalse(reader.hasNext());
        }
        try (NpyReader reader = NpyReader.open(fixture("npy-values", "float64_be.npy"))) {
            for (double value : new double[]{0d, -0d, 1.5d, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(reader.readDouble()));
            }
            assertTrue(Double.isNaN(reader.readDouble()));
            assertFalse(reader.hasNext());
        }
    }

    @Test
    void supportsEveryIntegerWidthAndByteOrder() throws IOException {
        for (String marker : List.of("<", ">", "=", "")) {
            for (int width : List.of(1, 2, 4, 8)) {
                long signedMin = Long.MIN_VALUE >> (Long.SIZE - width * Byte.SIZE);
                for (String kind : List.of("i", "u")) {
                    long bits = kind.equals("i") ? signedMin : -1;
                    byte[] payload = bytes(bits, width, marker);
                    try (NpyReader reader = NpyReader.open(new ByteArrayInputStream(
                            npy(marker + kind + width, "(1,)", payload)))) {
                        if (kind.equals("u") && width == 8) {
                            assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
                                    reader.readUnsignedLong());
                        } else {
                            long expected = kind.equals("i") ? signedMin : (1L << (width * Byte.SIZE)) - 1;
                            assertEquals(expected, reader.readLong(), marker + kind + width);
                        }
                        assertFalse(reader.hasNext());
                    }
                }
            }
        }
    }

    @Test
    void supportsFloatBitPatternsInEveryByteOrder() throws IOException {
        int[] floatBits = {0, 0x80000000, 1, 0x7f800000, 0xff800000, 0x7fc12345};
        long[] doubleBits = {0, Long.MIN_VALUE, 1, 0x7ff0000000000000L,
                0xfff0000000000000L, 0x7ff8123456789abCL};
        for (String marker : List.of("<", ">", "=", "")) {
            for (int bits : floatBits) {
                try (NpyReader reader = NpyReader.open(new ByteArrayInputStream(
                        npy(marker + "f4", "()", bytes(Integer.toUnsignedLong(bits), 4, marker))))) {
                    assertEquals(bits, Float.floatToRawIntBits(reader.readFloat()), marker);
                    assertFalse(reader.hasNext());
                }
            }
            for (long bits : doubleBits) {
                try (NpyReader reader = NpyReader.open(new ByteArrayInputStream(
                        npy(marker + "f8", "()", bytes(bits, 8, marker))))) {
                    assertEquals(bits, Double.doubleToRawLongBits(reader.readDouble()), marker);
                    assertFalse(reader.hasNext());
                }
            }
        }
    }

    @Test
    void scalarEmptyAndHigherRankShapesRespectPayloadBoundary() throws IOException {
        for (String shape : List.of("(0,)", "(9223372036854775807, 2, 0)", "(2, 0, 3)")) {
            ProbeInput input = new ProbeInput(npy("|i1", shape, new byte[]{42}));
            try (NpyReader reader = NpyReader.open(input)) {
                int atPayload = input.position;
                assertFalse(reader.hasNext());
                assertThrows(NoSuchElementException.class, reader::readLong);
                assertEquals(atPayload, input.position);
            }
            assertEquals(42, input.read());
        }
        ProbeInput scalar = new ProbeInput(npy("|i1", "()", new byte[]{7, 99}));
        try (NpyReader reader = NpyReader.open(scalar)) {
            assertEquals(7, reader.readLong());
            assertFalse(reader.hasNext());
            assertEquals(99, scalar.read());
        }
        ProbeInput rank3 = new ProbeInput(npy("|u1", "(2, 1, 2)", new byte[]{1, 2, 3, 4, 99}));
        try (NpyReader reader = NpyReader.open(rank3)) {
            for (int value = 1; value <= 4; value++) {
                assertEquals(value, reader.readLong());
            }
            assertFalse(reader.hasNext());
            assertEquals(99, rank3.read());
        }
    }

    @Test
    void rejectsMismatchesBeforeConsumptionEvenAtExhaustion() throws IOException {
        ProbeInput input = new ProbeInput(npy("<i2", "(1,)", new byte[]{0x34, 0x12}));
        try (NpyReader reader = NpyReader.open(input)) {
            int position = input.position;
            int calls = input.calls;
            assertTrue(reader.hasNext());
            assertTrue(reader.hasNext());
            assertEquals(calls, input.calls);
            assertThrows(IllegalArgumentException.class, reader::readUnsignedLong);
            assertThrows(IllegalArgumentException.class, reader::readFloat);
            assertThrows(IllegalArgumentException.class, reader::readDouble);
            assertThrows(IllegalArgumentException.class, reader::readBoolean);
            assertEquals(position, input.position);
            assertEquals(calls, input.calls);
            assertEquals(0x1234, reader.readLong());
            assertThrows(NoSuchElementException.class, reader::readLong);
            assertThrows(IllegalArgumentException.class, reader::readFloat);
        }
    }

    @Test
    void enforcesCompatibilityForEveryAccessor() throws IOException {
        List<ReaderAction> actions = List.of(
                reader -> reader.readLong(),
                reader -> reader.readUnsignedLong(),
                reader -> reader.readFloat(),
                reader -> reader.readDouble(),
                reader -> reader.readBoolean());
        List<String> descriptors = List.of("|i1", "|u1", "<u8", "<f4", ">f8", "|b1");
        int[] compatible = {0, 0, 1, 2, 3, 4};
        for (int i = 0; i < descriptors.size(); i++) {
            String descriptor = descriptors.get(i);
            int width = Math.toIntExact(NpyDType.parse(descriptor).itemWidthBytes());
            ProbeInput input = new ProbeInput(npy(descriptor, "(1,)", new byte[width]));
            try (NpyReader reader = NpyReader.open(input)) {
                int position = input.position;
                int calls = input.calls;
                for (int j = 0; j < actions.size(); j++) {
                    if (j != compatible[i]) {
                        ReaderAction action = actions.get(j);
                        assertThrows(IllegalArgumentException.class, () -> action.read(reader), descriptor);
                        assertEquals(position, input.position);
                        assertEquals(calls, input.calls);
                    }
                }
                ReaderAction action = actions.get(compatible[i]);
                action.read(reader);
                assertFalse(reader.hasNext());
                assertThrows(NoSuchElementException.class, () -> action.read(reader), descriptor);
            }
        }
    }

    @Test
    void callerStreamStaysOpenAndCloseStopsAllReads() throws IOException {
        ProbeInput input = new ProbeInput(npy("|i1", "(2,)", new byte[]{1, 2, 3}));
        NpyReader reader = NpyReader.open(input, new NpyReadOptions(true));
        assertEquals(1, reader.readLong());
        int calls = input.calls;
        reader.close();
        reader.close();
        assertFalse(input.closed);
        assertEquals(NpyVersion.V1_0, reader.version());
        assertEquals(List.of(2L), reader.header().shape());
        assertEquals("|i1", reader.dtype().descriptor());
        assertClosed(reader);
        assertEquals(calls, input.calls);
        assertEquals(2, input.read());

        ProbeInput invalid = new ProbeInput(new byte[]{1, 2, 3});
        assertThrows(EOFException.class, () -> NpyReader.open(invalid));
        assertFalse(invalid.closed);
        ProbeInput initializationFailure = new ProbeInput(npy("|i1", "(1,)", new byte[]{1}));
        initializationFailure.failAt = 0;
        assertSame(initializationFailure.failure,
                assertThrows(IOException.class, () -> NpyReader.open(initializationFailure)));
        assertFalse(initializationFailure.closed);
    }

    @Test
    void truncatedAndInjectedFailuresPermanentlyInvalidateReader() throws IOException {
        ProbeInput truncated = new ProbeInput(npy("<i2", "(1,)", new byte[]{1}));
        try (NpyReader reader = NpyReader.open(truncated)) {
            assertTrue(reader.hasNext());
            EOFException failure = assertThrows(EOFException.class, reader::readLong);
            assertFailed(reader, truncated, failure);
            assertEquals("<i2", reader.dtype().descriptor());
        }
        ProbeInput input = new ProbeInput(npy("<i2", "(1,)", new byte[]{1, 2}));
        NpyReader reader = NpyReader.open(input);
        input.failAt = input.position + 1;
        IOException failure = assertThrows(IOException.class, reader::readLong);
        assertSame(input.failure, failure);
        assertFailed(reader, input, failure);
        reader.close();
        assertFailed(reader, input, failure);
        assertEquals(NpyVersion.V1_0, reader.version());
        assertEquals(List.of(1L), reader.header().shape());
        assertFalse(input.closed);
    }

    @Test
    void validatesFileAndArgumentsBeforePayloadOrPathAccess(@TempDir Path temp) throws IOException {
        assertFalse(NpyReadOptions.DEFAULT.preserveStringPadding());
        assertTrue(new NpyReadOptions(true).preserveStringPadding());
        assertEquals(new NpyReadOptions(false), NpyReadOptions.DEFAULT);
        ProbeInput input = new ProbeInput(npy("|i1", "(1,)", new byte[]{1}));
        assertThrows(NullPointerException.class, () -> NpyReader.open(input, null));
        assertEquals(0, input.calls);
        assertThrows(NullPointerException.class, () -> NpyReader.open((InputStream) null));
        assertThrows(NullPointerException.class, () -> NpyReader.open((Path) null));
        assertThrows(NullPointerException.class, () -> NpyReader.open(temp.resolve("missing"), null));
        assertThrows(IOException.class, () -> NpyReader.open(temp.resolve("missing")));
        ProbeInput pathInput = new ProbeInput(npy("|i1", "(1,)", new byte[]{1}));
        assertThrows(NullPointerException.class, () -> NpyReader.open(TrackingPath.forInput(pathInput), null));
        assertEquals(0, pathInput.calls);
        assertFalse(pathInput.closed);

        for (String descriptor : List.of("|S0", "<U1", "|O", "|V1", "<f2", "invalid")) {
            byte[] data = npy(descriptor, "(1,)", new byte[]{77});
            ProbeInput invalid = new ProbeInput(data);
            IOException error = assertThrows(IOException.class, () -> NpyReader.open(invalid));
            assertNotNull(error.getMessage());
            assertEquals(data.length - 1, invalid.position, descriptor);
            assertFalse(invalid.closed);
            assertEquals(77, invalid.read());
        }
        for (String shape : List.of("(9223372036854775807, 2)", "(4611686018427387904,)")) {
            byte[] data = npy("<i4", shape, new byte[]{99});
            ProbeInput overflow = new ProbeInput(data);
            assertThrows(IOException.class, () -> NpyReader.open(overflow));
            assertEquals(data.length - 1, overflow.position);
        }
        assertThrows(IOException.class, () -> NpyReader.open(new ByteArrayInputStream(new byte[8])));
        assertThrows(EOFException.class, () -> NpyReader.open(new ByteArrayInputStream(new byte[]{(byte) 0x93})));
    }

    @Test
    void opensRealPathAndLeavesNextNpyOnCallerStream(@TempDir Path temp) throws IOException {
        byte[] first = npy("|i1", "(1,)", new byte[]{7});
        byte[] second = npy("|i1", "(1,)", new byte[]{8});
        Path path = temp.resolve("array.npy");
        Files.write(path, first);
        try (NpyReader reader = NpyReader.open(path)) {
            assertEquals(7, reader.readLong());
        }
        try (NpyReader reader = NpyReader.open(path, new NpyReadOptions(true))) {
            assertEquals(7, reader.readLong());
        }
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(first);
        combined.write(second);
        ProbeInput input = new ProbeInput(combined.toByteArray());
        try (NpyReader reader = NpyReader.open(input)) {
            assertEquals(7, reader.readLong());
            assertFalse(reader.hasNext());
        }
        try (NpyReader reader = NpyReader.open(input)) {
            assertEquals(8, reader.readLong());
            assertFalse(reader.hasNext());
        }
        assertEquals(-1, input.read());
    }

    @Test
    void pathOwnsStreamOnSuccessFailureAndEarlyClose() throws IOException {
        ProbeInput success = new ProbeInput(npy("|i1", "(2,)", new byte[]{1, 2}));
        NpyReader reader = NpyReader.open(TrackingPath.forInput(success));
        assertEquals(1, reader.readLong());
        int position = success.position;
        reader.close();
        reader.close();
        assertTrue(success.closed);
        assertEquals(1, success.closeCalls);
        assertEquals(position, success.position);
        assertClosed(reader);

        ProbeInput invalid = new ProbeInput(new byte[]{1});
        assertThrows(EOFException.class, () -> NpyReader.open(TrackingPath.forInput(invalid)));
        assertTrue(invalid.closed);
        assertEquals(1, invalid.closeCalls);

        ProbeInput unsupported = new ProbeInput(npy("|S1", "(1,)", new byte[]{99}));
        assertThrows(IOException.class, () -> NpyReader.open(TrackingPath.forInput(unsupported)));
        assertTrue(unsupported.closed);
        assertEquals(unsupported.bytes.length - 1, unsupported.position);

        ProbeInput failedRead = new ProbeInput(npy("|i1", "(1,)", new byte[0]));
        try (NpyReader owned = NpyReader.open(TrackingPath.forInput(failedRead))) {
            assertThrows(EOFException.class, owned::readLong);
            assertFalse(failedRead.closed);
        }
        assertTrue(failedRead.closed);

        ProbeInput failingClose = new ProbeInput(npy("|i1", "(0,)", new byte[0]));
        failingClose.closeFailure = new IOException("close failed");
        NpyReader closeReader = NpyReader.open(TrackingPath.forInput(failingClose));
        assertSame(failingClose.closeFailure, assertThrows(IOException.class, closeReader::close));
        closeReader.close();
        assertEquals(1, failingClose.closeCalls);
        assertClosed(closeReader);

        ProbeInput failedBoth = new ProbeInput(new byte[]{1});
        failedBoth.closeFailure = new IOException("close failed");
        EOFException failure = assertThrows(EOFException.class, () -> NpyReader.open(TrackingPath.forInput(failedBoth)));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(failedBoth.closeFailure, failure.getSuppressed()[0]);
    }

    private static void assertClosed(NpyReader reader) {
        assertThrows(IllegalStateException.class, reader::hasNext);
        assertThrows(IllegalStateException.class, reader::readLong);
        assertThrows(IllegalStateException.class, reader::readUnsignedLong);
        assertThrows(IllegalStateException.class, reader::readFloat);
        assertThrows(IllegalStateException.class, reader::readDouble);
        assertThrows(IllegalStateException.class, reader::readBoolean);
    }

    private static void assertFailed(NpyReader reader, ProbeInput input, IOException failure) {
        int calls = input.calls;
        assertSame(failure, assertThrows(IllegalStateException.class, reader::hasNext).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, reader::readLong).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, reader::readUnsignedLong).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, reader::readFloat).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, reader::readDouble).getCause());
        assertSame(failure, assertThrows(IllegalStateException.class, reader::readBoolean).getCause());
        assertEquals(calls, input.calls);
    }

    private static InputStream fixture(String directory, String name) throws IOException {
        try (InputStream input = NpyReaderTest.class.getResourceAsStream(
                "/com/horako/javanpy/" + directory + "/" + name)) {
            assertNotNull(input, name);
            return new ByteArrayInputStream(input.readAllBytes());
        }
    }

    private static byte[] npy(String descriptor, String shape, byte[] payload) throws IOException {
        String header = "{'descr': '" + descriptor + "', 'fortran_order': False, 'shape': " + shape + ", }\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0,
                (byte) headerBytes.length, (byte) (headerBytes.length >>> 8)});
        output.write(headerBytes);
        output.write(payload);
        return output.toByteArray();
    }

    private static byte[] bytes(long bits, int width, String marker) {
        boolean little = marker.equals("<") || (!marker.equals(">") && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
        byte[] result = new byte[width];
        for (int i = 0; i < width; i++) {
            result[i] = (byte) (bits >>> ((little ? i : width - 1 - i) * Byte.SIZE));
        }
        return result;
    }

    @FunctionalInterface
    private interface ReaderAction {
        void read(NpyReader reader) throws IOException;
    }

    private static final class ProbeInput extends InputStream {
        private final byte[] bytes;
        private final IOException failure = new IOException("injected read failure");
        private IOException closeFailure;
        private int failAt = -1;
        private int position;
        private int calls;
        private int closeCalls;
        private boolean closed;

        private ProbeInput(byte[] bytes) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public int read() throws IOException {
            calls++;
            if (closed) {
                throw new IOException("stream closed");
            }
            if (position == failAt) {
                throw failure;
            }
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
