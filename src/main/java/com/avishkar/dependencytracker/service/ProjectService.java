package com.avishkar.dependencytracker.service;

import com.avishkar.dependencytracker.model.DependencyEntry;
import com.avishkar.dependencytracker.model.TrackedProject;
import com.avishkar.dependencytracker.repository.DependencyEntryRepository;
import com.avishkar.dependencytracker.repository.TrackedProjectRepository;
import com.avishkar.dependencytracker.version.MavenVersion;
import com.avishkar.dependencytracker.version.VersionGap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    /** Builds the outdated report: every outdated dependency, worst gap first. */
    public OutdatedReport report(Long projectId) {
        TrackedProject project = getProject(projectId);
        List<DependencyEntry> all = dependencyRepository.findByProject(project);

        List<OutdatedReport.Entry> outdated = all.stream()
                .filter(DependencyEntry::isOutdated)
                .map(entry -> new OutdatedReport.Entry(
                        entry.getId(),
                        entry.getGroupId(),
                        entry.getArtifactId(),
                        entry.getCurrentVersion(),
                        entry.getLatestAllowedVersion(),
                        VersionGap.classify(
                                MavenVersion.parse(entry.getCurrentVersion()),
                                MavenVersion.parse(entry.getLatestAllowedVersion()))))
                .sorted(Comparator.comparing(OutdatedReport.Entry::severity)
                        .thenComparing(OutdatedReport.Entry::groupId)
                        .thenComparing(OutdatedReport.Entry::artifactId))
                .toList();

        Map<VersionGap.Severity, Integer> bySeverity = new EnumMap<>(VersionGap.Severity.class);
        outdated.forEach(entry -> bySeverity.merge(entry.severity(), 1, Integer::sum));

        return new OutdatedReport(
                project.getId(), project.getName(), Instant.now(),
                all.size(), outdated.size(), bySeverity, outdated);
    }

    /**
     * Sets (or, with a null version, clears) the policy ceiling for one dependency.
     * The ceiling must be a plain Maven version — ranges are rejected, because a
     * ceiling is a single decision ("the newest we allow"), not a constraint spec.
     */
    @Transactional
    public DependencyEntry setLatestAllowedVersion(Long projectId, Long dependencyId, String version) {
        TrackedProject project = getProject(projectId);
        DependencyEntry entry = dependencyRepository.findById(dependencyId)
                .filter(e -> e.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> new NoSuchElementException(
                        "No dependency " + dependencyId + " on project " + projectId));
        if (version != null) {
            version = version.trim();
            if (!MavenVersion.isValidVersionString(version)) {
                throw new IllegalArgumentException("Not a valid Maven version: '" + version + "'");
            }
        }
        entry.setLatestAllowedVersion(version);
        return dependencyRepository.save(entry);
    }
}
