package Vystorm.vystorm_core.singeltons;
import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.MessageHandler;
import Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.MessageInterface;

public class MessageSingelton {

    // Singleton instance
    private MessageInterface instance;

    // Private constructor to prevent instantiation
    public MessageSingelton() {
    }

    // Method to get the singleton instance
    public  MessageInterface getInstance() {
	  if (instance == null) {
		instance = new MessageHandler();
	  }
	  return instance;
    }

    // Add methods to manage player data here
    public void override(MessageInterface newMessage) {
		  this.instance = newMessage;
    }
public void reset() {
	    this.instance = new MessageHandler() {
	    };
}
}


