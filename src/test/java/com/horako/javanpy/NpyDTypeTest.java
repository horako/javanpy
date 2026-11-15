package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static com.horako.javanpy.NpyDType.ByteOrder.BIG_ENDIAN;
import static com.horako.javanpy.NpyDType.ByteOrder.LITTLE_ENDIAN;
import static com.horako.javanpy.NpyDType.ByteOrder.NATIVE;
import static com.horako.javanpy.NpyDType.ByteOrder.NOT_APPLICABLE;
import static com.horako.javanpy.NpyDType.Kind.BOOLEAN;
import static com.horako.javanpy.NpyDType.Kind.BYTE_STRING;
import static com.horako.javanpy.NpyDType.Kind.FLOAT;
import static com.horako.javanpy.NpyDType.Kind.SIGNED_INTEGER;
import static com.horako.javanpy.NpyDType.Kind.UNICODE;
import static com.horako.javanpy.NpyDType.Kind.UNSIGNED_INTEGER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyDTypeTest {
    @Test
    void parsesEverySupportedNumericWidthAndOrder() {
        for (String marker : List.of("", "=", "<", ">")) {
            NpyDType.ByteOrder order = order(marker);
            for (int width : List.of(1, 2, 4, 8)) {
                assertDType(marker + "i" + width, SIGNED_INTEGER, order, width);
                assertDType(marker + "u" + width, UNSIGNED_INTEGER, order, width);
            }
            for (int width : List.of(4, 8)) {
                assertDType(marker + "f" + width, FLOAT, order, width);
            }
        }
        assertDType("|i1", SIGNED_INTEGER, NOT_APPLICABLE, 1);
        assertDType("|u1", UNSIGNED_INTEGER, NOT_APPLICABLE, 1);
    }

    @Test
    void parsesBooleansAndStringsWithAllValidMarkers() {
        for (String marker : List.of("", "=", "<", ">", "|")) {
            NpyDType.ByteOrder order = order(marker);
            assertDType(marker + "b1", BOOLEAN, order, 1);
            assertDType(marker + "?", BOOLEAN, order, 1);
            assertDType(marker + "S0", BYTE_STRING, order, 0);
            assertDType(marker + "S20", BYTE_STRING, order, 20);
        }
        for (String marker : List.of("", "=", "<", ">")) {
            NpyDType.ByteOrder order = order(marker);
            assertDType(marker + "U0", UNICODE, order, 0);
            assertDType(marker + "U20", UNICODE, order, 80);
        }
        assertDType("|U0", UNICODE, NOT_APPLICABLE, 0);
        assertDType("|U000", UNICODE, NOT_APPLICABLE, 0);
    }

    @Test
    void preservesSpellingAndSupportsLeadingZeroes() {
        assertDType("=i0004", SIGNED_INTEGER, NATIVE, 4);
        assertDType("|S00020", BYTE_STRING, NOT_APPLICABLE, 20);
        assertDType(">U00020", UNICODE, BIG_ENDIAN, 80);
        assertDType("|S00000000000000000000000000000000000", BYTE_STRING, NOT_APPLICABLE, 0);
    }

    @Test
    void checksStringWidthBoundariesAndOverflow() {
        assertDType("|S9223372036854775807", BYTE_STRING, NOT_APPLICABLE, Long.MAX_VALUE);
        assertDType("<U2305843009213693951", UNICODE, LITTLE_ENDIAN, Long.MAX_VALUE - 3);
        for (String descriptor : List.of(
                "|S9223372036854775808", "S99999999999999999999999999999999",
                "<U2305843009213693952", ">U9223372036854775808")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> NpyDType.parse(descriptor), descriptor);
            assertTrue(error.getMessage().contains("long range"), error.getMessage());
        }
    }

    @Test
    void rejectsMalformedDescriptors() {
        for (String descriptor : List.of(
                "", "<", "i", "b", "S", "U",
                " i4", "i4 ", "< i4", "i-4", "i+4", "i4x", "i4.0", "i\u0664",
                "<<i4", "<>i4", "|=i1", "??", "?1", "|?1", "S-1")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> NpyDType.parse(descriptor), descriptor);
            assertTrue(error.getMessage().contains("dtype"), error.getMessage());
        }
        assertThrows(NullPointerException.class, () -> NpyDType.parse(null));
    }

    @Test
    void rejectsUnsupportedKindsWidthsAndIndependentMarkers() {
        for (String descriptor : List.of(
                "i0", "i3", "i16", "u0", "u3", "u16", "f0", "f1", "f2", "f16", "b0", "b2",
                "|i2", "|i4", "|i8", "|u2", "|u4", "|u8", "|f4", "|f8", "|U1", "|U10", "|U0001",
                "O", "|O8", "<c8", "<c16", "<M8", "<m8", "|V8", "T", "[('x', '<i4')]")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> NpyDType.parse(descriptor), descriptor);
            assertTrue(error.getMessage().contains(descriptor), error.getMessage());
        }
    }

    @Test
    void equalityAndHashCodeUseExactDescriptorSpelling() {
        for (String descriptor : List.of("|U0", "<U0", "|b1", "i04", "|S20")) {
            NpyDType first = NpyDType.parse(descriptor);
            NpyDType second = NpyDType.parse(new String(descriptor));
            assertEquals(first, first);
            assertEquals(first, second);
            assertEquals(second, first);
            assertEquals(first.hashCode(), second.hashCode());
            assertEquals(descriptor.hashCode(), first.hashCode());
        }
        for (List<String> spellings : List.of(
                List.of("|U0", "|U00", "U0", "=U0", "<U0", ">U0"),
                List.of("i04", "=i4", "i4"),
                List.of("?", "|b1", ">b01", "<b1"),
                List.of("|i1", ">i01"),
                List.of("=u1", "<u1"),
                List.of("|S20", ">S020"))) {
            HashSet<NpyDType> dtypes = new HashSet<>();
            for (String spelling : spellings) {
                NpyDType dtype = NpyDType.parse(spelling);
                for (NpyDType previous : dtypes) {
                    assertNotEquals(previous, dtype);
                    assertNotEquals(dtype, previous);
                }
                assertTrue(dtypes.add(dtype), spelling);
            }
            assertEquals(spellings.size(), dtypes.size());
        }
        assertNotEquals(NpyDType.parse("i4"), null);
        assertNotEquals(NpyDType.parse("i4"), "i4");
    }

    @Test
    void parsesZeroWidthUnicodeFromNpyHeader() throws IOException {
        String dictionary = "{'descr': '|U0', 'fortran_order': False, 'shape': (3,), }";
        String header = dictionary + " ".repeat(118 - dictionary.length() - 1) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0, 118, 0});
        output.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        assertEquals(128, output.size());

        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        NpyHeader parsed = NpyFormat.readHeader(input);
        assertEquals(List.of(3L), parsed.shape());
        assertEquals("|U0", parsed.descr());
        assertDType(parsed.descr(), UNICODE, NOT_APPLICABLE, 0);
        assertEquals(0, input.available());
    }

    @Test
    void parsesRealNumpyGeneratedHeaderWithoutChangingExistingApi() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/com/horako/javanpy/npy-header/v1_matrix_c.npy")) {
            assertNotNull(input);
            NpyHeader header = NpyFormat.readHeader(input);
            assertEquals("<i4", header.descr());
            NpyDType dtype = NpyDType.parse(header.descr());
            assertEquals(header.descr(), dtype.descriptor());
            assertEquals(SIGNED_INTEGER, dtype.kind());
            assertEquals(LITTLE_ENDIAN, dtype.byteOrder());
            assertEquals(4, dtype.itemWidthBytes());
        }
    }

    private static void assertDType(String descriptor, NpyDType.Kind kind,
                                    NpyDType.ByteOrder order, long width) {
        NpyDType dtype = NpyDType.parse(descriptor);
        assertEquals(descriptor, dtype.descriptor());
        assertEquals(kind, dtype.kind(), descriptor);
        assertEquals(order, dtype.byteOrder(), descriptor);
        assertEquals(width, dtype.itemWidthBytes(), descriptor);
    }

    private static NpyDType.ByteOrder order(String marker) {
        return switch (marker) {
            case "<" -> LITTLE_ENDIAN;
            case ">" -> BIG_ENDIAN;
            case "|" -> NOT_APPLICABLE;
            default -> NATIVE;
        };
    }
}
