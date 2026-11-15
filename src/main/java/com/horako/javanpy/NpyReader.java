package com.horako.javanpy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A single-use, non-thread-safe reader of numeric and boolean NPY values.
 * Values are read sequentially in physical file order, without reordering
 * Fortran-order arrays. No bytes beyond the declared payload are consumed.
 *
 * <p>Caller-provided streams are never closed. A reader opened from a path owns
 * its stream and must be closed, including after exhaustion or a read failure.
 * Closing early does not drain the payload.</p>
 *
 * <p>Typed reads reject incompatible dtypes without consuming input. Compatibility
 * is checked before exhaustion. Truncated payloads raise {@link java.io.EOFException}.
 * After an I/O failure, subsequent reads and {@link #hasNext()} throw
 * {@link IllegalStateException} with the original failure as their cause,
 * including after close.
 * Metadata remains available after failure or close.</p>
 */
public final class NpyReader implements AutoCloseable {
    private final InputStream input;
    private final boolean ownsInput;
    private final NpyVersion version;
    private final NpyHeader header;
    private final NpyDType dtype;
    private final NpyValueDecoder decoder;
    private final NpyReadOptions options;
    private boolean closed;

    private NpyReader(InputStream input, boolean ownsInput, NpyVersion version,
                      NpyHeader header, NpyDType dtype, NpyValueDecoder decoder, NpyReadOptions options) {
        this.input = input;
        this.ownsInput = ownsInput;
        this.version = version;
        this.header = header;
        this.dtype = dtype;
        this.decoder = decoder;
        this.options = options;
    }

    /** Opens a reader without taking ownership of the supplied stream. */
    public static NpyReader open(InputStream input) throws IOException {
        return open(input, NpyReadOptions.DEFAULT);
    }

    /**
     * Opens a reader without taking ownership of the supplied stream.
     * Initialization consumes the header but not the payload.
     *
     * @throws NullPointerException if input or options is null
     * @throws IOException if the input is truncated, invalid, unsupported, or cannot be read
     */
    public static NpyReader open(InputStream input, NpyReadOptions options) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        return initialize(input, false, options);
    }

    /** Opens a reader that owns the stream opened for the path. */
    public static NpyReader open(Path path) throws IOException {
        return open(path, NpyReadOptions.DEFAULT);
    }

    /**
     * Opens a reader that owns the stream opened for the path. If initialization
     * fails, the opened stream is closed.
     *
     * @throws NullPointerException if path or options is null
     * @throws IOException if the input is truncated, invalid, unsupported, or cannot be read
     */
    public static NpyReader open(Path path, NpyReadOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        InputStream input = Files.newInputStream(path);
        try {
            return initialize(input, true, options);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private static NpyReader initialize(InputStream input, boolean ownsInput, NpyReadOptions options) throws IOException {
        NpyVersion version = NpyFormat.readVersion(input);
        NpyHeader header = NpyFormat.readHeader(input, version);
        try {
            NpyDType dtype = NpyDType.parse(header.descr());
            long count = elementCount(header);
            NpyValueDecoder decoder = new NpyValueDecoder(input, dtype, count);
            return new NpyReader(input, ownsInput, version, header, dtype, decoder, options);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid or unsupported NPY array: " + exception.getMessage(), exception);
        }
    }

    private static long elementCount(NpyHeader header) {
        if (header.shape().contains(0L)) {
            return 0;
        }
        long count = 1;
        for (long dimension : header.shape()) {
            count = Math.multiplyExact(count, dimension);
        }
        return count;
    }

    /** Returns the NPY format version, including after failure or close. */
    public NpyVersion version() {
        return version;
    }

    /** Returns the parsed header, including after failure or close. */
    public NpyHeader header() {
        return header;
    }

    /** Returns the validated dtype, including after failure or close. */
    public NpyDType dtype() {
        return dtype;
    }

    /**
     * Returns whether the declared payload contains another element, without I/O.
     * This does not establish that the payload is complete.
     *
     * @throws IllegalStateException if closed or a previous read failed
     */
    public boolean hasNext() {
        ensureOpen();
        return decoder.hasNext();
    }

    /**
     * Reads a signed integer or an unsigned integer of at most 32 bits.
     *
     * @throws IllegalArgumentException if the dtype is incompatible
     * @throws java.util.NoSuchElementException if the payload is exhausted
     * @throws IllegalStateException if closed or a previous read failed
     * @throws IOException if the element cannot be completely read
     */
    public long readLong() throws IOException {
        ensureOpen();
        return decoder.readLong();
    }

    /**
     * Reads an unsigned 64-bit integer exactly.
     *
     * @throws IllegalArgumentException if the dtype is incompatible
     * @throws java.util.NoSuchElementException if the payload is exhausted
     * @throws IllegalStateException if closed or a previous read failed
     * @throws IOException if the element cannot be completely read
     */
    public BigInteger readUnsignedLong() throws IOException {
        ensureOpen();
        return decoder.readUnsignedLong();
    }

    /**
     * Reads a 32-bit floating-point value.
     *
     * @throws IllegalArgumentException if the dtype is incompatible
     * @throws java.util.NoSuchElementException if the payload is exhausted
     * @throws IllegalStateException if closed or a previous read failed
     * @throws IOException if the element cannot be completely read
     */
    public float readFloat() throws IOException {
        ensureOpen();
        return decoder.readFloat();
    }

    /**
     * Reads a 64-bit floating-point value.
     *
     * @throws IllegalArgumentException if the dtype is incompatible
     * @throws java.util.NoSuchElementException if the payload is exhausted
     * @throws IllegalStateException if closed or a previous read failed
     * @throws IOException if the element cannot be completely read
     */
    public double readDouble() throws IOException {
        ensureOpen();
        return decoder.readDouble();
    }

    /**
     * Reads a boolean; zero is false and any nonzero byte is true.
     *
     * @throws IllegalArgumentException if the dtype is incompatible
     * @throws java.util.NoSuchElementException if the payload is exhausted
     * @throws IllegalStateException if closed or a previous read failed
     * @throws IOException if the element cannot be completely read
     */
    public boolean readBoolean() throws IOException {
        ensureOpen();
        return decoder.readBoolean();
    }

    /**
     * Closes this reader without draining the payload. Only a stream opened by
     * the path overload is closed. Repeated calls do nothing, even if closing
     * the owned stream previously failed.
     *
     * @throws IOException if closing an owned stream fails
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (ownsInput) {
                input.close();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("NPY reader is closed", decoder.failure());
        }
    }
}
