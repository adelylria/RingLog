package com.adelylria.ringlog.portable;

public enum PortableCopyStage {
    CHECKING_SPACE,
    COPYING_APPLICATION,
    SNAPSHOTTING_DATABASE,
    COPYING_MEDIA,
    VERIFYING,
    PUBLISHING,
    COMPLETE
}
