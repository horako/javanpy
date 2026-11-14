package com.horako.javanpy;

import java.util.List;
import java.util.Objects;

/**
 * Metadata declared by an NPY file header.
 *
 * <p>The dtype descriptor is preserved as written. Structured dtype descriptors are
 * not supported by the current header reader.</p>
 *
 * @param descr the scalar string dtype descriptor
 * @param fortranOrder whether the array data is stored in Fortran order
 * @param shape the array dimensions; an empty list represents a scalar
 */
public record NpyHeader(String descr, boolean fortranOrder, List<Long> shape) {
    public NpyHeader {
        Objects.requireNonNull(descr, "descr");
        Objects.requireNonNull(shape, "shape");
        for (Long dimension : shape) {
            Objects.requireNonNull(dimension, "shape dimension");
            if (dimension < 0) {
                throw new IllegalArgumentException("Shape dimensions must be nonnegative");
            }
        }
        shape = List.copyOf(shape);
    }
}
