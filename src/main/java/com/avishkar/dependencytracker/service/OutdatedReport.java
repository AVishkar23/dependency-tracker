package com.avishkar.dependencytracker.service;

import com.avishkar.dependencytracker.version.VersionGap;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A point-in-time summary of how far a project's dependencies lag behind their
 * policy ceilings, with each outdated entry classified by {@link VersionGap}.
 * Entries are sorted worst-first (MAJOR before MINOR before PATCH before QUALIFIER).
 */
public record OutdatedReport(
        Long projectId,
        String projectName,
        Instant generatedAt,
        int totalDependencies,
        int outdatedCount,
        Map<VersionGap.Severity, Integer> bySeverity,
        List<Entry> outdated) {

    /** One outdated dependency and the size of the jump its team has allowed. */
    public record Entry(Long dependencyId, String groupId, String artifactId,
                        String currentVersion, String latestAllowedVersion,
                        VersionGap.Severity severity) {
    }
}
