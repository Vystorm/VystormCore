package Vystorm.vystorm_core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ChatChannelsTest {
    @Test
    void givenChannel_whenTagged_thenUsesLowercaseTranslationAndOriginalArgument() {
        Component original = Component.text("Hello");

        TranslatableComponent tagged = (TranslatableComponent) ChatChannels.tag(ChatChannels.Channel.GLOBAL, original);

        assertEquals("vystorm.chat.channel.global", tagged.key());
        assertEquals("%s", tagged.fallback());
        assertEquals(1, tagged.arguments().size());
        assertEquals(original, tagged.arguments().getFirst().value());
        assertEquals("party", ChatChannels.Channel.PARTY.wireKey());
        assertEquals("trade", ChatChannels.Channel.TRADE.wireKey());
        assertEquals("system", ChatChannels.Channel.SYSTEM.wireKey());
    }

    @Test
    void givenRichMessage_whenJsonRoundTripped_thenArgumentRetainsClickAndHover() {
        Component original = Component.text("Open offer")
                .clickEvent(ClickEvent.runCommand("/trade accept 17"))
                .hoverEvent(HoverEvent.showText(Component.text("Accept this trade")));
        var serializer = GsonComponentSerializer.gson();
        TranslatableComponent tagged = (TranslatableComponent) ChatChannels.tag(ChatChannels.Channel.TRADE, original);

        TranslatableComponent restored = (TranslatableComponent) serializer.deserialize(serializer.serialize(tagged));
        Component restoredArgument = (Component) restored.arguments().getFirst().value();
        assertEquals("vystorm.chat.channel.trade", restored.key());
        assertEquals("%s", restored.fallback());
        assertEquals(original, restoredArgument);
        assertEquals(ClickEvent.runCommand("/trade accept 17"), restoredArgument.clickEvent());
        assertEquals(HoverEvent.showText(Component.text("Accept this trade")), restoredArgument.hoverEvent());
    }

    @Test
    void givenPlayer_whenSent_thenUsesOrdinaryComponentMessage() {
        Player player = mock(Player.class);
        Component message = Component.text("Server notice");
        Component tagged = ChatChannels.tag(ChatChannels.Channel.SYSTEM, message);

        ChatChannels.send(player, ChatChannels.Channel.SYSTEM, message);

        verify(player).sendMessage(tagged);
    }

    @Test
    void givenTaggedMessage_whenPaperTranslates_thenRetainsChannelEnvelope() {
        var tagged = ChatChannels.tag(ChatChannels.Channel.PARTY, Component.text("Hello"));
        assertEquals(tagged, net.kyori.adventure.translation.GlobalTranslator.render(tagged, java.util.Locale.ENGLISH));
    }

    @Test
    void givenNullInput_whenTagged_thenRejectsIt() {
        assertThrows(NullPointerException.class, () -> ChatChannels.tag(null, Component.empty()));
        assertThrows(NullPointerException.class, () -> ChatChannels.tag(ChatChannels.Channel.GLOBAL, null));
    }
}
