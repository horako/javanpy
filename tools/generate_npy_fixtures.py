#!/usr/bin/env python3
"""Generate checked-in NPY header fixtures using NumPy 2.3.3."""

from pathlib import Path

import numpy as np
from numpy.lib.format import write_array


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "src/test/resources/com/horako/javanpy/npy-header"
EXPECTED_NUMPY_VERSION = "2.3.3"


def save_fixture(name: str, array: np.ndarray, version: tuple[int, int]) -> None:
    path = FIXTURE_DIR / name
    with path.open("wb") as stream:
        write_array(stream, array, version=version, allow_pickle=False)


def main() -> None:
    if np.__version__ != EXPECTED_NUMPY_VERSION:
        raise SystemExit(
            f"Expected NumPy {EXPECTED_NUMPY_VERSION}, found {np.__version__}; "
            "install the documented version to regenerate fixtures."
        )

    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)

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

    print(f"Generated NPY fixtures with NumPy {np.__version__} in {FIXTURE_DIR}")


if __name__ == "__main__":
    main()
