package com.adelylria.ringlog.storage;

/** Outcome of locating and validating one legacy media reference. */
public enum MediaStatus {
    PRESENT,
    MISSING,
    AMBIGUOUS,
    HASH_MISMATCH,
    UNSAFE_REFERENCE
}
