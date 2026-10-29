package info.openrocket.core.automation.commands;

import info.openrocket.core.automation.CommandRegistry;

/** Registers every headless automation command. */
public final class StandardCommands {
	private StandardCommands() {
	}

	public static CommandRegistry registry() {
		CommandRegistry r = new CommandRegistry();
		r.registerAll(HelpCommands.all());
		r.registerAll(DocumentCommands.all());
		r.registerAll(ComponentCommands.all());
		r.registerAll(ConfigCommands.all());
		r.registerAll(SimulationCommands.all());
		r.registerAll(AnalysisCommands.all());
		r.registerAll(DatabaseCommands.all());
		r.registerAll(ExportCommands.all());
		r.registerAll(PreferenceCommands.all());
		r.registerAll(ObjectCommands.all());
		r.registerAll(ScriptCommands.all());
		return r;
	}
}
