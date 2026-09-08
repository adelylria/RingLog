package com.adelylria.ringlog.update;

import java.nio.file.Path;
import java.util.List;

public final class UpdateInstallerLauncherTest {

    private UpdateInstallerLauncherTest() {
    }

    public static void installerHandoffIsSilentAndSingleRestartIsOwnedByInno() {
        Path setup = Path.of("C:/Temp/RingLog-Setup-x64.exe");
        List<String> command = UpdateInstallerLauncher.command(setup, 4242L);
        require(command.get(0).equals(setup.toAbsolutePath().normalize().toString()),
                "The verified setup must be launched directly");
        require(command.contains("/SILENT"), "Update must use /SILENT");
        require(command.contains("/NOCLOSEAPPLICATIONS"),
                "Restart Manager must not terminate another RingLog instance");
        require(command.contains("/NORESTARTAPPLICATIONS"),
                "Restart Manager must not create a second RingLog process");
        require(command.contains("/RINGLOGUPDATE=1"), "Inno must recognize an update handoff");
        require(command.contains("/RINGLOGPID=4242"), "Inno must wait for the old process");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        installerHandoffIsSilentAndSingleRestartIsOwnedByInno();
        System.out.println("UpdateInstallerLauncherTest: PASS");
    }
}
