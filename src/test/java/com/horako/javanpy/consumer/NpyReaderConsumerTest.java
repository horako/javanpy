package com.horako.javanpy.consumer;

import com.horako.javanpy.NpyDType;
import com.horako.javanpy.NpyHeader;
import com.horako.javanpy.NpyReadOptions;
import com.horako.javanpy.NpyReader;
import com.horako.javanpy.NpyVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyReaderConsumerTest {
    @Test
    void usesOnlyPublicApiFromAnotherPackage(@TempDir Path temp) throws IOException {
        Path path = temp.resolve("array.npy");
        try (InputStream fixture = getClass().getResourceAsStream(
                "/com/horako/javanpy/npy-header/v1_vector.npy")) {
            assertNotNull(fixture);
            Files.copy(fixture, path);
        }

        NpyReadOptions options = new NpyReadOptions(true);
        assertTrue(options.preserveStringPadding());
        assertFalse(NpyReadOptions.DEFAULT.preserveStringPadding());
        try (NpyReader reader = NpyReader.open(path, options)) {
            NpyVersion version = reader.version();
            NpyHeader header = reader.header();
            NpyDType dtype = reader.dtype();
            assertEquals(NpyVersion.V1_0, version);
            assertEquals(List.of(4L), header.shape());
            assertEquals(NpyDType.Kind.SIGNED_INTEGER, dtype.kind());
            for (int value = 1; value <= 4; value++) {
                assertTrue(reader.hasNext());
                assertEquals(value, reader.readLong());
            }
            assertFalse(reader.hasNext());
        }
        try (InputStream input = Files.newInputStream(path);
             NpyReader reader = NpyReader.open(input)) {
            assertEquals(1, reader.readLong());
        }
    }
}
