package dev.stonyperms.bridge.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats human duration strings (e.g. 30d, 1h30m, permanent). */
public final class DurationParser {

    public static final long MAX_DURATION_SECONDS = 315_360_000L; // 10 years (Gate max)
    private static final Pattern TOKEN = Pattern.compile("(\\d+)(mo|[smhdwy])");

    private DurationParser() {
    }

    public static long parseDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.equals("permanent") || normalized.equals("perm") || normalized.equals("0")) {
            return 0L;
        }

        Matcher matcher = TOKEN.matcher(normalized);
        int index = 0;
        long total = 0L;
        while (matcher.find()) {
            if (matcher.start() != index) {
                throw new IllegalArgumentException("Invalid duration: " + raw);
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Duration too large: " + raw, error);
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("Duration components must be positive: " + raw);
            }
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                case "w" -> 604_800L;
                case "mo" -> 2_592_000L;
                case "y" -> 31_536_000L;
                default -> throw new IllegalArgumentException("Unsupported duration unit: " + matcher.group(2));
            };
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, multiplier));
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException("Duration exceeds maximum allowed range", error);
            }
            index = matcher.end();
        }
        if (index != normalized.length() || total <= 0) {
            throw new IllegalArgumentException("Invalid duration (expected e.g. 30d, 1h30m, or 'permanent'): " + raw);
        }
        if (total > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("Duration exceeds 10 years maximum (" + MAX_DURATION_SECONDS + "s)");
        }
        return total;
    }

    public static boolean looksLikeDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            parseDurationSeconds(raw);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "permanent";
        }
        long rem = seconds;
        StringBuilder out = new StringBuilder();
        long years = rem / 31_536_000L;
        if (years > 0) {
            out.append(years).append("y");
            rem %= 31_536_000L;
        }
        long days = rem / 86_400L;
        if (days > 0) {
            out.append(days).append("d");
            rem %= 86_400L;
        }
        long hours = rem / 3_600L;
        if (hours > 0) {
            out.append(hours).append("h");
            rem %= 3_600L;
        }
        long minutes = rem / 60L;
        if (minutes > 0) {
            out.append(minutes).append("m");
            rem %= 60L;
        }
        if (rem > 0) {
            out.append(rem).append("s");
        }
        return out.toString();
    }
}
