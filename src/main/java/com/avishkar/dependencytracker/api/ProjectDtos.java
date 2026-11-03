package com.avishkar.dependencytracker.api;

import com.avishkar.dependencytracker.model.DependencyEntry;
import com.avishkar.dependencytracker.model.TrackedProject;
import com.avishkar.dependencytracker.service.OutdatedReport;
import com.avishkar.dependencytracker.version.VersionGap;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request/response shapes for the REST API, kept separate from the JPA entities. */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(String name, String repoUrl) {
    }

    public record ProjectResponse(Long id, String name, String repoUrl) {
        public static ProjectResponse from(TrackedProject project) {
            return new ProjectResponse(project.getId(), project.getName(), project.getRepoUrl());
        }
    }

    public record DependencyResponse(Long id, String groupId, String artifactId,
                                     String currentVersion, String latestAllowedVersion,
                                     String scope, boolean outdated) {
        public static DependencyResponse from(DependencyEntry entry) {
            return new DependencyResponse(
                    entry.getId(),
                    entry.getGroupId(),
                    entry.getArtifactId(),
                    entry.getCurrentVersion(),
                    entry.getLatestAllowedVersion(),
                    entry.getScope(),
                    entry.isOutdated());
        }
    }

    public record SetVersionRequest(String latestAllowedVersion) {
    }

    public record OutdatedEntryResponse(Long dependencyId, String groupId, String artifactId,
                                        String currentVersion, String latestAllowedVersion,
                                        String severity) {
        public static OutdatedEntryResponse from(OutdatedReport.Entry entry) {
            return new OutdatedEntryResponse(
                    entry.dependencyId(),
                    entry.groupId(),
                    entry.artifactId(),
                    entry.currentVersion(),
                    entry.latestAllowedVersion(),
                    entry.severity().name());
        }
    }

    public record OutdatedReportResponse(Long projectId, String projectName, Instant generatedAt,
                                         int totalDependencies, int outdatedCount,
                                         Map<String, Integer> bySeverity,
                                         List<OutdatedEntryResponse> outdated) {
        public static OutdatedReportResponse from(OutdatedReport report) {
            Map<String, Integer> bySeverity = new LinkedHashMap<>();
            for (VersionGap.Severity severity : VersionGap.Severity.values()) {
                Integer count = report.bySeverity().get(severity);
                if (count != null) {
                    bySeverity.put(severity.name(), count);
                }
            }
            return new OutdatedReportResponse(
                    report.projectId(),
                    report.projectName(),
                    report.generatedAt(),
                    report.totalDependencies(),
                    report.outdatedCount(),
                    bySeverity,
                    report.outdated().stream().map(OutdatedEntryResponse::from).toList());
        }
    }
}
