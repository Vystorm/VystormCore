package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser;

import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods.ReplacerEntity;
import org.bukkit.entity.Player;

public interface MessageInterface {
     /** @deprecated Use {@code api.lang().register(plugin).send(viewer, key, placeholders)}. */
     @Deprecated(since = "0.64.0")
     public void sendMessage(Player player, String messageKey);
     /** @deprecated Use {@code api.lang().register(plugin).send(viewer, key, placeholders)}. */
     @Deprecated(since = "0.64.0")
     public void sendMessage(Player player, String messageKey, String AdditionalInformation);
     /** @deprecated Use {@code api.lang().register(plugin).send(viewer, key, placeholders)}. */
     @Deprecated(since = "0.64.0")
     public void addPlaceholder(ReplacerEntity entity);
     /** @deprecated Use {@code api.lang().register(plugin).send(viewer, key, placeholders)}. */
     @Deprecated(since = "0.64.0")
     public void removePlaceholder(ReplacerEntity entity);

    /** Clickable chat components: buttons, commands, locations ({@code _uiMethods.chat.ChatActions}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.chat.ChatActionsInterface chat() { return Vystorm.vystorm_core._uiMethods.chat.ChatActionsInterface.DEFAULT; }

    /** Tagged chat channels ({@code chat.ChatChannels}). @since 0.64.0 */
    default Vystorm.vystorm_core.chat.ChatChannelsInterface channels() { return Vystorm.vystorm_core.chat.ChatChannelsInterface.DEFAULT; }

    /** Personal messages for online and offline players ({@code inbox.Inbox}). @since 0.64.0 */
    default Vystorm.vystorm_core.inbox.InboxInterface inbox() { return Vystorm.vystorm_core.inbox.InboxInterface.DEFAULT; }

    /** The server news board ({@code news.News}). @since 0.64.0 */
    default Vystorm.vystorm_core.news.NewsInterface news() { return Vystorm.vystorm_core.news.NewsInterface.DEFAULT; }

    /** Routine notifications a player can switch off ({@code _uiMethods.notifications.RoutineMessages}). @since 0.64.0 */
    default Vystorm.vystorm_core._uiMethods.notifications.RoutineMessagesInterface routine() { return Vystorm.vystorm_core._uiMethods.notifications.RoutineMessagesInterface.DEFAULT; }
}
