# javanpy

Java library for reading NumPy .npy files

The library reads and validates NPY metadata headers and scalar dtype descriptors,
and streams numeric and boolean array values in physical file order. Eager arrays
and string value decoding are not implemented.

```java
try (NpyReader reader = NpyReader.open(path)) {
    while (reader.hasNext()) {
        long value = reader.readLong(); // signed integers and uint8/16/32
    }
}
```

`NpyReader.open(Path)` owns its stream; `open(InputStream)` never closes the
caller's stream, even on failure. Closing early does not drain the payload.
Streaming preserves the file's physical C or Fortran order. Select the accessor
matching the dtype: `readUnsignedLong()` returns a `BigInteger` for uint64,
`readFloat()` reads float32, `readDouble()` reads float64, and `readBoolean()`
reads booleans. Incompatible accessors fail before consuming an element.
`hasNext()` checks the declared element count without checking for truncation.
After an I/O failure, the reader cannot continue; metadata remains available.
`NpyReadOptions.DEFAULT` sets `preserveStringPadding` to false; this option has
no effect until string reading is supported.

Parse a header's scalar descriptor explicitly with
`NpyDType.parse(header.descr())`. Header parsing itself remains independent of
dtype validation. The dtype retains the original descriptor and exposes its
kind, declared byte order and item width in bytes. Unicode descriptor widths
count UTF-32 code points, each occupying four bytes. The parser supports signed
and unsigned integers, 32- and 64-bit floats, booleans, and fixed-width byte
and Unicode strings, including zero-width strings.

Malformed or unsupported descriptors raise `IllegalArgumentException`;
structured and object dtypes are not supported. An omitted byte-order marker
means native byte order on the reading platform. The `|` marker is accepted
only where byte order is irrelevant: one-byte integers, booleans, byte strings,
and zero-width Unicode strings. Equality and hash codes use the original
descriptor string exactly, so equivalent dtypes with different spellings are
distinct.

## InputStream buffering and performance

Current header and payload reads operate one byte at a time, so unbuffered file
streams can be significantly slower when reading large arrays. javanpy
deliberately leaves buffering decisions to the caller, including buffer size
and stream composition:

```java
try (InputStream input = new BufferedInputStream(
        Files.newInputStream(path), 64 * 1024);
     NpyReader reader = NpyReader.open(input)) {

    while (reader.hasNext()) {
        float value = reader.readFloat();
        // Process value
    }
}
```

In an illustrative local benchmark using a 3932 × 3932 float32 matrix
(approximately 59 MiB), an unbuffered `InputStream` took 23.480 seconds,
while a `BufferedInputStream` with a 64 KiB buffer took 0.308 seconds, an
observed speedup of approximately 76×. These observations are
environment-specific, not guaranteed performance characteristics.

An externally buffered stream may prefetch data beyond the NPY payload. If
additional data follows the payload, continue reading through the same buffered
stream.

## Requirements

- Java 21
- Maven

## Build and test

Run the unit tests with:

```sh
mvn test
```

Build the project with:

```sh
mvn package
```

## Regenerating NPY test fixtures

The checked-in `.npy` fixtures under
`src/test/resources/com/horako/javanpy/npy-header/` and
`src/test/resources/com/horako/javanpy/npy-values/` were generated with NumPy
2.3.3. Regenerate them with an isolated Python environment:

```sh
python3 -m venv .venv-npy-fixtures
. .venv-npy-fixtures/bin/activate
python -m pip install numpy==2.3.3
python tools/generate_npy_fixtures.py
deactivate
```

Python and NumPy are needed only to regenerate the committed binary fixtures;
the Java tests load those resources and run with Maven alone.
The object and `StringDType` rejection fixtures contain pickle payloads generated
from fixed test values; the Java reader rejects their headers without reading or
deserializing those payloads. To regenerate only these two fixtures, run
`python tools/generate_npy_fixtures.py --unsupported-only` in the same environment.
