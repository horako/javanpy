package com.horako.javanpy;

/**
 * A supported version of the NumPy NPY file format.
 */
public enum NpyVersion {
    V1_0(1, 0),
    V2_0(2, 0),
    V3_0(3, 0);

    private final int major;
    private final int minor;

    NpyVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }
}
