package com.horako.javanpy;

/**
 * Immutable options for reading an NPY array.
 *
 * @param preserveStringPadding whether to preserve trailing NUL padding in
 *                              fixed-width byte strings; string reading is not yet supported
 */
public record NpyReadOptions(boolean preserveStringPadding) {
    /** Default options, which strip trailing byte-string padding when string reading is supported. */
    public static final NpyReadOptions DEFAULT = new NpyReadOptions(false);
}
