package com.avishkar.dependencytracker.model;

import com.avishkar.dependencytracker.version.MavenVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "dependency_entries")
public class DependencyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private TrackedProject project;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    @Column(name = "current_version")
    private String currentVersion;

    /**
     * The newest version the owning team allows (their version policy ceiling).
     * Null until someone sets the policy for this dependency.
     */
    @Column(name = "latest_allowed_version")
    private String latestAllowedVersion;

    @Column(nullable = false)
    private String scope;

    @Column(name = "last_checked_at", nullable = false)
    private Instant lastCheckedAt = Instant.now();

    protected DependencyEntry() {
        // JPA
    }

    public DependencyEntry(TrackedProject project, String groupId, String artifactId,
                           String currentVersion, String scope) {
        this.project = project;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.currentVersion = currentVersion;
        this.scope = scope;
    }

    /**
     * True when a policy ceiling exists and is semantically newer than the current
     * version, under Maven's version ordering. (An earlier version of this check
     * compared strings for inequality, which flagged a dependency whose current
     * version was <i>ahead</i> of the ceiling — and treated 2.15 and 2.15.0 as
     * different versions.)
     */
    public boolean isOutdated() {
        if (latestAllowedVersion == null || currentVersion == null) {
            return false;
        }
        return MavenVersion.parse(latestAllowedVersion)
                .compareTo(MavenVersion.parse(currentVersion)) > 0;
    }

    public Long getId() {
        return id;
    }

    public TrackedProject getProject() {
        return project;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getLatestAllowedVersion() {
        return latestAllowedVersion;
    }

    public void setLatestAllowedVersion(String latestAllowedVersion) {
        this.latestAllowedVersion = latestAllowedVersion;
        this.lastCheckedAt = Instant.now();
    }

    public String getScope() {
        return scope;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }
}
