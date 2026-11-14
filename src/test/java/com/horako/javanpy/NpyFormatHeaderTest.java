package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyFormatHeaderTest {
    private static final String VALID_HEADER =
            "{'descr': '<f8', 'fortran_order': False, 'shape': (2, 3), }";
    private static final int MAX_HEADER_SIZE = 1024 * 1024;

    @Test
    void readsMetadataForEverySupportedVersion() throws IOException {
        for (NpyVersion version : NpyVersion.values()) {
            NpyHeader header = NpyFormat.readHeader(input(version, VALID_HEADER));

            assertEquals("<f8", header.descr());
            assertFalse(header.fortranOrder());
            assertEquals(List.of(2L, 3L), header.shape());
        }
    }

    @Test
    void readsScalarShape() throws IOException {
        NpyHeader header = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': '|b1', 'fortran_order': False, 'shape': (), }"));

        assertEquals(List.of(), header.shape());
    }

    @Test
    void readsOneDimensionalShapeWithTrailingComma() throws IOException {
        NpyHeader header = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': '<i4', 'fortran_order': False, 'shape': (5,), }"));

        assertEquals(List.of(5L), header.shape());
    }

    @Test
    void readsZeroSizedDimensionsAndFortranOrder() throws IOException {
        NpyHeader header = NpyFormat.readHeader(input(NpyVersion.V2_0,
                "{'descr': '<f4', 'fortran_order': True, 'shape': (0, 3), }"));

        assertTrue(header.fortranOrder());
        assertEquals(List.of(0L, 3L), header.shape());
    }

    @Test
    void doesNotRejectShapeWhenDimensionProductOverflowsLong() throws IOException {
        NpyHeader header = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': '<f8', 'fortran_order': False, 'shape': (9223372036854775807, 2), }"));

        assertEquals(List.of(Long.MAX_VALUE, 2L), header.shape());
    }

    @Test
    void allowsWhitespaceAndHeaderPadding() throws IOException {
        NpyHeader header = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "  {  'descr' : \"<f8\" , 'shape' : ( 2 , 3 , ) , "
                        + "'fortran_order' : False , }   \t\r\n"));

        assertEquals(List.of(2L, 3L), header.shape());
    }

    @Test
    void parsesHexEscapesEscapedQuotesAndBackslashes() throws IOException {
        NpyHeader hexEscaped = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': '\\x3ci4', 'fortran_order': False, 'shape': (), }"));
        NpyHeader unicodeEscaped = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': '\\u003ci4', 'fortran_order': False, 'shape': (), }"));
        NpyHeader namedUnicodeEscaped = NpyFormat.readHeader(input(NpyVersion.V3_0,
                "{'descr': '\\N{LESS-THAN SIGN}i4', "
                        + "'fortran_order': False, 'shape': (), }"));
        NpyHeader quoteEscaped = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': 'a\\'b', 'fortran_order': False, 'shape': (), }"));
        NpyHeader backslashEscaped = NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': 'a\\\\b', 'fortran_order': False, 'shape': (), }"));

        assertEquals("<i4", hexEscaped.descr());
        assertEquals("<i4", unicodeEscaped.descr());
        assertEquals("<i4", namedUnicodeEscaped.descr());
        assertEquals("a'b", quoteEscaped.descr());
        assertEquals("a\\b", backslashEscaped.descr());
    }

    @Test
    void rejectsMalformedStringEscapesWithExplanation() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '\\x3', 'fortran_order': False, 'shape': (), }")));

        assertTrue(exception.getMessage().contains("hexadecimal escape"));
    }

    @Test
    void requiresTerminalNewlineButAllowsPaddingBeforeIt() throws IOException {
        byte[] paddedHeader = "{'descr': '<i4', 'fortran_order': False, 'shape': (), }   \n"
                .getBytes(StandardCharsets.US_ASCII);
        NpyHeader header = NpyFormat.readHeader(new ByteArrayInputStream(
                fileBytes(NpyVersion.V1_0, paddedHeader, new byte[0])));

        assertEquals("<i4", header.descr());
    }

    @Test
    void rejectsHeaderWithoutTerminalNewline() {
        byte[] header = "{'descr': '<i4', 'fortran_order': False, 'shape': (), }"
                .getBytes(StandardCharsets.US_ASCII);
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(
                        fileBytes(NpyVersion.V1_0, header, new byte[0]))));

        assertTrue(exception.getMessage().contains("must end with a newline"));
    }

    @Test
    void rejectsLeadingZerosInDimensionLiterals() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'fortran_order': False, 'shape': (01,), }")));

        assertTrue(exception.getMessage().contains("leading zeros"));
    }

    @Test
    void leavesStreamOpenAndPositionedAtArrayData() throws IOException {
        byte[] file = fileBytes(NpyVersion.V1_0, VALID_HEADER, new byte[]{0x12, 0x34});
        TrackingInputStream input = new TrackingInputStream(file);

        NpyFormat.readHeader(input);

        assertEquals(0x12, input.read());
        assertEquals(0x34, input.read());
        assertFalse(input.closed);
    }

    @Test
    void acceptsMaximumHeaderSize() throws IOException {
        String dictionary = "{'descr': '<f8', 'fortran_order': False, 'shape': (), }";
        byte[] body = paddedHeader(dictionary, NpyVersion.V2_0, MAX_HEADER_SIZE);

        NpyHeader header = NpyFormat.readHeader(new ByteArrayInputStream(
                fileBytes(NpyVersion.V2_0, body, new byte[0])));

        assertEquals("<f8", header.descr());
        assertEquals(List.of(), header.shape());
    }

    @Test
    void rejectsHeaderLargerThanMaximumBeforeReadingBody() {
        byte[] lengthOnly = fileBytes(NpyVersion.V2_0, MAX_HEADER_SIZE + 1L);
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(lengthOnly)));

        assertTrue(exception.getMessage().contains("exceeds maximum size"));
    }

    @Test
    void rejectsMissingFields() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'shape': (), }")));

        assertTrue(exception.getMessage().contains("Missing required NPY header field"));
    }

    @Test
    void rejectsDuplicateFields() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'descr': '<f8', "
                                + "'fortran_order': False, 'shape': (), }")));

        assertTrue(exception.getMessage().contains("Duplicate NPY header field"));
    }

    @Test
    void rejectsWrongFieldTypes() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'fortran_order': 0, 'shape': (), }")));

        assertTrue(exception.getMessage().contains("fortran_order must be a boolean"));
    }

    @Test
    void rejectsNegativeDimensions() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'fortran_order': False, 'shape': (-1,), }")));

        assertTrue(exception.getMessage().contains("dimensions must be nonnegative"));
    }

    @Test
    void rejectsDimensionsOutsideLongRange() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'fortran_order': False, "
                                + "'shape': (9223372036854775808,), }")));

        assertTrue(exception.getMessage().contains("exceeds the supported long range"));
    }

    @Test
    void rejectsNonTupleShape() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'fortran_order': False, 'shape': (5), }")));

        assertTrue(exception.getMessage().contains("shape must be a tuple"));
    }

    @Test
    void rejectsStructuredDtypeDescriptorWithExplanation() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': [('value', '<i4')], "
                                + "'fortran_order': False, 'shape': (2,), }")));

        assertTrue(exception.getMessage().contains("Structured dtype descriptors are not supported"));
    }

    @Test
    void rejectsUnexpectedFields() {
        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                        "{'descr': '<i4', 'fortran_order': False, 'shape': (), 'extra': 1, }")));

        assertTrue(exception.getMessage().contains("Unsupported NPY header field"));
    }

    @Test
    void rejectsMalformedDictionary() {
        assertThrows(IOException.class, () -> NpyFormat.readHeader(input(NpyVersion.V1_0,
                "{'descr': '<i4', 'fortran_order': False, 'shape': (2,)")));
    }

    @Test
    void rejectsInvalidAsciiInVersionOneHeader() {
        byte[] header = "{'descr': '<i4', 'fortran_order': False, 'shape': (), }"
                .concat(" ")
                .getBytes(StandardCharsets.US_ASCII);
        header[header.length - 1] = (byte) 0xFF;

        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(
                        fileBytes(NpyVersion.V1_0, header, new byte[0]))));

        assertTrue(exception.getMessage().contains("not valid US-ASCII"));
    }

    @Test
    void rejectsInvalidUtf8InVersionThreeHeader() {
        byte[] header = "{'descr': '<i4', 'fortran_order': False, 'shape': (), }".getBytes(
                StandardCharsets.UTF_8);
        header[header.length - 1] = (byte) 0xFF;

        IOException exception = assertThrows(IOException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(
                        fileBytes(NpyVersion.V3_0, header, new byte[0]))));

        assertTrue(exception.getMessage().contains("not valid UTF-8"));
    }

    @Test
    void rejectsTruncatedHeaderLengthAndBody() {
        byte[] complete = fileBytes(NpyVersion.V2_0, VALID_HEADER, new byte[0]);
        byte[] truncatedLength = Arrays.copyOf(complete, 9);
        assertThrows(EOFException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(truncatedLength)));

        byte[] truncatedBody = Arrays.copyOf(complete, complete.length - 1);
        assertThrows(EOFException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(truncatedBody)));
    }

    @Test
    void rejectsTruncatedVersionOneLength() {
        byte[] complete = fileBytes(NpyVersion.V1_0, VALID_HEADER, new byte[0]);
        byte[] truncated = Arrays.copyOf(complete, 9);

        assertThrows(EOFException.class,
                () -> NpyFormat.readHeader(new ByteArrayInputStream(truncated)));
    }

    @Test
    void recordMakesShapeImmutableAndValidatesDimensions() {
        List<Long> source = new java.util.ArrayList<>(List.of(2L, 3L));
        NpyHeader header = new NpyHeader("<i4", false, source);
        source.add(4L);

        assertEquals(List.of(2L, 3L), header.shape());
        assertThrows(UnsupportedOperationException.class, () -> header.shape().add(4L));
        assertThrows(IllegalArgumentException.class,
                () -> new NpyHeader("<i4", false, List.of(-1L)));
    }

    private static ByteArrayInputStream input(NpyVersion version, String header) {
        return new ByteArrayInputStream(fileBytes(version, header, new byte[0]));
    }

    private static byte[] fileBytes(NpyVersion version, String header, byte[] arrayData) {
        String paddedHeader = header.endsWith("\n") ? header : header + "\n";
        return fileBytes(version, paddedHeader.getBytes(
                        version == NpyVersion.V3_0 ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII),
                arrayData);
    }

    private static byte[] fileBytes(NpyVersion version, byte[] header, byte[] arrayData) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x93);
        output.writeBytes(new byte[]{'N', 'U', 'M', 'P', 'Y',
                (byte) version.major(), (byte) version.minor()});
        writeLength(output, version, header.length);
        output.writeBytes(header);
        output.writeBytes(arrayData);
        return output.toByteArray();
    }

    private static byte[] fileBytes(NpyVersion version, long headerSize) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x93);
        output.writeBytes(new byte[]{'N', 'U', 'M', 'P', 'Y',
                (byte) version.major(), (byte) version.minor()});
        writeLength(output, version, headerSize);
        return output.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream output, NpyVersion version, long length) {
        int lengthFieldSize = version == NpyVersion.V1_0 ? 2 : 4;
        for (int i = 0; i < lengthFieldSize; i++) {
            output.write((int) (length >>> (8 * i)) & 0xFF);
        }
    }

    private static byte[] paddedHeader(String dictionary, NpyVersion version, int targetSize) {
        byte[] content = dictionary.getBytes(
                version == NpyVersion.V3_0 ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII);
        int paddingSize = targetSize - content.length - 1;
        if (paddingSize < 0) {
            throw new IllegalArgumentException("Target header size is too small");
        }
        byte[] header = new byte[targetSize];
        System.arraycopy(content, 0, header, 0, content.length);
        Arrays.fill(header, content.length, targetSize - 1, (byte) ' ');
        header[targetSize - 1] = '\n';
        return header;
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
