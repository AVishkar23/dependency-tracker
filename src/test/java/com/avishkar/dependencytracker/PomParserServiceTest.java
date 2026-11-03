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

    @Test
    void parsesRealWorldPomWithNamespace() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>payments</artifactId>
                  <version>1.2.0</version>
                  <properties>
                    <java.version>17</java.version>
                    <jackson.version>2.17.2</jackson.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>${jackson.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).version()).isEqualTo("2.17.2");
    }

    @Test
    void dependencyManagementFillsMissingVersions_butImportedBomDoesNot() {
        String pom = """
                <project>
                  <properties>
                    <jackson.version>2.17.2</jackson.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>${jackson.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>3.3.5</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).version()).isEqualTo("2.17.2");
        // Only the imported BOM manages this one; its contents are never fetched.
        assertThat(deps.get(1).version()).isNull();
    }

    @Test
    void parentPomSuppliesProjectVersionAndGroupId() {
        String pom = """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>3.3.5</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>${project.groupId}</groupId>
                      <artifactId>sibling</artifactId>
                      <version>${project.parent.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>com.other</groupId>
                      <artifactId>self-versioned</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).groupId()).isEqualTo("com.example");
        assertThat(deps.get(0).version()).isEqualTo("3.3.5");
        assertThat(deps.get(1).version()).isEqualTo("3.3.5");
    }

    @Test
    void ciFriendlyVersionResolvesThroughProjectVersion() {
        String pom = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>${revision}</version>
                  <properties>
                    <revision>1.4.0</revision>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps.get(0).version()).isEqualTo("1.4.0");
    }

    @Test
    void inlineInterpolation_andUnknownPlaceholdersStayLiteral() {
        String pom = """
                <project>
                  <properties>
                    <major>42</major>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>partial</artifactId>
                      <version>${major}.7</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>unknown</artifactId>
                      <version>${not.defined}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps.get(0).version()).isEqualTo("42.7");
        assertThat(deps.get(1).version()).isEqualTo("${not.defined}");
    }

    @Test
    void duplicateDependencyLastDeclarationWins() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>dup</artifactId>
                      <version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>other</artifactId>
                      <version>0.1</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>dup</artifactId>
                      <version>2.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).artifactId()).isEqualTo("dup");
        assertThat(deps.get(0).version()).isEqualTo("2.0");
        assertThat(deps.get(1).artifactId()).isEqualTo("other");
    }

    @Test
    void versionRangeIsRecordedAtItsFloor() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>ranged</artifactId>
                      <version>[1.0,2.0)</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>exact</artifactId>
                      <version>[1.5]</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps.get(0).version()).isEqualTo("1.0");
        assertThat(deps.get(1).version()).isEqualTo("1.5");
    }

    @Test
    void profileDependenciesAreIgnored() {
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>always</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                  <profiles>
                    <profile>
                      <id>dev</id>
                      <dependencies>
                        <dependency>
                          <groupId>com.example</groupId>
                          <artifactId>sometimes</artifactId>
                          <version>1.0</version>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """;

        List<ParsedDependency> deps = parser.parse(pom);

        assertThat(deps).extracting(ParsedDependency::artifactId).containsExactly("always");
    }

    @Test
    void rejectsNonPomRootElement() {
        assertThatThrownBy(() -> parser.parse("<html><body/></html>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a Maven pom.xml");
    }

    @Test
    void xxeDeclarationsAreRejected() {
        String pom = """
                <?xml version="1.0"?>
                <!DOCTYPE project [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>evil</artifactId>
                      <version>&xxe;</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        assertThatThrownBy(() -> parser.parse(pom))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
