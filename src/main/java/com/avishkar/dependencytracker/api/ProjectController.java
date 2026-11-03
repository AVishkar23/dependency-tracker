package com.avishkar.dependencytracker.api;

import com.avishkar.dependencytracker.api.ProjectDtos.CreateProjectRequest;
import com.avishkar.dependencytracker.api.ProjectDtos.DependencyResponse;
import com.avishkar.dependencytracker.api.ProjectDtos.ProjectResponse;
import com.avishkar.dependencytracker.api.ProjectDtos.SetVersionRequest;
import com.avishkar.dependencytracker.service.ProjectService;
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

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@RequestBody CreateProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Project name is required");
        }
        return ProjectResponse.from(projectService.createProject(request.name(), request.repoUrl()));
    }

    @GetMapping
    public List<ProjectResponse> listProjects() {
        return projectService.listProjects().stream().map(ProjectResponse::from).toList();
    }

    /** Upload a pom.xml (raw XML body) and replace the project's tracked dependencies with it. */
    @PostMapping(value = "/{id}/pom", consumes = MediaType.APPLICATION_XML_VALUE)
    public List<DependencyResponse> importPom(@PathVariable Long id, @RequestBody String pomXml) {
        return projectService.importPom(id, pomXml).stream().map(DependencyResponse::from).toList();
    }

    @GetMapping("/{id}/dependencies")
    public List<DependencyResponse> listDependencies(@PathVariable Long id) {
        return projectService.listDependencies(id).stream().map(DependencyResponse::from).toList();
    }

    @GetMapping("/{id}/dependencies/outdated")
    public List<DependencyResponse> listOutdated(@PathVariable Long id) {
        return projectService.listOutdated(id).stream().map(DependencyResponse::from).toList();
    }

    /** Set the version-policy ceiling ("latest allowed") for one dependency. */
    @PatchMapping("/{id}/dependencies/{dependencyId}")
    public DependencyResponse setLatestAllowedVersion(@PathVariable Long id,
                                                      @PathVariable Long dependencyId,
                                                      @RequestBody SetVersionRequest request) {
        return DependencyResponse.from(projectService
                .setLatestAllowedVersion(id, dependencyId, request.latestAllowedVersion()));
    }
}
