package com.avishkar.dependencytracker.version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionRangeTest {

    private static boolean accepts(String spec, String version) {
        return VersionRange.parse(spec).contains(MavenVersion.parse(version));
    }

    @Test
    void halfOpenRange() {
        assertThat(accepts("[1.0,2.0)", "1.0")).isTrue();
        assertThat(accepts("[1.0,2.0)", "1.5")).isTrue();
        assertThat(accepts("[1.0,2.0)", "2.0")).isFalse();
        assertThat(accepts("[1.0,2.0)", "0.9")).isFalse();
    }

    @Test
    void openEndedRanges() {
        assertThat(accepts("(,1.5]", "0.1")).isTrue();
        assertThat(accepts("(,1.5]", "1.5")).isTrue();
        assertThat(accepts("(,1.5]", "1.5.1")).isFalse();
        assertThat(accepts("[2.0,)", "99.0")).isTrue();
        assertThat(accepts("[2.0,)", "1.9")).isFalse();
    }

    @Test
    void exclusiveOnBothEnds() {
        assertThat(accepts("(1.0,2.0)", "1.0")).isFalse();
        assertThat(accepts("(1.0,2.0)", "2.0")).isFalse();
        assertThat(accepts("(1.0,2.0)", "1.5")).isTrue();
    }

    @Test
    void singleVersionRangeUsesMavenEquality() {
        assertThat(accepts("[1.2]", "1.2")).isTrue();
        // Maven version equality: 1.2.0 is the same version as 1.2.
        assertThat(accepts("[1.2]", "1.2.0")).isTrue();
        assertThat(accepts("[1.2]", "1.3")).isFalse();
    }

    @Test
    void unionOfRestrictions() {
        assertThat(accepts("[1.0,1.5],[2.0,3.0)", "1.2")).isTrue();
        assertThat(accepts("[1.0,1.5],[2.0,3.0)", "1.7")).isFalse();
        assertThat(accepts("[1.0,1.5],[2.0,3.0)", "2.5")).isTrue();
        assertThat(accepts("[1.0,1.5],[2.0,3.0)", "3.0")).isFalse();
    }

    @Test
    void qualifierAwareBounds() {
        // rc ranks below snapshot in Maven's qualifier order, so it falls outside the range.
        assertThat(accepts("[1.0-SNAPSHOT,1.0]", "1.0-rc-1")).isFalse();
        assertThat(accepts("[1.0-SNAPSHOT,1.0]", "1.0-SNAPSHOT")).isTrue();
        assertThat(accepts("[1.0-SNAPSHOT,1.0]", "1.0")).isTrue();
    }

    @Test
    void softSpecIsARecommendationOnly() {
        VersionRange range = VersionRange.parse("1.5");
        assertThat(range.contains(MavenVersion.parse("9.9"))).isTrue();
        assertThat(range.recommendedVersion()).contains(MavenVersion.parse("1.5"));
        assertThat(range.trackedVersion()).contains(MavenVersion.parse("1.5"));
    }

    @Test
    void trackedVersionFallsBackToLowestAllowed() {
        assertThat(VersionRange.parse("[1.0,2.0)").trackedVersion())
                .contains(MavenVersion.parse("1.0"));
        assertThat(VersionRange.parse("(,1.5]").trackedVersion()).isEmpty();
    }

    @Test
    void malformedSpecsAreRejected() {
        assertThatThrownBy(() -> VersionRange.parse("[1.0,2.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.parse("[2.0,1.0]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.parse("(1.0)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.parse("[1.0],x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.parse("(,)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.parse("[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangeSpecDetection() {
        assertThat(VersionRange.isRangeSpec("[1.0]")).isTrue();
        assertThat(VersionRange.isRangeSpec("(,1.0]")).isTrue();
        assertThat(VersionRange.isRangeSpec("1.5")).isFalse();
        assertThat(VersionRange.isRangeSpec(null)).isFalse();
    }
}
