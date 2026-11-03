package com.avishkar.dependencytracker.repository;

import com.avishkar.dependencytracker.model.TrackedProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrackedProjectRepository extends JpaRepository<TrackedProject, Long> {
    Optional<TrackedProject> findByName(String name);
}
