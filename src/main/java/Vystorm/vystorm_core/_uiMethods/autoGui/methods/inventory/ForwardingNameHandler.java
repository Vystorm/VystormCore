package Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory;

public class ForwardingNameHandler {
	// Main Menu  []
	public String up(String name) {
		String temp;
		int number;
		if(name.contains("[") && !name.contains("[0]")){
			number = Integer.parseInt(name.substring(name.indexOf("[") + 1, name.indexOf("]")));
			name = name.replace("[" + number + "]", "[" + (++number) + "]");
		} else {
			    if (name.contains("[0]")) {
					  name = name.replace("[0]", "[1]");
			    } else {
					  name = name + " [1]";
			    }
		}
		return name;
	}

	public String down(String name) {
		    String temp;
		    int number;
		    if(name.contains("[") && !name.contains("[0]")){
				  number = Integer.parseInt(name.substring(name.indexOf("[") - 1, name.indexOf("]")));
				  if(number <= 1){
						name = name.replace("[" + number + "]" ,"[" + --number + "]");
				  }

		    }

		return name;
	}
}
