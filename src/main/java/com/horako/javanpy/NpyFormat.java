package com.horako.javanpy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utilities for reading NPY file headers.
 */
public final class NpyFormat {
    private static final int MAX_HEADER_SIZE = 1024 * 1024;
    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final byte[] MAGIC = {
            (byte) 0x93, 'N', 'U', 'M', 'P', 'Y'
    };

    private NpyFormat() {
    }

    /**
     * Reads and validates the eight-byte NPY magic and version prefix.
     * The input stream remains open and is positioned immediately after the prefix.
     *
     * @param input the stream to read
     * @return the supported NPY version
     * @throws EOFException if the complete eight-byte prefix is not available
     * @throws IOException if the magic prefix is invalid or the version is unsupported
     */
    public static NpyVersion readVersion(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");

        byte[] magic = input.readNBytes(MAGIC.length);
        if (magic.length != MAGIC.length) {
            throw truncatedHeader();
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("Invalid NPY magic prefix");
            }
        }

        byte[] version = input.readNBytes(2);
        if (version.length != 2) {
            throw truncatedHeader();
        }

        int major = Byte.toUnsignedInt(version[0]);
        int minor = Byte.toUnsignedInt(version[1]);
        for (NpyVersion supported : NpyVersion.values()) {
            if (supported.major() == major && supported.minor() == minor) {
                return supported;
            }
        }
        throw new IOException("Unsupported NPY format version: " + major + "." + minor);
    }

    /**
     * Reads and validates an NPY header, leaving the input positioned at the array data.
     * The input stream remains open. Structured dtype descriptors are not supported.
     *
     * @param input the stream positioned at the beginning of an NPY file
     * @return the metadata declared by the NPY header
     * @throws EOFException if the version prefix, header length, or header is truncated
     * @throws IOException if the file or header is invalid or unsupported
     */
    public static NpyHeader readHeader(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");

        NpyVersion version = readVersion(input);
        int lengthFieldSize = version == NpyVersion.V1_0 ? 2 : 4;
        byte[] lengthBytes = input.readNBytes(lengthFieldSize);
        if (lengthBytes.length != lengthFieldSize) {
            throw new EOFException("Truncated NPY header length");
        }

        long headerSize = readUnsignedLittleEndian(lengthBytes);
        if (headerSize > MAX_HEADER_SIZE) {
            throw new IOException("NPY header exceeds maximum size of " + MAX_HEADER_SIZE + " bytes");
        }

        byte[] headerBytes = input.readNBytes((int) headerSize);
        if (headerBytes.length != headerSize) {
            throw new EOFException("Truncated NPY header: expected " + headerSize
                    + " bytes, received " + headerBytes.length);
        }

        Charset encoding = version == NpyVersion.V3_0 ? StandardCharsets.UTF_8 : ASCII;
        String header = decodeHeader(headerBytes, encoding);
        if (!header.endsWith("\n")) {
            throw new IOException("NPY header must end with a newline");
        }
        return new HeaderParser(header).parse();
    }

    private static long readUnsignedLittleEndian(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return bytes.length == 2 ? Short.toUnsignedInt(buffer.getShort())
                : Integer.toUnsignedLong(buffer.getInt());
    }

    private static String decodeHeader(byte[] bytes, Charset encoding) throws IOException {
        try {
            return encoding.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("NPY header is not valid " + encoding.name(), exception);
        }
    }

    private static EOFException truncatedHeader() {
        return new EOFException("Truncated NPY header: expected an 8-byte magic and version prefix");
    }

    private static final class HeaderParser {
        private final String text;
        private final Set<String> fields = new HashSet<>();
        private int position;

        private HeaderParser(String text) {
            this.text = text;
        }

        private NpyHeader parse() throws IOException {
            expect('{');
            skipWhitespace();

            String descr = null;
            Boolean fortranOrder = null;
            List<Long> shape = null;
            if (!consume('}')) {
                while (true) {
                    String field = parseString();
                    if (!fields.add(field)) {
                        throw error("Duplicate NPY header field: " + field);
                    }
                    expect(':');

                    switch (field) {
                        case "descr" -> descr = parseDescr();
                        case "fortran_order" -> fortranOrder = parseFortranOrder();
                        case "shape" -> shape = parseShape();
                        default -> throw error("Unsupported NPY header field: " + field);
                    }

                    skipWhitespace();
                    if (consume('}')) {
                        break;
                    }
                    expect(',');
                    skipWhitespace();
                    if (consume('}')) {
                        break;
                    }
                }
            }

            skipWhitespace();
            if (position != text.length()) {
                throw error("Unexpected content after NPY header dictionary");
            }
            if (descr == null) {
                throw error("Missing required NPY header field: descr");
            }
            if (fortranOrder == null) {
                throw error("Missing required NPY header field: fortran_order");
            }
            if (shape == null) {
                throw error("Missing required NPY header field: shape");
            }

            return new NpyHeader(descr, fortranOrder, shape);
        }

        private String parseDescr() throws IOException {
            skipWhitespace();
            if (peek() == '[') {
                throw error("Structured dtype descriptors are not supported");
            }
            return parseString();
        }

        private boolean parseFortranOrder() throws IOException {
            skipWhitespace();
            if (consumeWord("True")) {
                return true;
            }
            if (consumeWord("False")) {
                return false;
            }
            throw error("NPY header field fortran_order must be a boolean");
        }

        private List<Long> parseShape() throws IOException {
            expect('(');
            skipWhitespace();
            List<Long> dimensions = new ArrayList<>();
            if (consume(')')) {
                return dimensions;
            }

            while (true) {
                dimensions.add(parseDimension());
                skipWhitespace();
                if (consume(',')) {
                    skipWhitespace();
                    if (consume(')')) {
                        return dimensions;
                    }
                } else if (dimensions.size() > 1 && consume(')')) {
                    return dimensions;
                } else {
                    throw error("NPY header field shape must be a tuple");
                }
            }
        }

        private long parseDimension() throws IOException {
            skipWhitespace();
            if (consume('-')) {
                throw error("NPY shape dimensions must be nonnegative");
            }
            consume('+');
            int start = position;
            while (position < text.length() && isDigit(text.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("Expected a nonnegative integer shape dimension");
            }
            if (position - start > 1 && text.charAt(start) == '0') {
                throw error("NPY shape dimensions must not contain leading zeros");
            }

            try {
                return Long.parseLong(text.substring(start, position));
            } catch (NumberFormatException exception) {
                throw new IOException("NPY shape dimension exceeds the supported long range", exception);
            }
        }

        private String parseString() throws IOException {
            skipWhitespace();
            if (position >= text.length()
                    || (text.charAt(position) != '\'' && text.charAt(position) != '"')) {
                throw error("Expected a quoted string");
            }

            char quote = text.charAt(position++);
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == quote) {
                    return value.toString();
                }
                if (character == '\\') {
                    if (position >= text.length()) {
                        throw error("Unterminated escape in quoted string");
                    }
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '\\', '\'', '"' -> value.append(escaped);
                        case 'a' -> value.append('\u0007');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'v' -> value.append('\u000b');
                        case 'x' -> value.append((char) parseHexEscape(2, "hexadecimal"));
                        case 'u' -> value.append((char) parseHexEscape(4, "Unicode"));
                        case 'U' -> {
                            long codePoint = parseHexEscape(8, "Unicode");
                            if (codePoint > Character.MAX_CODE_POINT) {
                                throw error("Malformed Unicode escape: code point is out of range");
                            }
                            value.appendCodePoint((int) codePoint);
                        }
                        case 'N' -> value.appendCodePoint(parseNamedUnicodeEscape());
                        case '0', '1', '2', '3', '4', '5', '6', '7' ->
                                value.append((char) parseOctalEscape(escaped));
                        case '\n' -> {
                        }
                        case '\r' -> {
                            if (position < text.length() && text.charAt(position) == '\n') {
                                position++;
                            }
                        }
                        default -> throw error("Unsupported escape sequence: \\" + escaped);
                    }
                } else {
                    if (character == '\n' || character == '\r') {
                        throw error("Unterminated quoted string");
                    }
                    value.append(character);
                }
            }
            throw error("Unterminated quoted string");
        }

        private long parseHexEscape(int digitCount, String escapeName) throws IOException {
            if (text.length() - position < digitCount) {
                throw error("Malformed " + escapeName + " escape: expected "
                        + digitCount + " hexadecimal digits");
            }
            long value = 0;
            for (int i = 0; i < digitCount; i++) {
                int digit = hexValue(text.charAt(position++));
                if (digit < 0) {
                    throw error("Malformed " + escapeName + " escape: expected "
                            + digitCount + " hexadecimal digits");
                }
                value = value * 16 + digit;
            }
            return value;
        }

        private int parseNamedUnicodeEscape() throws IOException {
            if (!consume('{')) {
                throw error("Malformed Unicode name escape: expected '{'");
            }
            int start = position;
            while (position < text.length() && text.charAt(position) != '}') {
                position++;
            }
            if (position == text.length() || position == start) {
                throw error("Malformed Unicode name escape");
            }
            String name = text.substring(start, position++);
            try {
                return Character.codePointOf(name);
            } catch (IllegalArgumentException exception) {
                throw error("Malformed Unicode name escape: unknown character name");
            }
        }

        private int parseOctalEscape(char firstDigit) {
            int value = firstDigit - '0';
            int digits = 1;
            while (digits < 3 && position < text.length()) {
                char character = text.charAt(position);
                if (character < '0' || character > '7') {
                    break;
                }
                value = value * 8 + character - '0';
                position++;
                digits++;
            }
            return value;
        }

        private static int hexValue(char character) {
            if (character >= '0' && character <= '9') {
                return character - '0';
            }
            if (character >= 'a' && character <= 'f') {
                return character - 'a' + 10;
            }
            if (character >= 'A' && character <= 'F') {
                return character - 'A' + 10;
            }
            return -1;
        }

        private boolean consumeWord(String word) {
            if (!text.startsWith(word, position)) {
                return false;
            }
            int end = position + word.length();
            if (end < text.length() && isIdentifierCharacter(text.charAt(end))) {
                return false;
            }
            position = end;
            return true;
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private char peek() {
            return position < text.length() ? text.charAt(position) : '\0';
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private IOException error(String message) {
            return new IOException(message + " at header offset " + position);
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private static boolean isIdentifierCharacter(char character) {
            return Character.isLetterOrDigit(character) || character == '_';
        }
    }
}
