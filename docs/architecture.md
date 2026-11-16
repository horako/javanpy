# Architecture

## Goals and scope

Provide a Java 21 API for reading NumPy `.npy` arrays from non-seekable streams without additional runtime dependencies. The design supports:

- Sequential streaming in physical file order.
- Eager arrays with logical multidimensional indexing.
- Numeric, boolean, and fixed-width string dtypes.
- Explicit resource ownership and bounded streaming memory.
- Compatibility with the existing `NpyFormat`, `NpyHeader`, and `NpyVersion` APIs.

Python pickle deserialization is out of scope.

## Public API sketch

Names and signatures are illustrative.

```java
// The caller owns the InputStream.
NpyArray array = NpyReader.read(input); // Default options strip trailing S padding.
NpyArray paddedArray = NpyReader.read(inputWithPadding, new NpyReadOptions(true));

NpyVersion version = array.version();
NpyHeader header = array.header();
NpyDType dtype = array.dtype();

long integer = array.getLong(1, 2);
String unicode = array.getString(1, 2);          // U dtype
byte[] bytes = array.getBytes(1, 2);              // S dtype; behavior set by read options
String decoded = array.getString(charset, 1, 2);  // S dtype
```

```java
// The reader owns the stream opened for the Path.
try (NpyReader reader = NpyReader.open(path, new NpyReadOptions(true))) {
    NpyVersion version = reader.version();
    NpyHeader header = reader.header();
    NpyDType dtype = reader.dtype();

    while (reader.hasNext()) {
        byte[] bytes = reader.readBytes(); // S dtype; preserves trailing NUL padding
    }
}
```

`NpyReadOptions` is an immutable configuration object. Its smallest practical public API is:

```java
public record NpyReadOptions(boolean preserveStringPadding) {
    public static final NpyReadOptions DEFAULT = new NpyReadOptions(false);
}
```

The existing no-options `NpyReader.open(...)` and `NpyReader.read(...)` methods use `NpyReadOptions.DEFAULT`. Options-aware overloads accept `NpyReadOptions` for both `InputStream` and `Path` variants. The options selected when opening a reader or creating an eager array remain fixed for its lifetime; `readBytes()` and `getBytes(indices)` take no padding-related boolean parameter.

### API responsibilities

| API | Responsibility |
| --- | --- |
| `NpyReader.open(InputStream)` | Open a sequential reader without taking ownership of the input. |
| `NpyReader.open(InputStream, NpyReadOptions)` | Open a sequential reader using the supplied options without taking ownership of the input. |
| `NpyReader.open(Path)` | Open an owned stream and return a closeable reader. |
| `NpyReader.open(Path, NpyReadOptions)` | Open an owned stream and return a closeable reader using the supplied options. |
| `NpyReader.read(InputStream)` | Read an eager array without closing the caller’s input. |
| `NpyReader.read(InputStream, NpyReadOptions)` | Read an eager array using the supplied options without closing the caller’s input. |
| `NpyReader.read(Path)` | Read an eager array and close the internally opened stream. |
| `NpyReader.read(Path, NpyReadOptions)` | Read an eager array using the supplied options and close the internally opened stream. |
| `NpyReader` | Expose version, header, dtype, and typed sequential reads. |
| `NpyArray` | Expose version, header, dtype, and typed logical indexing. |
| `NpyDType` | Describe the original descriptor, dtype kind, item width, and byte order. |
| `NpyReadOptions` | Immutable read configuration, including whether fixed-width `S` padding is preserved. |

Typed accessors reject incompatible dtypes rather than silently converting values with possible precision loss. A type mismatch must be detected before consuming the element.

The initial public API does not include bulk reads. The internal decoder should nevertheless permit future bulk reading into caller-provided primitive arrays without a separate decoding implementation. Bulk range and partial-failure semantics can be designed when the feature is justified.

**Alternative:** Java `Stream` and `Iterator` APIs are familiar, but checked I/O failures and ownership are less straightforward. A dedicated reader makes those contracts explicit.

## Internal architecture

A single sequential decoder is the foundation for both usage patterns:

1. Parse the NPY version and header.
2. Parse and validate the scalar dtype descriptor.
3. Validate element count and payload size with checked arithmetic.
4. Decode each element in physical file order.
5. For eager reading, store decoded elements in a private backing representation.

The decoder handles partial reads without seeking or `mark`/`reset`. It must not request or prefetch data beyond the declared payload. Eager and streaming paths must not have separate binary decoding implementations.

### Input streams and buffering

`InputStream` remains the primary input abstraction. javanpy deliberately does
not wrap caller-provided streams in `BufferedInputStream` or otherwise introduce
hidden buffering or read-ahead. This is an architectural decision, not a
missing optimization: callers control whether and how to buffer, including the
buffer size, memory use, and stream composition. `NpyReader.open(Path)` must
not introduce automatic buffering at this stage either.

Sequential decoding must respect the declared NPY payload boundary. A
caller-supplied `BufferedInputStream` can nevertheless prefetch bytes beyond that
boundary into its own buffer. If additional data follows the NPY payload, the
caller must continue reading through the same buffered stream to access it.

Future performance improvements may optimize decoding or use bounded bulk reads,
but must not introduce hidden read-ahead beyond the requested payload.

## Existing API compatibility

The current `NpyHeader` record exposes `descr()`, `fortranOrder()`, and `shape()`. It does not expose the version.

The new reader and eager array should expose `NpyVersion` separately. Internally, header parsing can be factored so the version is read once and the remaining header is parsed through shared code. Existing public signatures and behavior of `NpyFormat.readVersion`, `NpyFormat.readHeader`, `NpyHeader`, and `NpyVersion` remain unchanged.

Structured dtype descriptors are currently rejected by the header parser and remain unsupported in the initial array reader.

## Dtype compatibility

| NumPy dtype | Proposed Java access | Notes |
| --- | --- | --- |
| `int8`, `int16`, `int32`, `int64` | `long` | Exact sign extension. |
| `uint8`, `uint16`, `uint32` | `long` | Exact zero extension. |
| `uint64` | `BigInteger` | Preserves the full unsigned range. |
| `float32` | `float` | Preserve native precision. |
| `float64` | `double` | Preserve native precision. |
| Boolean | `boolean` | Validate representation against NumPy fixtures. |
| Fixed-width byte strings (`S`) | `byte[]` | Raw byte access; trailing NUL padding is stripped by default and preserved when `preserveStringPadding` is `true`. Explicit `Charset` decoding is optional; there is no default encoding. |
| Fixed-width Unicode (`U`) | `String` | Decode endian-aware UCS-4 into Java strings. |
| `T` / `StringDType` | Unsupported initially | Do not infer a wire format or deserialize pickle. |
| Object (`O`) | Unsupported | Never deserialize Python pickle. |
| Structured and other dtypes | Unsupported initially | Fail explicitly. |

Preserve the original dtype descriptor, including byte-order information. Multi-byte numeric and Unicode values are decoded using their declared byte order. Validate dtype widths and byte-order markers before consuming payload.

## String semantics

For `S`, eager `getBytes(indices)` and streaming `readBytes()` strip trailing NUL padding when using the default `NpyReadOptions`. Set `preserveStringPadding` to `true` in the options passed to `NpyReader.read(...)` or `NpyReader.open(...)` to preserve the complete fixed-width byte sequence, including trailing NUL bytes. Both modes preserve embedded NUL bytes. These semantics are identical for eager and streaming access.

The NPY dtype alone cannot distinguish trailing NUL padding from actual trailing NUL content. Consequently, the default treats trailing NUL bytes as padding; preserving them requires `preserveStringPadding`. A caller may explicitly supply a `Charset` to decode `S` bytes. Malformed or unmappable input should be reported, not silently replaced.

For `U`, expose Java `String` values. Decode each fixed-width element as endian-aware UCS-4, converting supplementary code points to Java surrogate pairs. Embedded zero values must not be mistaken for the end of the element.

### Variable-width strings and pickle

NumPy’s 2.3.3 NPY writer writes non-legacy dtypes using pickle and represents them as an object dtype in the header. Such files cannot safely be distinguished from ordinary object arrays by the header alone. Reject them without reading or deserializing the pickle payload.

This observation is specific to the investigated NumPy version; future serialization formats require separate evaluation. See also [NumPy’s string dtype documentation](https://numpy.org/doc/stable/user/basics.strings.html).

## Streaming and array order

Streaming always returns elements in physical file order. It does not reorder Fortran-order data.

For a logical array `[[0, 1, 2], [3, 4, 5]]`:

| Storage order | Streamed sequence |
| --- | --- |
| C | `0, 1, 2, 3, 4, 5` |
| Fortran | `0, 3, 1, 4, 2, 5` |

An eager array retains physical order internally and uses strides to map logical indices:

- C-order: the last dimension varies fastest.
- Fortran-order: the first dimension varies fastest.

Logical access therefore returns the same value for the same coordinates regardless of storage order. A scalar shape `()` contains one element; any zero-sized dimension makes the array empty.

**Alternative:** Reorder Fortran arrays into C-order during eager reading. This simplifies one indexing formula but adds work and may require another large allocation.

## Resource ownership and memory

### Ownership

- A caller-provided `InputStream` is never closed by the library, including on failure or when the reader is closed.
- A `Path`-based API owns the stream it opens.
- `read(Path)` closes its stream on success or failure.
- `open(Path)` returns a closeable reader; construction failure closes the newly opened stream.
- Closing a reader early does not drain its input. After early close or failure, the stream may be positioned partway through the payload.

These behaviors require explicit tests for success, failure, and early termination.

### Memory

Streaming uses memory proportional to the header, rank, bounded decoder buffer, and current returned value—not the total element count. Fixed-width string values can themselves be large and require checked allocation limits.

Eager reading uses memory proportional to the payload plus representation overhead. The backing representation stays private so segmented storage can be introduced later.

Public counts and indices should use `long`. Initially, reject eager arrays that exceed supported Java backing-storage or allocation limits with an explanatory error. Check shape products and payload byte counts for overflow before consuming the payload. Handle zero dimensions before multiplication.

## Errors and reader lifecycle

- `hasNext()` checks the declared remaining element count without I/O; it does not establish that the payload is complete.
- Truncated input produces `EOFException`.
- Underlying I/O failures propagate as `IOException`.
- Invalid or unsupported file data produces an explanatory `IOException`.
- Invalid indices and incompatible typed access are caller errors.
- Reading past the declared element count produces a documented `NoSuchElementException`.
- After an I/O failure or malformed element, the reader is unusable.
- No eager array is returned after a partial read failure.

A reader is single-use and not thread-safe.

## Testing strategy

Use checked-in NumPy-generated fixtures so Maven tests do not require Python or NumPy at runtime.

- Numeric and boolean dtypes, endian variants, unsigned boundaries, and floating-point special values.
- `S` zero-width values should return an empty byte sequence in both modes. Verify that default options strip trailing NUL bytes while retaining embedded NUL bytes, and that `preserveStringPadding=true` returns the complete fixed-width sequence. Cover both behaviors for eager `getBytes()` and streaming `readBytes()`, and confirm options do not change during an array or reader's lifetime. Also cover non-UTF-8 bytes. `U` values with both byte orders, supplementary characters, and malformed values.
- Asymmetric C- and Fortran-order arrays, scalars, empty arrays, 1D arrays, and higher-rank arrays.
- Streaming physical sequences compared with eager logical coordinates.
- Non-seekable streams, short reads, truncation, injected I/O failures, and exact payload boundaries.
- Caller-owned and `Path` ownership on success, failure, and early close.
- Overflow, large counts, and eager allocation limits.
- Object and `StringDType` fixture rejection without pickle deserialization.

## Implementation milestones

1. Finalize API names, typed access, errors, and allocation limits.
2. Share version/header parsing internally and introduce the bounded sequential decoder.
3. Add numeric and boolean streaming and eager access, including C/Fortran indexing.
4. Add fixed-width `S` and `U` access, including explicit charset decoding.
5. Complete compatibility, resource-ownership, overflow, and failure coverage, then update user documentation.

Each feature and bug fix includes appropriate tests. Run `mvn test` after code changes.

## Decisions requiring approval

- Exact public method names and exception types.
- Whether to provide a raw-bit `long` accessor alongside `BigInteger` for `uint64`.
- Strict malformed Unicode behavior and whether raw code-point access is needed.
- Allocation limits and first-release support for zero-width strings.
- The exact public placement and naming of version access on the reader and eager array.