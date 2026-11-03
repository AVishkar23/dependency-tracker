package com.avishkar.dependencytracker.version;

import com.avishkar.dependencytracker.version.VersionGap.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionGapTest {

    private static Severity gap(String current, String ceiling) {
        return VersionGap.classify(MavenVersion.parse(current), MavenVersion.parse(ceiling));
    }

    @Test
    void majorGap() {
        assertThat(gap("1.9", "2.0")).isEqualTo(Severity.MAJOR);
        assertThat(gap("0.9", "1.0")).isEqualTo(Severity.MAJOR);
        assertThat(gap("1.0-alpha", "2.0")).isEqualTo(Severity.MAJOR);
    }

    @Test
    void minorGap() {
        assertThat(gap("2.15.0", "2.17.2")).isEqualTo(Severity.MINOR);
        assertThat(gap("1.0", "1.1")).isEqualTo(Severity.MINOR);
    }

    @Test
    void patchGap() {
        assertThat(gap("2.17.1", "2.17.2")).isEqualTo(Severity.PATCH);
        assertThat(gap("1.0", "1.0.1")).isEqualTo(Severity.PATCH);
    }

    @Test
    void buildSuffixGapCountsAsPatch() {
        assertThat(gap("1.0-1", "1.0-2")).isEqualTo(Severity.PATCH);
    }

    @Test
    void qualifierOnlyGap() {
        assertThat(gap("1.0-SNAPSHOT", "1.0")).isEqualTo(Severity.QUALIFIER);
        assertThat(gap("1.0-alpha", "1.0-beta")).isEqualTo(Severity.QUALIFIER);
        assertThat(gap("1.0-alpha-1", "1.0-alpha-2")).isEqualTo(Severity.QUALIFIER);
    }
}
