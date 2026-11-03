package com.avishkar.dependencytracker.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The spec itself is generated from the controller by springdoc
 * and served at /v3/api-docs, with the Swagger UI at /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dependencyTrackerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Dependency Tracker API")
                .version("0.1.0")
                .description("Tracks the dependencies declared in a project's pom.xml against "
                        + "per-dependency policy ceilings — the newest version a team allows — "
                        + "and reports which dependencies lag behind, classified by gap severity "
                        + "under Maven's version ordering."));
    }
}
