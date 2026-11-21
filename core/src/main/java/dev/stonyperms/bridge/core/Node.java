package dev.stonyperms.bridge.core;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One resolved or stored permission node. Source ordering is carried by the snapshot:
 * user nodes come first, then groups in descending weight order.
 */
public record Node(
        String key,
        boolean value,
        Map<String, String> contexts,
        long expiry,
        String source,
        int weight,
        boolean user) {

    public static final int MAX_SHORTHAND_EXPANSIONS = 128;
    public static final int MAX_COMPILED_REGEX_CACHE = 1024;

    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger REGEX_CACHE_COUNT = new AtomicInteger();

    public record MatchResult(boolean matched, boolean exact, int specificityScore) {
    }

    public Node(String key, boolean value, long expiry, Map<String, String> contexts) {
        this(key, value, contexts, expiry, "unknown", 0, false);
    }

    public Node {
        if (key == null) {
            key = "";
        } else {
            String trimmed = key.trim();
            if (trimmed.regionMatches(true, 0, "r=", 0, 2)) {
                key = "r=" + trimmed.substring(2).trim();
            } else {
                key = trimmed.toLowerCase(Locale.ROOT);
            }
        }
        contexts = contexts == null || contexts.isEmpty() ? Map.of() : Map.copyOf(contexts);
        source = source == null ? "unknown" : source;
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiry > 0 && nowEpochSeconds >= expiry;
    }

    /** Mirrors model.Node.MatchesContext in gate-stonyperms. */
    public boolean matchesContext(Map<String, String> query) {
        return MetaEntry.matchesContextMap(contexts, query);
    }

    private static Pattern compileNodeRegex(String pat) {
        Pattern cached = REGEX_CACHE.get(pat);
        if (cached != null) {
            return cached;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile("(?i)^(?:" + pat + ")$");
        } catch (PatternSyntaxException ignored) {
            return null;
        }
        if (REGEX_CACHE_COUNT.incrementAndGet() <= MAX_COMPILED_REGEX_CACHE) {
            Pattern prev = REGEX_CACHE.putIfAbsent(pat, compiled);
            if (prev != null) {
                return prev;
            }
        }
        return compiled;
    }

    /** Mirrors model.ExpandShorthand in gate-stonyperms. */
    public static List<String> expandShorthand(String pattern) {
        if (pattern == null) {
            return List.of();
        }
        String trimmed = pattern.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("r=")) {
            return List.of(trimmed);
        }
        List<String> cur = List.of(lower);
        for (int step = 0; step < 8; step++) {
            boolean changed = false;
            List<String> next = new ArrayList<>();
            for (String item : cur) {
                int[] group = findFirstShorthandGroup(item);
                if (group == null) {
                    next.add(item);
                    continue;
                }
                List<String> variants = expandShorthandSegment(item.substring(group[0] + 1, group[1]));
                if (variants.isEmpty()) {
                    next.add(item);
                    continue;
                }
                changed = true;
                String prefix = item.substring(0, group[0]);
                String suffix = item.substring(group[1] + 1);
                for (String v : variants) {
                    next.add(prefix + v + suffix);
                    if (next.size() >= MAX_SHORTHAND_EXPANSIONS) {
                        break;
                    }
                }
                if (next.size() >= MAX_SHORTHAND_EXPANSIONS) {
                    break;
                }
            }
            cur = next;
            if (!changed || cur.size() >= MAX_SHORTHAND_EXPANSIONS) {
                break;
            }
        }
        return cur;
    }

    private static int[] findFirstShorthandGroup(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char want;
            if (c == '{') {
                want = '}';
            } else if (c == '(') {
                want = ')';
            } else {
                continue;
            }
            for (int j = i + 1; j < s.length(); j++) {
                if (s.charAt(j) == want) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    private static List<String> expandShorthandSegment(String inner) {
        char sep = ',';
        if (inner.indexOf('|') >= 0) {
            sep = '|';
        } else if (inner.indexOf(',') < 0) {
            int dash = inner.indexOf('-');
            if (dash > 0) {
                try {
                    int a = Integer.parseInt(inner.substring(0, dash).trim());
                    int b = Integer.parseInt(inner.substring(dash + 1).trim());
                    if (a >= 0 && b >= 0) {
                        int step = b >= a ? 1 : -1;
                        List<String> nums = new ArrayList<>();
                        for (int v = a; ; v += step) {
                            nums.add(Integer.toString(v));
                            if (v == b || nums.size() >= MAX_SHORTHAND_EXPANSIONS) {
                                break;
                            }
                        }
                        return nums;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        List<String> rawParts = splitChar(inner, sep);
        if (rawParts.size() <= 1) {
            return List.of();
        }
        List<String> out = new ArrayList<>(rawParts.size());
        for (String part : rawParts) {
            out.add(part.trim());
        }
        return out;
    }

    private static List<String> splitChar(String s, char sep) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Evaluates match kind and specificity score against a normalized lowercase permission target. */
    public MatchResult evaluateMatch(String targetPerm) {
        if (targetPerm == null || key.isEmpty()) {
            return new MatchResult(false, false, 0);
        }
        String t = targetPerm.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) {
            return new MatchResult(false, false, 0);
        }
        if (key.regionMatches(true, 0, "r=", 0, 2)) {
            String pat = key.substring(2).trim();
            if (pat.isEmpty()) {
                return new MatchResult(false, false, 0);
            }
            Pattern compiled = compileNodeRegex(pat);
            if (compiled != null && compiled.matcher(t).matches()) {
                return new MatchResult(true, false, pat.length());
            }
            return new MatchResult(false, false, 0);
        }
        String lk = key.toLowerCase(Locale.ROOT);
        if (lk.indexOf('{') < 0 && lk.indexOf('(') < 0) {
            if (lk.equals(t)) {
                return new MatchResult(true, true, t.length() + 1);
            }
            if (lk.equals("*")) {
                return new MatchResult(true, false, 1);
            }
            if (lk.endsWith(".*")) {
                String prefix = lk.substring(0, lk.length() - 1);
                if (t.startsWith(prefix)) {
                    return new MatchResult(true, false, prefix.length());
                }
            }
            return new MatchResult(false, false, 0);
        }

        boolean matched = false;
        int bestScore = 0;
        for (String cand : expandShorthand(lk)) {
            if (cand.equals(t)) {
                return new MatchResult(true, true, t.length());
            }
            if (cand.equals("*") && 1 > bestScore) {
                matched = true;
                bestScore = 1;
            } else if (cand.endsWith(".*")) {
                String prefix = cand.substring(0, cand.length() - 1);
                if (t.startsWith(prefix) && prefix.length() > bestScore) {
                    matched = true;
                    bestScore = prefix.length();
                }
            }
        }
        return new MatchResult(matched, false, bestScore);
    }

    /** Mirrors model.Node.MatchesPermission (exact, wildcards, shorthand, and r= regex). */
    public boolean matchesPermission(String permission) {
        return evaluateMatch(permission).matched();
    }

    public boolean isWildcard() {
        String lk = key.toLowerCase(Locale.ROOT);
        return lk.equals("*") || lk.endsWith(".*");
    }

    public boolean isPattern() {
        String lk = key.toLowerCase(Locale.ROOT);
        return isWildcard() || lk.startsWith("r=") || lk.indexOf('{') >= 0 || lk.indexOf('(') >= 0;
    }

    public boolean active(long nowEpochSeconds, Map<String, String> query) {
        return !isExpired(nowEpochSeconds) && matchesContext(query);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Node node)) {
            return false;
        }
        return value == node.value
                && expiry == node.expiry
                && weight == node.weight
                && user == node.user
                && key.equals(node.key)
                && contexts.equals(node.contexts)
                && source.equals(node.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value, contexts, expiry, source, weight, user);
    }
}
