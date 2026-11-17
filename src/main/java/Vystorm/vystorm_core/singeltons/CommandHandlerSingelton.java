package Vystorm.vystorm_core.singeltons;

import Vystorm.vystorm_core._uiMethods.commands.CommandInterface;
import Vystorm.vystorm_core._uiMethods.commands.DefaultCommandHandler;

public class CommandHandlerSingelton {

	    private CommandInterface instance;

	    public CommandHandlerSingelton() {
			  // Private constructor to prevent instantiation
	    }

	    public CommandInterface getInstance() {
			  if (instance == null) {
					instance = new DefaultCommandHandler() {
					};
			  }
			  return instance;
	    }
	    /**
	     * Override the current command handler with a new one.
	     *
	     * @param newCommandHandler The new command handler to set.
	     */
	    public void override(CommandInterface newCommandHandler) {
			  this.instance = newCommandHandler;
	    }
	    public void reset() {
			  this.instance = new DefaultCommandHandler() {
			  };
	    }

}

