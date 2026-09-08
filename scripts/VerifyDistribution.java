import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.imageio.ImageIO;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Verifies the physical Maven distribution without invoking Inno Setup or GitHub. */
public final class VerifyDistribution {

    private static final String MAIN_CLASS = "com.adelylria.ringlog.RingLog";
    private static final String BUILD_INFO = "com.adelylria.ringlog.diagnostics.BuildInfo";
    private static final int JAVA_17_CLASS_VERSION = 61;

    private VerifyDistribution() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String version = projectVersion(root.resolve("pom.xml"));
        Path jar = root.resolve("target/RingLog-" + version + ".jar");
        if (args.length == 1 && "--startup-only".equals(args[0])) {
            verifyStartup(jar);
            return;
        }
        require(args.length == 0, "Usage: java scripts/VerifyDistribution.java [--startup-only]");
        verifyStaticDistribution(root, version, jar);
        System.out.println("VerifyDistribution: PASS");
    }

    private static void verifyStaticDistribution(Path root, String version, Path jar)
            throws Exception {
        require(Runtime.version().feature() == 17,
                "VerifyDistribution must itself run on JDK 17");
        require(Files.isRegularFile(jar), "Missing versioned JAR: " + jar);
        Path libraries = root.resolve("target/libs");
        require(Files.isDirectory(libraries), "Missing runtime library directory: " + libraries);

        require(Files.isRegularFile(root.resolve("distribution/inno/RingLog.iss")),
                "Missing Inno Setup source");
        require(Files.isRegularFile(root.resolve("distribution/runtime-lock.properties")),
                "Missing runtime lock");
        require(Files.isRegularFile(root.resolve("distribution/assets/RingLog.ico")),
                "Missing Windows application icon");
        require(!Files.exists(root.resolve("package.json")),
                "Obsolete jDeploy package.json still exists");

        try (JarFile packaged = new JarFile(jar.toFile())) {
            Manifest manifest = packaged.getManifest();
            require(manifest != null, "The JAR has no manifest");
            Attributes attributes = manifest.getMainAttributes();
            require(MAIN_CLASS.equals(attributes.getValue(Attributes.Name.MAIN_CLASS)),
                    "Manifest Main-Class is missing or incorrect");
            require("RingLog".equals(attributes.getValue("Implementation-Title")),
                    "Manifest Implementation-Title is missing or incorrect");
            require(version.equals(attributes.getValue("Implementation-Version")),
                    "Manifest Implementation-Version differs from Maven");

            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            require(classPath != null && !classPath.isBlank(),
                    "Manifest Class-Path must list target/libs dependencies");
            for (String entry : classPath.split("\\s+")) {
                require(entry.startsWith("libs/"),
                        "Manifest dependency must be relative to the JAR: " + entry);
                require(Files.isRegularFile(root.resolve("target").resolve(entry)),
                        "Manifest dependency does not exist: " + entry);
            }

            var ringLogClass = packaged.getJarEntry("com/adelylria/ringlog/RingLog.class");
            require(ringLogClass != null, "RingLog.class is missing from the JAR");
            try (InputStream input = packaged.getInputStream(ringLogClass)) {
                byte[] header = input.readNBytes(8);
                require(header.length == 8, "RingLog.class has an invalid header");
                int major = (Byte.toUnsignedInt(header[6]) << 8) | Byte.toUnsignedInt(header[7]);
                require(major == JAVA_17_CLASS_VERSION,
                        "RingLog.class is not Java 17 bytecode: major=" + major);
            }
            var publicKey = packaged.getJarEntry("ringlog-update-public-key.der");
            require(publicKey != null, "Update public key is missing from the JAR");
            try (InputStream input = packaged.getInputStream(publicKey)) {
                KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(input.readAllBytes())
                );
            }
            var applicationIcon = packaged.getJarEntry("icons/ringlog.png");
            require(applicationIcon != null, "Application icon is missing from the JAR");
            try (InputStream input = packaged.getInputStream(applicationIcon)) {
                BufferedImage icon = ImageIO.read(input);
                require(icon != null && icon.getWidth() >= 512 && icon.getHeight() >= 512,
                        "Application icon is invalid or too small");
            }
        }

        requireCriticalLibraries(libraries);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> buildInfo = Class.forName(BUILD_INFO, true, loader);
            Method applicationVersion = buildInfo.getMethod("applicationVersion");
            String packagedVersion = (String) applicationVersion.invoke(null);
            require(version.equals(packagedVersion),
                    "The packaged application reports the wrong version: " + packagedVersion);
            require(!"development".equals(packagedVersion),
                    "The packaged application must never report development");
        }
    }

    private static void requireCriticalLibraries(Path libraries) throws Exception {
        List<String> prefixes = List.of(
                "sqlite-jdbc-", "ormlite-jdbc-", "flatlaf-", "poi-ooxml-",
                "pdfbox-", "jackson-core-", "log4j-api-", "log4j-core-"
        );
        List<String> names;
        try (var files = Files.list(libraries)) {
            names = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
        }
        for (String prefix : prefixes) {
            require(names.stream().anyMatch(name -> name.startsWith(prefix)),
                    "Missing runtime dependency beginning with " + prefix);
        }
    }

    private static void verifyStartup(Path jar) throws Exception {
        require(Runtime.version().feature() == 17,
                "The distribution startup must be launched from JDK 17");
        require(Files.isRegularFile(jar), "Missing executable JAR: " + jar);
        Path temporary = Files.createTempDirectory("ringlog-distribution-startup-")
                .toAbsolutePath().normalize();
        Path work = Files.createDirectories(temporary.resolve("outside-project"));
        Path data = temporary.resolve("data-root");
        Path preferences = temporary.resolve("preferences.properties");
        Path output = temporary.resolve("startup-output.log");
        Process process = null;
        try {
            process = new ProcessBuilder(
                    javaExecutable().toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-Dringlog.test.mode=true",
                    "-Dringlog.data.dir=" + data,
                    "-Dringlog.preferences.path=" + preferences,
                    "-jar", jar.toAbsolutePath().toString()
            ).directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            Path database = data.resolve("data/ringlog.db");
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(deadline)
                    && process.isAlive()
                    && !Files.isRegularFile(database)) {
                Thread.sleep(100);
            }
            require(Files.isRegularFile(database),
                    "The packaged app did not create its isolated database. Output: "
                            + readOutput(output));
            Thread.sleep(750);
            require(process.isAlive(),
                    "The packaged app exited during startup. Output: " + readOutput(output));
            require(!work.startsWith(jar.getParent().getParent()),
                    "Startup verification must run outside the project directory");
            System.out.println("packagedDistributionCanStartWithJava17: PASS");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
            deleteTemporaryTree(temporary);
        }
    }

    private static String readOutput(Path output) throws Exception {
        return Files.isRegularFile(output) ? Files.readString(output) : "<no output>";
    }

    private static void deleteTemporaryTree(Path root) throws Exception {
        Path systemTemporary = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        require(root.startsWith(systemTemporary) && !root.equals(systemTemporary),
                "Refusing to clean a non-temporary startup directory");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                deleteAfterWindowsReleasesHandles(path);
            }
        }
    }

    private static void deleteAfterWindowsReleasesHandles(Path path) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (true) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (FileSystemException exception) {
                if (!System.getProperty("os.name").toLowerCase().contains("win")
                        || Instant.now().isAfter(deadline)) {
                    throw exception;
                }
                Thread.sleep(50);
            }
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
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
        throw new IllegalArgumentException("Direct Maven project version not found");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
