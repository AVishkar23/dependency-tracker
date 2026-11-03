package com.avishkar.dependencytracker.api;

import com.avishkar.dependencytracker.api.ProjectDtos.CreateProjectRequest;
import com.avishkar.dependencytracker.api.ProjectDtos.DependencyResponse;
import com.avishkar.dependencytracker.api.ProjectDtos.OutdatedReportResponse;
import com.avishkar.dependencytracker.api.ProjectDtos.ProjectResponse;
import com.avishkar.dependencytracker.api.ProjectDtos.SetVersionRequest;
import com.avishkar.dependencytracker.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Projects", description = "Register projects, import their pom.xml, "
        + "manage per-dependency policy ceilings, and read outdated reports.")
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Operation(summary = "Register a project to track")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@RequestBody CreateProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Project name is required");
        }
        return ProjectResponse.from(projectService.createProject(request.name(), request.repoUrl()));
    }

    @Operation(summary = "List all tracked projects")
    @GetMapping
    public List<ProjectResponse> listProjects() {
        return projectService.listProjects().stream().map(ProjectResponse::from).toList();
    }

    /** Upload a pom.xml (raw XML body) and replace the project's tracked dependencies with it. */
    @Operation(summary = "Import (or re-import) a project's pom.xml",
            description = "Parses the pom \u2014 properties, dependencyManagement and parent-POM "
                    + "inheritance included \u2014 and replaces the project's dependency list. "
                    + "Each dependency keeps the policy ceiling previously set for the same "
                    + "groupId + artifactId.")
    @PostMapping(value = "/{id}/pom", consumes = MediaType.APPLICATION_XML_VALUE)
    public List<DependencyResponse> importPom(@PathVariable Long id, @RequestBody String pomXml) {
        return projectService.importPom(id, pomXml).stream().map(DependencyResponse::from).toList();
    }

    @Operation(summary = "List a project's dependencies, each flagged outdated or not")
    @GetMapping("/{id}/dependencies")
    public List<DependencyResponse> listDependencies(@PathVariable Long id) {
        return projectService.listDependencies(id).stream().map(DependencyResponse::from).toList();
    }

    @Operation(summary = "List only the dependencies behind their policy ceiling")
    @GetMapping("/{id}/dependencies/outdated")
    public List<DependencyResponse> listOutdated(@PathVariable Long id) {
        return projectService.listOutdated(id).stream().map(DependencyResponse::from).toList();
    }

    /** Outdated report: counts and per-dependency gap classification, worst first. */
    @Operation(summary = "Outdated report: counts, severity breakdown, gaps worst-first")
    @GetMapping("/{id}/report")
    public OutdatedReportResponse report(@PathVariable Long id) {
        return OutdatedReportResponse.from(projectService.report(id));
    }

    /** Set the version-policy ceiling ("latest allowed") for one dependency. */
    @Operation(summary = "Set (or clear, with null) a dependency's policy ceiling",
            description = "The ceiling must be a plain Maven version; ranges are rejected with 400.")
    @PatchMapping("/{id}/dependencies/{dependencyId}")
    public DependencyResponse setLatestAllowedVersion(@PathVariable Long id,
                                                      @PathVariable Long dependencyId,
                                                      @RequestBody SetVersionRequest request) {
        return DependencyResponse.from(projectService
                .setLatestAllowedVersion(id, dependencyId, request.latestAllowedVersion()));
    }
}
