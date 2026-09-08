package com.adelylria.ringlog.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

public final class UpdateInstallerLauncher {

    private UpdateInstallerLauncher() {
    }

    public static List<String> command(Path verifiedSetup, long currentPid) {
        Path normalized = verifiedSetup.toAbsolutePath().normalize();
        if (currentPid <= 0) {
            throw new IllegalArgumentException("Current PID must be positive");
        }
        return List.of(
                normalized.toString(),
                "/SILENT",
                "/NOCLOSEAPPLICATIONS",
                "/NORESTARTAPPLICATIONS",
                "/NORESTART",
                "/RINGLOGUPDATE=1",
                "/RINGLOGPID=" + currentPid
        );
    }

    public static Process launch(Path verifiedSetup, long currentPid) throws IOException {
        Path normalized = verifiedSetup.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("El instalador verificado ya no está disponible.");
        }
        return new ProcessBuilder(command(normalized, currentPid)).start();
    }
}
