import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validates the Maven version against a future stable Git tag. */
public final class ValidateReleaseVersion {

    private static final Pattern SUPPORTED_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-SNAPSHOT)?$"
    );
    private static final Pattern RELEASE_TAG = Pattern.compile(
            "^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"
    );

    private ValidateReleaseVersion() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            fail("Usage: java scripts/ValidateReleaseVersion.java <pom.xml> <vMAJOR.MINOR.PATCH>");
        }
        try {
            String version = projectVersion(Path.of(args[0]));
            String tag = args[1];
            if (!SUPPORTED_VERSION.matcher(version).matches()) {
                fail("Unsupported Maven version: " + version);
            }
            if (version.endsWith("-SNAPSHOT")) {
                fail("A SNAPSHOT Maven version cannot be released: " + version);
            }
            Matcher tagMatcher = RELEASE_TAG.matcher(tag);
            if (!tagMatcher.matches()) {
                fail("Release tag must use vMAJOR.MINOR.PATCH: " + tag);
            }
            if (!tag.substring(1).equals(version)) {
                fail("Tag " + tag + " does not match Maven version " + version);
            }
            System.out.println("Release version validated: " + version);
        } catch (Exception exception) {
            fail("Could not validate release version: " + exception.getMessage());
        }
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

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
