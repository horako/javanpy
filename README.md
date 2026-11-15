# javanpy

Java library for reading NumPy .npy files

The library reads and validates NPY metadata headers and provides a validated
model for supported scalar dtype descriptors. Array data reading is not
implemented.

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

## Regenerating NPY header test fixtures

The checked-in `.npy` fixtures under
`src/test/resources/com/horako/javanpy/npy-header/` were generated with
NumPy 2.3.3. Regenerate them with an isolated Python environment:

```sh
python3 -m venv .venv-npy-fixtures
. .venv-npy-fixtures/bin/activate
python -m pip install numpy==2.3.3
python tools/generate_npy_fixtures.py
deactivate
```

Python and NumPy are needed only to regenerate the committed binary fixtures;
the Java tests load those resources and run with Maven alone.
