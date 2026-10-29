package info.openrocket.core.automation.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.automation.AutomationSession;
import info.openrocket.core.automation.Command;

/** Runs commands in this process. */
public class LocalBackend implements Backend {
	private final AutomationSession session;

	public LocalBackend(AutomationSession session) {
		this.session = session;
	}

	public AutomationSession getSession() {
		return session;
	}

	@Override
	public JsonArray listCommands() {
		JsonArray a = new JsonArray();
		for (Command c : session.getRegistry().list()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", c.getName());
			o.addProperty("description", c.getDescription());
			o.add("inputSchema", c.inputSchema());
			o.addProperty("mutating", c.isMutating());
			o.addProperty("readOnly", c.isReadOnly());
			a.add(o);
		}
		return a;
	}

	@Override
	public JsonElement call(String command, JsonObject params) throws Exception {
		return session.call(command, params);
	}
}
