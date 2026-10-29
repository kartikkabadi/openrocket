package info.openrocket.core.automation;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.unit.UnitGroup;

/**
 * Typed access to a command's JSON parameters.
 */
public class Params {
	private final JsonObject json;

	public Params(JsonObject json) {
		this.json = json == null ? new JsonObject() : json;
	}

	public JsonObject json() {
		return json;
	}

	public boolean has(String name) {
		return json.has(name) && !json.get(name).isJsonNull();
	}

	public JsonElement get(String name) {
		return has(name) ? json.get(name) : null;
	}

	public JsonElement require(String name) {
		if (!has(name)) {
			throw AutomationException.badParam("Missing required parameter '" + name + "'");
		}
		return json.get(name);
	}

	public String string(String name) {
		JsonElement e = require(name);
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	public String string(String name, String def) {
		return has(name) ? string(name) : def;
	}

	public double number(String name) {
		return ValueCodec.parseQuantity(require(name), null);
	}

	public double number(String name, double def) {
		return has(name) ? number(name) : def;
	}

	/** A number that may be given with units ("5 cm"); returned in SI units. */
	public double quantity(String name, UnitGroup group) {
		return ValueCodec.parseQuantity(require(name), group);
	}

	public double quantity(String name, UnitGroup group, double def) {
		return has(name) ? quantity(name, group) : def;
	}

	public int integer(String name) {
		try {
			return require(name).getAsInt();
		} catch (NumberFormatException | UnsupportedOperationException e) {
			throw AutomationException.badParam("Parameter '" + name + "' must be an integer");
		}
	}

	public int integer(String name, int def) {
		return has(name) ? integer(name) : def;
	}

	public boolean bool(String name, boolean def) {
		if (!has(name)) {
			return def;
		}
		JsonElement e = json.get(name);
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
			return Boolean.parseBoolean(e.getAsString());
		}
		return e.getAsBoolean();
	}

	public JsonObject object(String name) {
		JsonElement e = require(name);
		if (!e.isJsonObject()) {
			throw AutomationException.badParam("Parameter '" + name + "' must be an object");
		}
		return e.getAsJsonObject();
	}

	public JsonArray array(String name) {
		JsonElement e = require(name);
		if (e.isJsonArray()) {
			return e.getAsJsonArray();
		}
		JsonArray a = new JsonArray();
		a.add(e);
		return a;
	}

	/** A list of strings; accepts a JSON array or a comma-separated string. */
	public List<String> strings(String name) {
		List<String> out = new ArrayList<>();
		if (!has(name)) {
			return out;
		}
		JsonElement e = json.get(name);
		if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				out.add(x.getAsString());
			}
		} else {
			for (String s : e.getAsString().split(",")) {
				if (!s.isBlank()) {
					out.add(s.trim());
				}
			}
		}
		return out;
	}
}
