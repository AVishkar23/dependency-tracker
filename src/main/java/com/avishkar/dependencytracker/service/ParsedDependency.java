package com.avishkar.dependencytracker.service;

/**
 * One dependency as read from a pom.xml. The version is null when the pom leaves it
 * to something outside this file — an imported BOM or a parent POM's
 * dependencyManagement — because resolving those would mean fetching other artifacts.
 */
public record ParsedDependency(String groupId, String artifactId, String version, String scope) {
}
