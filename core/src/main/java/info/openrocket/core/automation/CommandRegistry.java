package info.openrocket.core.automation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The set of available automation commands.
 */
public class CommandRegistry {
	private final Map<String, Command> commands = new TreeMap<>();

	public void register(Command command) {
		commands.put(command.getName(), command);
	}

	public void registerAll(Collection<Command> list) {
		list.forEach(this::register);
	}

	public Command get(String name) {
		Command c = commands.get(name);
		if (c == null) {
			// Accept MCP-style names (dots replaced by underscores)
			for (Command cmd : commands.values()) {
				if (toToolName(cmd.getName()).equals(name)) {
					return cmd;
				}
			}
		}
		return c;
	}

	public List<Command> list() {
		return new ArrayList<>(commands.values());
	}

	public static String toToolName(String commandName) {
		return commandName.replace('.', '_');
	}

	/**
	 * Run a command by name.
	 */
	public JsonElement invoke(String name, JsonObject params, AutomationSession session) throws Exception {
		Command c = get(name);
		if (c == null) {
			throw new AutomationException("unknown_command", "Unknown command '" + name
					+ "'. Use 'help' to list the available commands.", null);
		}
		return session.execute(c, params == null ? new JsonObject() : params);
	}
}
