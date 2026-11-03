package com.avishkar.dependencytracker;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end flow tests over MockMvc. The suite shares one application context and
 * database, so tests capture the IDs the API returns instead of assuming the
 * sequence starts at 1 — no test depends on another test's data or on run order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectFlowTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String POM = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>2.15.0</version>
                </dependency>
                <dependency>
                  <groupId>org.postgresql</groupId>
                  <artifactId>postgresql</artifactId>
                  <version>42.7.3</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private long createProject(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"repoUrl\":\"https://example.com/" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Imports a pom and returns dependency IDs keyed by artifactId. */
    private Map<String, Long> importPom(long projectId, String pom) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects/" + projectId + "/pom")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(pom))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> dependencies =
                JsonPath.read(result.getResponse().getContentAsString(), "$");
        Map<String, Long> idsByArtifact = new HashMap<>();
        for (Map<String, Object> dependency : dependencies) {
            idsByArtifact.put(
                    (String) dependency.get("artifactId"),
                    ((Number) dependency.get("id")).longValue());
        }
        return idsByArtifact;
    }

    private void setCeiling(long projectId, long dependencyId, String version) throws Exception {
        mockMvc.perform(patch("/api/projects/" + projectId + "/dependencies/" + dependencyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"" + version + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void createProject_importPom_flagOutdated() throws Exception {
        long projectId = createProject("payments-service");
        Map<String, Long> ids = importPom(projectId, POM);

        mockMvc.perform(get("/api/projects/" + projectId + "/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].artifactId").value("jackson-databind"))
                .andExpect(jsonPath("$[0].outdated").value(false));

        // Set the policy ceiling above the current version -> dependency becomes outdated.
        mockMvc.perform(patch("/api/projects/" + projectId + "/dependencies/" + ids.get("jackson-databind"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"2.17.2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outdated").value(true));

        mockMvc.perform(get("/api/projects/" + projectId + "/dependencies/outdated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].artifactId").value("jackson-databind"));
    }

    @Test
    void outdatedUsesMavenSemanticsNotStringComparison() throws Exception {
        long projectId = createProject("semantics-service");
        Map<String, Long> ids = importPom(projectId, POM);
        long jackson = ids.get("jackson-databind");

        // 2.9.0 sorts after "2.15.0" as a string but is OLDER under Maven rules:
        // a ceiling of 2.9.0 must not flag a project already running 2.15.0.
        mockMvc.perform(patch("/api/projects/" + projectId + "/dependencies/" + jackson)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"2.9.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outdated").value(false));

        // 2.15 and 2.15.0 are the same Maven version: still not outdated.
        mockMvc.perform(patch("/api/projects/" + projectId + "/dependencies/" + jackson)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"2.15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outdated").value(false));

        mockMvc.perform(get("/api/projects/" + projectId + "/dependencies/outdated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reportClassifiesGapsAndSortsWorstFirst() throws Exception {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>2.15.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                      <version>42.7.3</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0-SNAPSHOT</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>current</artifactId>
                      <version>3.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        long projectId = createProject("report-service");
        Map<String, Long> ids = importPom(projectId, pom);
        setCeiling(projectId, ids.get("jackson-databind"), "2.17.2");  // MINOR gap
        setCeiling(projectId, ids.get("postgresql"), "42.7.4");        // PATCH gap
        setCeiling(projectId, ids.get("lib"), "1.0");                  // QUALIFIER gap
        setCeiling(projectId, ids.get("current"), "3.0");              // up to date

        mockMvc.perform(get("/api/projects/" + projectId + "/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("report-service"))
                .andExpect(jsonPath("$.totalDependencies").value(4))
                .andExpect(jsonPath("$.outdatedCount").value(3))
                .andExpect(jsonPath("$.bySeverity.MINOR").value(1))
                .andExpect(jsonPath("$.bySeverity.PATCH").value(1))
                .andExpect(jsonPath("$.bySeverity.QUALIFIER").value(1))
                .andExpect(jsonPath("$.outdated.length()").value(3))
                .andExpect(jsonPath("$.outdated[0].artifactId").value("jackson-databind"))
                .andExpect(jsonPath("$.outdated[0].severity").value("MINOR"))
                .andExpect(jsonPath("$.outdated[1].artifactId").value("postgresql"))
                .andExpect(jsonPath("$.outdated[1].severity").value("PATCH"))
                .andExpect(jsonPath("$.outdated[2].artifactId").value("lib"))
                .andExpect(jsonPath("$.outdated[2].severity").value("QUALIFIER"));
    }

    @Test
    void invalidCeilingIsRejected() throws Exception {
        long projectId = createProject("strict-service");
        Map<String, Long> ids = importPom(projectId, POM);
        long dependencyId = ids.get("jackson-databind");

        mockMvc.perform(patch("/api/projects/" + projectId + "/dependencies/" + dependencyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"not a version!!\"}"))
                .andExpect(status().isBadRequest());

        // A range is a constraint spec, not a ceiling.
        mockMvc.perform(patch("/api/projects/" + projectId + "/dependencies/" + dependencyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"[1.0,2.0)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownProjectIs404() throws Exception {
        mockMvc.perform(get("/api/projects/999999/dependencies"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/projects/999999/report"))
                .andExpect(status().isNotFound());
    }
}
