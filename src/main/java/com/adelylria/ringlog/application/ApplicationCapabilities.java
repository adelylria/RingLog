package com.adelylria.ringlog.application;

/** Features exposed by one application mode. */
public record ApplicationCapabilities(
        boolean mutateDiary,
        boolean manageCatalogs,
        boolean importOrRestore,
        boolean exportNativeBackup,
        boolean resolveConflicts,
        boolean checkUpdates,
        boolean createPortableCopy,
        boolean exportReports
) {
    public static ApplicationCapabilities normal() {
        return new ApplicationCapabilities(true, true, true, true, true, true, true, true);
    }

    public static ApplicationCapabilities portableReadOnly() {
        return new ApplicationCapabilities(false, false, false, false, false, false, false, true);
    }

    public boolean readOnly() {
        return !mutateDiary && !manageCatalogs && !importOrRestore
                && !exportNativeBackup && !resolveConflicts;
    }
}
