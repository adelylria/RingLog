import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Creates the deterministic RingLog Update v1 manifest from verified installer files. */
public final class CreateUpdateManifest {

    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern RELEASE_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"
    );

    private CreateUpdateManifest() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        String version = projectVersion(parsed.projectRoot().resolve("pom.xml"));
        require(RELEASE_VERSION.matcher(version).matches(),
                "Update manifests require a release Maven version; found " + version);
        require(REPOSITORY.matcher(parsed.repository()).matches(), "Invalid owner/repository");

        StringBuilder json = new StringBuilder(1024);
        json.append("{\n")
                .append("  \"format\": \"RingLog Update\",\n")
                .append("  \"formatVersion\": 1,\n")
                .append("  \"version\": \"").append(version).append("\",\n")
                .append("  \"assets\": {\n");
        int index = 0;
        for (String architecture : new String[]{"x64", "x86"}) {
            Path installer = parsed.installers().get(architecture);
            if (installer == null) {
                continue;
            }
            String expectedName = "RingLog-Setup-" + architecture + ".exe";
            require(Files.isRegularFile(installer), "Installer does not exist: " + installer);
            require(installer.getFileName().toString().equals(expectedName),
                    "Unexpected installer filename: " + installer.getFileName());
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("    \"windows-").append(architecture).append("\": {\n")
                    .append("      \"url\": \"https://github.com/")
                    .append(parsed.repository()).append("/releases/download/v")
                    .append(version).append('/').append(expectedName).append("\",\n")
                    .append("      \"sha256\": \"").append(sha256(installer)).append("\",\n")
                    .append("      \"size\": ").append(Files.size(installer)).append('\n')
                    .append("    }");
        }
        json.append("\n  }\n}\n");
        Files.createDirectories(parsed.output().getParent());
        Files.writeString(parsed.output(), json, StandardCharsets.UTF_8);
        System.out.println(parsed.output());
    }

    private record Arguments(
            Path projectRoot,
            String repository,
            Map<String, Path> installers,
            Path output
    ) {
        private static Arguments parse(String[] args) {
            Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            String repository = null;
            Path output = null;
            Map<String, Path> installers = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                require(index + 1 < args.length, "Missing value for " + argument);
                String value = args[++index];
                switch (argument) {
                    case "--project-root" -> projectRoot = Path.of(value).toAbsolutePath().normalize();
                    case "--repository" -> repository = value;
                    case "--output" -> output = Path.of(value).toAbsolutePath().normalize();
                    case "--installer" -> {
                        int separator = value.indexOf('=');
                        require(separator > 0, "Use --installer x64=<path> or x86=<path>");
                        String architecture = value.substring(0, separator);
                        require("x64".equals(architecture) || "x86".equals(architecture),
                                "Unsupported architecture: " + architecture);
                        require(!installers.containsKey(architecture),
                                "Duplicate architecture: " + architecture);
                        installers.put(architecture,
                                Path.of(value.substring(separator + 1)).toAbsolutePath().normalize());
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            require(repository != null, "--repository is required");
            require(!installers.isEmpty(), "At least one --installer is required");
            require(output != null, "--output is required");
            return new Arguments(projectRoot, repository, installers, output);
        }
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
        throw new IllegalArgumentException("Direct Maven project version not found");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
