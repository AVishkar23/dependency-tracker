package com.avishkar.dependencytracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void createProject_importPom_flagOutdated() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"payments-service\",\"repoUrl\":\"https://example.com/payments\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mockMvc.perform(post("/api/projects/1/pom")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(POM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].artifactId").value("jackson-databind"))
                .andExpect(jsonPath("$[0].outdated").value(false));

        // Set the policy ceiling above the current version -> dependency becomes outdated.
        mockMvc.perform(patch("/api/projects/1/dependencies/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latestAllowedVersion\":\"2.17.2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outdated").value(true));

        mockMvc.perform(get("/api/projects/1/dependencies/outdated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].artifactId").value("jackson-databind"));
    }

    @Test
    void unknownProjectIs404() throws Exception {
        mockMvc.perform(get("/api/projects/999/dependencies"))
                .andExpect(status().isNotFound());
    }
}
