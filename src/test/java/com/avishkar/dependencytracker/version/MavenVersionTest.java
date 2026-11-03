package com.avishkar.dependencytracker.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vectors follow Maven's own versioning rules (the ComparableVersion semantics
 * documented by the Maven project): qualifier ranking, alias normalization,
 * shorthand expansion, and hyphen-digit sub-lists.
 */
class MavenVersionTest {

    private static MavenVersion v(String version) {
        return MavenVersion.parse(version);
    }

    private static void assertEqual(String left, String right) {
        assertThat(v(left)).as("%s == %s", left, right).isEqualTo(v(right));
        assertThat(v(left).compareTo(v(right))).as("%s <=> %s", left, right).isZero();
        assertThat(v(left).hashCode()).as("hashCode %s == %s", left, right).isEqualTo(v(right).hashCode());
    }

    private static void assertLess(String left, String right) {
        assertThat(v(left).compareTo(v(right))).as("%s < %s", left, right).isNegative();
        assertThat(v(right).compareTo(v(left))).as("%s > %s", right, left).isPositive();
    }

    @Test
    void trailingZerosAndReleaseAliasesCollapse() {
        List<String> equal = List.of("1.0", "1", "1.0.0", "1.0.0.0",
                "1.0-ga", "1.0-FINAL", "1.0-release", "1.0.0.Final");
        for (String a : equal) {
            for (String b : equal) {
                assertEqual(a, b);
            }
        }
    }

    @Test
    void shorthandQualifiersExpand() {
        assertEqual("1.0-alpha-1", "1.0a1");
        assertEqual("1.0-alpha-1", "1.0-alpha1");
        assertEqual("1.0-alpha-1", "1.0-alpha.1");
        assertEqual("1.0-beta-1", "1.0b1");
        assertEqual("1.0-milestone-1", "1.0m1");
        assertEqual("1.0-rc-1", "1.0-cr-1");
        assertEqual("1.0-rc-1", "1.0rc1");
    }

    @Test
    void caseAndLeadingZerosAreIgnored() {
        assertEqual("1.0-ALPHA-1", "1.0-alpha-1");
        assertEqual("1.01", "1.1");
        assertEqual("1.007", "1.7");
    }

    @Test
    void qualifierOrderingChain() {
        List<String> ascending = List.of(
                "0.9.9",
                "1.0-alpha-1", "1.0-alpha-2", "1.0-alpha-123",
                "1.0-beta-2", "1.0-beta-123",
                "1.0-milestone-1", "1.0-milestone-2", "1.0-milestone-10", "1.0-milestone-123",
                "1.0-rc-1", "1.0-rc-123",
                "1.0-SNAPSHOT",
                "1.0",
                "1.0-1",
                "1.0-sp-1", "1.0-sp-123",
                "1.0.1",
                "1.1", "1.9", "1.10",
                "2.0-alpha-1", "2.0", "2.1.1",
                "10.0");
        for (int i = 0; i + 1 < ascending.size(); i++) {
            assertLess(ascending.get(i), ascending.get(i + 1));
        }
        // every element is also below every later element (total order, no local inversions)
        for (int i = 0; i < ascending.size(); i++) {
            for (int j = i + 1; j < ascending.size(); j++) {
                assertLess(ascending.get(i), ascending.get(j));
            }
        }
    }

    @Test
    void numericSegmentsCompareNumericallyNotLexicographically() {
        assertLess("2.9.0", "2.17.2");
        assertLess("1.9", "1.10");
        assertLess("1.0-milestone-2", "1.0-milestone-10");
    }

    @Test
    void arbitraryPrecisionNumbers() {
        assertLess("1.99999999999999999999999999", "1.100000000000000000000000000");
        assertEqual("1.00000000000000000000000001", "1.1");
    }

    @Test
    void hyphenDigitSuffixSitsBetweenReleaseAndNextPatch() {
        assertLess("1.0", "1.0-1");
        assertLess("1.0-1", "1.0-2");
        assertLess("1.0-1", "1.0.1");
    }

    @Test
    void bareQualifierSortsBeforeNumberedQualifier() {
        assertLess("1.0-alpha", "1.0-alpha-1");
        assertLess("1.0-alpha", "1.0-beta");
    }

    @Test
    void unknownQualifiersSortAfterRelease() {
        // Maven semantics: an unrecognized qualifier is "newer" than the plain release.
        assertLess("1.0", "1.0-foo");
        assertLess("1.0-abc", "1.0-xyz");
    }

    @Test
    void snapshotDetection() {
        assertThat(v("1.0-SNAPSHOT").isSnapshot()).isTrue();
        assertThat(v("2.17.2-SNAPSHOT").isSnapshot()).isTrue();
        assertThat(v("1.0").isSnapshot()).isFalse();
        assertThat(v("1.0-alpha-1").isSnapshot()).isFalse();
    }

    @Test
    void validVersionStrings() {
        assertThat(MavenVersion.isValidVersionString("2.17.2")).isTrue();
        assertThat(MavenVersion.isValidVersionString("1.0-SNAPSHOT")).isTrue();
        assertThat(MavenVersion.isValidVersionString("1.0.0.Final")).isTrue();
        assertThat(MavenVersion.isValidVersionString("")).isFalse();
        assertThat(MavenVersion.isValidVersionString("  ")).isFalse();
        assertThat(MavenVersion.isValidVersionString("[1.0,2.0)")).isFalse();
        assertThat(MavenVersion.isValidVersionString("1.0 beta")).isFalse();
        assertThat(MavenVersion.isValidVersionString(null)).isFalse();
    }
}
