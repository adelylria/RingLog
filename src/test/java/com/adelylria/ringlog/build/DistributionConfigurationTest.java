package com.adelylria.ringlog.build;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Fast checks that prevent Java, Inno and runtime distribution metadata from drifting. */
public final class DistributionConfigurationTest {

    private static final Pattern SUPPORTED_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-SNAPSHOT)?$"
    );
    private static final String APP_ID = "CD4DE8C8-42D5-4A9C-B792-CA3D29616163";

    private DistributionConfigurationTest() {
    }

    public static void innoSetupContractIsPinnedAndArchitectureNeutral() throws Exception {
        Path root = projectRoot();
        Path inno = root.resolve("distribution/inno/RingLog.iss");
        Path lock = root.resolve("distribution/runtime-lock.properties");
        require(Files.isRegularFile(inno), "The tracked Inno Setup script is missing");
        require(Files.isRegularFile(lock), "The runtime lock file is missing");
        Path pngIcon = root.resolve("src/main/resources/icons/ringlog.png");
        Path windowsIcon = root.resolve("distribution/assets/RingLog.ico");
        require(Files.isRegularFile(pngIcon), "The application PNG icon is missing");
        require(Files.isRegularFile(windowsIcon), "The Windows ICO icon is missing");

        String script = Files.readString(inno);
        require(script.contains("Source: \"{#RingLogStageDir}\\distribution.properties\"; DestDir: \"{app}\""),
                "Installed RingLog must retain runtime metadata to create portable copies");
        require(script.contains("AppId={{" + APP_ID + '}'), "The stable AppId changed");
        require(script.contains("PrivilegesRequired=lowest"), "Installer must remain per-user");
        require(script.contains("DefaultDirName={localappdata}\\Programs\\RingLog"),
                "Installer must use LOCALAPPDATA Programs");
        require(script.contains("MinVersion=6.2"), "Windows 8 must remain testable for now");
        require(script.contains("x64compatible"), "x64 architecture guard is missing");
        require(script.contains("x86compatible and not x64compatible"),
                "x86 architecture guard is missing");
        require(script.contains("runtime\\bin\\javaw.exe")
                        && script.contains("-jar \"\"{app}\\RingLog.jar\"\""),
                "Shortcuts must use only the bundled runtime and stable JAR");
        require(script.contains("RestartApplications=no"),
                "Only RingLog's explicit [Run] entry may reopen the app");
        require(script.contains("MessagesFile: \"compiler:Languages\\Spanish.isl\""),
                "Installer messages must match RingLog's Spanish UI");
        require(script.contains("SetupIconFile={#RingLogIconFile}"),
                "The installer executable must use RingLog's icon");
        require(script.contains("UninstallDisplayIcon={app}\\RingLog.ico"),
                "Installed Apps must show RingLog's icon");
        require(script.contains("IconFilename: \"{app}\\RingLog.ico\""),
                "Desktop and Start shortcuts must use RingLog's icon");
        require(!script.contains("{localappdata}\\RingLog")
                        && !script.contains("{appdata}\\RingLog"),
                "Installer must never know RingLog's data directories");

        BufferedImage image = ImageIO.read(pngIcon.toFile());
        require(image != null && image.getWidth() == image.getHeight()
                        && image.getWidth() >= 512,
                "The application icon must be a square, high-resolution PNG");
        byte[] icoHeader = Files.readAllBytes(windowsIcon);
        require(icoHeader.length > 6 && icoHeader[0] == 0 && icoHeader[1] == 0
                        && icoHeader[2] == 1 && icoHeader[3] == 0,
                "The Windows icon must be a valid ICO container");

        Properties runtime = new Properties();
        try (InputStream input = Files.newInputStream(lock)) {
            runtime.load(input);
        }
        require("6.7.3".equals(runtime.getProperty("inno.version")),
                "Inno Setup must be pinned exactly to 6.7.3");
        require("zulu17.42.19-ca-jre17.0.7-win_x64.zip".equals(
                        runtime.getProperty("x64.filename")), "Wrong x64 runtime");
        require("8e6d0708ae79742d2652772a91b37b05e014306df1950bfe68c9f88f0fefaf16"
                        .equals(runtime.getProperty("x64.sha256")), "Wrong x64 runtime hash");
        require("zulu17.42.19-ca-jre17.0.7-win_i686.zip".equals(
                        runtime.getProperty("x86.filename")), "Wrong x86 runtime");
        require("1741cfe5724b93ec8306f6decb83f78ad4f1c3aa9ddd9f10daddcce4a97b2268"
                        .equals(runtime.getProperty("x86.sha256")), "Wrong x86 runtime hash");
    }

    public static void jdeployConfigurationHasBeenRemoved() throws Exception {
        Path root = projectRoot();
        String pom = Files.readString(root.resolve("pom.xml"));
        require(!Files.exists(root.resolve("src/jdeploy/package.json.template")),
                "The jDeploy template must be removed");
        require(!Files.exists(root.resolve("package.json")),
                "The generated jDeploy package.json must be removed");
        require(!pom.toLowerCase().contains("jdeploy"), "pom.xml still contains jDeploy");
        String ignore = Files.readString(root.resolve(".gitignore"));
        require(!ignore.contains("/.jdeploy/") && !ignore.contains("/package.json"),
                "Obsolete jDeploy outputs remain in .gitignore");
    }

    public static void buildMetadataStillComesOnlyFromMaven() throws Exception {
        Path root = projectRoot();
        String version = projectVersion(root.resolve("pom.xml"));
        require(SUPPORTED_VERSION.matcher(version).matches(),
                "pom.xml must use MAJOR.MINOR.PATCH with an optional -SNAPSHOT");
        Properties build = new Properties();
        try (InputStream input = DistributionConfigurationTest.class
                .getResourceAsStream("/ringlog-build.properties")) {
            require(input != null, "ringlog-build.properties must be included in Maven resources");
            build.load(input);
        }
        require(version.equals(build.getProperty("version")),
                "Fallback build metadata must match Maven");
    }

    public static void releaseInfrastructureDoesNotContainPrivateKeys() throws Exception {
        Path root = projectRoot();
        require(Files.isRegularFile(root.resolve("scripts/PrepareWindowsDistribution.java")),
                "Windows staging tool is missing");
        require(Files.isRegularFile(root.resolve("scripts/BuildWindowsInstaller.ps1")),
                "Windows installer tool is missing");
        require(Files.isRegularFile(root.resolve("scripts/CreateUpdateManifest.java")),
                "Manifest tool is missing");
        require(Files.isRegularFile(root.resolve("scripts/SignUpdateManifest.java")),
                "Signing tool is missing");
        require(Files.isRegularFile(root.resolve("docs/windows-distribution.md")),
                "Manual release procedure is missing");
        String ignore = Files.readString(root.resolve(".gitignore"));
        require(ignore.contains("/.release-secrets/")
                        && ignore.contains("*.pk8")
                        && ignore.contains("/docs/superpowers/"),
                "Private update keys must be excluded from Git");
        String makefile = Files.readString(root.resolve("Makefile"));
        require(makefile.contains(
                        "UPDATE_BASE_URL ?= https://github.com/adelylria/RingLog/"
                                + "releases/latest/download"
                ) && makefile.contains("-Dringlog.update.baseUrl=$(UPDATE_BASE_URL)"),
                "Installer builds must embed the stable public update channel");
        try (var source = Files.walk(root.resolve("src"))) {
            require(source.noneMatch(path -> path.getFileName().toString().endsWith(".pk8")),
                    "A private update key exists under src");
        }
    }

    public static void buildRequiresTheApprovedToolchain() throws Exception {
        String pom = Files.readString(projectRoot().resolve("pom.xml"));
        require(pom.contains("<maven.compiler.release>17</maven.compiler.release>"),
                "The bytecode target must remain Java 17");
        require(pom.contains("<requireJavaVersion>") && pom.contains("[17,18)"),
                "Maven Enforcer must reject builds outside a real JDK 17");
        require(pom.contains("<requireMavenVersion>"),
                "Maven Enforcer must state the minimum supported Maven version");
        require(pom.contains("<project.build.outputTimestamp>"),
                "The build must define a stable output timestamp");
    }

    public static void noVersionIsDuplicatedInJavaSource() throws Exception {
        Path root = projectRoot();
        String version = projectVersion(root.resolve("pom.xml"));
        Pattern standaloneVersion = Pattern.compile(
                "(?<![0-9A-Za-z.])" + Pattern.quote(version) + "(?![0-9A-Za-z.])"
        );
        try (var paths = Files.walk(root.resolve("src/main/java"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                require(!standaloneVersion.matcher(Files.readString(path)).find(),
                        "Application version must not be hard-coded in " + root.relativize(path));
            }
        }
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static String projectVersion(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        for (Node node = project.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "version".equals(element.getLocalName())) {
                return element.getTextContent().trim();
            }
        }
        throw new AssertionError("Direct Maven project version not found");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        innoSetupContractIsPinnedAndArchitectureNeutral();
        jdeployConfigurationHasBeenRemoved();
        buildMetadataStillComesOnlyFromMaven();
        releaseInfrastructureDoesNotContainPrivateKeys();
        buildRequiresTheApprovedToolchain();
        noVersionIsDuplicatedInJavaSource();
        System.out.println("DistributionConfigurationTest: PASS");
    }
}
