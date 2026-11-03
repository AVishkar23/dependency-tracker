package com.avishkar.dependencytracker.service;

/** One dependency as read from a pom.xml (version may be null if it comes from dependencyManagement). */
public record ParsedDependency(String groupId, String artifactId, String version, String scope) {
}
