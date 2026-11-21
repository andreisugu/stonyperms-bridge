package dev.stonyperms.bridge.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayFormatTest {

    @Test
    void formatsPrimaryGroupDisplayAndChatWithoutParsingMessagePlaceholder() {
        UserInfo vip = new UserInfo("uuid-1", "Andrei", "vip", false, Map.of(), "&a[VIP] ", " &e*");

        assertEquals("&a[VIP] Andrei &e*", DisplayFormat.formatDisplayName(vip, "Andrei"));

        DisplayFormat.ChatParts parts = DisplayFormat.formatChatParts(
                "{prefix}{name}{suffix}&r&7: &f{message}", vip, "Andrei");
        assertEquals("&a[VIP] Andrei &e*&r&7: &f", parts.beforeMessage());
        assertEquals("", parts.afterMessage());
    }

    @Test
    void translatesLegacyCodesAndExtractsTrailingActiveColor() {
        assertEquals("\u00a74[Owner] \u00a7lAndrei & notacode",
                DisplayFormat.translateAlternateColorCodes("&4[Owner] &LAndrei & notacode"));
        assertEquals('4', DisplayFormat.trailingColorCode("&4&l[Owner] "));
        assertEquals('\0', DisplayFormat.trailingColorCode("&4[Owner] &r"));
        assertEquals('7', DisplayFormat.trailingColorCode("&7"));
    }
}
