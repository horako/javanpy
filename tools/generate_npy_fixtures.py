#!/usr/bin/env python3
"""Generate checked-in NPY test fixtures using NumPy 2.3.3."""

import argparse
from pathlib import Path

import numpy as np
from numpy.lib.format import write_array


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "src/test/resources/com/horako/javanpy/npy-header"
VALUE_FIXTURE_DIR = ROOT / "src/test/resources/com/horako/javanpy/npy-values"
EXPECTED_NUMPY_VERSION = "2.3.3"


def save_fixture(
    name: str,
    array: np.ndarray,
    version: tuple[int, int],
    directory: Path = FIXTURE_DIR,
    allow_pickle: bool = False,
) -> None:
    path = directory / name
    with path.open("wb") as stream:
        write_array(stream, array, version=version, allow_pickle=allow_pickle)


def save_unsupported_fixtures() -> None:
    arrays = {
        "object_pickle.npy": np.array(["object fixture", 42], dtype=object),
        "string_dtype_pickle.npy": np.array(["variable", "strings"], dtype=np.dtypes.StringDType()),
    }
    for name, array in arrays.items():
        save_fixture(name, array, (1, 0), VALUE_FIXTURE_DIR, allow_pickle=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--unsupported-only",
        action="store_true",
        help="generate only the object and StringDType rejection fixtures",
    )
    args = parser.parse_args()
    if np.__version__ != EXPECTED_NUMPY_VERSION:
        raise SystemExit(
            f"Expected NumPy {EXPECTED_NUMPY_VERSION}, found {np.__version__}; "
            "install the documented version to regenerate fixtures."
        )

    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    VALUE_FIXTURE_DIR.mkdir(parents=True, exist_ok=True)

    if args.unsupported_only:
        save_unsupported_fixtures()
        print(f"Generated unsupported NPY fixtures with NumPy {np.__version__}")
        return

    scalar = np.array(7, dtype=np.dtype("<i4"))
    vector = np.array([1, 2, 3, 4], dtype=np.dtype("<i4"))
    matrix = np.array([[1, 2, 3], [4, 5, 6]], dtype=np.dtype("<i4"))

    save_fixture("v1_scalar.npy", scalar, (1, 0))
    save_fixture("v1_vector.npy", vector, (1, 0))
    save_fixture("v1_matrix_c.npy", matrix, (1, 0))
    save_fixture("v1_matrix_fortran.npy", np.asfortranarray(matrix), (1, 0))
    save_fixture("v2_matrix_c.npy", matrix, (2, 0))
    save_fixture("v2_matrix_fortran.npy", np.asfortranarray(matrix), (2, 0))
    save_fixture("v3_matrix_c.npy", matrix, (3, 0))
    save_fixture("v3_matrix_fortran.npy", np.asfortranarray(matrix), (3, 0))

    values = {
        "bool.npy": np.array([False, True, False, True], dtype=np.bool_),
        "bool_noncanonical.npy": np.frombuffer(bytes([0, 1, 2, 255]), dtype=np.bool_),
        "uint64_be.npy": np.array([0, 1, 2**63 - 1, 2**63, 2**64 - 1], dtype=">u8"),
        "float32_le.npy": np.array([0.0, -0.0, 1.5, np.inf, -np.inf, np.nan], dtype="<f4"),
        "float64_be.npy": np.array([0.0, -0.0, 1.5, np.inf, -np.inf, np.nan], dtype=">f8"),
    }
    for marker, order in (("<", "le"), (">", "be")):
        values[f"int16_{order}.npy"] = np.array([0x1234, -0x1234], dtype=f"{marker}i2")
        values[f"int32_{order}.npy"] = np.array([0x12345678, -0x1234567], dtype=f"{marker}i4")
        values[f"uint64_mixed_{order}.npy"] = np.array(
            [0x0123456789ABCDEF, 0xFEDCBA9876543210], dtype=f"{marker}u8"
        )
    for name, array in values.items():
        save_fixture(name, array, (1, 0), VALUE_FIXTURE_DIR)
    save_unsupported_fixtures()

    print(f"Generated NPY fixtures with NumPy {np.__version__}")


if __name__ == "__main__":
    main()
