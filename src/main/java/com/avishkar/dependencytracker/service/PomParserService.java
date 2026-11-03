package com.avishkar.dependencytracker.service;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Maven pom.xml with the JDK's built-in DOM parser.
 *
 * Handles the two things that make naive text parsing wrong:
 *  - versions written as ${property} references (resolved from &lt;properties&gt;)
 *  - dependencies with no version of their own (managed by a parent/BOM) — kept with a null version
 * External entities are disabled: a pom is untrusted input and must never trigger XXE.
 */
@Service
public class PomParserService {

    public List<ParsedDependency> parse(String pomXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(pomXml)));
            Element project = doc.getDocumentElement();

            Map<String, String> properties = readProperties(project);
            // ${project.version} / ${pom.version} refer to the project itself.
            String ownVersion = childText(project, "version");
            if (ownVersion != null) {
                properties.put("project.version", ownVersion);
                properties.put("pom.version", ownVersion);
            }

            List<ParsedDependency> result = new ArrayList<>();
            Element dependencies = directChild(project, "dependencies");
            if (dependencies == null) {
                return result;
            }
            NodeList nodes = dependencies.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE || !"dependency".equals(node.getNodeName())) {
                    continue;
                }
                Element dep = (Element) node;
                String groupId = childText(dep, "groupId");
                String artifactId = childText(dep, "artifactId");
                if (groupId == null || artifactId == null) {
                    continue;
                }
                String version = resolve(childText(dep, "version"), properties);
                String scope = childText(dep, "scope");
                result.add(new ParsedDependency(
                        resolve(groupId, properties),
                        artifactId,
                        version,
                        scope != null ? scope : "compile"));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse pom.xml: " + e.getMessage(), e);
        }
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
                properties.put(node.getNodeName(), node.getTextContent().trim());
            }
        }
        return properties;
    }

    private String resolve(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String resolved = value.trim();
        if (resolved.startsWith("${") && resolved.endsWith("}")) {
            String key = resolved.substring(2, resolved.length() - 1);
            return properties.getOrDefault(key, resolved);
        }
        return resolved;
    }

    private Element directChild(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }
}
