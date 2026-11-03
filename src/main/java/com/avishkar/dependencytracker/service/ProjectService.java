package com.avishkar.dependencytracker.service;

import com.avishkar.dependencytracker.model.DependencyEntry;
import com.avishkar.dependencytracker.model.TrackedProject;
import com.avishkar.dependencytracker.repository.DependencyEntryRepository;
import com.avishkar.dependencytracker.repository.TrackedProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ProjectService {

    private final TrackedProjectRepository projectRepository;
    private final DependencyEntryRepository dependencyRepository;
    private final PomParserService pomParserService;

    public ProjectService(TrackedProjectRepository projectRepository,
                          DependencyEntryRepository dependencyRepository,
                          PomParserService pomParserService) {
        this.projectRepository = projectRepository;
        this.dependencyRepository = dependencyRepository;
        this.pomParserService = pomParserService;
    }

    public List<TrackedProject> listProjects() {
        return projectRepository.findAll();
    }

    @Transactional
    public TrackedProject createProject(String name, String repoUrl) {
        projectRepository.findByName(name).ifPresent(existing -> {
            throw new IllegalArgumentException("A project named '" + name + "' is already tracked");
        });
        return projectRepository.save(new TrackedProject(name, repoUrl));
    }

    public TrackedProject getProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No project with id " + id));
    }

    /**
     * Replaces the project's tracked dependencies with the ones parsed from a fresh pom.xml.
     * Version-policy ceilings set earlier are carried over, so re-importing a pom
     * never wipes out the "latest allowed" decisions a team already made.
     */
    @Transactional
    public List<DependencyEntry> importPom(Long projectId, String pomXml) {
        TrackedProject project = getProject(projectId);
        List<ParsedDependency> parsed = pomParserService.parse(pomXml);

        List<DependencyEntry> previous = dependencyRepository.findByProject(project);
        dependencyRepository.deleteByProject(project);
        dependencyRepository.flush();

        List<DependencyEntry> saved = parsed.stream()
                .map(p -> {
                    DependencyEntry entry = new DependencyEntry(
                            project, p.groupId(), p.artifactId(), p.version(), p.scope());
                    previous.stream()
                            .filter(old -> old.getGroupId().equals(p.groupId())
                                    && old.getArtifactId().equals(p.artifactId()))
                            .findFirst()
                            .ifPresent(old -> entry.setLatestAllowedVersion(old.getLatestAllowedVersion()));
                    return entry;
                })
                .toList();
        return dependencyRepository.saveAll(saved);
    }

    public List<DependencyEntry> listDependencies(Long projectId) {
        return dependencyRepository.findByProject(getProject(projectId));
    }

    public List<DependencyEntry> listOutdated(Long projectId) {
        return listDependencies(projectId).stream()
                .filter(DependencyEntry::isOutdated)
                .toList();
    }

    @Transactional
    public DependencyEntry setLatestAllowedVersion(Long projectId, Long dependencyId, String version) {
        TrackedProject project = getProject(projectId);
        DependencyEntry entry = dependencyRepository.findById(dependencyId)
                .filter(e -> e.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> new NoSuchElementException(
                        "No dependency " + dependencyId + " on project " + projectId));
        entry.setLatestAllowedVersion(version);
        return dependencyRepository.save(entry);
    }
}
