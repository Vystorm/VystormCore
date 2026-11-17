package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;

/** Wandelt {@code &}-Farbcodes ({@code &0-&9}, {@code &a-&f}, {@code &k-&o}, {@code &r}) in {@code §}-Codes um. */
public class ColorCorrector {
private static final String CODES = "0123456789abcdefklmnor";

public String color(String message) {
     if (message == null) {
          DebugMessages.low("[ColorCorrector] Color conversion skipped: message absent");
          return null;
     }
     char[] chars = message.toCharArray();
     for (int i = 0; i < chars.length - 1; i++) {
          if (chars[i] != '&') continue;
          char code = Character.toLowerCase(chars[i + 1]);
          if (CODES.indexOf(code) < 0) continue;
          chars[i] = '§';
          chars[i + 1] = code;
          i++;
     }
     return new String(chars);
}
}
