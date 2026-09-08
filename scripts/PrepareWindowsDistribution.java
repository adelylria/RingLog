import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Creates a verified, architecture-specific directory consumed by Inno Setup. */
public final class PrepareWindowsDistribution {

    private static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;

    private PrepareWindowsDistribution() {
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2,
                "Usage: java scripts/PrepareWindowsDistribution.java <x64|x86> <runtime.zip>");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String architecture = requireArchitecture(args[0]);
        Path runtimeZip = Path.of(args[1]).toAbsolutePath().normalize();

        Properties lock = loadProperties(root.resolve("distribution/runtime-lock.properties"));
        String expectedName = requireProperty(lock, architecture + ".filename");
        String expectedHash = requireProperty(lock, architecture + ".sha256");
        require(Files.isRegularFile(runtimeZip), "Runtime ZIP does not exist: " + runtimeZip);
        require(runtimeZip.getFileName().toString().equals(expectedName),
                "Runtime ZIP filename differs from runtime-lock.properties");
        require(sha256(runtimeZip).equals(expectedHash),
                "Runtime ZIP SHA-256 differs from runtime-lock.properties");

        String version = projectVersion(root.resolve("pom.xml"));
        Path sourceJar = root.resolve("target/RingLog-" + version + ".jar");
        Path sourceLibraries = root.resolve("target/libs");
        Path sourceIcon = root.resolve("distribution/assets/RingLog.ico");
        require(Files.isRegularFile(sourceJar), "Run Maven package first: " + sourceJar);
        require(Files.isDirectory(sourceLibraries), "Runtime libraries are missing: " + sourceLibraries);
        require(Files.isRegularFile(sourceIcon), "Application icon is missing: " + sourceIcon);

        Path packagingRoot = root.resolve("target/windows-installer").normalize();
        require(packagingRoot.startsWith(root.resolve("target").normalize()),
                "Unsafe packaging directory");
        Files.createDirectories(packagingRoot);
        Path temporary = packagingRoot.resolve(".prepare-" + UUID.randomUUID()).normalize();
        Path destination = packagingRoot.resolve(architecture).normalize();
        require(temporary.getParent().equals(packagingRoot)
                        && destination.getParent().equals(packagingRoot),
                "Unsafe architecture staging path");

        try {
            Path extracted = Files.createDirectories(temporary.resolve("extracted"));
            extractZip(runtimeZip, extracted);
            Path runtimeRoot = findRuntimeRoot(extracted);
            Path staged = Files.createDirectories(temporary.resolve("ready"));
            Files.copy(sourceJar, staged.resolve("RingLog.jar"));
            Files.copy(sourceIcon, staged.resolve("RingLog.ico"));
            copyTree(sourceLibraries, staged.resolve("libs"));
            copyTree(runtimeRoot, staged.resolve("runtime"));
            require(Files.isRegularFile(staged.resolve("runtime/bin/javaw.exe")),
                    "Verified runtime does not contain bin/javaw.exe");
            Files.writeString(staged.resolve("distribution.properties"),
                    "architecture=" + architecture + "\n"
                            + "version=" + version + "\n"
                            + "runtime.filename=" + expectedName + "\n"
                            + "runtime.sha256=" + expectedHash + "\n"
                            + "icon.sha256=" + sha256(sourceIcon) + "\n",
                    StandardCharsets.UTF_8);
            publish(staged, destination);
            System.out.println(destination);
        } finally {
            deleteTree(temporary);
        }
    }

    private static void extractZip(Path zip, Path destination) throws Exception {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                require(output.startsWith(destination), "Runtime ZIP contains path traversal");
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                long entryBytes = 0;
                try (var target = Files.newOutputStream(output)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        entryBytes += read;
                        total += read;
                        require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES,
                                "Runtime ZIP exceeds extraction limits");
                        target.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static Path findRuntimeRoot(Path extracted) throws Exception {
        List<Path> launchers;
        try (var paths = Files.walk(extracted)) {
            launchers = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("javaw.exe"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .toList();
        }
        require(launchers.size() == 1, "Runtime ZIP must contain exactly one bin/javaw.exe");
        return launchers.get(0).getParent().getParent();
    }

    private static void publish(Path staged, Path destination) throws Exception {
        Path previous = destination.resolveSibling(destination.getFileName() + ".previous");
        deleteTree(previous);
        if (Files.exists(destination)) {
            move(destination, previous);
        }
        try {
            move(staged, destination);
            deleteTree(previous);
        } catch (Exception exception) {
            if (!Files.exists(destination) && Files.exists(previous)) {
                move(previous, destination);
            }
            throw exception;
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path)).normalize();
                require(target.startsWith(destination), "Unsafe copied path");
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                } else {
                    throw new IOException("Unsupported runtime entry: " + path);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requireProperty(Properties properties, String name) {
        String value = properties.getProperty(name);
        require(value != null && !value.isBlank(), "Missing property: " + name);
        return value.strip();
    }

    private static String requireArchitecture(String value) {
        require("x64".equals(value) || "x86".equals(value),
                "Architecture must be x64 or x86");
        return value;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
                return element.getTextContent().strip();
            }
        }
        throw new IOException("Direct Maven project version not found");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
