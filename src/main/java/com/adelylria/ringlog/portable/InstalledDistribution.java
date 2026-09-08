package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Whitelisted files copied from the real installed RingLog distribution. */
public final class InstalledDistribution {

    private static final String X64_RUNTIME = "zulu17.42.19-ca-jre17.0.7-win_x64.zip";
    private static final String X64_RUNTIME_SHA256 =
            "8e6d0708ae79742d2652772a91b37b05e014306df1950bfe68c9f88f0fefaf16";
    private static final String X64_JAVA_VERSION = "\"17.0.7\"";
    private static final String X64_ZULU_VERSION = "\"Zulu17.42+19-CA\"";
    private static final List<String> REQUIRED_LIBRARIES = List.of(
            "sqlite-jdbc-", "ormlite-jdbc-", "flatlaf-", "poi-ooxml-",
            "pdfbox-", "jackson-core-", "log4j-api-", "log4j-core-"
    );

    private final Path root;

    public InstalledDistribution(Path root) throws PortableCopyException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        validate();
    }

    public static InstalledDistribution fromRunningApplication(Class<?> anchor)
            throws PortableCopyException {
        Objects.requireNonNull(anchor, "anchor");
        try {
            Path codeSource = Path.of(anchor.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(codeSource)
                    || !"RingLog.jar".equalsIgnoreCase(codeSource.getFileName().toString())) {
                throw new PortableCopyException(
                        "La copia portátil solo puede crearse desde una instalación empaquetada."
                );
            }
            return new InstalledDistribution(codeSource.getParent());
        } catch (URISyntaxException exception) {
            throw new PortableCopyException(
                    "No se pudo localizar la instalación actual de RingLog.", exception
            );
        }
    }

    public Path root() {
        return root;
    }

    public Path jar() {
        return root.resolve("RingLog.jar");
    }

    public Path libraries() {
        return root.resolve("libs");
    }

    public Path runtime() {
        return root.resolve("runtime");
    }

    public Path icon() {
        return root.resolve("RingLog.ico");
    }

    public Path propertiesFile() {
        return root.resolve("distribution.properties");
    }

    public String version() throws PortableCopyException {
        String version = properties().getProperty("version", "").strip();
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
            throw new PortableCopyException(
                    "La instalación no contiene una versión estable válida de RingLog."
            );
        }
        return version;
    }

    private void validate() throws PortableCopyException {
        requireRegular(jar(), "No se encontró RingLog.jar en la instalación actual.");
        requireDirectory(libraries(), "No se encontró la carpeta libs de RingLog.");
        requireDirectory(runtime(), "No se encontró el runtime Java incluido.");
        requireRegular(runtime().resolve("bin/javaw.exe"),
                "El runtime incluido no contiene javaw.exe.");
        requireRegular(runtime().resolve("bin/java.exe"),
                "El runtime incluido no contiene java.exe.");
        requireRegular(runtime().resolve("release"),
                "El runtime incluido no contiene su identificación técnica.");
        requireRegular(icon(), "No se encontró el icono de RingLog.");
        requireRegular(propertiesFile(),
                "No se encontró la identificación de la distribución instalada.");
        validateLibraries();
        validateProperties();
    }

    private void validateLibraries() throws PortableCopyException {
        try (var files = Files.list(libraries())) {
            List<String> names = files.filter(path -> Files.isRegularFile(
                            path, LinkOption.NOFOLLOW_LINKS
                    ))
                    .map(path -> path.getFileName().toString())
                    .toList();
            for (String prefix : REQUIRED_LIBRARIES) {
                if (names.stream().noneMatch(name -> name.startsWith(prefix))) {
                    throw new PortableCopyException(
                            "La instalación está incompleta; falta la librería " + prefix
                    );
                }
            }
        } catch (IOException exception) {
            throw new PortableCopyException(
                    "No se pudieron validar las librerías instaladas.", exception
            );
        }
    }

    private void validateProperties() throws PortableCopyException {
        Properties properties = properties();
        requireValue(properties, "architecture", "x64");
        requireValue(properties, "runtime.filename", X64_RUNTIME);
        requireValue(properties, "runtime.sha256", X64_RUNTIME_SHA256);
        Properties runtimeRelease = properties(runtime().resolve("release"));
        requireValue(runtimeRelease, "JAVA_VERSION", X64_JAVA_VERSION);
        requireValue(runtimeRelease, "IMPLEMENTOR_VERSION", X64_ZULU_VERSION);
        requireValue(runtimeRelease, "OS_ARCH", "\"x86_64\"");
        version();
    }

    private Properties properties() throws PortableCopyException {
        return properties(propertiesFile());
    }

    private static Properties properties(Path file) throws PortableCopyException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new PortableCopyException(
                    "No se pudo leer la identificación de la instalación.", exception
            );
        }
    }

    private static void requireValue(Properties properties, String key, String expected)
            throws PortableCopyException {
        if (!expected.equalsIgnoreCase(properties.getProperty(key, "").strip())) {
            throw new PortableCopyException(
                    "La distribución instalada no usa el runtime x64 fijado de RingLog."
            );
        }
    }

    private static void requireRegular(Path path, String message) throws PortableCopyException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException(message);
        }
    }

    private static void requireDirectory(Path path, String message) throws PortableCopyException {
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException(message);
        }
    }
}
