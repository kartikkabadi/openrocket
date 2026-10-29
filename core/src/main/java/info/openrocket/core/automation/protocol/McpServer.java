package info.openrocket.core.automation.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.automation.CommandRegistry;
import info.openrocket.core.util.BuildProperties;

/**
 * Model Context Protocol server over stdio (newline-delimited JSON-RPC 2.0). Every automation
 * command is exposed as a tool; dots in command names become underscores.
 *
 * A result object containing {@code "_image": {"mimeType": ..., "data": <base64>}} is returned as an
 * image content block (used for screenshots and renders).
 */
public class McpServer {
	private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
	/** Commands that delete or overwrite work; hinted as destructive so clients can ask before running them. */
	private static final Set<String> DESTRUCTIVE = Set.of("doc.close", "doc.save", "component.remove", "config.remove",
			"sim.remove", "sim.remove_extension", "export.rocksim", "export.rasaero", "export.obj", "sim.export_csv",
			"pref.set", "units.set_preferred", "script.eval", "object.call", "gui.close_window");
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

	private static final String INSTRUCTIONS = """
			OpenRocket model-rocket design and flight-simulation software, fully controllable through these tools.
			Typical flow: doc_new / doc_open / doc_examples → component_tree → component_add / component_set →
			config_set_motor (find motors with motor_search) → analysis_stability → sim_create / sim_run →
			sim_data / sim_events → doc_save. All numbers are SI (m, kg, s, rad, N) unless you pass a string with
			a unit such as "30 cm", "2 in", "5 deg". Components are referenced by id, unique name, a name path
			"Stage/Body tube/Fins", "Type#n" (e.g. "FinSet#0"), or "rocket". component_get lists every property of
			a component with type and allowed values. Anything without a dedicated tool can be reached with
			object_get / object_set / object_call (any getter, setter or public method of any model object) or
			script_eval (JavaScript with the full Java API). Changes are undoable with doc_undo. When connected to
			the GUI, the gui_* tools operate windows, menus, dialogs and take screenshots.""";

	private final Backend backend;
	private final Writer out;
	private final BufferedReader in;
	private final Map<String, String> toolToCommand = new HashMap<>();

	public McpServer(Backend backend, InputStream in, OutputStream out) {
		this.backend = backend;
		this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		this.out = new OutputStreamWriter(out, StandardCharsets.UTF_8);
	}

	/** Serve until stdin is closed. */
	public void run() throws IOException {
		String line;
		while ((line = in.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			JsonElement msg;
			try {
				msg = JsonParser.parseString(line);
			} catch (RuntimeException e) {
				send(errorResponse(null, -32700, "Parse error"));
				continue;
			}
			if (msg.isJsonArray()) {
				JsonArray responses = new JsonArray();
				for (JsonElement m : msg.getAsJsonArray()) {
					JsonObject r = handle(m.getAsJsonObject());
					if (r != null) {
						responses.add(r);
					}
				}
				if (!responses.isEmpty()) {
					send(responses);
				}
			} else {
				JsonObject r = handle(msg.getAsJsonObject());
				if (r != null) {
					send(r);
				}
			}
		}
	}

	private synchronized void send(JsonElement e) throws IOException {
		out.write(e.toString());
		out.write('\n');
		out.flush();
	}

	JsonObject handle(JsonObject req) {
		JsonElement id = req.get("id");
		String method = req.has("method") ? req.get("method").getAsString() : null;
		if (method == null) {
			return null; // a response to something we never send
		}
		JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params")
				: new JsonObject();
		boolean notification = id == null || id.isJsonNull();
		try {
			JsonElement result = switch (method) {
				case "initialize" -> initialize(params);
				case "ping" -> new JsonObject();
				case "tools/list" -> listTools();
				case "tools/call" -> callTool(params);
				case "resources/list" -> listOf("resources");
				case "resources/templates/list" -> listOf("resourceTemplates");
				case "prompts/list" -> listOf("prompts");
				case "logging/setLevel" -> new JsonObject();
				default -> {
					if (method.startsWith("notifications/")) {
						yield null;
					}
					throw new MethodNotFound(method);
				}
			};
			if (notification || result == null) {
				return null;
			}
			JsonObject resp = new JsonObject();
			resp.addProperty("jsonrpc", "2.0");
			resp.add("id", id);
			resp.add("result", result);
			return resp;
		} catch (MethodNotFound e) {
			return notification ? null : errorResponse(id, -32601, "Method not found: " + method);
		} catch (Throwable e) {
			return notification ? null : errorResponse(id, -32603, Errors.toJson(e).get("message").getAsString());
		}
	}

	private static final class MethodNotFound extends Exception {
		MethodNotFound(String m) {
			super(m);
		}
	}

	private static JsonObject listOf(String key) {
		JsonObject o = new JsonObject();
		o.add(key, new JsonArray());
		return o;
	}

	private JsonObject initialize(JsonObject params) {
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
		String version = requested != null && PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", version);
		JsonObject caps = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		caps.add("tools", tools);
		r.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", "openrocket");
		info.addProperty("version", BuildProperties.getVersion());
		r.add("serverInfo", info);
		r.addProperty("instructions", INSTRUCTIONS);
		return r;
	}

	private JsonObject listTools() throws Exception {
		JsonArray tools = new JsonArray();
		for (JsonElement e : backend.listCommands()) {
			JsonObject c = e.getAsJsonObject();
			String name = c.get("name").getAsString();
			String tool = CommandRegistry.toToolName(name);
			toolToCommand.put(tool, name);
			JsonObject t = new JsonObject();
			t.addProperty("name", tool);
			t.addProperty("description", c.get("description").getAsString());
			t.add("inputSchema", c.get("inputSchema"));
			JsonObject ann = new JsonObject();
			boolean readOnly = c.has("readOnly") && c.get("readOnly").getAsBoolean();
			ann.addProperty("readOnlyHint", readOnly);
			ann.addProperty("destructiveHint", !readOnly && DESTRUCTIVE.contains(name));
			ann.addProperty("openWorldHint", false);
			t.add("annotations", ann);
			tools.add(t);
		}
		JsonObject r = new JsonObject();
		r.add("tools", tools);
		return r;
	}

	private JsonObject callTool(JsonObject params) throws Exception {
		String tool = params.get("name").getAsString();
		if (toolToCommand.isEmpty()) {
			listTools();
		}
		String command = toolToCommand.getOrDefault(tool, tool);
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();
		JsonObject r = new JsonObject();
		JsonArray content = new JsonArray();
		try {
			JsonElement result = backend.call(command, args);
			if (result != null && result.isJsonObject() && result.getAsJsonObject().has("_image")) {
				JsonObject img = result.getAsJsonObject().remove("_image").getAsJsonObject();
				JsonObject block = new JsonObject();
				block.addProperty("type", "image");
				block.add("data", img.get("data"));
				block.add("mimeType", img.get("mimeType"));
				content.add(block);
			}
			content.add(text(result == null ? "null" : PRETTY.toJson(result)));
			r.addProperty("isError", false);
		} catch (Throwable e) {
			JsonObject err = Errors.toJson(e);
			content.add(text("Error (" + err.get("code").getAsString() + "): " + err.get("message").getAsString()));
			r.addProperty("isError", true);
		}
		r.add("content", content);
		return r;
	}

	private static JsonObject text(String s) {
		JsonObject t = new JsonObject();
		t.addProperty("type", "text");
		t.addProperty("text", s);
		return t;
	}

	private static JsonObject errorResponse(JsonElement id, int code, String message) {
		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", id);
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		resp.add("error", err);
		return resp;
	}
}
