package com.adelylria.ringlog.update;

import java.util.Locale;
import java.util.Optional;

public enum UpdateArchitecture {
    WINDOWS_X64("windows-x64", "x64"),
    WINDOWS_X86("windows-x86", "x86");

    private final String manifestKey;
    private final String installerSuffix;

    UpdateArchitecture(String manifestKey, String installerSuffix) {
        this.manifestKey = manifestKey;
        this.installerSuffix = installerSuffix;
    }

    public String manifestKey() {
        return manifestKey;
    }

    public String installerSuffix() {
        return installerSuffix;
    }

    public static Optional<UpdateArchitecture> fromManifestKey(String value) {
        for (UpdateArchitecture architecture : values()) {
            if (architecture.manifestKey.equals(value)) {
                return Optional.of(architecture);
            }
        }
        return Optional.empty();
    }

    public static Optional<UpdateArchitecture> fromOsArch(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> Optional.of(WINDOWS_X64);
            case "x86", "i386", "i486", "i586", "i686" -> Optional.of(WINDOWS_X86);
            default -> Optional.empty();
        };
    }

    public static Optional<UpdateArchitecture> current() {
        return fromOsArch(System.getProperty("os.arch"));
    }
}
