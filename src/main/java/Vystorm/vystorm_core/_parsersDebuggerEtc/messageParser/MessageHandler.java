package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;


public class MessageHandler implements  MessageInterface {
	FileConfiguration messagesConfig;
	LanguageSystem languageSystem = new LanguageSystem();
	PlaceholderReplacer placeholderReplacer = new PlaceholderReplacer();
	ColorCorrector colorCorrector = new ColorCorrector();
	MessageSplitter messageSplitter = new MessageSplitter();

@Override
@SuppressWarnings("deprecation")
public void sendMessage(Player player, String messageKey) {
     DebugMessages.low("[MessageHandler] Preparing message; key=" + messageKey);
     String message = languageSystem.handleMessage(messageKey, player);
     if (message == null) {
	  DebugMessages.high("[MessageHandler] MessageHandler: sendMessage: No message found for key: " + messageKey);
	  return;
     }
     message = placeholderReplacer.replacePlaceholders(message, player);
     message = colorCorrector.color(message);
     String[] splitMessages = messageSplitter.splitMessage(message);
     if (splitMessages == null || splitMessages.length == 0) {
	  DebugMessages.high("[MessageHandler] MessageHandler: sendMessage: No message found for key: " + messageKey);
	  return;
     }
     for (String msg : splitMessages) {
	  player.sendMessage(msg);
     }

     DebugMessages.low("[MessageHandler] Message sent; key=" + messageKey + "; parts=" + splitMessages.length);
}

@Override
@SuppressWarnings("deprecation")
public void sendMessage(Player player, String messageKey, String AdditionalInformation) {
	sendMessage(player ,messageKey);
	player.sendMessage(AdditionalInformation);
}

/**
 * @param entity
 */
@Override
@SuppressWarnings("deprecation")
public void addPlaceholder(ReplacerEntity entity) {
     placeholderReplacer.addPlaceholder(entity);


}

/**
 * @param placeholder
 */
@Override
@SuppressWarnings("deprecation")
public void removePlaceholder(ReplacerEntity placeholder) {
     placeholderReplacer.removePlaceholder(placeholder);
}
}
