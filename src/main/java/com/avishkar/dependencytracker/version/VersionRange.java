package com.avishkar.dependencytracker.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A Maven version range specification, e.g. {@code [1.0,2.0)}, {@code (,1.5]},
 * {@code [1.2]}, or a union like {@code [1.0,1.5],[2.0,3.0)}.
 *
 * <p>Semantics follow Maven: square brackets are inclusive bounds, parentheses
 * exclusive; a bare version with no brackets (a "soft" requirement such as
 * {@code 1.5}) is a recommendation that any version satisfies; multiple bracketed
 * restrictions form a union — a version is contained if any restriction contains it.
 *
 * <p>Maven allows a hard range in a pom's {@code <version>} element. When a project
 * declares one, the build resolves some version inside the range, so this tracker
 * records the range's recommended version, or failing that its lowest allowed
 * version — the floor the build could resolve to. See {@link #trackedVersion()}.
 */
public final class VersionRange {

    /** One bracketed restriction: an interval with independently inclusive/exclusive ends. */
    private record Restriction(MavenVersion lower, boolean lowerInclusive,
                               MavenVersion upper, boolean upperInclusive) {

        boolean contains(MavenVersion version) {
            if (lower != null) {
                int compared = version.compareTo(lower);
                if (compared < 0 || (compared == 0 && !lowerInclusive)) {
                    return false;
                }
            }
            if (upper != null) {
                int compared = version.compareTo(upper);
                if (compared > 0 || (compared == 0 && !upperInclusive)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final String spec;
    private final List<Restriction> restrictions;
    private final MavenVersion recommended;

    private VersionRange(String spec, List<Restriction> restrictions, MavenVersion recommended) {
        this.spec = spec;
        this.restrictions = restrictions;
        this.recommended = recommended;
    }

    /** True if the spec uses range syntax (starts with '[' or '('). */
    public static boolean isRangeSpec(String candidate) {
        if (candidate == null) {
            return false;
        }
        String trimmed = candidate.trim();
        return trimmed.startsWith("[") || trimmed.startsWith("(");
    }

    /**
     * Parses a range specification.
     *
     * @throws IllegalArgumentException if the spec is malformed
     */
    public static VersionRange parse(String spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        String s = spec.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty version range specification");
        }
        if (!isRangeSpec(s)) {
            return new VersionRange(s, List.of(), MavenVersion.parse(s));
        }

        List<Restriction> restrictions = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char open = s.charAt(i);
            if (open != '[' && open != '(') {
                throw new IllegalArgumentException(
                        "Invalid version range '" + spec + "': expected '[' or '(' at position " + i);
            }
            int close = -1;
            for (int j = i + 1; j < n; j++) {
                char ch = s.charAt(j);
                if (ch == ']' || ch == ')') {
                    close = j;
                    break;
                }
            }
            if (close < 0) {
                throw new IllegalArgumentException("Invalid version range '" + spec + "': unclosed restriction");
            }
            boolean lowerInclusive = open == '[';
            boolean upperInclusive = s.charAt(close) == ']';
            restrictions.add(parseRestriction(
                    spec, s.substring(i + 1, close), lowerInclusive, upperInclusive));
            i = close + 1;
            if (i < n) {
                if (s.charAt(i) != ',') {
                    throw new IllegalArgumentException(
                            "Invalid version range '" + spec + "': expected ',' at position " + i);
                }
                i++;
                while (i < n && s.charAt(i) == ' ') {
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("Invalid version range '" + spec + "': trailing ','");
                }
            }
        }
        return new VersionRange(s, List.copyOf(restrictions), null);
    }

    private static Restriction parseRestriction(String spec, String inner,
                                                boolean lowerInclusive, boolean upperInclusive) {
        String[] parts = inner.split(",", -1);
        if (parts.length == 1) {
            String single = parts[0].trim();
            if (single.isEmpty() || !lowerInclusive || !upperInclusive) {
                throw new IllegalArgumentException(
                        "Invalid version range '" + spec + "': a single version must be written as [version]");
            }
            MavenVersion version = MavenVersion.parse(single);
            return new Restriction(version, true, version, true);
        }
        if (parts.length > 2) {
            throw new IllegalArgumentException(
                    "Invalid version range '" + spec + "': too many bounds in one restriction");
        }
        String lowerText = parts[0].trim();
        String upperText = parts[1].trim();
        MavenVersion lower = lowerText.isEmpty() ? null : MavenVersion.parse(lowerText);
        MavenVersion upper = upperText.isEmpty() ? null : MavenVersion.parse(upperText);
        if (lower == null && upper == null) {
            throw new IllegalArgumentException("Invalid version range '" + spec + "': restriction has no bounds");
        }
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw new IllegalArgumentException(
                    "Invalid version range '" + spec + "': lower bound is greater than upper bound");
        }
        return new Restriction(lower, lowerInclusive, upper, upperInclusive);
    }

    /** True if the version satisfies this range (any restriction, for a union). Soft specs accept everything. */
    public boolean contains(MavenVersion version) {
        Objects.requireNonNull(version, "version must not be null");
        if (restrictions.isEmpty()) {
            return true;
        }
        return restrictions.stream().anyMatch(restriction -> restriction.contains(version));
    }

    /** The recommended version of a soft spec ({@code 1.5}), or empty for a bracketed range. */
    public Optional<MavenVersion> recommendedVersion() {
        return Optional.ofNullable(recommended);
    }

    /** The smallest lower bound across all restrictions, if any restriction has one. */
    public Optional<MavenVersion> lowestAllowed() {
        return restrictions.stream()
                .map(Restriction::lower)
                .filter(Objects::nonNull)
                .min(MavenVersion::compareTo);
    }

    /**
     * The single version this tracker records for a range declaration: the recommended
     * version of a soft spec, else the lowest version the range allows.
     */
    public Optional<MavenVersion> trackedVersion() {
        return recommendedVersion().or(this::lowestAllowed);
    }

    @Override
    public String toString() {
        return spec;
    }
}
