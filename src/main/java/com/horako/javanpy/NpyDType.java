package com.horako.javanpy;

import java.util.Objects;

/**
 * An immutable, validated scalar NumPy dtype descriptor.
 *
 * <p>Equality and hash codes are based on the original descriptor string.
 * Different spellings remain distinct even when they describe equivalent
 * types, including byte-order markers, leading zeroes and boolean aliases.</p>
 */
public final class NpyDType {
    /** Supported scalar dtype kinds. */
    public enum Kind {
        SIGNED_INTEGER, UNSIGNED_INTEGER, FLOAT, BOOLEAN, BYTE_STRING, UNICODE
    }

    /**
     * The descriptor's byte-order marker. An omitted marker means {@link #NATIVE}.
     * A future decoder must resolve native order on the reading platform.
     */
    public enum ByteOrder {
        LITTLE_ENDIAN, BIG_ENDIAN, NATIVE, NOT_APPLICABLE
    }

    private final String descriptor;
    private final Kind kind;
    private final ByteOrder byteOrder;
    private final long itemWidthBytes;

    private NpyDType(String descriptor, Kind kind, ByteOrder byteOrder, long itemWidthBytes) {
        this.descriptor = descriptor;
        this.kind = kind;
        this.byteOrder = byteOrder;
        this.itemWidthBytes = itemWidthBytes;
    }

    /**
     * Parses a scalar descriptor without reading any array data.
     *
     * <p>The optional order marker is {@code <}, {@code >}, {@code =} or
     * {@code |}; if omitted, it is native. The independent marker {@code |}
     * is permitted only for one-byte integers, booleans, byte strings and
     * zero-width Unicode strings.
     * Widths are required except for the boolean alias {@code ?}. Leading
     * zeroes are allowed. For Unicode, the width is a count of UTF-32 code
     * points and each code point occupies four bytes. Zero-width strings are
     * supported.</p>
     *
     * @param descriptor the original scalar descriptor
     * @return the validated dtype
     * @throws NullPointerException if descriptor is null
     * @throws IllegalArgumentException if the descriptor is malformed,
     *         unsupported, or its item width exceeds the long range
     */
    public static NpyDType parse(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.isEmpty()) {
            throw invalid(descriptor, "missing dtype kind");
        }

        int position = 0;
        ByteOrder order = switch (descriptor.charAt(0)) {
            case '<' -> ByteOrder.LITTLE_ENDIAN;
            case '>' -> ByteOrder.BIG_ENDIAN;
            case '=' -> ByteOrder.NATIVE;
            case '|' -> ByteOrder.NOT_APPLICABLE;
            default -> null;
        };
        if (order == null) {
            order = ByteOrder.NATIVE;
        } else {
            position++;
        }
        if (position == descriptor.length()) {
            throw invalid(descriptor, "missing dtype kind");
        }

        char code = descriptor.charAt(position++);
        Kind kind = switch (code) {
            case 'i' -> Kind.SIGNED_INTEGER;
            case 'u' -> Kind.UNSIGNED_INTEGER;
            case 'f' -> Kind.FLOAT;
            case 'b', '?' -> Kind.BOOLEAN;
            case 'S' -> Kind.BYTE_STRING;
            case 'U' -> Kind.UNICODE;
            default -> throw invalid(descriptor, "unsupported dtype kind");
        };

        long width;
        if (code == '?') {
            if (position != descriptor.length()) {
                throw invalid(descriptor, "boolean alias must not have a width");
            }
            width = 1;
        } else {
            if (position == descriptor.length()) {
                throw invalid(descriptor, "missing dtype width");
            }
            long declaredWidth = 0;
            while (position < descriptor.length()) {
                char digit = descriptor.charAt(position++);
                if (digit < '0' || digit > '9') {
                    throw invalid(descriptor, "width must contain only ASCII digits");
                }
                int value = digit - '0';
                if (declaredWidth > (Long.MAX_VALUE - value) / 10) {
                    throw invalid(descriptor, "dtype width exceeds the supported long range");
                }
                declaredWidth = declaredWidth * 10 + value;
            }
            if (kind == Kind.UNICODE) {
                if (declaredWidth > Long.MAX_VALUE / 4) {
                    throw invalid(descriptor, "Unicode item width exceeds the supported long range");
                }
                width = declaredWidth * 4;
            } else {
                width = declaredWidth;
            }
        }

        boolean validWidth = switch (kind) {
            case SIGNED_INTEGER, UNSIGNED_INTEGER ->
                    width == 1 || width == 2 || width == 4 || width == 8;
            case FLOAT -> width == 4 || width == 8;
            case BOOLEAN -> width == 1;
            case BYTE_STRING, UNICODE -> true;
        };
        if (!validWidth) {
            throw invalid(descriptor, "unsupported dtype width");
        }
        boolean orderIndependent = kind == Kind.BYTE_STRING || kind == Kind.BOOLEAN
                || (kind == Kind.UNICODE && width == 0)
                || ((kind == Kind.SIGNED_INTEGER || kind == Kind.UNSIGNED_INTEGER) && width == 1);
        if (order == ByteOrder.NOT_APPLICABLE && !orderIndependent) {
            throw invalid(descriptor, "byte-order-independent marker is invalid for this dtype");
        }
        return new NpyDType(descriptor, kind, order, width);
    }

    /** Returns the descriptor exactly as supplied to {@link #parse(String)}. */
    public String descriptor() {
        return descriptor;
    }

    /** Returns the dtype kind. */
    public Kind kind() {
        return kind;
    }

    /** Returns the declared byte order, or native if the marker was omitted. */
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    /** Returns the number of bytes per item, including four per Unicode code point. */
    public long itemWidthBytes() {
        return itemWidthBytes;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NpyDType dtype)) {
            return false;
        }
        return descriptor.equals(dtype.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }

    private static IllegalArgumentException invalid(String descriptor, String reason) {
        return new IllegalArgumentException("Invalid or unsupported dtype descriptor '" + descriptor + "': " + reason);
    }
}
