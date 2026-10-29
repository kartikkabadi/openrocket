package info.openrocket.core.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A single automation command: a name, a description, a parameter schema and a handler.
 * Every command is exposed identically through the CLI, the MCP server and the socket server.
 */
public final class Command {

	@FunctionalInterface
	public interface Handler {
		JsonElement run(Params params, AutomationSession session) throws Exception;
	}

	public record Param(String name, String type, String description, boolean required, List<String> choices) {
	}

	private final String name;
	private final String description;
	private final List<Param> params;
	private final Handler handler;
	private final boolean mutating;
	private final boolean edt;

	private Command(Builder b) {
		this.name = b.name;
		this.description = b.description;
		this.params = Collections.unmodifiableList(new ArrayList<>(b.params));
		this.handler = b.handler;
		this.mutating = b.mutating;
		this.edt = b.edt;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public List<Param> getParams() {
		return params;
	}

	public Handler getHandler() {
		return handler;
	}

	/** Whether the command changes a document (used for auto-save in one-shot CLI mode). */
	public boolean isMutating() {
		return mutating;
	}

	/** Whether the command must run on the GUI thread when a GUI is attached. */
	public boolean runsOnGuiThread() {
		return edt;
	}

	public String getGroup() {
		int i = name.indexOf('.');
		return i < 0 ? name : name.substring(0, i);
	}

	/** JSON schema of the parameters object. */
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		JsonArray required = new JsonArray();
		for (Param p : params) {
			JsonObject ps = new JsonObject();
			switch (p.type()) {
				case "quantity" -> {
					JsonArray t = new JsonArray();
					t.add("number");
					t.add("string");
					ps.add("type", t);
				}
				case "any" -> {
				}
				case "ref" -> {
					JsonArray t = new JsonArray();
					t.add("string");
					t.add("integer");
					ps.add("type", t);
				}
				default -> ps.addProperty("type", p.type());
			}
			ps.addProperty("description", p.description());
			if (p.choices() != null && !p.choices().isEmpty()) {
				JsonArray e = new JsonArray();
				p.choices().forEach(e::add);
				ps.add("enum", e);
			}
			props.add(p.name(), ps);
			if (p.required()) {
				required.add(p.name());
			}
		}
		schema.add("properties", props);
		if (!required.isEmpty()) {
			schema.add("required", required);
		}
		return schema;
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static final class Builder {
		private final String name;
		private String description = "";
		private final List<Param> params = new ArrayList<>();
		private Handler handler;
		private boolean mutating = false;
		private boolean edt = true;

		private Builder(String name) {
			this.name = name;
		}

		public Builder description(String d) {
			this.description = d;
			return this;
		}

		public Builder param(String name, String type, String description) {
			params.add(new Param(name, type, description, false, null));
			return this;
		}

		public Builder required(String name, String type, String description) {
			params.add(new Param(name, type, description, true, null));
			return this;
		}

		public Builder choice(String name, boolean required, String description, List<String> choices) {
			params.add(new Param(name, "string", description, required, choices));
			return this;
		}

		/** Adds the common optional "doc" parameter. */
		public Builder doc() {
			return param("doc", "string", "Document id (from doc.list) or path to a design file. "
					+ "Defaults to the active document.");
		}

		public Builder mutating() {
			this.mutating = true;
			return this;
		}

		/** The command may run off the GUI thread (it does its own synchronization). */
		public Builder anyThread() {
			this.edt = false;
			return this;
		}

		public Builder handler(Handler h) {
			this.handler = h;
			return this;
		}

		public Command build() {
			if (handler == null) {
				throw new IllegalStateException("No handler for " + name);
			}
			return new Command(this);
		}
	}
}
