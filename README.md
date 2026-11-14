# javanpy

Java library for reading NumPy .npy files

The library reads and validates NPY metadata headers. Array data reading is not
implemented.

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
