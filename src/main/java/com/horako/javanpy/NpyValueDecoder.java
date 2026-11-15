package com.horako.javanpy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Decodes numeric and boolean elements in physical payload order.
 * The supplied stream remains owned by the caller.
 */
final class NpyValueDecoder {
    private final InputStream input;
    private final NpyDType dtype;
    private final int width;
    private final boolean littleEndian;
    private final byte[] scratch = new byte[8];
    private long remaining;
    private IOException failure;

    NpyValueDecoder(InputStream input, NpyDType dtype, long elementCount) {
        this.input = Objects.requireNonNull(input, "input");
        this.dtype = Objects.requireNonNull(dtype, "dtype");
        if (elementCount < 0) {
            throw new IllegalArgumentException("Element count must be nonnegative");
        }
        if (dtype.kind() == NpyDType.Kind.BYTE_STRING || dtype.kind() == NpyDType.Kind.UNICODE) {
            throw new IllegalArgumentException("Unsupported decoder dtype: " + dtype.descriptor());
        }
        if (elementCount > Long.MAX_VALUE / dtype.itemWidthBytes()) {
            throw new IllegalArgumentException("NPY payload size exceeds the supported long range");
        }
        this.width = Math.toIntExact(dtype.itemWidthBytes());
        this.littleEndian = switch (dtype.byteOrder()) {
            case LITTLE_ENDIAN -> true;
            case BIG_ENDIAN, NOT_APPLICABLE -> false;
            case NATIVE -> ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        };
        this.remaining = elementCount;
    }

    boolean hasNext() {
        ensureUsable();
        return remaining > 0;
    }

    IOException failure() {
        return failure;
    }

    long readLong() throws IOException {
        ensureReadable(dtype.kind() == NpyDType.Kind.SIGNED_INTEGER
                || (dtype.kind() == NpyDType.Kind.UNSIGNED_INTEGER && width < Long.BYTES));
        long bits = readBits();
        long value = dtype.kind() == NpyDType.Kind.SIGNED_INTEGER
                ? (bits << (Long.SIZE - width * Byte.SIZE)) >> (Long.SIZE - width * Byte.SIZE)
                : bits;
        remaining--;
        return value;
    }

    BigInteger readUnsignedLong() throws IOException {
        ensureReadable(dtype.kind() == NpyDType.Kind.UNSIGNED_INTEGER && width == Long.BYTES);
        long bits = readBits();
        BigInteger value = BigInteger.valueOf(bits & Long.MAX_VALUE);
        if (bits < 0) {
            value = value.setBit(Long.SIZE - 1);
        }
        remaining--;
        return value;
    }

    float readFloat() throws IOException {
        ensureReadable(dtype.kind() == NpyDType.Kind.FLOAT && width == Float.BYTES);
        float value = Float.intBitsToFloat((int) readBits());
        remaining--;
        return value;
    }

    double readDouble() throws IOException {
        ensureReadable(dtype.kind() == NpyDType.Kind.FLOAT && width == Double.BYTES);
        double value = Double.longBitsToDouble(readBits());
        remaining--;
        return value;
    }

    boolean readBoolean() throws IOException {
        ensureReadable(dtype.kind() == NpyDType.Kind.BOOLEAN);
        boolean value = readBits() != 0;
        remaining--;
        return value;
    }

    private void ensureReadable(boolean compatible) {
        ensureUsable();
        if (!compatible) {
            throw new IllegalArgumentException("Incompatible read for dtype " + dtype.descriptor());
        }
        if (remaining == 0) {
            throw new NoSuchElementException("NPY payload is exhausted");
        }
    }

    private void ensureUsable() {
        if (failure != null) {
            throw new IllegalStateException("NPY decoder cannot be used after an I/O failure", failure);
        }
    }

    private long readBits() throws IOException {
        try {
            for (int i = 0; i < width; i++) {
                int next = input.read();
                if (next < 0) {
                    throw new EOFException("Truncated NPY payload: expected " + width
                            + " bytes for an element, received " + i);
                }
                scratch[i] = (byte) next;
            }
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        }

        long bits = 0;
        for (int i = 0; i < width; i++) {
            int index = littleEndian ? width - 1 - i : i;
            bits = (bits << Byte.SIZE) | Byte.toUnsignedLong(scratch[index]);
        }
        return bits;
    }
}
