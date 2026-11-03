package com.avishkar.dependencytracker.api;

import com.avishkar.dependencytracker.model.DependencyEntry;
import com.avishkar.dependencytracker.model.TrackedProject;

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
}
