package net.byteflux.libby.resolver;

import net.byteflux.libby.resolver.RawPom.Coordinates;
import net.byteflux.libby.resolver.RawPom.RawDependency;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses POM files with a hardened XML parser: document type declarations
 * are rejected outright, so neither external nor internal entities are ever
 * processed, and external DTDs, schemas and XIncludes are never loaded.
 * <p>
 * Elements are matched by local name, so POMs with or without the Maven
 * namespace (or with a namespace prefix) are accepted. Only the direct
 * children of {@code <project>} that the resolver needs are read; plugin
 * dependencies, reporting and build sections are ignored.
 */
final class PomParser {
    /**
     * Maximum size of a POM file in bytes (1 MiB)
     */
    static final int MAX_POM_SIZE = 1024 * 1024;

    /**
     * Error handler that turns every error into an exception and ignores
     * warnings instead of printing them to standard error
     */
    private static final ErrorHandler STRICT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    private PomParser() {
    }

    /**
     * Reads and parses a POM file.
     *
     * @param file the POM file
     * @return the parsed POM
     * @throws IOException if the file can't be read, is too large or isn't a valid POM
     */
    static RawPom parse(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_POM_SIZE) {
            throw new IOException("POM is larger than " + MAX_POM_SIZE + " bytes");
        }

        return parse(Files.readAllBytes(file));
    }

    /**
     * Parses a POM document.
     *
     * @param bytes the POM document
     * @return the parsed POM
     * @throws IOException if the document is too large or isn't a valid POM
     */
    static RawPom parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_POM_SIZE) {
            throw new IOException("POM is larger than " + MAX_POM_SIZE + " bytes");
        }

        Document document;
        try {
            document = newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (SAXException e) {
            throw new IOException("invalid POM XML: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The XML parser doesn't support secure processing", e);
        }

        Element project = document.getDocumentElement();
        if (!"project".equals(localName(project))) {
            throw new IOException("not a POM, the root element is <" + localName(project) + '>');
        }

        Element parentElement = child(project, "parent");
        Coordinates parent = parentElement != null ? coordinates(parentElement) : null;

        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, RawDependency> management = new LinkedHashMap<>();
        Map<String, RawDependency> dependencies = new LinkedHashMap<>();
        readSection(project, properties, management, dependencies);

        // A profile that is active by default applies when no other profile is
        // active. JDK, OS, property and file activation are never evaluated, so
        // only these profiles are ever active.
        Element profiles = child(project, "profiles");
        if (profiles != null) {
            for (Element profile : children(profiles, "profile")) {
                Element activation = child(profile, "activation");
                if (activation != null && "true".equals(text(activation, "activeByDefault"))) {
                    readSection(profile, properties, management, dependencies);
                }
            }
        }

        Coordinates relocation = null;
        Element distribution = child(project, "distributionManagement");
        Element relocationElement = distribution != null ? child(distribution, "relocation") : null;
        if (relocationElement != null) {
            relocation = coordinates(relocationElement);
        }

        return new RawPom(
            parent,
            text(project, "groupId"),
            text(project, "artifactId"),
            text(project, "version"),
            text(project, "packaging"),
            Map.copyOf(properties),
            List.copyOf(management.values()),
            List.copyOf(dependencies.values()),
            relocation
        );
    }

    /**
     * Reads the properties, dependency management and dependencies of a
     * project or profile, replacing entries with the same key.
     *
     * @param section      the {@code <project>} or {@code <profile>} element
     * @param properties   properties to add to
     * @param management   managed dependencies by key to add to
     * @param dependencies dependencies by key to add to
     */
    private static void readSection(Element section,
                                    Map<String, String> properties,
                                    Map<String, RawDependency> management,
                                    Map<String, RawDependency> dependencies) {

        Element propertiesElement = child(section, "properties");
        if (propertiesElement != null) {
            for (Element property : children(propertiesElement, null)) {
                properties.put(localName(property), property.getTextContent().strip());
            }
        }

        Element managementElement = child(section, "dependencyManagement");
        if (managementElement != null) {
            readDependencies(child(managementElement, "dependencies"), management);
        }

        readDependencies(child(section, "dependencies"), dependencies);
    }

    /**
     * Reads a {@code <dependencies>} element.
     *
     * @param element the element, or null
     * @param into    dependencies by key to add to
     */
    private static void readDependencies(@Nullable Element element, Map<String, RawDependency> into) {
        if (element == null) {
            return;
        }

        for (Element dependency : children(element, "dependency")) {
            List<Coordinates> exclusions = new ArrayList<>();
            Element exclusionsElement = child(dependency, "exclusions");
            if (exclusionsElement != null) {
                for (Element exclusion : children(exclusionsElement, "exclusion")) {
                    exclusions.add(coordinates(exclusion));
                }
            }

            RawDependency raw = new RawDependency(
                text(dependency, "groupId"),
                text(dependency, "artifactId"),
                text(dependency, "version"),
                text(dependency, "type"),
                text(dependency, "classifier"),
                text(dependency, "scope"),
                text(dependency, "optional"),
                List.copyOf(exclusions)
            );
            into.remove(raw.key());
            into.put(raw.key(), raw);
        }
    }

    /**
     * Reads the {@code groupId}, {@code artifactId} and {@code version}
     * children of an element.
     *
     * @param element the element
     * @return the coordinates
     */
    private static Coordinates coordinates(Element element) {
        return new Coordinates(text(element, "groupId"), text(element, "artifactId"), text(element, "version"));
    }

    /**
     * Creates a document builder with external entities, DTDs, schemas and
     * XIncludes disabled and document type declarations rejected.
     *
     * @return the document builder
     * @throws ParserConfigurationException if a security feature isn't supported
     */
    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        // The JDK's built-in implementation, never one found on the server's classpath.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setIgnoringComments(true);
        factory.setCoalescing(true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(STRICT);
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External entities are not allowed: " + systemId);
        });
        return builder;
    }

    /**
     * Gets the first direct child element with a local name.
     *
     * @param parent the parent element
     * @param name   the local name
     * @return the child element, or null if there is none
     */
    private static @Nullable Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && name.equals(localName((Element) node))) {
                return (Element) node;
            }
        }

        return null;
    }

    /**
     * Gets the direct child elements with a local name.
     *
     * @param parent the parent element
     * @param name   the local name, or null for every child element
     * @return the child elements in document order
     */
    private static List<Element> children(Element parent, @Nullable String name) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && (name == null || name.equals(localName((Element) node)))) {
                children.add((Element) node);
            }
        }

        return children;
    }

    /**
     * Gets the trimmed text of a direct child element.
     *
     * @param parent the parent element
     * @param name   the child's local name
     * @return the text, or null if the child doesn't exist or is blank
     */
    private static @Nullable String text(Element parent, String name) {
        Element child = child(parent, name);
        if (child == null) {
            return null;
        }

        String text = child.getTextContent().strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Gets the local name of an element parsed without namespace awareness.
     *
     * @param element the element
     * @return the element name without any namespace prefix
     */
    private static String localName(Element element) {
        String name = element.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
