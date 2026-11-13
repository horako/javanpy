package com.horako.javanpy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Utilities for recognizing NPY files.
 */
public final class NpyFormat {
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

    private static EOFException truncatedHeader() {
        return new EOFException("Truncated NPY header: expected an 8-byte magic and version prefix");
    }
}
