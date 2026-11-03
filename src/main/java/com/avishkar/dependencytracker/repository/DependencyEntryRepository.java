package com.avishkar.dependencytracker.repository;

import com.avishkar.dependencytracker.model.DependencyEntry;
import com.avishkar.dependencytracker.model.TrackedProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DependencyEntryRepository extends JpaRepository<DependencyEntry, Long> {
    List<DependencyEntry> findByProject(TrackedProject project);

    Optional<DependencyEntry> findByProjectAndGroupIdAndArtifactId(
            TrackedProject project, String groupId, String artifactId);

    void deleteByProject(TrackedProject project);
}
