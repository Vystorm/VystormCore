package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

import java.util.ArrayList;

public class MessageSplitter {


public String[] splitMessage(String message) {
     DebugMessages.low("[MessageSplitter] Splitting message into chat segments");
     ArrayList<String> messageParts = new ArrayList<>();
     if (message.contains("\n") || message.contains("\\n") || message.contains("/n")) {
	  String[] lines = message.split("\n|\\\\n|/n");
	  return lines;
     }
     if (message.length() > 200) {
	  int breakPoints = message.length() / 200;
	  for (int i = 1; i <= breakPoints + 1; i++) {
	       int breakIndex = i * 200;
	       int startIndex = (i - 1) * 200;
	       if (breakIndex < message.length()) {
		    messageParts.add(message.substring(startIndex, breakIndex));
	       } else {
		    messageParts.add(message.substring(startIndex));
		    break;
	       }
	  }
     } else {
	  messageParts.add(message);
     }
     return messageParts.toArray(new String[0]);}
}
