package com.avishkar.dependencytracker.version;

import com.avishkar.dependencytracker.version.MavenVersion.Item;
import com.avishkar.dependencytracker.version.MavenVersion.Num;
import com.avishkar.dependencytracker.version.MavenVersion.Qual;

import java.util.List;

/**
 * Classifies how far behind a dependency is: the distance between its current
 * version and the newest version its team allows, judged by where the two
 * {@link MavenVersion}s first differ.
 *
 * <ul>
 *   <li>{@code MAJOR} — the first numeric segment differs ({@code 1.9 -&gt; 2.0})</li>
 *   <li>{@code MINOR} — the second numeric segment differs ({@code 2.15 -&gt; 2.17})</li>
 *   <li>{@code PATCH} — a later numeric segment differs ({@code 2.17.1 -&gt; 2.17.2}),
 *       or the only difference is inside a build-style suffix ({@code 1.0-1 -&gt; 1.0-2})</li>
 *   <li>{@code QUALIFIER} — the release segments all match and the difference is in
 *       the qualifier portion ({@code 1.0-SNAPSHOT -&gt; 1.0},
 *       {@code 1.0-alpha-1 -&gt; 1.0-alpha-2})</li>
 * </ul>
 */
public final class VersionGap {

    public enum Severity {
        MAJOR, MINOR, PATCH, QUALIFIER
    }

    private VersionGap() {
    }

    /**
     * Classifies the gap from {@code current} to {@code ceiling}. Callers only
     * classify outdated entries, where {@code ceiling} is strictly greater; for
     * equal versions the result is {@code QUALIFIER} by fallback.
     */
    public static Severity classify(MavenVersion current, MavenVersion ceiling) {
        List<Item> from = current.items();
        List<Item> to = ceiling.items();
        int max = Math.max(from.size(), to.size());
        for (int i = 0; i < max; i++) {
            Item x = i < from.size() ? from.get(i) : null;
            Item y = i < to.size() ? to.get(i) : null;
            if (MavenVersion.compareItem(x, y) == 0) {
                continue;
            }
            // First differing segment decides the classification.
            if (x instanceof Qual || y instanceof Qual) {
                return Severity.QUALIFIER;
            }
            if (hasQualifierBefore(from, i) || hasQualifierBefore(to, i)) {
                // The numeric difference is inside the qualifier tail (alpha-1 -> alpha-2).
                return Severity.QUALIFIER;
            }
            if (isNumeric(x) && isNumeric(y)) {
                return switch (i) {
                    case 0 -> Severity.MAJOR;
                    case 1 -> Severity.MINOR;
                    default -> Severity.PATCH;
                };
            }
            // The difference lives inside a sub-list (a build-style suffix like -1 -> -2).
            return Severity.PATCH;
        }
        return Severity.QUALIFIER;
    }

    private static boolean hasQualifierBefore(List<Item> items, int index) {
        for (int i = 0; i < index && i < items.size(); i++) {
            if (items.get(i) instanceof Qual) {
                return true;
            }
        }
        return false;
    }

    /** A segment counts as numeric if it is a number token or missing (padding reads as zero). */
    private static boolean isNumeric(Item item) {
        return item == null || item instanceof Num;
    }
}
