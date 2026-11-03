package com.avishkar.dependencytracker;

import com.avishkar.dependencytracker.service.ParsedDependency;
import com.avishkar.dependencytracker.service.PomParserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PomParserServiceTest {

    private final PomParserService parser = new PomParserService();

    @Test
    void parsesPlainAndPropertyVersions_andDefaultsScope() {
        String pom = """
                <project>
                  <properties>
                    <jackson.version>2.17.2</jackson.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>${jackson.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                      <version>4.13.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).version()).isEqualTo("2.17.2");
        assertThat(deps.get(0).scope()).isEqualTo("compile");
        assertThat(deps.get(1).scope()).isEqualTo("test");
    }

    @Test
    void keepsManagedDependencyWithNullVersion() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).version()).isNull();
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> parser.parse("<project><dependencies>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresDependencyManagementSection() {
        String pom = """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>managed-only</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>real</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).extracting(ParsedDependency::artifactId).containsExactly("real");
    }
}
