package com.horako.javanpy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpyUnsupportedFixtureTest {
    @Test
    void rejectsNumpyPickledObjectAndStringDTypeWithoutReadingPayload() throws IOException {
        for (String name : List.of("object_pickle.npy", "string_dtype_pickle.npy")) {
            byte[] data;
            try (InputStream input = getClass().getResourceAsStream("/com/horako/javanpy/npy-values/" + name)) {
                assertNotNull(input, name);
                data = input.readAllBytes();
            }
            int headerLength = Byte.toUnsignedInt(data[8]) | Byte.toUnsignedInt(data[9]) << 8;
            int payloadStart = 10 + headerLength;
            assertEquals(0x80, Byte.toUnsignedInt(data[payloadStart]), name);
            assertEquals("|O", NpyFormat.readHeader(new ByteArrayInputStream(data)).descr(), name);

            PayloadGuard callerInput = new PayloadGuard(data, payloadStart);
            assertThrows(IOException.class, () -> NpyReader.open(callerInput), name);
            assertEquals(payloadStart, callerInput.position, name);
            assertFalse(callerInput.closed, name);

            PayloadGuard pathInput = new PayloadGuard(data, payloadStart);
            assertThrows(IOException.class, () -> NpyReader.open(TrackingPath.forInput(pathInput)), name);
            assertEquals(payloadStart, pathInput.position, name);
            assertTrue(pathInput.closed, name);
        }
    }

    private static final class PayloadGuard extends InputStream {
        private final byte[] bytes;
        private final int payloadStart;
        private int position;
        private boolean closed;

        private PayloadGuard(byte[] bytes, int payloadStart) {
            this.bytes = bytes;
            this.payloadStart = payloadStart;
        }

        @Override
        public int read() {
            if (position >= payloadStart) {
                throw new AssertionError("Reader attempted to consume the pickle payload");
            }
            return Byte.toUnsignedInt(bytes[position++]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
