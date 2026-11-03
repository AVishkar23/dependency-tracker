package com.avishkar.dependencytracker.service;

import com.avishkar.dependencytracker.version.VersionRange;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Maven pom.xml with the JDK's built-in DOM parser (namespace-aware, so
 * both bare test poms and real poms carrying the Maven XML namespace parse alike).
 *
 * <p>What makes pom parsing non-trivial, and how each case is handled:
 * <ul>
 *   <li><b>Property placeholders</b> — versions are often {@code ${jackson.version}}.
 *       Placeholders are interpolated anywhere in the string, recursively (a property
 *       may reference another property), with a depth cap against reference cycles.
 *       Unknown placeholders are left literal rather than failing the import.</li>
 *   <li><b>Parent POM</b> — a project that omits its own {@code <version>} or
 *       {@code <groupId>} inherits them from its {@code <parent>}; both are exposed
 *       as the {@code project.*} built-ins, plus {@code project.parent.version}.</li>
 *   <li><b>dependencyManagement</b> — a dependency declared without a version takes
 *       the version this pom's {@code <dependencyManagement>} section pins for it.
 *       That only covers management declared in the same file: an imported BOM
 *       ({@code scope=import}) lives in another artifact this service never fetches,
 *       so those dependencies keep a null version, as do dependencies managed by a
 *       parent pom.</li>
 *   <li><b>Version ranges</b> — a hard range in {@code <version>} (e.g.
 *       {@code [1.0,2.0)}) is recorded at the version the range resolves from, via
 *       {@link VersionRange#trackedVersion()}.</li>
 *   <li><b>Duplicates</b> — if a pom declares the same dependency twice, the last
 *       declaration wins (Maven's own rule), keeping first-seen order.</li>
 * </ul>
 *
 * <p>Only the direct {@code <dependencies>} child of {@code <project>} is read, so
 * {@code <dependencyManagement>} entries are never mistaken for real dependencies,
 * and dependencies inside {@code <profiles>} (which apply conditionally) are ignored.
 * External entities are disabled: a pom is untrusted input and must never trigger XXE.
 */
@Service
public class PomParserService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_RESOLUTION_DEPTH = 10;

    public List<ParsedDependency> parse(String pomXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(pomXml)));
            Element project = doc.getDocumentElement();
            if (!"project".equals(localName(project))) {
                throw new IllegalArgumentException(
                        "Not a Maven pom.xml: root element is <" + project.getNodeName() + ">");
            }

            Map<String, String> properties = readProperties(project);
            addBuiltInProperties(project, properties);

            Map<String, String> managedVersions = readManagedVersions(project, properties);

            Map<String, ParsedDependency> byCoordinates = new LinkedHashMap<>();
            Element dependencies = directChild(project, "dependencies");
            if (dependencies == null) {
                return List.of();
            }
            for (Element dep : childElements(dependencies, "dependency")) {
                String groupId = resolve(childText(dep, "groupId"), properties);
                String artifactId = resolve(childText(dep, "artifactId"), properties);
                if (groupId == null || artifactId == null) {
                    continue;
                }
                String version = resolve(childText(dep, "version"), properties);
                if (version == null || version.isEmpty()) {
                    version = managedVersions.get(groupId + ":" + artifactId);
                }
                version = resolveRange(version);
                String scope = childText(dep, "scope");
                byCoordinates.put(groupId + ":" + artifactId, new ParsedDependency(
                        groupId,
                        artifactId,
                        version,
                        scope != null ? scope : "compile"));
            }
            return List.copyOf(byCoordinates.values());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse pom.xml: " + e.getMessage(), e);
        }
    }

    /**
     * Versions pinned by this pom's own {@code <dependencyManagement>}, keyed by
     * {@code groupId:artifactId}. Imported BOMs are skipped: their contents live in
     * another artifact and are never fetched.
     */
    private Map<String, String> readManagedVersions(Element project, Map<String, String> properties) {
        Map<String, String> managed = new HashMap<>();
        Element dependencyManagement = directChild(project, "dependencyManagement");
        if (dependencyManagement == null) {
            return managed;
        }
        Element dependencies = directChild(dependencyManagement, "dependencies");
        if (dependencies == null) {
            return managed;
        }
        for (Element dep : childElements(dependencies, "dependency")) {
            String scope = childText(dep, "scope");
            String type = childText(dep, "type");
            if ("import".equals(scope) && "pom".equals(type)) {
                continue; // an external BOM import, not a version pin in this file
            }
            String groupId = resolve(childText(dep, "groupId"), properties);
            String artifactId = resolve(childText(dep, "artifactId"), properties);
            String version = resolve(childText(dep, "version"), properties);
            if (groupId != null && artifactId != null && version != null && !version.isEmpty()) {
                managed.put(groupId + ":" + artifactId, version);
            }
        }
        return managed;
    }

    private Map<String, String> readProperties(Element project) {
        Map<String, String> properties = new HashMap<>();
        Element props = directChild(project, "properties");
        if (props == null) {
            return properties;
        }
        NodeList nodes = props.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                properties.put(localName(node), node.getTextContent().trim());
            }
        }
        return properties;
    }

    /**
     * The {@code project.*} built-ins Maven provides: the project's own version and
     * groupId, falling back to the parent's when the project inherits them, plus the
     * parent's version under its own name. Values are stored raw; interpolation at
     * the point of use resolves any placeholders inside them (e.g. a CI-friendly
     * {@code <version>${revision}</version>}).
     */
    private void addBuiltInProperties(Element project, Map<String, String> properties) {
        Element parent = directChild(project, "parent");
        String parentVersion = parent == null ? null : childText(parent, "version");
        String parentGroupId = parent == null ? null : childText(parent, "groupId");

        String ownVersion = childText(project, "version");
        String effectiveVersion = ownVersion != null ? ownVersion : parentVersion;
        if (effectiveVersion != null) {
            properties.put("project.version", effectiveVersion);
            properties.put("pom.version", effectiveVersion);
        }
        if (parentVersion != null) {
            properties.put("project.parent.version", parentVersion);
        }
        String ownGroupId = childText(project, "groupId");
        String effectiveGroupId = ownGroupId != null ? ownGroupId : parentGroupId;
        if (effectiveGroupId != null) {
            properties.put("project.groupId", effectiveGroupId);
            properties.put("pom.groupId", effectiveGroupId);
        }
    }

    /**
     * A hard version range in a pom does not pin one version; record the version the
     * range resolves from (see {@link VersionRange#trackedVersion()}). Malformed
     * ranges and unbounded-below ranges are kept as-is rather than failing the import.
     */
    private String resolveRange(String version) {
        if (version == null || !VersionRange.isRangeSpec(version)) {
            return version;
        }
        try {
            return VersionRange.parse(version)
                    .trackedVersion()
                    .map(Object::toString)
                    .orElse(version);
        } catch (IllegalArgumentException e) {
            return version;
        }
    }

    /**
     * Interpolates every {@code ${...}} placeholder in the value, recursively (a
     * property's value may itself contain placeholders), up to a depth cap that
     * breaks reference cycles. Unknown placeholders are left as literal text.
     */
    private String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String current = value.trim();
        for (int depth = 0; depth < MAX_RESOLUTION_DEPTH && current.contains("${"); depth++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder replaced = new StringBuilder();
            boolean anyReplaced = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) {
                    matcher.appendReplacement(replaced, Matcher.quoteReplacement(matcher.group()));
                } else {
                    matcher.appendReplacement(replaced, Matcher.quoteReplacement(replacement.trim()));
                    anyReplaced = true;
                }
            }
            matcher.appendTail(replaced);
            current = replaced.toString();
            if (!anyReplaced) {
                break;
            }
        }
        return current;
    }

    private List<Element> childElements(Element parent, String name) {
        List<Element> result = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(node))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private Element directChild(Element parent, String name) {
        List<Element> children = childElements(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    private String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }
}
