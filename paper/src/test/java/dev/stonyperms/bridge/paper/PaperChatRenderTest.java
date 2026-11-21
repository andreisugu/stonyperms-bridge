package dev.stonyperms.bridge.paper;

import dev.stonyperms.bridge.core.UserInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperChatRenderTest {

    @Test
    void rendersPrefixNameAndPreservesPlainPlayerMessage() {
        UserInfo vip = new UserInfo("u1", "Andrei", "vip", false, Map.of(), "&a[VIP] ", "");
        Component message = Component.text("&4not-parsed");

        Component rendered = StonyPermsBridgePlugin.renderChatMessage(
                "{prefix}{name}{suffix}&r&7: &f{message}", vip, "Andrei", message);

        assertEquals("[VIP] Andrei: &4not-parsed", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(NamedTextColor.WHITE, rendered.children().get(1).color());
    }
}
