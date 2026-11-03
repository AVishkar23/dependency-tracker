package com.avishkar.dependencytracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "tracked_projects")
public class TrackedProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "repo_url")
    private String repoUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TrackedProject() {
        // JPA
    }

    public TrackedProject(String name, String repoUrl) {
        this.name = name;
        this.repoUrl = repoUrl;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
