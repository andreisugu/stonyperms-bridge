package dev.stonyperms.bridge.core;

/** Shared chat and display-name formatting helpers for StonyPerms bridges. */
public final class DisplayFormat {

    public static final String DEFAULT_CHAT_FORMAT = "{prefix}{name}{suffix}&r&7: &f{message}";
    private static final String COLOR_CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private DisplayFormat() {
    }

    public record ChatParts(String beforeMessage, String afterMessage) {
    }

    public static String normalizeChatFormat(String format) {
        return format == null || format.isBlank() ? DEFAULT_CHAT_FORMAT : format;
    }

    public static String formatDisplayName(UserInfo user, String fallbackName) {
        String name = resolveName(user, fallbackName);
        String prefix = user == null ? "" : user.prefix();
        String suffix = user == null ? "" : user.suffix();
        return prefix + name + suffix;
    }

    public static ChatParts formatChatParts(String template, UserInfo user, String fallbackName) {
        String effective = normalizeChatFormat(template);
        String name = resolveName(user, fallbackName);
        String prefix = user == null ? "" : user.prefix();
        String suffix = user == null ? "" : user.suffix();
        String primaryGroup = user == null || user.primaryGroup().isEmpty() ? "default" : user.primaryGroup();

        String substituted = effective
                .replace("{prefix}", prefix)
                .replace("{suffix}", suffix)
                .replace("{name}", name)
                .replace("{player}", name)
                .replace("{username}", name)
                .replace("{displayname}", prefix + name + suffix)
                .replace("{group}", primaryGroup);

        int idx = substituted.indexOf("{message}");
        if (idx < 0) {
            return new ChatParts(substituted + " ", "");
        }
        return new ChatParts(substituted.substring(0, idx), substituted.substring(idx + "{message}".length()));
    }

    /** Translates '&' legacy color/formatting codes to section signs ('§') when followed by a valid code. */
    public static String translateAlternateColorCodes(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && COLOR_CODES.indexOf(chars[i + 1]) >= 0) {
                chars[i] = '\u00a7';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    /**
     * Returns the last active legacy color character ('0'-'9', 'a'-'f') in {@code input},
     * or {@code '\0'} if no color is active (including after a reset {@code &r}).
     */
    public static char trailingColorCode(String input) {
        if (input == null || input.isEmpty()) {
            return '\0';
        }
        char activeColor = '\0';
        for (int i = 0; i < input.length() - 1; i++) {
            char marker = input.charAt(i);
            if (marker != '&' && marker != '\u00a7') {
                continue;
            }
            char code = Character.toLowerCase(input.charAt(i + 1));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                activeColor = code;
                i++;
            } else if (code == 'r') {
                activeColor = '\0';
                i++;
            } else if (code >= 'k' && code <= 'o') {
                i++;
            }
        }
        return activeColor;
    }

    private static String resolveName(UserInfo user, String fallbackName) {
        if (fallbackName != null && !fallbackName.isEmpty()) {
            return fallbackName;
        }
        return user == null ? "" : user.username();
    }
}
