package com.avishkar.dependencytracker.version;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A Maven version with Maven's own ordering semantics — the rules implemented by
 * {@code org.apache.maven.artifact.versioning.ComparableVersion}, reimplemented here
 * so the tracker never has to compare version strings lexicographically
 * (where {@code "2.9.0"} would wrongly sort after {@code "2.17.2"}).
 *
 * <p>The model: a version is a tree of tokens. Separators are {@code '.'} and
 * {@code '-'}, and a transition between digits and letters also splits tokens
 * ({@code 1.0alpha10} parses as {@code 1.0-alpha-10}). A hyphen followed by a digit
 * after a numeric token opens a nested sub-list, which is why Maven orders
 * {@code 1.0 < 1.0-1 < 1.0.1}. String tokens are qualifiers and compare by the
 * well-known qualifier ranking
 * {@code alpha < beta < milestone < rc < snapshot < (release) < sp},
 * with aliases {@code ga}/{@code final}/{@code release} meaning the release itself
 * and {@code cr} meaning {@code rc}. A one-letter qualifier immediately followed by
 * a digit expands: {@code a1} is {@code alpha-1}, {@code b1} is {@code beta-1},
 * {@code m1} is {@code milestone-1}. Numeric tokens compare as integers of
 * arbitrary size and ignore leading zeros, so {@code 1.01} equals {@code 1.1} and
 * {@code 1.0} equals {@code 1.0.0} equals {@code 1}.
 */
public final class MavenVersion implements Comparable<MavenVersion> {

    /** Qualifier ranking, lowest first. The empty string is the plain release. */
    private static final List<String> QUALIFIERS =
            List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");

    /** Qualifier aliases normalized at parse time. */
    private static final Map<String, String> ALIASES = Map.of(
            "ga", "",
            "final", "",
            "release", "",
            "cr", "rc");

    /** One-letter shorthands, expanded only when a digit follows immediately. */
    private static final Map<String, String> SHORT_ALIASES = Map.of(
            "a", "alpha",
            "b", "beta",
            "m", "milestone");

    /** One token of a parsed version. Package-private so {@link VersionGap} can inspect the tree. */
    sealed interface Item permits Num, Qual, Sub {
    }

    /** A numeric token, compared as an arbitrary-precision integer. */
    record Num(BigInteger value) implements Item {
    }

    /** A string (qualifier) token, already alias-normalized. */
    record Qual(String value) implements Item {
    }

    /** A nested token list, opened by a hyphen-digit sequence after a numeric token. */
    record Sub(List<Item> items) implements Item {
    }

    private final String original;
    private final List<Item> items;

    private MavenVersion(String original, List<Item> items) {
        this.original = original;
        this.items = items;
    }

    /** Parses a version string. Any non-null string is a syntactically acceptable Maven version. */
    public static MavenVersion parse(String version) {
        Objects.requireNonNull(version, "version must not be null");
        String normalized = version.trim().toLowerCase(Locale.ROOT);
        List<Item> parsed = new Tokenizer(normalized).tokenize();
        return new MavenVersion(version.trim(), normalize(parsed));
    }

    /**
     * A version string usable as a policy ceiling: dot/hyphen-separated alphanumeric
     * tokens, e.g. {@code 2.17.2} or {@code 1.0-SNAPSHOT}. Version ranges and blank
     * strings are not plain versions.
     */
    public static boolean isValidVersionString(String candidate) {
        return candidate != null
                && candidate.matches("[A-Za-z0-9]+([._\\-+][A-Za-z0-9]+)*");
    }

    /** True if any qualifier token in this version is {@code snapshot}. */
    public boolean isSnapshot() {
        return containsSnapshot(items);
    }

    private static boolean containsSnapshot(List<Item> items) {
        for (Item item : items) {
            if (item instanceof Qual qual && qual.value().equals("snapshot")) {
                return true;
            }
            if (item instanceof Sub sub && containsSnapshot(sub.items())) {
                return true;
            }
        }
        return false;
    }

    /** The token tree, for {@link VersionGap}. */
    List<Item> items() {
        return items;
    }

    @Override
    public int compareTo(MavenVersion other) {
        return compareItems(this.items, other.items);
    }

    static int compareItems(List<Item> left, List<Item> right) {
        int max = Math.max(left.size(), right.size());
        for (int i = 0; i < max; i++) {
            Item x = i < left.size() ? left.get(i) : null;
            Item y = i < right.size() ? right.get(i) : null;
            int result = compareItem(x, y);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    static int compareItem(Item x, Item y) {
        if (x == null && y == null) {
            return 0;
        }
        if (x == null) {
            return -compareItem(y, null);
        }
        if (y == null) {
            // A missing token pads as numeric zero / the release qualifier / an empty list.
            if (x instanceof Num num) {
                return num.value().signum();
            }
            if (x instanceof Qual qual) {
                return comparableQualifier(qual.value()).compareTo(comparableQualifier(""));
            }
            Sub sub = (Sub) x;
            return compareItems(sub.items(), List.of());
        }
        if (x instanceof Num numX && y instanceof Num numY) {
            return numX.value().compareTo(numY.value());
        }
        if (x instanceof Num) {
            return 1; // a number outranks any qualifier or sub-list at the same position
        }
        if (y instanceof Num) {
            return -1;
        }
        if (x instanceof Qual qualX && y instanceof Qual qualY) {
            return comparableQualifier(qualX.value()).compareTo(comparableQualifier(qualY.value()));
        }
        if (x instanceof Qual) {
            return -1; // a qualifier sorts before a build-style sub-list (1.0-alpha < 1.0-1)
        }
        if (y instanceof Qual) {
            return 1;
        }
        return compareItems(((Sub) x).items(), ((Sub) y).items());
    }

    /**
     * Maps a qualifier to its sort key: its rank index for known qualifiers, or
     * {@code "<rank count>-<qualifier>"} for unknown ones, so unknown qualifiers sort
     * after every known qualifier and alphabetically among themselves.
     */
    static String comparableQualifier(String qualifier) {
        int index = QUALIFIERS.indexOf(qualifier);
        return index == -1 ? QUALIFIERS.size() + "-" + qualifier : Integer.toString(index);
    }

    /** Removes trailing tokens that carry no information (0, the release qualifier, empty sub-lists). */
    private static List<Item> normalize(List<Item> parsed) {
        List<Item> copy = new ArrayList<>(parsed.size());
        for (Item item : parsed) {
            if (item instanceof Sub sub) {
                copy.add(new Sub(normalize(sub.items())));
            } else {
                copy.add(item);
            }
        }
        for (int i = copy.size() - 1; i >= 0; i--) {
            if (isNullEquivalent(copy.get(i))) {
                copy.remove(i);
            } else {
                break;
            }
        }
        return List.copyOf(copy);
    }

    private static boolean isNullEquivalent(Item item) {
        if (item instanceof Num num) {
            return num.value().signum() == 0;
        }
        if (item instanceof Qual qual) {
            return qual.value().isEmpty();
        }
        return ((Sub) item).items().isEmpty();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MavenVersion other && items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return original;
    }

    /**
     * Splits a lower-cased version string into the token tree. Besides '.' and '-',
     * digit/letter transitions are token boundaries. A '-' followed by a digit opens
     * a nested sub-list, but only when the token before it was numeric — that is what
     * makes {@code 1.0-1} a build-style suffix of {@code 1.0} while {@code 1.0-alpha-1}
     * stays a flat qualifier sequence equal to {@code 1.0-alpha1} and {@code 1.0-alpha.1}.
     */
    private static final class Tokenizer {

        private final String text;
        private final List<Item> root = new ArrayList<>();
        private final Deque<List<Item>> stack = new ArrayDeque<>();
        private List<Item> current = root;
        private Item lastAdded;

        Tokenizer(String text) {
            this.text = text;
        }

        List<Item> tokenize() {
            int length = text.length();
            boolean expectToken = true;
            int i = 0;
            while (i < length) {
                char c = text.charAt(i);
                if (c == '.' || c == '-') {
                    if (expectToken) {
                        add(new Num(BigInteger.ZERO));
                    }
                    if (c == '-' && i + 1 < length
                            && isDigit(text.charAt(i + 1))
                            && lastAdded instanceof Num) {
                        descend();
                    }
                    expectToken = true;
                    i++;
                    continue;
                }
                boolean digit = isDigit(c);
                int j = i;
                while (j < length) {
                    char d = text.charAt(j);
                    if (d == '.' || d == '-' || isDigit(d) != digit) {
                        break;
                    }
                    j++;
                }
                String token = text.substring(i, j);
                boolean followedByDigit = j < length && isDigit(text.charAt(j));
                add(digit ? new Num(new BigInteger(token)) : qualifier(token, followedByDigit));
                expectToken = false;
                i = j;
            }
            return root;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void add(Item item) {
            current.add(item);
            lastAdded = item;
        }

        private void descend() {
            Sub sub = new Sub(new ArrayList<>());
            add(sub);
            stack.push(current);
            current = sub.items();
        }

        private static Item qualifier(String token, boolean followedByDigit) {
            String value = token;
            if (followedByDigit && value.length() == 1) {
                value = SHORT_ALIASES.getOrDefault(value, value);
            }
            value = ALIASES.getOrDefault(value, value);
            return new Qual(value);
        }
    }
}
