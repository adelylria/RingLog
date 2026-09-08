package com.adelylria.ringlog.storage;

import java.nio.file.Path;

/** Paths required to read one RingLog dataset, regardless of where it is hosted. */
public interface DataPaths {

    Path dataRoot();

    Path databasePath();

    Path photosDirectory();

    Path eventPhotosDirectory();

    Path nativePhotosDirectory();

    Path unassignedPhotosDirectory();

    Path logsDirectory();
}
