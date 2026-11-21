package dev.stonyperms.bridge.fabric;

import dev.stonyperms.bridge.core.DisplayFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.scores.TeamColor;

import java.util.Optional;

/** Converts legacy '&' / '§' formatted StonyPerms affixes into Minecraft Components and TeamColors. */
public final class FabricLegacyText {

    private FabricLegacyText() {
    }

    public static MutableComponent parse(String legacy) {
        MutableComponent root = Component.empty();
        if (legacy == null || legacy.isEmpty()) {
            return root;
        }

        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            char ch = legacy.charAt(i);
            if ((ch == '&' || ch == '\u00a7') && i + 1 < legacy.length()) {
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(legacy.charAt(i + 1)));
                if (fmt != null) {
                    if (!run.isEmpty()) {
                        root.append(Component.literal(run.toString()).setStyle(style));
                        run.setLength(0);
                    }
                    style = applyFormat(style, fmt);
                    i++;
                    continue;
                }
            }
            run.append(ch);
        }
        if (!run.isEmpty()) {
            root.append(Component.literal(run.toString()).setStyle(style));
        }
        return root;
    }

    public static Style trailingStyle(String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return Style.EMPTY;
        }
        Style style = Style.EMPTY;
        for (int i = 0; i < legacy.length() - 1; i++) {
            char ch = legacy.charAt(i);
            if (ch == '&' || ch == '\u00a7') {
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(legacy.charAt(i + 1)));
                if (fmt != null) {
                    style = applyFormat(style, fmt);
                    i++;
                }
            }
        }
        return style;
    }

    public static Optional<TeamColor> trailingTeamColor(String legacy) {
        char code = DisplayFormat.trailingColorCode(legacy);
        return Optional.ofNullable(switch (code) {
            case '0' -> TeamColor.BLACK;
            case '1' -> TeamColor.DARK_BLUE;
            case '2' -> TeamColor.DARK_GREEN;
            case '3' -> TeamColor.DARK_AQUA;
            case '4' -> TeamColor.DARK_RED;
            case '5' -> TeamColor.DARK_PURPLE;
            case '6' -> TeamColor.GOLD;
            case '7' -> TeamColor.GRAY;
            case '8' -> TeamColor.DARK_GRAY;
            case '9' -> TeamColor.BLUE;
            case 'a' -> TeamColor.GREEN;
            case 'b' -> TeamColor.AQUA;
            case 'c' -> TeamColor.RED;
            case 'd' -> TeamColor.LIGHT_PURPLE;
            case 'e' -> TeamColor.YELLOW;
            case 'f' -> TeamColor.WHITE;
            default -> null;
        });
    }

    private static Style applyFormat(Style style, ChatFormatting fmt) {
        if (fmt == ChatFormatting.RESET) {
            return Style.EMPTY;
        }
        return style.applyLegacyFormat(fmt);
    }
}
