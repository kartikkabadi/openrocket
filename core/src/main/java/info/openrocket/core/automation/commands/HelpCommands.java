package info.openrocket.core.automation.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationException;
import info.openrocket.core.automation.Command;
import info.openrocket.core.automation.J;
import info.openrocket.core.util.BuildProperties;

/** Discovery of the available commands. */
public final class HelpCommands {
	private HelpCommands() {
	}

	public static List<Command> all() {
		List<Command> l = new ArrayList<>();
		l.add(Command.builder("help").readOnly()
				.description("List all commands by group, or describe one command and its parameters.")
				.param("command", "string", "Command to describe")
				.anyThread()
				.handler((p, s) -> {
					if (p.has("command")) {
						Command c = s.getRegistry().get(p.string("command"));
						if (c == null) {
							throw AutomationException.notFound("No command " + p.string("command"));
						}
						return J.obj("name", c.getName(), "description", c.getDescription(), "params", c.inputSchema(),
								"mutating", c.isMutating());
					}
					Map<String, JsonArray> groups = new TreeMap<>();
					for (Command c : s.getRegistry().list()) {
						groups.computeIfAbsent(c.getGroup(), k -> new JsonArray())
								.add(J.obj("name", c.getName(), "description", c.getDescription()));
					}
					JsonObject o = new JsonObject();
					o.addProperty("openrocket", BuildProperties.getVersion());
					o.addProperty("gui", s.getHost().isGui());
					JsonObject g = new JsonObject();
					groups.forEach(g::add);
					o.add("commands", g);
					return o;
				}).build());
		return l;
	}
}
