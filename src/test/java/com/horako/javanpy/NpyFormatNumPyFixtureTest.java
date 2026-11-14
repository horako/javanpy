package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpyFormatNumPyFixtureTest {
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("v1_scalar.npy", NpyVersion.V1_0, List.of(), false),
            new Fixture("v1_vector.npy", NpyVersion.V1_0, List.of(4L), false),
            new Fixture("v1_matrix_c.npy", NpyVersion.V1_0, List.of(2L, 3L), false),
            new Fixture("v1_matrix_fortran.npy", NpyVersion.V1_0, List.of(2L, 3L), true),
            new Fixture("v2_matrix_c.npy", NpyVersion.V2_0, List.of(2L, 3L), false),
            new Fixture("v2_matrix_fortran.npy", NpyVersion.V2_0, List.of(2L, 3L), true),
            new Fixture("v3_matrix_c.npy", NpyVersion.V3_0, List.of(2L, 3L), false),
            new Fixture("v3_matrix_fortran.npy", NpyVersion.V3_0, List.of(2L, 3L), true));

    @Test
    void readsMetadataFromNumpyGeneratedFiles() throws IOException {
        for (Fixture fixture : FIXTURES) {
            try (InputStream versionInput = fixture.open()) {
                assertEquals(fixture.version(), NpyFormat.readVersion(versionInput), fixture.name());
            }

            try (InputStream headerInput = fixture.open()) {
                NpyHeader header = NpyFormat.readHeader(headerInput);

                assertEquals("<i4", header.descr(), fixture.name());
                assertEquals(fixture.fortranOrder(), header.fortranOrder(), fixture.name());
                assertEquals(fixture.shape(), header.shape(), fixture.name());
            }
        }
    }

    private record Fixture(String name, NpyVersion version, List<Long> shape, boolean fortranOrder) {
        private InputStream open() throws IOException {
            InputStream input = NpyFormatNumPyFixtureTest.class.getResourceAsStream(
                    "/com/horako/javanpy/npy-header/" + name);
            if (input == null) {
                throw new IOException("Missing NumPy-generated test fixture: " + name);
            }
            return input;
        }
    }
}
